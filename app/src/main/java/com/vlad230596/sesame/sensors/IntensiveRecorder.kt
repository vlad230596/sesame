package com.vlad230596.sesame.sensors

import com.vlad230596.sesame.data.SessionLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Интенсивная запись (§4.3).
 *
 * Запускается только вручную — из приложения или из виджета. Пишет все датчики,
 * вернувшиеся из `SensorManager.getSensorList(TYPE_ALL)`, на `SENSOR_DELAY_FASTEST`,
 * локацию — 1 Гц с `PRIORITY_HIGH_ACCURACY`. Отсутствие любого датчика не ошибка
 * и фиксируется в манифесте сессии.
 *
 * TODO(v0): реализовать.
 */
@Singleton
class IntensiveRecorder @Inject constructor() {

    fun start(label: SessionLabel = SessionLabel.NONE) {
        // TODO(v0): создать сессию, слить кольцевой буфер, подписаться на датчики.
    }

    fun extend(minutes: Int = DEFAULT_DURATION_MINUTES) {
        // TODO(v0): продлить жёсткий предохранитель.
    }

    fun stop() {
        // TODO(v0): закрыть файлы, записать session.json.
    }

    /** Состав датчиков определяется в рантайме (§3) и пишется в `session.json` (§6). */
    fun buildSensorManifest(): String = "{}"

    companion object {
        /** §4.3: по умолчанию 15 минут, значение в настройках. */
        const val DEFAULT_DURATION_MINUTES: Int = 15
    }
}
