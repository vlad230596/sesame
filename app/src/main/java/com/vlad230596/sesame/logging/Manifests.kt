package com.vlad230596.sesame.logging

import android.content.Context
import android.hardware.Sensor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.vlad230596.sesame.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Манифесты `session.json` и `day.json` (§6).
 *
 * Каждый манифест содержит:
 *
 * - список **фактически доступных** датчиков с параметрами (производитель,
 *   разрешение, максимальная частота) и — отдельным списком — тех, которых на
 *   устройстве не оказалось: отсутствие датчика не ошибка, а запись в манифест (§3);
 * - метку сессии, версию приложения, модель устройства и версию Android;
 * - пару `(elapsedRealtimeNanos, System.currentTimeMillis())`, снятую в момент
 *   старта, часовой пояс и идентификатор загрузки устройства. Без этой пары
 *   сырые `elapsedRealtimeNanos` в CSV невозможно привязать к календарю (§6).
 */
object Manifests {

    const val SESSION_FILE = "session.json"
    const val DAY_FILE = "day.json"

    const val SCHEMA_VERSION = 1

    /**
     * Снимок «монотонное время ↔ стенные часы». Снимается один раз при открытии
     * файла и больше не пересчитывается: перевод часов и дрейф NTP не должны
     * задним числом сдвигать уже записанные отсчёты (§6).
     */
    fun clockSnapshot(context: Context): JSONObject {
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val wallMillis = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val offsetSeconds = ZonedDateTime.now(zone).offset.totalSeconds
        return JSONObject().apply {
            put("elapsedRealtimeNanos", elapsedNanos)
            put("currentTimeMillis", wallMillis)
            put("timeZone", zone.id)
            put("utcOffsetSeconds", offsetSeconds)
            // Избыточно, но удобно: момент загрузки в стенных часах — именно та
            // величина, которую иначе приходится считать руками.
            put("bootTimeMillis", wallMillis - elapsedNanos / 1_000_000L)
            put("bootId", bootId(context))
        }
    }

    fun appInfo(): JSONObject = JSONObject().apply {
        put("packageName", BuildConfig.APPLICATION_ID)
        put("versionName", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("buildType", BuildConfig.BUILD_TYPE)
    }

    fun deviceInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("fingerprint", Build.FINGERPRINT)
    }

    /**
     * Параметры одного датчика так, как их вернул рантайм (§3, §6).
     *
     * @param requestedDelayUs с какой периодичностью мы попросили отсчёты.
     * @param requestedMaxReportLatencyUs аппаратная пакетная передача: сколько
     *        разрешено копить в FIFO датчика перед доставкой.
     */
    fun sensorInfo(
        sensor: Sensor,
        streamKey: String,
        requestedDelayUs: Int,
        requestedMaxReportLatencyUs: Int,
    ): JSONObject = JSONObject().apply {
        put("stream", streamKey)
        put("file", "$streamKey.csv.gz")
        put("name", sensor.name)
        put("vendor", sensor.vendor)
        put("type", sensor.type)
        put("stringType", sensor.stringType)
        put("version", sensor.version)
        put("resolution", sensor.resolution.toDouble())
        put("maximumRange", sensor.maximumRange.toDouble())
        put("power", sensor.power.toDouble())
        put("minDelayUs", sensor.minDelay)
        put("maxDelayUs", sensor.maxDelay)
        put("maxFrequencyHz", if (sensor.minDelay > 0) 1_000_000.0 / sensor.minDelay else 0.0)
        put("reportingMode", sensor.reportingMode)
        put("fifoMaxEventCount", sensor.fifoMaxEventCount)
        put("fifoReservedEventCount", sensor.fifoReservedEventCount)
        put("isWakeUpSensor", sensor.isWakeUpSensor)
        put("requestedDelayUs", requestedDelayUs)
        put("requestedMaxReportLatencyUs", requestedMaxReportLatencyUs)
        put("requestedHz", if (requestedDelayUs > 0) 1_000_000.0 / requestedDelayUs else 0.0)
    }

    fun locationInfo(
        available: Boolean,
        priority: String,
        intervalMillis: Long,
        minDisplacementMeters: Float,
        permissionGranted: Boolean,
    ): JSONObject = JSONObject().apply {
        put("stream", SensorStreams.LOCATION)
        put("file", "${SensorStreams.LOCATION}.csv.gz")
        put("available", available)
        put("permissionGranted", permissionGranted)
        put("priority", priority)
        put("intervalMillis", intervalMillis)
        put("minDisplacementMeters", minDisplacementMeters.toDouble())
        put("columns", JSONArray(SensorStreams.LOCATION_COLUMNS))
    }

    fun strings(values: Collection<String>): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    /**
     * `Settings.Global.BOOT_COUNT` меняется при каждой загрузке и разрешений не
     * требует: вместе с `bootTimeMillis` однозначно отвечает на вопрос «эти
     * `elapsedRealtimeNanos` из той же загрузки или уже из следующей».
     */
    private fun bootId(context: Context): String = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString()
    }.getOrDefault("unknown")
}
