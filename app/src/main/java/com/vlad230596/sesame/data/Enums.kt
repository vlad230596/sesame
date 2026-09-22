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
}
