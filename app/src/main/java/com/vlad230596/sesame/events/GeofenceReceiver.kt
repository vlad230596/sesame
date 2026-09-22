package com.vlad230596.sesame.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Вход и выход из геофенса (§4.4) — в журнал, и больше ничего.
 *
 * Объявлен в манифесте и не экспортирован: сюда приходит **явный**
 * `PendingIntent`, созданный нами же в [GeofenceWatcher], поэтому ограничения
 * Android 8 на неявные броадкасты к нему не относятся, а Play Services шлют его
 * от нашего имени.
 *
 * `@AndroidEntryPoint` здесь неприменим (инъекция в `BroadcastReceiver` живёт в
 * `super.onReceive()` Hilt-базы, а из Kotlin этот вызов недоступен), поэтому
 * зависимости берутся через [EntryPointAccessors] — как в `service/BootReceiver`.
 */
class GeofenceReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeofenceEntryPoint {
        fun journal(): CollectorJournal
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val journal = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, GeofenceEntryPoint::class.java)
                .journal()
        }.getOrNull() ?: return

        if (event.hasError()) {
            // Самый частый случай — GEOFENCE_NOT_AVAILABLE: пользователь выключил
            // геолокацию целиком. Молчать нельзя: иначе дыра в событиях выглядела
            // бы как «приложение усыпили», а это совсем другой диагноз (§11).
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf(
                    "reason" to "geofence_event_error",
                    "code" to event.errorCode,
                    "message" to GeofenceStatusCodes.getStatusCodeString(event.errorCode),
                ),
            )
            Log.w(TAG, "Ошибка геофенса: ${event.errorCode}")
            return
        }

        val type = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> LogEventType.GEOFENCE_ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> LogEventType.GEOFENCE_EXIT
            else -> return
        }

        val receivedNanos = SystemClock.elapsedRealtimeNanos()
        val location = event.triggeringLocation

        // §6: время события — не время приёма. У геофенса оно есть по-настоящему:
        // Play Services отдают локацию, на которой сработал переход, и разница
        // между её меткой и моментом приёма — это и есть измеренная задержка
        // доставки фонового события, ради которой §2 отказалась от shadow mode.
        val eventTime = location?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val eventNanos = location?.elapsedRealtimeNanos?.takeIf { it > 0 } ?: receivedNanos

        event.triggeringGeofences.orEmpty().forEach { fence ->
            journal.log(
                type = type,
                payload = buildMap {
                    put("geofenceId", fence.requestId)
                    put("transition", event.geofenceTransition)
                    put("eventTimeSource", if (location?.time != null) "location" else "receive")
                    put("deliveryDelayMillis", (System.currentTimeMillis() - eventTime))
                    location?.let {
                        put("lat", it.latitude)
                        put("lon", it.longitude)
                        put("accuracyMeters", it.accuracy)
                        put("provider", it.provider)
                    }
                },
                eventTime = eventTime,
                elapsedRealtimeNanos = eventNanos,
            )
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"

        /** Собственное действие: приёмник наш и получает только наши интенты. */
        const val ACTION = "com.vlad230596.sesame.action.GEOFENCE_EVENT"
    }
}
