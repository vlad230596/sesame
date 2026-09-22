package com.vlad230596.sesame.logging

import android.hardware.Sensor

/**
 * Имена потоков и столбцов CSV (§5, §6).
 *
 * Состав датчиков определяется в рантайме (§3), поэтому таблица здесь — не
 * закрытый список, а набор человекочитаемых имён для того, что встречается
 * заведомо; всё остальное получает имя из `Sensor.getStringType()`, а столбцы —
 * `v0…vN`. Первый столбец всегда `elapsed_realtime_nanos` — сырое монотонное
 * время события без преобразований (§6), второй — `accuracy`: для магнитометра
 * это состояние калибровки, без которого его показания читать нельзя.
 */
object SensorStreams {

    const val COLUMN_TIME = "elapsed_realtime_nanos"
    const val COLUMN_ACCURACY = "accuracy"

    const val ACCELEROMETER = "accel"
    const val GYROSCOPE = "gyro"
    const val MAGNETOMETER = "mag"
    const val BAROMETER = "baro"
    const val STEP_COUNTER = "steps"
    const val LOCATION = "location"

    /** Столбцы `location.csv.gz`; первый — общий `elapsed_realtime_nanos`. */
    val LOCATION_COLUMNS: List<String> = listOf(
        COLUMN_TIME,
        "time_millis",
        "latitude",
        "longitude",
        "accuracy_m",
        "altitude_m",
        "altitude_accuracy_m",
        "speed_mps",
        "speed_accuracy_mps",
        "bearing_deg",
        "bearing_accuracy_deg",
        "provider",
    )

    /** Базовое имя файла датчика без расширения. */
    fun streamKey(sensor: Sensor): String = when (sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> ACCELEROMETER
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accel_uncal"
        Sensor.TYPE_GYROSCOPE -> GYROSCOPE
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyro_uncal"
        Sensor.TYPE_MAGNETIC_FIELD -> MAGNETOMETER
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "mag_uncal"
        Sensor.TYPE_PRESSURE -> BAROMETER
        Sensor.TYPE_STEP_COUNTER -> STEP_COUNTER
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_accel"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "geomag_rotation_vector"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity"
        else -> fallbackKey(sensor)
    }

    /**
     * Сколько значений `SensorEvent.values` осмысленно. Для известных датчиков
     * длина массива может быть больше содержательной части, для неизвестных —
     * берём как есть.
     */
    fun valueCount(sensor: Sensor, actual: Int): Int {
        val expected = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            -> 3

            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            -> 6

            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            -> 5

            Sensor.TYPE_GAME_ROTATION_VECTOR -> 4

            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            -> 1

            else -> actual
        }
        return minOf(expected, actual)
    }

    /**
     * Имена столбцов значений. Заголовок пишется в момент первого отсчёта, когда
     * фактическая длина массива уже известна, — см. [GzipCsvWriter].
     */
    fun columns(sensorType: Int, valueCount: Int): List<String> {
        val names: List<String> = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            -> listOf("x", "y", "z")

            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            -> listOf("x", "y", "z", "bias_x", "bias_y", "bias_z")

            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            -> listOf("x", "y", "z", "w", "heading_accuracy")

            Sensor.TYPE_GAME_ROTATION_VECTOR -> listOf("x", "y", "z", "w")
            Sensor.TYPE_PRESSURE -> listOf("pressure_hpa")
            Sensor.TYPE_STEP_COUNTER -> listOf("steps_since_boot")
            Sensor.TYPE_STEP_DETECTOR -> listOf("step")
            Sensor.TYPE_LIGHT -> listOf("lux")
            Sensor.TYPE_PROXIMITY -> listOf("distance_cm")
            Sensor.TYPE_AMBIENT_TEMPERATURE -> listOf("temperature_c")
            Sensor.TYPE_RELATIVE_HUMIDITY -> listOf("humidity_percent")
            else -> emptyList()
        }
        val values = (0 until valueCount).map { index -> names.getOrElse(index) { "v$index" } }
        return listOf(COLUMN_TIME, COLUMN_ACCURACY) + values
    }

    private fun fallbackKey(sensor: Sensor): String {
        val raw = sensor.stringType.orEmpty().substringAfterLast('.')
        val sanitized = buildString(raw.length) {
            raw.lowercase().forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
            }
        }.trim('_')
        return sanitized.ifEmpty { "type_${sensor.type}" }
    }
}
