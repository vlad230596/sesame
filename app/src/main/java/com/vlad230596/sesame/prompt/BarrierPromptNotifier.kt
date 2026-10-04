package com.vlad230596.sesame.prompt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vlad230596.sesame.R
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Уведомление со шлагбаумами (NEXT-notification-and-car.md, §1).
 *
 * Решает одну проблему — не искать приложение за рулём: кнопки шлагбаумов
 * прямо в шторке и на экране блокировки, нажатие звонит сразу. Смахнуть можно:
 * это «не сейчас», и [PromptController] на время замолкает.
 *
 * Канал с высокой важностью — уведомление всплывает поверх экрана: на подъезде
 * в шторку никто не полезет. Звук и вибрация выключены — это подсказка, а не
 * будильник.
 */
@Singleton
class BarrierPromptNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager? get() = context.getSystemService()

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.prompt_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.prompt_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        runCatching { manager?.createNotificationChannel(channel) }
    }

    /**
     * @param used звонок уже ушёл: уведомление остаётся ещё немного — проезд часто
     *        через два шлагбаума подряд, — но больше не всплывает.
     */
    fun show(barriers: List<Barrier>, trigger: Trigger, used: Boolean = false) {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sesame)
            .setContentTitle(
                context.getString(
                    if (trigger == Trigger.ARRIVE) R.string.prompt_title_arrive else R.string.prompt_title_depart,
                ),
            )
            .setContentText(context.getString(if (used) R.string.prompt_text_used else R.string.prompt_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setShowWhen(false)
            .setContentIntent(activityIntent())
            .setDeleteIntent(receiverIntent(PromptActionReceiver.ACTION_DISMISSED, null, REQUEST_DISMISS))
        // Не больше трёх: столько кнопок система показывает в уведомлении.
        barriers.sortedBy { it.orderIndex }.take(MAX_ACTIONS).forEach { barrier ->
            builder.addAction(
                0,
                barrier.displayName,
                receiverIntent(PromptActionReceiver.ACTION_CALL, barrier.id, REQUEST_CALL_BASE + barrier.id.toInt()),
            )
        }
        runCatching { manager?.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "Не удалось показать уведомление со шлагбаумами", it) }
    }

    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun receiverIntent(action: String, barrierId: Long?, requestCode: Int): PendingIntent {
        val intent = Intent(context, PromptActionReceiver::class.java).setAction(action)
        if (barrierId != null) intent.putExtra(PromptActionReceiver.EXTRA_BARRIER_ID, barrierId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "BarrierPromptNotifier"
        const val CHANNEL_ID = "sesame_barrier_prompt"

        /** 1 занят уведомлением постоянного сервиса. */
        const val NOTIFICATION_ID = 2

        private const val MAX_ACTIONS = 3
        private const val REQUEST_OPEN = 100
        private const val REQUEST_DISMISS = 101
        private const val REQUEST_CALL_BASE = 200
    }
}
