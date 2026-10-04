"""Офлайн-реплей уведомления «открыть шлагбаум» по выгрузке Sesame.

Алгоритм получает события строго по времени, когда о них узнало приложение
(см. data.py), и в каждый момент решает: показать уведомление или нет. Потом
решения сверяются с фактическими звонками.

Метрики:
1. **Полнота** — доля использований шлагбаума, к которым уведомление уже висело
   и появилось не позже чем за `min_lead` секунд (по умолчанию 5 с: столько
   нужно, чтобы дотянуться до телефона). Цель — 100 %.
2. **Ложные показы** — эпизоды уведомления, за время которых шлагбаумом так и
   не воспользовались (в штуках и в штуках на сутки).
3. **Опережение** — за сколько секунд до звонка уведомление появилось. Большое
   опережение — не запас, а цена: всё это время уведомление висит без дела.

Сводная цена — **лишнее время на экране**, минут в сутки: опережение сверх
идеальных `ideal_lead` секунд у каждого попадания плюс полная длительность
каждого ложного показа. Варианты со 100 % полнотой сравниваются по ней.

Эпизод уведомления: появился по триггеру, висит `ttl_s` (повторный триггер
продлевает), после использования шлагбаума держится ещё `after_use_s` — проезд
часто включает два шлагбаума подряд — и скрывается. Ещё `cooldown_after_use_s`
после использования новые триггеры не срабатывают: о звонке приложение знает
само. Смахивание человеком в реплее не моделируется.

Запуск:
    python -m analysis.notify_replay.replay D:/Projects/sesame-data/Sesame
"""
from __future__ import annotations

import argparse
import itertools
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import numpy as np
import pandas as pd

from .data import MSK, Dataset, Event, Use, haversine, load


@dataclass(frozen=True)
class Policy:
    name: str = "custom"
    # Триггеры
    on_geofence: bool = False          # вход в геофенс любого шлагбаума
    depart_on_car_bt: bool = False     # подключилась машина, а мы у дома
    depart_on_ar: bool = False         # AR: начало IN_VEHICLE, а мы у дома
    arrive_with_car_bt: bool = False   # машина подключена и очередной фикс близко к дому
    arrive_with_ar: bool = False       # то же, но признак «в машине» — от AR
    # Пороги
    r_depart_m: float = 300            # «у дома» для выезда
    r_arrive_m: float = 1500           # «близко к дому» для въезда
    fix_max_age_s: float = 900         # фикс старше — для «у дома» не годится
    require_approach: bool = True      # для въезда: расстояние до дома уменьшается
    home_wifi_counts_as_home: bool = True  # домашний Wi-Fi (или потерян недавно) = «у дома»
    wifi_recent_s: float = 600
    # Жизнь уведомления
    ttl_s: float = 600
    after_use_s: float = 120
    cooldown_after_use_s: float = 600  # после проезда новые триггеры молчат: кружим у дома, а не едем к шлагбауму
    # Где машина: во дворе или за ним (см. State.car_place)
    track_car: bool = False


@dataclass
class Episode:
    start: int
    end: int
    trigger: str
    uses: list = None


