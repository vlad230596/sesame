package com.vlad230596.sesame.data

/**
 * Направление проезда относительно двора (§4.5).
 * Заполняется догадкой по геофенсу двора и правится вручную в истории.
 */
enum class Direction {
    IN,
    OUT,
    UNKNOWN,
}

/**
 * Способ перемещения (§4.5).
 * Заполняется догадкой по состоянию Bluetooth и правится вручную в истории.
 */
enum class TravelMode {
    DRIVER,
    PASSENGER,
    FOOT,
    UNKNOWN,
}

/**
 * Откуда пришла метка (§4.5).
 */
enum class LabelSource {
    WIDGET,
    APP,
    MANUAL,

    /** Кнопка в уведомлении, появившемся по приближению. */
    NOTIFICATION,
}

/**
 * Исход попытки открыть шлагбаум (§4.1).
 *
 * - [CALLED]             — звонок ушёл;
 * - [DIALER_FALLBACK]    — открыта системная звонилка с введённым номером;
 * - [NO_NUMBER]          — номер не задан, открыт экран настроек шлагбаумов;
 * - [CANCELLED_BY_USER]  — пользователь нажал «Отмена» до истечения таймера.
 */
enum class CallOutcome {
    CALLED,
    DIALER_FALLBACK,
    NO_NUMBER,
    CANCELLED_BY_USER,
}

/**
 * Метка интенсивной сессии, выбираемая на стартовом экране (§4.3).
 */
enum class SessionLabel {
    GOING_HOME,
    LEAVING_HOME,
    ON_FOOT,
    OTHER,
    NONE,
}

/**
 * Перечисление всех событий журнала (§4.4). `payloadJson` хранит специфику события.
 */
enum class LogEventType {
    BLUETOOTH_CONNECTED,
    BLUETOOTH_DISCONNECTED,
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    SCREEN_ON,
    SCREEN_OFF,
    USER_PRESENT,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    ACTIVITY_TRANSITION,
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    RECORDING_SESSION_STARTED,
    RECORDING_SESSION_STOPPED,
    SERVICE_STARTED,
    SERVICE_STOPPED,
    DEVICE_BOOTED,
    BARRIER_CALL_ATTEMPT,

    /**
     * Подписка на локацию не состоялась из-за отозванного разрешения (§8).
     * Молчаливой деградации быть не должно: дыра в `location.csv.gz` иначе
     * необъяснима, а Android отзывает разрешения у неиспользуемых приложений.
     */
    LOCATION_PERMISSION_MISSING,

    /** Сбор частично не поднялся: датчик не подписался, файл не открылся (§9). */
    COLLECTION_ERROR,

    /** §5: посуточная ротация пассивных файлов — отметка стыка суток в журнале. */
    PASSIVE_DAY_ROTATED,

    /**
     * Уведомление со шлагбаумами появилось / скрылось / его смахнули
     * (NEXT-notification-and-car.md). Это **выход** алгоритма: реплей сверяет
     * по ним прогноз с реальностью и не подаёт их себе на вход.
     */
    PROMPT_SHOWN,
    PROMPT_HIDDEN,
    PROMPT_DISMISSED,

    /** Точная локация у дома включена / выключена (зона подъезда). */
    APPROACH_ZONE_ON,
    APPROACH_ZONE_OFF,

    /** Машину отключили — точка парковки; и смена «во дворе / за двором». */
    CAR_PARKED,
    CAR_PLACE_CHANGED,
}
