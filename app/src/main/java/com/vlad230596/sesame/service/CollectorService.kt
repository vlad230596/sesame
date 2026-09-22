package com.vlad230596.sesame.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vlad230596.sesame.R
import com.vlad230596.sesame.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственный foreground service приложения (§9).
 *
 * Живёт постоянно, поднимается при старте системы, ведёт пассивный слой (§4.4)
 * и по команде переходит в режим интенсивной записи (§4.3). Уведомление — канал
 * с `IMPORTANCE_MIN`, одна строка, без кнопок.
 *
 * В каркасе сервис только поднимается и останавливается: сбора данных нет.
 */
@AndroidEntryPoint
class CollectorService : android.app.Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundSafely()
        }
        // TODO(v0): запустить пассивный слой (PassiveSensorCollector) и приёмники событий.
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO(v0): остановить пассивный слой, дописать и закрыть файлы.
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            // Android 14+ не даёт поднять FGS типа location без выданного разрешения
            // на геолокацию. Это не падение: экран состояния (§8) покажет, чего не хватает.
            Log.w(TAG, "Не удалось поднять foreground service типа location", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sesame)
            .setContentTitle(getString(R.string.notification_collector_title))
            .setContentText(getString(R.string.notification_collector_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CollectorService"

        const val CHANNEL_ID = "sesame_collector"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.vlad230596.sesame.action.START_COLLECTOR"
        const val ACTION_STOP = "com.vlad230596.sesame.action.STOP_COLLECTOR"

        fun start(context: Context) {
            val intent = Intent(context, CollectorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CollectorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