class State:
    """Всё, что алгоритм знает о мире на текущий момент."""

    def __init__(self, cfg: dict):
        self.barriers = [tuple(v) for v in cfg["barriers"].values()]
        self.car = cfg.get("carBluetooth")
        self.home_ssid = cfg.get("homeSsid")
        self.fix = None            # (t, lat, lon, acc, dist)
        self.prev_dist = None
        self.car_connected = False
        self.in_vehicle = False
        self.home_wifi = False
        self.home_wifi_lost = None
        # Где стоит машина относительно двора: "inside" / "outside" / None (не знаем).
        # Меняется только проездом за рулём, о котором приложение знает само, —
        # звонком из приложения при подключённой машине. Открытие с другого
        # телефона алгоритм не видит, как не увидит его и приложение.
        self.car_place = None
        self.car_parked_at = None  # (t, lat, lon, acc) — последний фикс при отключении машины

    def dist(self, lat, lon):
        return min(haversine(lat, lon, b[0], b[1]) for b in self.barriers)

    def near_home(self, t, p: Policy) -> bool:
        if p.home_wifi_counts_as_home and (self.home_wifi or (
                self.home_wifi_lost is not None and t - self.home_wifi_lost <= p.wifi_recent_s * 1000)):
            return True
        return (self.fix is not None and t - self.fix[0] <= p.fix_max_age_s * 1000
                and self.fix[4] <= p.r_depart_m + self.fix[3])

    def passed_barrier(self, use: Use, trigger: str | None):
        """Проезд за рулём переворачивает «во дворе / за двором».

        Если места ещё не знаем, его подсказывает триггер текущего уведомления:
        уведомление «выезд» — значит, после проезда машина снаружи, «въезд» — внутри.
        """
        if use.source != "APP" or not self.car_connected:
            return
        if self.car_place is None:
            if trigger and trigger.startswith("depart"):
                self.car_place = "outside"
            elif trigger and trigger.startswith("arrive"):
                self.car_place = "inside"
        else:
            self.car_place = "outside" if self.car_place == "inside" else "inside"

    def update(self, e: Event):
        d = e.data
        if e.kind == "LOCATION":
            self.prev_dist = self.fix[4] if self.fix else None
            self.fix = (e.t, d["lat"], d["lon"], d["acc"], self.dist(d["lat"], d["lon"]))
        elif e.kind in ("BLUETOOTH_CONNECTED", "BLUETOOTH_DISCONNECTED") and d.get("name") == self.car:
            self.car_connected = e.kind == "BLUETOOTH_CONNECTED"
            if not self.car_connected and self.fix is not None:
                self.car_parked_at = (e.t,) + self.fix[1:4]
        elif e.kind == "ACTIVITY_TRANSITION" and d.get("activity") == "IN_VEHICLE":
            self.in_vehicle = d.get("transition") == "ENTER"
        elif e.kind == "WIFI_CONNECTED" and self.home_ssid and d.get("ssid") == self.home_ssid:
            self.home_wifi, self.home_wifi_lost = True, None
        elif e.kind == "WIFI_DISCONNECTED" and self.home_wifi:
            self.home_wifi, self.home_wifi_lost = False, e.t


def triggers(p: Policy, s: State, e: Event, before: dict) -> str | None:
    """Решение алгоритма по одному событию: имя сработавшего триггера или None."""
    d = e.data
    if p.on_geofence and e.kind == "GEOFENCE_ENTER":
        return "geofence"
    car_outside = p.track_car and s.car_place == "outside"
    car_inside = p.track_car and s.car_place == "inside"
    if (p.depart_on_car_bt and not car_outside and e.kind == "BLUETOOTH_CONNECTED"
            and d.get("name") == s.car and s.near_home(e.t, p)):
        return "depart:car_bt"
    if (p.depart_on_ar and e.kind == "ACTIVITY_TRANSITION" and d.get("activity") == "IN_VEHICLE"
            and d.get("transition") == "ENTER" and s.near_home(e.t, p)):
        return "depart:ar"
    if e.kind == "LOCATION":
        driving = (p.arrive_with_car_bt and s.car_connected) or (p.arrive_with_ar and s.in_vehicle)
        approaching = not p.require_approach or (before["dist"] is not None and s.fix[4] < before["dist"])
        if driving and approaching and not car_inside and s.fix[4] - s.fix[3] <= p.r_arrive_m:
            return "arrive:" + ("car_bt" if p.arrive_with_car_bt and s.car_connected else "ar")
    return None


