package com.vlad230596.sesame.service

import android.content.Context
import android.util.Log
import com.vlad230596.sesame.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Поднимает сбор, если он лежит, а владелец его не останавливал.
 *
 * 17.11 сбор умер при обновлении приложения, `MY_PACKAGE_REPLACED` его не
 * поднял, и больше суток «Где машина» показывала парковку 16.11 как текущую.
 * Почему приёмник не сработал, воспроизвести не удалось. Поэтому подъём не
 * держится на одном броадкасте: процесс приложения будят переходы активности,
 * геофенсы и загрузка устройства, и каждый такой старт проверяет сбор
 * ([com.vlad230596.sesame.SesameApp.onCreate]).
 *
 * Запуск foreground service из фона Android 12+ разрешает приложению,
 * снятому с оптимизации батареи (экран разрешений этого требует). Иначе
 * [CollectorService.start] тихо не сработает — как и раньше.
 */
@Singleton
class CollectorAutostart @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureRunning(reason: String) {
        if (CollectorService.running.value) return
        scope.launch {
            if (settings.current().collectionStoppedByUser) return@launch
            if (CollectorService.running.value) return@launch
            Log.i(TAG, "Сбор не идёт — поднимаем ($reason)")
            CollectorService.start(context)
        }
    }

    private companion object {
        const val TAG = "CollectorAutostart"
    }
}
