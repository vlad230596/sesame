package com.vlad230596.sesame.sensors

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пассивный слой (§4.4).
 *
 * Работает постоянно под foreground service, интенсивную запись не запускает.
 * По умолчанию: локация раз в 120 с (`PRIORITY_BALANCED_POWER_ACCURACY`, смещение 25 м),
 * акселерометр 5 Гц, барометр 1 Гц при наличии, аппаратный `TYPE_STEP_COUNTER`.
 * Гироскоп и магнитометр в пассивном слое не опрашиваются.
 *
 * Держит кольцевой буфер последних [RING_BUFFER_SECONDS] секунд своих отсчётов,
 * который сбрасывается в файл сессии первым при старте интенсивной записи (§4.3).
 *
 * TODO(v0): реализовать.
 */
@Singleton
class PassiveSensorCollector @Inject constructor() {

    fun start() {
        // TODO(v0): подписка на датчики и локацию.
    }

    fun stop() {
        // TODO(v0): отписка и флаш буферов.
    }

    /** Содержимое кольцевого буфера для предыстории интенсивной сессии. */
    fun drainRingBuffer(): List<Nothing> = emptyList()

    companion object {
        /** §4.3: 60 секунд предыстории. */
        const val RING_BUFFER_SECONDS: Int = 60
    }
}
