package com.vlad230596.sesame.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Поднимает [CollectorService] после перезагрузки устройства и после обновления
 * приложения (§4.4, §9).
 *
 * Приёмник намеренно не помечен `@AndroidEntryPoint`: инъекция в
 * `BroadcastReceiver` выполняется в `super.onReceive()` Hilt-базы, а из Kotlin
 * этот вызов недоступен (метод абстрактный в `BroadcastReceiver`). Зависимости
 * поэтому берутся через [EntryPointAccessors] от `applicationContext`.
 */
class BootReceiver : BroadcastReceiver() {

    /** Точка доступа к графу Hilt для приёмника без `@AndroidEntryPoint`. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun journal(): CollectorJournal
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                logBoot(context, intent.action.orEmpty())
                CollectorService.start(context)
            }
        }
    }

    /**
     * §6: три времени сразу. Здесь они особенно содержательны — разница между
     * `eventTime` и `receivedTime` показывает, насколько поздно One UI отдаёт
     * `BOOT_COMPLETED`, а `elapsedRealtimeNanos` сразу после загрузки мал и
     * служит нулём отсчёта для всех последующих файлов.
     */
    private fun logBoot(context: Context, action: String) {
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootEntryPoint::class.java,
            )
            val elapsedNanos = SystemClock.elapsedRealtimeNanos()
            val bootedAt = System.currentTimeMillis() - elapsedNanos / 1_000_000L
            entryPoint.journal().log(
                type = LogEventType.DEVICE_BOOTED,
                payload = mapOf(
                    "action" to action,
                    "elapsedRealtimeMillisAtReceive" to elapsedNanos / 1_000_000L,
                ),
                // Загрузка произошла тогда, когда произошла, а не когда о ней
                // сообщили; переустановка пакета — здесь и сейчас.
                eventTime = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    System.currentTimeMillis()
                } else {
                    bootedAt
                },
                elapsedRealtimeNanos = elapsedNanos,
            )
        }
    }
}
