package com.vlad230596.sesame.sensors

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.logging.DataFileStore
import com.vlad230596.sesame.logging.SensorStreams
import com.vlad230596.sesame.logging.WriteScope
import com.vlad230596.sesame.ui.permissions.PermissionsChecker

/**
 * Подписка на локацию и запись `location.csv.gz` (§4.4, §4.3).
 *
 * Одна реализация на два режима: пассивный слой просит редкие и дешёвые
 * обновления, интенсивная сессия — 1 Гц с `PRIORITY_HIGH_ACCURACY`. Отличаются
 * только параметры запроса, формат строки один, чтобы оффлайн оба потока
 * читались одинаково.
 *
 * Разрешение проверяется до подписки: без него не падать, а фиксировать в
 * журнале — это делает вызывающий по возвращённому `false`.
 */
class LocationStreamWriter(
    context: Context,
    private val scope: WriteScope,
    private val store: DataFileStore,
    private val permissions: PermissionsChecker,
) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null

    /** Последняя записанная локация: догадки `direction`/`mode` в §4.5 — следующий заход. */
    @Volatile
    var lastLocation: Location? = null
        private set

    val hasPermission: Boolean
        get() = permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            permissions.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun start(
        priority: LocationPriority,
        intervalMillis: Long,
        minDisplacementMeters: Float,
        looper: Looper,
    ): Boolean {
        if (callback != null) return true
        if (!hasPermission) return false

        store.declareStream(SensorStreams.LOCATION) { SensorStreams.LOCATION_COLUMNS }

        val request = LocationRequest.Builder(priority.toGmsPriority(), intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setMinUpdateDistanceMeters(minDisplacementMeters)
            // Ждать «точную» первую фиксацию незачем: пропущенное обновление в
            // покое — это нормальное поведение по §4.4, а не потеря данных.
            .setWaitForAccurateLocation(false)
            .build()

        val target = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { write(it) }
            }
        }
        return runCatching {
            client.requestLocationUpdates(request, target, looper)
            callback = target
            true
        }.onFailure { Log.w(TAG, "Подписка на локацию не удалась", it) }.getOrDefault(false)
    }

    fun stop() {
        val target = callback ?: return
        callback = null
        runCatching { client.removeLocationUpdates(target) }
            .onFailure { Log.w(TAG, "Не удалось снять подписку на локацию", it) }
    }

    private fun write(location: Location) {
        lastLocation = location
        val cells = arrayOf(
            location.time.toString(),
            location.latitude.toString(),
            location.longitude.toString(),
            if (location.hasAccuracy()) location.accuracy.toString() else "",
            if (location.hasAltitude()) location.altitude.toString() else "",
            if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toString() else "",
            if (location.hasSpeed()) location.speed.toString() else "",
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toString() else "",
            if (location.hasBearing()) location.bearing.toString() else "",
            if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toString() else "",
            location.provider.orEmpty(),
        )
        // §6: в CSV идёт сырой elapsedRealtimeNanos самой фиксации, а не момент
        // её доставки — доставка у локации опаздывает заметно.
        store.writeRow(scope, SensorStreams.LOCATION, location.elapsedRealtimeNanos, cells)
    }

    private companion object {
        const val TAG = "LocationStreamWriter"
    }
}

/** §4.4: приоритет запроса локации хранится строкой, а в GMS уходит константой. */
fun LocationPriority.toGmsPriority(): Int = when (this) {
    LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    LocationPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
}