def simulate(ds: Dataset, p: Policy, start: int, end: int) -> list[Episode]:
    s = State(ds.config)
    episodes: list[Episode] = []
    cur: Episode | None = None
    uses = [u for u in ds.uses if start <= u.t <= end]
    ui = 0
    last_use = -10**15
    for e in ds.events:
        # использования до этого события закрывают/продлевают текущий эпизод
        while ui < len(uses) and uses[ui].t <= e.t:
            u = uses[ui]
            if cur and cur.start <= u.t <= cur.end:
                cur.uses.append(u)
                cur.end = u.t + int(p.after_use_s * 1000)
            s.passed_barrier(u, cur.trigger if cur else None)
            if u.source == "APP":  # про чужое открытие приложение не знает
                last_use = u.t
            ui += 1
        if cur and e.t > cur.end:
            episodes.append(cur)
            cur = None
        before = {"dist": s.fix[4] if s.fix else None}
        s.update(e)
        if e.t < start or e.t > end:
            continue
        trig = triggers(p, s, e, before)
        if trig and e.t - last_use < p.cooldown_after_use_s * 1000 and cur is None:
            trig = None
        if trig:
            if cur is None:
                cur = Episode(e.t, e.t + int(p.ttl_s * 1000), trig, [])
            elif not cur.uses:
                cur.end = max(cur.end, e.t + int(p.ttl_s * 1000))
    while ui < len(uses):  # хвост
        u = uses[ui]
        if cur and cur.start <= u.t <= cur.end:
            cur.uses.append(u)
            cur.end = u.t + int(p.after_use_s * 1000)
        ui += 1
    if cur:
        episodes.append(cur)
    return episodes


def evaluate(ds: Dataset, p: Policy, start: int, end: int, min_lead_s: float, ideal_lead_s: float = 5) -> dict:
    eps = simulate(ds, p, start, end)
    uses = [u for u in ds.uses if start <= u.t <= end]
    rows = []
    for u in uses:
        ep = next((x for x in eps if x.start <= u.t <= x.end), None)
        lead = (u.t - ep.start) / 1000 if ep else None
        rows.append({"use": u.ref, "t": u.t, "direction": u.direction, "source": u.source,
                     "lead_s": lead, "hit": lead is not None and lead >= min_lead_s,
                     "trigger": ep.trigger if ep else None})
    fp = [x for x in eps if not x.uses]
    days = (end - start) / 86400e3
    leads = [r["lead_s"] for r in rows if r["hit"]]
    waste_s = sum(max(0.0, x - ideal_lead_s) for x in leads) + sum((x.end - x.start) / 1000 for x in fp)
    return {
        "policy": p, "uses": len(uses), "hits": sum(r["hit"] for r in rows),
        "recall": sum(r["hit"] for r in rows) / max(len(uses), 1),
        "episodes": len(eps), "false": len(fp), "false_per_day": len(fp) / days,
        "shown_min_per_day": sum((x.end - x.start) for x in eps) / 60000 / days,
        "lead_min": min(leads) if leads else None, "lead_median": float(np.median(leads)) if leads else None,
        "lead_max": max(leads) if leads else None, "waste_min_per_day": waste_s / 60 / days,
        "rows": rows, "false_episodes": fp,
    }


# --- кандидаты ---------------------------------------------------------------------

BASELINES = [
    Policy("геофенс", on_geofence=True),
    Policy("машина у дома (выезд)", depart_on_car_bt=True),
    Policy("машина: выезд + подъезд", depart_on_car_bt=True, arrive_with_car_bt=True),
    Policy("машина + где стоит машина", depart_on_car_bt=True, arrive_with_car_bt=True, track_car=True),
    Policy("AR: выезд + подъезд", depart_on_ar=True, arrive_with_ar=True),
    Policy("машина + AR + геофенс", on_geofence=True, depart_on_car_bt=True, arrive_with_car_bt=True,
           depart_on_ar=True, arrive_with_ar=True),
]


def grid() -> list[Policy]:
    out = []
    for car, ar, gf, track, r_arr, r_dep, ttl, approach in itertools.product(
            [True, False], [True, False], [True, False], [True, False],
            [500, 800, 1200, 1500, 2000, 3000], [200, 300, 500], [120, 180, 300, 600, 900], [True, False]):
        if not (car or ar or gf) or (track and not car):
            continue
        name = "+".join(n for n, f in (("машина", car), ("AR", ar), ("геофенс", gf), ("где машина", track)) if f)
        out.append(Policy(f"{name} r↘{r_arr} r⌂{r_dep} ttl{ttl}с{'' if approach else ' без приближения'}",
                          on_geofence=gf, depart_on_car_bt=car, arrive_with_car_bt=car,
                          depart_on_ar=ar, arrive_with_ar=ar, r_arrive_m=r_arr, r_depart_m=r_dep,
                          ttl_s=ttl, require_approach=approach, track_car=track))
    return out


