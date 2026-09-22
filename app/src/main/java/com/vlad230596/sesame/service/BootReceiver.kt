package com.vlad230596.sesame.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Поднимает [CollectorService] после перезагрузки устройства и после обновления
 * приложения (§4.4, §9). Сам факт перезагрузки в дальнейшем пойдёт в журнал
 * как `DEVICE_BOOTED`.
 *
 * Приёмник намеренно не помечен `@AndroidEntryPoint`: инъекция в
 * `BroadcastReceiver` выполняется в `super.onReceive()` Hilt-базы, а из Kotlin
 * этот вызов недоступен (метод абстрактный в `BroadcastReceiver`). Когда
 * приёмнику понадобятся зависимости, их следует получать через
 * `dagger.hilt.android.EntryPointAccessors` от `applicationContext`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                // TODO(v0): записать LogEvent(DEVICE_BOOTED) с тремя временами (§6).
                CollectorService.start(context)
            }
        }
    }
}
