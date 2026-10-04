package com.vlad230596.sesame.prompt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.logging.DataFileStore
import com.vlad230596.sesame.logging.SensorStreams
import com.vlad230596.sesame.logging.WriteScope
import com.vlad230596.sesame.sensors.LocationStreamWriter
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Подсказка у шлагбаума на телефоне: связывает [PromptEngine] с Android.
 *
 * Входы — подключение Bluetooth машины, пассивная локация, точная локация зоны
 * у дома, звонки на шлагбаум, смахивание уведомления. Выходы — уведомление
 * ([BarrierPromptNotifier]), включение и выключение точной локации и события
 * журнала. Журнал — не украшение: по `PROMPT_*` и `APPROACH_ZONE_*` следующий
 * офлайн-реплей сверит, что алгоритм решил на телефоне, с тем, что решил бы он.
 *
 * Все обращения к движку идут через один последовательный диспетчер: входы
 * приходят из разных потоков (приёмник, поток датчиков, главный поток), а
 * движок однопоточный.
 *
 * Живёт вместе с постоянным сервисом (§9): [start] — при запуске сбора, [stop] —
 * в `onDestroy`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PromptController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val barrierRepository: BarrierRepository,
    private val journal: CollectorJournal,
    private val notifier: BarrierPromptNotifier,
    private val store: DataFileStore,
    private val permissions: PermissionsChecker,
) {

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var scope: CoroutineScope? = null
    private var tickJob: Job? = null

    private val engine = PromptEngine(PromptParams(), emptyList(), CarPlace.UNKNOWN)
    private var config = SesameSettings()
    private var barriers: List<Barrier> = emptyList()
    private var shownTrigger: Trigger? = null
    private var zoneWriter: LocationStreamWriter? = null

    private val active: Boolean
        get() = config.promptEnabled && config.carBluetoothAddress != null && engine.barriers.isNotEmpty()

    fun start() {
        if (scope != null) return
        val own = CoroutineScope(SupervisorJob() + serial)
        scope = own
        notifier.ensureChannel()
        own.launch {
            combine(settings.settings, barrierRepository.observeAll()) { s, b -> s to b }.collect { (s, b) ->
                val carChanged = s.carBluetoothAddress != config.carBluetoothAddress
                config = s
                barriers = b
                engine.barriers = b.mapNotNull { barrier ->
                    val lat = barrier.lat ?: return@mapNotNull null
                    val lon = barrier.lon ?: return@mapNotNull null
                    BarrierPoint(barrier.id, lat, lon)
                }
                engine.setCarPlace(s.carPlace?.let { runCatching { CarPlace.valueOf(it) }.getOrNull() } ?: CarPlace.UNKNOWN)
                if (!active || carChanged) reset(if (carChanged) "car_changed" else "disabled")
                if (carChanged && s.carBluetoothAddress != null) checkCarConnectedNow(s.carBluetoothAddress)
            }
        }
    }

    fun stop() {
        val own = scope ?: return
        scope = null
        tickJob = null
        stopZone()
        notifier.cancel()
        own.cancel()
    }

    // --- входы ---------------------------------------------------------------------

    fun onBluetooth(connected: Boolean, address: String?, at: Long = now()) = post {
        if (!active || address == null || address != config.carBluetoothAddress) return@post
        if (connected == engine.carConnected) return@post
        apply(if (connected) engine.onCarConnected(at) else engine.onCarDisconnected(at))
    }

    fun onPassiveLocation(location: Location) = post { onLocation(location, precise = false) }

    fun onBarrierUsed(at: Long = now()) = post {
        if (!active) return@post
        val wasVisible = engine.promptVisible
        apply(engine.onBarrierUsed(at))
        if (wasVisible) shownTrigger?.let { notifier.show(barriers, it, used = true) }
    }

    /**
     * Догадка `direction` / `mode` для метки проезда (§4.5) — до [onBarrierUsed].
     * Читается из потока звонка без диспетчера: два поля движка, гонка безвредна —
     * в худшем случае догадка будет `UNKNOWN`, и человек поправит её в истории.
     */
    fun guessForUse(): Pair<Direction, TravelMode> {
        if (!active || !engine.carConnected) return Direction.UNKNOWN to TravelMode.UNKNOWN
        val direction = when (engine.directionHint()) {
            Trigger.DEPART -> Direction.OUT
            Trigger.ARRIVE -> Direction.IN
            null -> Direction.UNKNOWN
        }
        // Пассажир от водителя по Bluetooth не отличается; за рулём — чаще.
        return direction to TravelMode.DRIVER
    }

    fun onDismissed(at: Long = now()) = post {
        apply(engine.onDismissed(at))
    }

    // --- внутреннее ----------------------------------------------------------------

    private fun onLocation(location: Location, precise: Boolean) {
        if (!active) return
        apply(
            engine.onLocation(
                t = now(),
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 1_000f,
                precise = precise,
            ),
        )
    }

    private fun apply(actions: List<PromptAction>) {
        actions.forEach { action ->
            when (action) {
                is PromptAction.Show -> {
                    shownTrigger = action.trigger
                    notifier.show(barriers, action.trigger)
                    journal.log(
                        LogEventType.PROMPT_SHOWN,
                        mapOf(
                            "trigger" to action.trigger.name,
                            "distanceMeters" to action.distanceMeters.toInt(),
                            "zone" to engine.zoneActive,
                            "carPlace" to engine.carPlace.name,
                        ),
                    )
                }
                is PromptAction.Hide -> {
                    if (action.reason == "dismissed") {
                        journal.log(LogEventType.PROMPT_DISMISSED, mapOf("trigger" to shownTrigger?.name))
                    } else {
                        notifier.cancel()
                        journal.log(
                            LogEventType.PROMPT_HIDDEN,
                            mapOf("reason" to action.reason, "used" to action.used, "trigger" to shownTrigger?.name),
                        )
                    }
                    shownTrigger = null
                }
                is PromptAction.ZoneOn -> {
                    startZone()
                    journal.log(LogEventType.APPROACH_ZONE_ON, mapOf("distanceMeters" to action.distanceMeters.toInt()))
                }
                is PromptAction.ZoneOff -> {
                    stopZone()
                    journal.log(LogEventType.APPROACH_ZONE_OFF, mapOf("reason" to action.reason))
                }
                is PromptAction.CarPlaceChanged -> {
                    scope?.launch { settings.setCarPlace(action.place.name) }
                    journal.log(LogEventType.CAR_PLACE_CHANGED, mapOf("place" to action.place.name))
                }
                is PromptAction.CarParked -> recordParking(action.place)
                PromptAction.RequestFix -> requestFix { location ->
                    post { onLocation(location, precise = true) }
                }
            }
        }
        scheduleTick()
    }

    private fun scheduleTick() {
        tickJob?.cancel()
        val deadline = engine.nextDeadline ?: return
        tickJob = scope?.launch {
            delay((deadline - now()).coerceAtLeast(0))
            apply(engine.onTick(now()))
        }
    }

    /**
     * Зона у дома: точная локация раз в секунду (как в интенсивной сессии, §4.3),
     * в свой поток суток — пассивный поток остаётся чистым для реплея.
     */
    private fun startZone() {
        if (zoneWriter != null) return
        val writer = LocationStreamWriter(
            context,
            WriteScope.PASSIVE,
            store,
            permissions,
            streamKey = SensorStreams.LOCATION_ZONE,
            onLocation = { location -> post { onLocation(location, precise = true) } },
        )
        val started = writer.start(
            priority = LocationPriority.HIGH_ACCURACY,
            intervalMillis = ZONE_INTERVAL_MILLIS,
            minDisplacementMeters = 0f,
            looper = Looper.getMainLooper(),
        )
        if (started) {
            zoneWriter = writer
        } else {
            journal.log(LogEventType.COLLECTION_ERROR, mapOf("reason" to "location_request_failed", "layer" to "zone"))
        }
    }

    private fun stopZone() {
        zoneWriter?.stop()
        zoneWriter = null
    }

    /** Точка парковки: одна точная фиксация в момент отключения машины (§4 NEXT). */
    private fun recordParking(place: CarPlace) {
        val at = now()
        requestFix { location ->
            val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
            scope?.launch { settings.setCarParked(location.latitude, location.longitude, accuracy, at) }
            journal.log(
                LogEventType.CAR_PARKED,
                mapOf(
                    "lat" to location.latitude,
                    "lon" to location.longitude,
                    "accuracyMeters" to accuracy,
                    "place" to place.name,
                    "fixDelayMillis" to (location.time - at),
                ),
            )
        }
    }

    @SuppressLint("MissingPermission") // проверено через permissions
    private fun requestFix(onFix: (Location) -> Unit) {
        if (!permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location -> if (location != null) onFix(location) }
        }.onFailure { Log.w(TAG, "Разовая фиксация не удалась", it) }
    }

    /**
     * При старте сервиса или выборе новой машины — уже подключена ли она.
     * Иначе перезапуск процесса посреди поездки оставил бы движок в уверенности,
     * что машины нет, до следующего подключения.
     */
    @SuppressLint("MissingPermission") // проверено через permissions
    private fun checkCarConnectedNow(address: String) {
        if (!permissions.isGranted(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = context.getSystemService<BluetoothManager>()?.adapter ?: return
        listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).forEach { profile ->
            runCatching {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val connected = runCatching { proxy.connectedDevices.any { it.address == address } }
                                .getOrDefault(false)
                            runCatching { adapter.closeProfileProxy(p, proxy) }
                            if (connected) onBluetooth(connected = true, address = address)
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    },
                    profile,
                )
            }
        }
    }

    private fun post(block: () -> Unit) {
        val own = scope ?: return
        own.launch {
            runCatching(block).onFailure { Log.w(TAG, "Подсказка у шлагбаума: ошибка обработки", it) }
        }
    }

    private fun reset(reason: String) {
        if (engine.promptVisible) notifier.cancel()
        if (engine.zoneActive) {
            stopZone()
            journal.log(LogEventType.APPROACH_ZONE_OFF, mapOf("reason" to reason))
        }
        engine.reset()
        shownTrigger = null
        tickJob?.cancel()
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "PromptController"
        const val ZONE_INTERVAL_MILLIS = 1_000L
    }
}
