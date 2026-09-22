package com.vlad230596.sesame.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Переходы Activity Recognition (§4.4) — **только запись в журнал**.
 *
 * Ни одного действия, кроме записи, здесь появиться не должно: §4.4 прямо
 * запрещает вешать на этот API триггеры, потому что он врёт и опаздывает на
 * десятки секунд. Насколько именно опаздывает — как раз и покажет разница
 * `eventTime` и `receivedTime` (§6), которую этот приёмник умеет посчитать
 * честно: у события есть собственное `elapsedRealTimeNanos`.
 *
 * Объявлен в манифесте и не экспортирован — приходит явный `PendingIntent`,
 * созданный нами (см. [GeofenceReceiver] о том же).
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ActivityEntryPoint {
        fun journal(): CollectorJournal
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val journal = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, ActivityEntryPoint::class.java)
                .journal()
        }.getOrNull() ?: return

        val nowMillis = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()

        result.transitionEvents.forEach { event ->
            // Монотонное время события известно точно; стенное восстанавливается
            // из него сдвигом от «сейчас» — часы могли перевести, а
            // elapsedRealtime нет (§6).
            val ageMillis = ((nowNanos - event.elapsedRealTimeNanos) / 1_000_000L)
                .coerceAtLeast(0L)
            journal.log(
                type = LogEventType.ACTIVITY_TRANSITION,
                payload = mapOf(
                    "activity" to activityName(event.activityType),
                    "activityType" to event.activityType,
                    "transition" to transitionName(event.transitionType),
                    "deliveryDelayMillis" to ageMillis,
                    "eventTimeSource" to "elapsedRealtime",
                ),
                eventTime = nowMillis - ageMillis,
                elapsedRealtimeNanos = event.elapsedRealTimeNanos,
            )
        }
    }

    private fun activityName(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.WALKING -> "WALKING"
        else -> "UNKNOWN_$type"
    }

    private fun transitionName(type: Int): String = when (type) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
        else -> "UNKNOWN_$type"
    }

    companion object {
        const val ACTION = "com.vlad230596.sesame.action.ACTIVITY_TRANSITION"
    }
}
