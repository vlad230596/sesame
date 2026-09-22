package com.vlad230596.sesame.events

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Одна зона наблюдения. Радиус и координата — из настроек (§4.4, §4.6).
 *
 * [id] — то, что вернётся в `GeofencingEvent` и попадёт в журнал, поэтому он
 * стабилен между перерегистрациями: `home` и `barrier-<id>`.
 */
data class GeofenceSpec(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Float,
) {
    companion object {
        const val HOME_ID = "home"
        fun barrierId(barrierId: Long): String = "barrier-$barrierId"
    }
}

/**
 * Геофенсы (§4.4): 500 м вокруг дома и по 100 м вокруг каждого шлагбаума.
 *
 * **Зачем они в v0.** Не ради функциональности: ни одна кнопка от них не
 * зависит. Это измерительный прибор для §11 — единственный вопрос, на который
 * нельзя ответить оффлайн по датасету, это усыпляет ли One UI приложение и с
 * какой задержкой Android реально доставляет фоновые события. Поэтому у каждого
 * события в журнале три времени (§6), а `eventTime` берётся из
 * `triggeringLocation`, а не из момента приёма.
 *
 * **Что здесь намеренно не делается.**
 * - Начальный триггер выключен (`initialTrigger = 0`). Включённый выдал бы ENTER
 *   сразу после каждой перерегистрации, с координатой, снятой заметно раньше, —
 *   и в датасете это выглядело бы как гигантская задержка доставки, то есть
 *   ровно портило бы ту метрику, ради которой геофенсы и заведены.
 * - При остановке сервиса геофенсы **не снимаются**. Они живут в системе, а не в
 *   нашем процессе, и должны срабатывать именно тогда, когда процесса нет.
 *   Перезагрузка их всё-таки стирает — поэтому [start] зовётся при каждом старте
 *   сервиса, а сервис поднимается по `BOOT_COMPLETED`.
 *
 * Перерегистрация после правки координат в настройках — бесплатное следствие
 * подписки на настройки и на список шлагбаумов: список зон пересобирается,
 * и если он изменился, регистрация повторяется.
 */
