package com.vlad230596.sesame.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Сценарии из реплея (docs/NEXT-notification-and-car.md): выезд, подъезд с
 * пассивной и точной локацией, «где машина», пауза после проезда, смахивание,
 * зона точной локации.
 */
class PromptEngineTest {

    private lateinit var engine: PromptEngine
    private var t = 1_000_000L

    @Before
    fun setUp() {
        engine = PromptEngine(PromptParams(), listOf(BarrierPoint(1, BASE_LAT, BASE_LON)), CarPlace.UNKNOWN)
    }

    /** Фикс в [meters] к северу от шлагбаума. */
    private fun fix(meters: Double, accuracy: Float = 10f, precise: Boolean = false, dt: Long = 1_000) =
        engine.onLocation(advance(dt), BASE_LAT + meters / METERS_PER_DEG_LAT, BASE_LON, accuracy, precise)

    private fun advance(dt: Long): Long {
        t += dt
        return t
    }

    private fun List<PromptAction>.shows() = filterIsInstance<PromptAction.Show>()

    @Test
    fun `выезд — подключилась машина у дома, показываем сразу`() {
        fix(50.0)
        val actions = engine.onCarConnected(advance(30_000))
        assertEquals(Trigger.DEPART, actions.shows().single().trigger)
    }

    @Test
    fun `выезд без свежего фикса — просим фиксацию и решаем по ней`() {
        fix(50.0)
        val connected = engine.onCarConnected(advance(60 * 60_000L))
        assertTrue(PromptAction.RequestFix in connected)
        assertTrue(connected.shows().isEmpty())
        assertEquals(Trigger.DEPART, fix(40.0, precise = true).shows().single().trigger)
    }

    @Test
    fun `машина подключилась далеко от дома — молчим`() {
        fix(5_000.0)
        assertTrue(engine.onCarConnected(advance(1_000)).shows().isEmpty())
    }

    @Test
    fun `подъезд по пассивной локации — порог 800 м и приближение`() {
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        assertTrue(fix(1_500.0, dt = 120_000).shows().isEmpty())
        assertEquals(Trigger.ARRIVE, fix(700.0, dt = 120_000).shows().single().trigger)
    }

    @Test
    fun `удаляемся от дома — подъезда нет`() {
        fix(300.0)
        engine.onCarConnected(advance(1_000))
        engine.onBarrierUsed(advance(1_000))
        assertTrue(fix(600.0, dt = 30_000).shows().isEmpty())
    }

    @Test
    fun `зона у дома — точная локация включается и сужает порог до 150 м`() {
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        val entered = fix(1_900.0, dt = 120_000)
        assertTrue(entered.any { it is PromptAction.ZoneOn })
        assertTrue(engine.zoneActive)
        // Точные фиксы раз в секунду: на 400 м ещё рано, на 140 м — пора.
        assertTrue(fix(400.0, precise = true).shows().isEmpty())
        assertTrue(fix(300.0, precise = true).shows().isEmpty())
        assertEquals(Trigger.ARRIVE, fix(140.0, precise = true).shows().single().trigger)
    }

    @Test
    fun `зона выключается при выходе из неё и при отключении машины`() {
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        fix(1_900.0, dt = 120_000)
        assertTrue(fix(2_600.0).any { it is PromptAction.ZoneOff })
        fix(1_900.0, dt = 120_000)
        assertTrue(engine.zoneActive)
        assertTrue(engine.onCarDisconnected(advance(1_000)).any { it is PromptAction.ZoneOff })
        assertFalse(engine.zoneActive)
    }

