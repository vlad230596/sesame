package com.vlad230596.sesame.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разрешения из таблицы §8.
 *
 * @param runtime требует ли разрешение запроса у пользователя. Нормальные
 *        разрешения (`FOREGROUND_SERVICE`, `ACCESS_WIFI_STATE`, …) выдаются при
 *        установке; они всё равно показаны в списке — экран состояния должен
 *        отвечать на вопрос «что вообще нужно приложению», а не только «что
 *        отвалилось».
 * @param separateStep запрашивается отдельным шагом после базовой локации
 *        (`ACCESS_BACKGROUND_LOCATION`, §8: «Разрешить всегда»).
 * @param code короткое системное имя разрешения для списка «выдано». Показывается
 *        моноширинным рядом с человеческой подписью: через месяц по скриншоту
 *        экрана надо понимать, о каком именно разрешении Android идёт речь, —
 *        а «Устройства поблизости» и `BLUETOOTH_CONNECT` связаны неочевидно.
 * @param currently что система показывает вместо выданного состояния. Нужна
 *        только там, где «не выдано» — это не пусто, а другой режим.
 */
data class PermissionSpec(
    val permission: String,
    val title: String,
    val reason: String,
    val code: String,
    val runtime: Boolean = true,
    val separateStep: Boolean = false,
    val currently: String? = null,
)

/** Таблица §8 в том же порядке. */
val SesamePermissions: List<PermissionSpec> = listOf(
    PermissionSpec(
        permission = Manifest.permission.CALL_PHONE,
        title = "Звонки без подтверждения",
        reason = "звонок шлагбауму без экрана набора",
        code = "CALL_PHONE",
    ),
    PermissionSpec(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        title = "Точная геолокация",
        reason = "локация и геофенсы",
        code = "FINE_LOCATION",
    ),
    PermissionSpec(
        permission = Manifest.permission.ACCESS_COARSE_LOCATION,
        title = "Примерная геолокация",
        reason = "локация и SSID Wi-Fi",
        code = "COARSE_LOCATION",
    ),
    PermissionSpec(
        permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        title = "Геолокация всегда",
        reason = "геофенсы и сбор при свёрнутом приложении. Выдаётся отдельно: " +
            "в системном диалоге нужно выбрать «Разрешить всегда»",
        code = "BACKGROUND_LOCATION",
        separateStep = true,
        currently = "только при использовании",
    ),
    PermissionSpec(
        permission = Manifest.permission.ACTIVITY_RECOGNITION,
        title = "Физическая активность",
        reason = "переходы IN_VEHICLE / ON_FOOT / STILL в журнале",
        code = "ACTIVITY",
    ),
    PermissionSpec(
        permission = Manifest.permission.POST_NOTIFICATIONS,
        title = "Уведомления",
        reason = "уведомление постоянного сервиса сбора",
        code = "POST_NOTIF",
    ),
    PermissionSpec(
        permission = Manifest.permission.BLUETOOTH_CONNECT,
        title = "Устройства поблизости",
        reason = "имена подключаемых устройств: магнитола — сильнейший признак «я в машине»",
        code = "BT_CONNECT",
    ),
    PermissionSpec(
        permission = Manifest.permission.READ_PHONE_STATE,
        title = "Состояние телефона",
        reason = "список звонящих аккаунтов при двух SIM",
        code = "PHONE_STATE",
    ),
    PermissionSpec(
        permission = Manifest.permission.FOREGROUND_SERVICE,
        title = "Foreground service",
        reason = "постоянный сервис сбора",
        code = "FGS",
        runtime = false,
    ),
    PermissionSpec(
        permission = Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        title = "Foreground service: локация",
        reason = "сервис с типом location",
        code = "FGS_LOCATION",
        runtime = false,
    ),
    PermissionSpec(
        permission = Manifest.permission.ACCESS_WIFI_STATE,
        title = "Состояние Wi-Fi",
        reason = "SSID подключённой сети",
        code = "WIFI_STATE",
        runtime = false,
    ),
    PermissionSpec(
        permission = Manifest.permission.RECEIVE_BOOT_COMPLETED,
        title = "Автозапуск после перезагрузки",
        reason = "поднять сервис после перезагрузки устройства",
        code = "BOOT_COMPLETED",
        runtime = false,
    ),
)

/**
 * Проверка состояния разрешений и системных исключений (§8).
 *
 * Экран разрешений — экран состояния, а не визард: Android отзывает разрешения у
 * неиспользуемых приложений, а One UI возвращает приложение в «спящие» после
 * обновлений системы. Поэтому состояние читается заново при каждом показе, а не
 * кэшируется.
 */
@Singleton
class PermissionsChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Не выданные разрешения, которые пользователь может выдать сам. */
    fun missing(): List<PermissionSpec> =
        SesamePermissions.filter { it.runtime && !isGranted(it.permission) }

    /** Отключена ли оптимизация батареи для приложения (§8). */
    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull() ?: false
}

/**
 * Системные экраны, разрешениями не являющиеся (§8). У каждого — цепочка интентов
 * от самого точного к самому общему: если ни один не открылся, UI показывает
 * текстовую инструкцию, но не падает.
 */
object SystemScreens {

    fun batteryOptimizationIntents(packageName: String): List<Intent> = listOf(
        @Suppress("BatteryLife")
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appSettings(packageName),
    )

    /**
     * «Спящие приложения» One UI. Явных компонентов у Samsung несколько, и они
     * менялись между версиями оболочки, поэтому перебираем известные и в конце
     * падаем на общий экран настроек приложения.
     */
    fun sleepingAppsIntents(packageName: String): List<Intent> = listOf(
        Intent().setClassName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        Intent().setClassName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
        Intent().setClassName(
            "com.samsung.android.sm",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        Intent(Settings.ACTION_APPLICATION_SETTINGS),
        appSettings(packageName),
    )

    fun appSettings(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /**
     * Пробует интенты по очереди. Возвращает `false`, если не открылся ни один —
     * вызывающий показывает текст, а не падает.
     */
    fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        intents.forEach { intent ->
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return true
        }
        return false
    }
}
