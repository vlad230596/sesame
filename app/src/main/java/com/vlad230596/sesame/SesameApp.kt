package com.vlad230596.sesame

import android.app.Application
import com.vlad230596.sesame.service.CollectorAutostart
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Точка входа приложения: Hilt и подъём сбора при каждом старте процесса.
 */
@HiltAndroidApp
class SesameApp : Application() {

    @Inject lateinit var autostart: CollectorAutostart

    override fun onCreate() {
        super.onCreate()
        // Процесс стартует ради приёмника, сервиса или экрана — в любом случае
        // сбор должен идти, если владелец его не останавливал.
        autostart.ensureRunning("process_start")
    }
}
