"""Загрузка выгрузки Sesame для реплея и калибровка «настроек».

Реплей честный: алгоритм видит только то, что приложение знало бы в тот же
момент. Поэтому здесь же решается, что в поток событий **не** попадает:

- `BARRIER_CALL_ATTEMPT` и `RECORDING_SESSION_*` — это и есть ответ;
- `SCREEN_ON/OFF` и `USER_PRESENT` — человек включает экран, чтобы позвонить,
  то есть экран рядом со звонком — следствие звонка, а не его предвестник;
- потоки интенсивных сессий — сессию запускал человек перед проездом.

Время события — момент, когда приложение о нём **узнало** (`received_time`),
а не когда оно произошло: геофенс, доставленный через 15 с, бесполезен
в первые 15 с.

Калибровка (`calibrate`) подменяет то, что в приложении лежит в настройках и в
выгрузку не попадает: координаты шлагбаумов, имя Bluetooth машины, домашнюю
сеть Wi-Fi. Координаты берутся по фиксам в моменты звонков — это одноразовая
настройка, как ввод координат в приложении, а не подсказка по каждому событию.
Результат пишется в `replay_config.json` рядом с данными (вне репозитория:
там координаты дома).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

MSK = "Europe/Moscow"

# Типы событий журнала, которые алгоритм видеть вправе.
ALLOWED_TYPES = {
    "GEOFENCE_ENTER", "GEOFENCE_EXIT", "ACTIVITY_TRANSITION",
    "BLUETOOTH_CONNECTED", "BLUETOOTH_DISCONNECTED",
    "WIFI_CONNECTED", "WIFI_DISCONNECTED",
    "POWER_CONNECTED", "POWER_DISCONNECTED",
}

# Пассивная локация приходит без батчинга (LocationStreamWriter не задаёт
# maxUpdateDelay); секунда — запас на доставку колбэка.
LOCATION_DELIVERY_MS = 1000


def haversine(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    a = np.sin((p2 - p1) / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(np.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * np.arcsin(np.sqrt(a))


@dataclass(frozen=True)
class Event:
    t: int          # когда приложение узнало, мс эпохи
    kind: str       # тип события журнала или "LOCATION"
    data: dict


@dataclass(frozen=True)
class Use:
    """Факт использования шлагбаума — то, что уведомление должно было предвосхитить."""
    t: int
    barrier: int | None
    direction: str
    source: str     # APP — звонок из приложения; иное — ручная разметка
    ref: str


@dataclass
class Dataset:
    events: list[Event]
    uses: list[Use]
    start: int
    end: int
    config: dict = field(default_factory=dict)


def _journal(root: Path) -> pd.DataFrame:
    fs = sorted((root / "journal").glob("*/events.csv.gz"))
    ev = pd.concat([pd.read_csv(f) for f in fs], ignore_index=True)
    ev["p"] = ev.payload_json.fillna("{}").map(json.loads)
    return ev.sort_values(["received_time_millis", "id"]).reset_index(drop=True)


def _passive_fixes(root: Path) -> pd.DataFrame:
    fs = sorted((root / "passive").glob("*/location.csv.gz"))
    return pd.concat([pd.read_csv(f) for f in fs], ignore_index=True).sort_values("time_millis")


def load_uses(root: Path, manual_path: Path | None) -> list[Use]:
    """Истина: состоявшиеся звонки из приложения плюс ручная разметка.

    Отменённые и отброшенные звонки — не использование шлагбаума.
    """
    lab = pd.read_csv(root / "journal" / "labels.csv.gz")
    lab = lab[(lab.outcome == "CALLED") & (~lab.discarded.astype(bool))]
    uses = [Use(int(r.timestamp), int(r.barrier_id) if pd.notna(r.barrier_id) else None,
                r.direction, "APP", f"#{r.id}") for r in lab.itertuples()]
    if manual_path and manual_path.exists():
        for i, m in enumerate(json.loads(manual_path.read_text(encoding="utf-8"))):
            ts = int(pd.Timestamp(m["time"], tz=MSK).timestamp() * 1000)
            uses.append(Use(ts, m.get("barrier"), m.get("direction", "UNKNOWN"), m.get("source", "MANUAL"), f"M{i + 1}"))
    return sorted(uses, key=lambda u: u.t)


def calibrate(root: Path) -> dict:
    """Восстанавливает «настройки приложения» из данных (см. описание модуля)."""
    fixes = [pd.read_csv(f) for f in root.glob("passive/*/location.csv.gz")]
    fixes += [pd.read_csv(f) for f in root.glob("sessions/*/location.csv.gz")]
    fx = pd.concat(fixes, ignore_index=True)
    fx = fx[fx.accuracy_m < 25]
    lab = pd.read_csv(root / "journal" / "labels.csv.gz")
    lab = lab[(lab.outcome == "CALLED") & (~lab.discarded.astype(bool))]
    pts: dict[int, list[tuple[float, float]]] = {}
    for r in lab.itertuples():
        w = fx[(fx.time_millis - r.timestamp).abs() < 20_000]
        if len(w):
            f = w.iloc[(w.time_millis - r.timestamp).abs().argmin()]
            pts.setdefault(int(r.barrier_id), []).append((f.latitude, f.longitude))
    barriers = {str(b): [float(np.median([p[0] for p in v])), float(np.median([p[1] for p in v]))] for b, v in pts.items()}

    ev = _journal(root)
    bt = ev[ev.type == "BLUETOOTH_CONNECTED"].p.map(lambda p: p.get("name"))
    car = bt.value_counts().index[0] if len(bt.dropna()) else None

    wifi = ev[ev.type == "WIFI_CONNECTED"].copy()
    wifi["ssid"] = wifi.p.map(lambda p: p.get("ssid"))
    wifi["hour"] = pd.to_datetime(wifi.event_time_millis, unit="ms", utc=True).dt.tz_convert(MSK).dt.hour
    night = wifi[(wifi.hour >= 22) | (wifi.hour < 7)].ssid.dropna()
    home_ssid = night.value_counts().index[0] if len(night) else None
    return {"barriers": barriers, "carBluetooth": car, "homeSsid": home_ssid}


def load(root: Path, manual_path: Path | None = None, config_path: Path | None = None) -> Dataset:
    config_path = config_path or root.parent / "replay_config.json"
    if config_path.exists():
        config = json.loads(config_path.read_text(encoding="utf-8"))
    else:
        config = calibrate(root)
        config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")

    ev = _journal(root)
    ev = ev[ev.type.isin(ALLOWED_TYPES)]
    events = [Event(int(r.received_time_millis), r.type, r.p) for r in ev.itertuples()]

    fx = _passive_fixes(root)
    events += [Event(int(r.time_millis) + LOCATION_DELIVERY_MS, "LOCATION",
                     {"lat": r.latitude, "lon": r.longitude, "acc": r.accuracy_m,
                      "speed": None if pd.isna(r.speed_mps) else float(r.speed_mps)})
               for r in fx.itertuples()]
    events.sort(key=lambda e: e.t)

    uses = load_uses(root, manual_path or root.parent / "manual_labels.json")
    return Dataset(events, uses, events[0].t, events[-1].t, config)