# --- отчёт -------------------------------------------------------------------------

def fmt_t(ms):
    return pd.Timestamp(ms, unit="ms", tz="UTC").tz_convert(MSK).strftime("%d.%m %H:%M:%S")


def summary_line(r):
    lm = "—" if r["lead_min"] is None else f"{r['lead_min']:.0f}"
    lmed = "—" if r["lead_median"] is None else f"{r['lead_median']:.0f}"
    lmax = "—" if r["lead_max"] is None else f"{r['lead_max']:.0f}"
    return (f"{r['hits']:>2}/{r['uses']:<2} {r['recall']*100:5.1f}%  лишнее {r['waste_min_per_day']:5.1f} мин/сут  "
            f"ложных {r['false']:>3} ({r['false_per_day']:4.1f}/сут)  "
            f"опережение min/med/max {lm:>4}/{lmed:>4}/{lmax:>5} с  {r['policy'].name}")


def run(root: Path, min_lead_s: float, out: Path | None):
    ds = load(root)
    bt_events = [e.t for e in ds.events if e.kind.startswith("BLUETOOTH")]
    periods = {"весь период": (ds.start, ds.end)}
    if bt_events:
        periods["с Bluetooth (0.2.2+)"] = (min(bt_events) - 60_000, ds.end)
    report = {}
    for pname, (a, b) in periods.items():
        print(f"\n=== {pname}: {fmt_t(a)} – {fmt_t(b)}, порог опережения {min_lead_s:.0f} с ===")
        print("-- базовые алгоритмы")
        for p in BASELINES:
            print("  " + summary_line(evaluate(ds, p, a, b, min_lead_s)))
        res = [evaluate(ds, p, a, b, min_lead_s) for p in grid()]
        best_recall = max(r["recall"] for r in res)
        top = sorted([r for r in res if r["recall"] == best_recall],
                     key=lambda r: (r["waste_min_per_day"], r["false"]))[:8]
        print(f"-- лучшие из сетки ({len(res)} вариантов), максимум полноты {best_recall*100:.1f}%")
        for r in top:
            print("  " + summary_line(r))
        best = top[0]
        print(f"-- по проездам, лучший: {best['policy'].name}")
        print("   ложные показы: " + ", ".join(f"{fmt_t(x.start)} {x.trigger} {(x.end - x.start) / 1000:.0f}с"
                                          for x in best["false_episodes"]))
        for row in best["rows"]:
            lead = "НЕТ" if row["lead_s"] is None else f"{row['lead_s']:+.0f} с"
            print(f"  {row['use']:>4} {fmt_t(row['t'])} {row['direction']:7s} {'✓' if row['hit'] else '✗'} {lead:>8}  {row['trigger'] or ''}")
        report[pname] = {"best": {**{k: v for k, v in best.items() if k not in ("policy", "false_episodes")},
                                  "policy": asdict(best["policy"]),
                                  "false_episodes": [{"start": x.start, "end": x.end, "trigger": x.trigger}
                                                     for x in best["false_episodes"]]},
                         "top": [{"policy": asdict(r["policy"]), **{k: r[k] for k in
                                  ("uses", "hits", "recall", "false", "false_per_day", "waste_min_per_day",
                                   "lead_min", "lead_median", "lead_max")}}
                                 for r in top]}
    if out:
        out.write_text(json.dumps(report, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
        print(f"\nотчёт: {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("root", type=Path, help="каталог выгрузки Documents/Sesame")
    ap.add_argument("--min-lead", type=float, default=5, help="уведомление должно появиться не позже чем за N с до звонка")
    ap.add_argument("--out", type=Path, help="куда записать JSON-отчёт")
    a = ap.parse_args()
    run(a.root, a.min_lead, a.out)


if __name__ == "__main__":
    main()
