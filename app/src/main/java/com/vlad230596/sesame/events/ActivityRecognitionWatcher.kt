package com.vlad230596.sesame.events

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Подписка на переходы Activity Recognition (§4.4).
 *
 * **Переходы пишутся в журнал и не запускают ничего.** Это прямое требование
 * §4.4 и §2: API врёт и опаздывает на десятки секунд, и повесить на него запуск
 * интенсивной записи значило бы набрать мусорных окон. Здесь нет и не должно
 * появиться ни одного вызова, кроме записи события.
 *
 * Подписка, как и геофенсы, живёт в системе, а не в процессе: при остановке
 * сервиса она не снимается, потому что её ценность именно в доставке, когда
 * процесса нет. Перезагрузка устройства её стирает — сервис поднимается по
 * `BOOT_COMPLETED` и подписывается заново.
 */
@Singleton
class ActivityRecognitionWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionsChecker,
    private val journal: CollectorJournal,
) {

    @Volatile
    private var requested = false

    /** О нехватке разрешения говорим один раз, а не на каждую попытку. */
    @Volatile
    private var complained = false

    /**
     * Повторная попытка подписаться. Зовётся сторожем сервиса: разрешение
     * «Распознавание активности» могли выдать уже после старта сбора. Молчалива
     * по построению — жалуется только [start], и только однажды.
     */
    fun retry() {
        if (requested) return
        if (!permissions.isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) return
        start()
    }

    fun start() {
        if (requested) return
        if (!permissions.isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // §8: не падать, а фиксировать. Иначе пустой поток переходов в
            // датасете неотличим от «система ничего не распознала».
            if (complained) return
            complained = true
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "activity_recognition_permission_missing"),
            )
            return
        }
        complained = false

        val transitions = TRACKED_ACTIVITIES.flatMap { activity ->
            listOf(
                ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                ActivityTransition.ACTIVITY_TRANSITION_EXIT,
            ).map { transition ->
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(transition)
                    .build()
            }
        }

        runCatching {
            @Suppress("MissingPermission")
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions),
                    pendingIntent(),
                )
                .addOnSuccessListener {
                    requested = true
                    Log.i(TAG, "Подписка на переходы активности оформлена")
                }
                .addOnFailureListener { error ->
                    journal.log(
                        LogEventType.COLLECTION_ERROR,
                        mapOf(
                            "reason" to "activity_transition_request_failed",
                            "message" to (error.message ?: error::class.java.simpleName),
                        ),
                    )
                }
        }.onFailure { error ->
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "activity_transition_threw", "message" to error.message),
            )
        }
    }

    /** Полная отписка. В v0 не вызывается: подписка обязана пережить сервис. */
    fun remove() {
        runCatching {
            @Suppress("MissingPermission")
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent())
        }
        requested = false
    }

    /** См. [GeofenceWatcher]: Play Services дописывают результат в интент. */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ActivityTransitionReceiver::class.java)
            .setAction(ActivityTransitionReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "ActivityRecognition"
        const val REQUEST_CODE = 1002

        /**
         * §4.4 называет `IN_VEHICLE`, `ON_FOOT`, `STILL`, «…». Берём весь
         * осмысленный набор: различить пешком/бегом/на велосипеде оффлайн
         * полезно, а стоит это ничего — события редкие.
         *
         * `TILTING` и `UNKNOWN` в переходах не поддерживаются и вызывают отказ
         * всего запроса целиком, поэтому их здесь нет.
         */
        val TRACKED_ACTIVITIES = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
        )
    }
}