    @Test
    fun `заехал и выехал — машина за двором, утром выезд без уведомления`() {
        // Вечер: подъезд, въезд, тут же выезд через второй шлагбаум.
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        fix(700.0, dt = 120_000)
        engine.onBarrierUsed(advance(60_000))
        assertEquals(CarPlace.INSIDE, engine.carPlace)
        engine.onBarrierUsed(advance(80_000))
        assertEquals(CarPlace.OUTSIDE, engine.carPlace)
        engine.onCarDisconnected(advance(120_000))
        // Утро: подключились у дома — машина за двором, шлагбаум не нужен.
        fix(80.0, dt = 10 * 60 * 60_000L)
        assertTrue(engine.onCarConnected(advance(30_000)).shows().isEmpty())
    }

    @Test
    fun `поставил во двор — утром выезд с уведомлением`() {
        engine.setCarPlace(CarPlace.INSIDE)
        fix(60.0)
        assertEquals(Trigger.DEPART, engine.onCarConnected(advance(30_000)).shows().single().trigger)
    }

    @Test
    fun `машина во дворе — подъезд не показываем`() {
        engine.setCarPlace(CarPlace.INSIDE)
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        assertTrue(fix(700.0, dt = 120_000).shows().isEmpty())
    }

    @Test
    fun `после проезда — уведомление живёт ещё 2 минуты и 10 минут не возвращается`() {
        fix(50.0)
        engine.onCarConnected(advance(1_000))
        engine.onBarrierUsed(advance(45_000))
        assertTrue(engine.onTick(advance(60_000)).isEmpty())
        assertTrue(engine.onTick(advance(61_000)).any { it is PromptAction.Hide && it.used })
        // Кружим у дома: подъезд в пределах паузы не срабатывает.
        engine.setCarPlace(CarPlace.OUTSIDE)
        fix(1_000.0, dt = 60_000)
        assertTrue(fix(600.0, dt = 60_000).shows().isEmpty())
    }

    @Test
    fun `не нажали — уведомление гаснет через 2 минуты`() {
        fix(50.0)
        engine.onCarConnected(advance(1_000))
        assertTrue(engine.promptVisible)
        val hidden = engine.onTick(advance(PromptParams().ttlMillis))
        assertTrue(hidden.any { it is PromptAction.Hide && it.reason == "ttl" })
        assertFalse(engine.promptVisible)
    }

    @Test
    fun `стоим у шлагбаума — уведомление не гаснет`() {
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        fix(1_900.0, dt = 120_000)
        fix(300.0, precise = true)
        fix(140.0, precise = true)
        repeat(5) {
            fix(30.0, precise = true, dt = 60_000)
            assertTrue(engine.onTick(t).none { it is PromptAction.Hide })
        }
        assertTrue(engine.promptVisible)
    }

    @Test
    fun `смахнули — 10 минут не показываем`() {
        fix(50.0)
        engine.onCarConnected(advance(1_000))
        assertTrue(engine.onDismissed(advance(5_000)).any { it is PromptAction.Hide && it.reason == "dismissed" })
        fix(3_000.0, dt = 60_000)
        assertTrue(fix(700.0, dt = 60_000).shows().isEmpty())
    }

    @Test
    fun `машину отключили без проезда — уведомление гаснет, парковка записывается`() {
        fix(3_000.0)
        engine.onCarConnected(advance(1_000))
        fix(700.0, dt = 120_000)
        val actions = engine.onCarDisconnected(advance(60_000))
        assertTrue(actions.any { it is PromptAction.Hide && it.reason == "car_disconnected" })
        assertTrue(actions.any { it is PromptAction.CarParked })
    }

    @Test
    fun `догадка направления для метки`() {
        assertEquals(null, engine.directionHint())
        fix(50.0)
        engine.onCarConnected(advance(1_000))
        assertEquals(Trigger.DEPART, engine.directionHint())
        engine.setCarPlace(CarPlace.OUTSIDE)
        assertEquals(Trigger.ARRIVE, engine.directionHint())
    }

    private companion object {
        const val BASE_LAT = 55.0
        const val BASE_LON = 37.0
        const val METERS_PER_DEG_LAT = 111_195.0
    }
}
