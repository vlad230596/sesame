package com.vlad230596.sesame.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionGateTest {

    private var now = 0L
    private val logs = mutableListOf<Map<String, Any?>>()
    private val gate = MotionGate(clock = { now }, log = { logs += it })

    /** Окно 10 с при 5 Гц: 51 отсчёт с заданным разбросом вокруг g. */
    private fun window(startNanos: Long, amplitude: Float) {
        for (i in 0..50) {
            val z = 9.81f + if (i % 2 == 0) amplitude else -amplitude
            gate.onAccelerometer(startNanos + i * 200_000_000L, 0f, 0f, z)
        }
    }

    /** Спокойные окна подряд, с ходом часов. */
    private fun quietMinutes(minutes: Int) {
        repeat(minutes * 6) { k ->
            now += 10_000L
            window(now * 1_000_000L + k, 0.01f)
        }
    }

    @Test
    fun startsMovingAndGoesStillAfterQuietPeriod() {
        assertEquals(MotionGate.Mode.MOVING, gate.current)
        quietMinutes(4)
        assertEquals(MotionGate.Mode.MOVING, gate.current)
        quietMinutes(2)
        assertEquals(MotionGate.Mode.STILL, gate.current)
        assertEquals("quiet", logs.last()["reason"])
    }

    @Test
    fun shakingWakesUp() {
        quietMinutes(6)
        assertEquals(MotionGate.Mode.STILL, gate.current)
        window(now * 1_000_000L + 1, 1.5f)
        assertEquals(MotionGate.Mode.MOVING, gate.current)
        assertEquals("accelerometer", logs.last()["reason"])
    }

    @Test
    fun carConnectedKeepsMovingOnSmoothRoad() {
        gate.onCarConnection(true)
        quietMinutes(20)
        assertEquals(MotionGate.Mode.MOVING, gate.current)
        gate.onCarConnection(false)
        quietMinutes(6)
        assertEquals(MotionGate.Mode.STILL, gate.current)
    }

    @Test
    fun vehicleActivityKeepsMovingUntilStillEntered() {
        gate.onActivityEnter("IN_VEHICLE")
        quietMinutes(10)
        assertEquals(MotionGate.Mode.MOVING, gate.current)
        gate.onActivityEnter("STILL")
        assertEquals(MotionGate.Mode.STILL, gate.current)
    }

    @Test
    fun foreignFixFarFromAnchorWakesUp() {
        gate.onLocation(55.0, 37.0)
        quietMinutes(6)
        assertEquals(MotionGate.Mode.STILL, gate.current)
        gate.onLocation(55.0005, 37.0) // ~55 м — дрожание, остаёмся
        assertEquals(MotionGate.Mode.STILL, gate.current)
        gate.onLocation(55.003, 37.0) // ~330 м — поехали
        assertEquals(MotionGate.Mode.MOVING, gate.current)
    }
}