@Singleton
class GeofenceWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val barriers: BarrierRepository,
    private val permissions: PermissionsChecker,
    private val journal: CollectorJournal,
) {

    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /**
     * Что зарегистрировано этим процессом. После перезапуска процесса пусто —
     * и первая же сборка списка перерегистрирует зоны заново. Это правильно:
     * после перезагрузки устройства геофенсов в системе нет.
     */
    @Volatile
    private var registered: List<GeofenceSpec>? = null

    /** Последний посчитанный список зон — для повторной попытки регистрации. */
    @Volatile
    private var lastSpecs: List<GeofenceSpec>? = null

    /** Набор зон, о нехватке разрешения для которого уже сказано в журнале. */
    @Volatile
    private var complainedFor: List<GeofenceSpec>? = null

    /** Зовётся при каждом старте сбора: и обычном, и после перезагрузки. */
    fun start() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            combine(settings.settings, barriers.observeAll()) { config, list -> specs(config, list) }
                .distinctUntilChanged()
                .collect {
                    lastSpecs = it
                    apply(it)
                }
        }
    }

    /**
     * Повторная попытка зарегистрировать те же зоны.
     *
     * Нужна ровно для одного сценария, зато частого: «Разрешить всегда» выдают
     * уже после того, как сбор запустился. Настройки при этом не менялись, новых
     * событий в подписке нет, и без этой попытки геофенсы молчали бы до
     * следующего перезапуска сервиса — то есть эксперимент §11 не состоялся бы
     * и никто бы этого не заметил.
     *
     * Когда регистрировать нечего или всё уже зарегистрировано, стоит два
     * сравнения, поэтому её не жалко звать из сторожа сервиса.
     */
    fun retry() {
        val specs = lastSpecs ?: return
        if (registered == specs) return
        apply(specs)
    }

    /**
     * Прекращает следить за изменениями настроек. Сами геофенсы остаются
     * зарегистрированными в системе — см. описание класса.
     */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    /** Только для полного отказа от зон (в v0 не вызывается из UI). */
    fun removeAll() {
        runCatching { client.removeGeofences(pendingIntent()) }
        registered = emptyList()
        lastSpecs = emptyList()
    }

    private fun specs(config: SesameSettings, list: List<Barrier>): List<GeofenceSpec> = buildList {
        val lat = config.homeLat
        val lon = config.homeLon
        // §4.4: координаты по умолчанию пустые. Незаданный дом — не ошибка.
        if (lat != null && lon != null) {
            add(
                GeofenceSpec(
                    id = GeofenceSpec.HOME_ID,
                    label = "Дом",
                    lat = lat,
                    lon = lon,
                    radiusMeters = config.homeRadiusMeters.toFloat(),
                ),
            )
        }
        list.forEach { barrier ->
            val bLat = barrier.lat ?: return@forEach
            val bLon = barrier.lon ?: return@forEach
            add(
                GeofenceSpec(
                    id = GeofenceSpec.barrierId(barrier.id),
                    label = barrier.label,
                    lat = bLat,
                    lon = bLon,
                    radiusMeters = barrier.radiusMeters,
                ),
            )
        }
    }

    private fun apply(specs: List<GeofenceSpec>) {
        if (registered == specs) return

        if (!hasPermissions()) {
            // §8: без разрешения не падать, а фиксировать в журнале. Экран
            // состояния покажет, чего не хватает; здесь же остаётся след в
            // датасете, иначе отсутствие событий геофенсов необъяснимо.
            //
            // Ровно один раз на набор зон: сюда заходит и сторож сервиса раз в
            // десять секунд, и запись каждой его попытки превратила бы журнал в
            // мусор, а строку «последнее фоновое событие» на главном экране —
            // во вечное «только что» при полностью мёртвом сборе.
            if (complainedFor == specs) return
            complainedFor = specs
            journal.log(
                LogEventType.LOCATION_PERMISSION_MISSING,
                mapOf(
                    "layer" to "geofence",
                    "backgroundLocation" to
                        permissions.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    "fineLocation" to
                        permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
                    "requested" to specs.size,
                ),
            )
            return
        }

        complainedFor = null
        val pending = pendingIntent()
        // Снимаем всё разом: иначе зона с изменившейся координатой осталась бы
        // висеть по старому месту рядом с новой.
        runCatching { client.removeGeofences(pending) }
            .onFailure { Log.w(TAG, "Не удалось снять геофенсы", it) }

        if (specs.isEmpty()) {
            registered = specs
            Log.i(TAG, "Координаты не заданы — геофенсов нет")
            return
        }

        val request = GeofencingRequest.Builder()
            // См. описание класса: начальный триггер испортил бы измерение задержки.
            .setInitialTrigger(0)
            .addGeofences(specs.map { it.toGeofence() })
            .build()

        runCatching {
            @Suppress("MissingPermission")
            client.addGeofences(request, pending)
                .addOnSuccessListener {
                    registered = specs
                    Log.i(TAG, "Геофенсы зарегистрированы: ${specs.map { it.id }}")
                }
                .addOnFailureListener { error ->
                    registered = null
                    journal.log(
                        LogEventType.COLLECTION_ERROR,
                        mapOf(
                            "reason" to "geofence_register_failed",
                            "message" to (error.message ?: error::class.java.simpleName),
                            "count" to specs.size,
                        ),
                    )
                    Log.w(TAG, "Регистрация геофенсов не удалась", error)
                }
        }.onFailure { error ->
            registered = null
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "geofence_register_threw", "message" to error.message),
            )
        }
    }

    private fun hasPermissions(): Boolean =
        permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            permissions.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun GeofenceSpec.toGeofence(): Geofence = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(lat, lon, radiusMeters)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        // Ноль — «отдавать как можно быстрее». Мы измеряем задержку доставки,
        // а не экономим на ней батарею: §11 требует знать её настоящую величину.
        .setNotificationResponsiveness(0)
        .build()

    /**
     * `FLAG_MUTABLE` обязателен: Play Services дописывают в интент результат
     * срабатывания, и с неизменяемым интентом приёмник получал бы пустоту.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "GeofenceWatcher"
        const val REQUEST_CODE = 1001
    }
}
