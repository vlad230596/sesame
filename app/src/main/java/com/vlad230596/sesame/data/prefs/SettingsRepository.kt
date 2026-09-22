package com.vlad230596.sesame.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Приоритет запроса локации в пассивном слое (§4.4).
 *
 * Значения совпадают с константами `com.google.android.gms.location.Priority`,
 * но хранятся строкой: настройка читается и глазами в экспорте, и кодом.
 */
enum class LocationPriority(
    val title: String,
    val hint: String,
) {
    HIGH_ACCURACY("Высокая точность", "GPS, самый большой расход батареи"),
    BALANCED("Сбалансированный", "по умолчанию: ~100 м без GPS"),
    LOW_POWER("Низкое потребление", "вышки и Wi-Fi, точность ~10 км"),
    PASSIVE("Пассивный", "только чужие запросы локации, своих нет"),
}

/**
 * Все настройки приложения (§4.6) одним снимком.
 *
 * Значения по умолчанию — из §4.1 (таймер 3 с), §4.3 (запись 15 мин) и §4.4
 * (параметры сбора). Настройки крутятся на телефоне без пересборки, поэтому
 * границы допустимых значений проверяются здесь, а не в UI.
 */
data class SesameSettings(
    /** §4.1: 0 = звонить немедленно, без подтверждения. */
    val cancelTimeoutSeconds: Int = DEFAULT_CANCEL_TIMEOUT_SECONDS,
    val recordingDurationMinutes: Int = DEFAULT_RECORDING_MINUTES,
    val locationIntervalSeconds: Int = DEFAULT_LOCATION_INTERVAL_SECONDS,
    val locationPriority: LocationPriority = LocationPriority.BALANCED,
    val locationMinDisplacementMeters: Int = DEFAULT_LOCATION_DISPLACEMENT_METERS,
    val accelerometerHz: Int = DEFAULT_ACCELEROMETER_HZ,
    val barometerHz: Int = DEFAULT_BAROMETER_HZ,
    /**
     * `PhoneAccountHandle.id` выбранной SIM (§4.1). Значим только когда звонящих
     * аккаунтов больше одного; `null` — системный выбор по умолчанию.
     */
    val phoneAccountId: String? = null,
    /**
     * Идентификатор незавершённой интенсивной сессии и её плановый конец (§4.3).
     * Лежит в настройках, а не в памяти, чтобы жёсткий предохранитель пережил
     * перезапуск процесса.
     */
    val activeSessionId: Long? = null,
    val activeSessionPlannedEndAt: Long? = null,
    /** Один раз засеянные две записи шлагбаумов (§4.6). */
    val barriersSeeded: Boolean = false,
) {
    companion object {
        const val DEFAULT_CANCEL_TIMEOUT_SECONDS = 3
        const val MAX_CANCEL_TIMEOUT_SECONDS = 10

        const val DEFAULT_RECORDING_MINUTES = 15
        const val MIN_RECORDING_MINUTES = 1
        const val MAX_RECORDING_MINUTES = 120

        const val DEFAULT_LOCATION_INTERVAL_SECONDS = 120
        const val MIN_LOCATION_INTERVAL_SECONDS = 10
        const val MAX_LOCATION_INTERVAL_SECONDS = 900

        const val DEFAULT_LOCATION_DISPLACEMENT_METERS = 25
        const val MAX_LOCATION_DISPLACEMENT_METERS = 200

        const val DEFAULT_ACCELEROMETER_HZ = 5
        const val MIN_ACCELEROMETER_HZ = 1
        const val MAX_ACCELEROMETER_HZ = 50

        const val DEFAULT_BAROMETER_HZ = 1
        const val MIN_BAROMETER_HZ = 1
        const val MAX_BAROMETER_HZ = 10

        /** §7: жёсткий лимит хранилища. */
        const val STORAGE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sesame_settings",
)

/**
 * Единственный владелец настроек (§4.6). DataStore Preferences: настроек мало,
 * они плоские, и схема им не нужна.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val CancelTimeout = intPreferencesKey("cancel_timeout_seconds")
        val RecordingMinutes = intPreferencesKey("recording_duration_minutes")
        val LocationInterval = intPreferencesKey("location_interval_seconds")
        val LocationPriorityKey = stringPreferencesKey("location_priority")
        val LocationDisplacement = intPreferencesKey("location_min_displacement_meters")
        val AccelerometerHz = intPreferencesKey("accelerometer_hz")
        val BarometerHz = intPreferencesKey("barometer_hz")
        val PhoneAccountId = stringPreferencesKey("phone_account_id")
        val ActiveSessionId = longPreferencesKey("active_session_id")
        val ActiveSessionPlannedEnd = longPreferencesKey("active_session_planned_end")
        val BarriersSeeded = booleanPreferencesKey("barriers_seeded")
    }

    val settings: Flow<SesameSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun current(): SesameSettings = settings.first()

    suspend fun setCancelTimeoutSeconds(value: Int) = edit {
        it[Keys.CancelTimeout] = value.coerceIn(0, SesameSettings.MAX_CANCEL_TIMEOUT_SECONDS)
    }

    suspend fun setRecordingDurationMinutes(value: Int) = edit {
        it[Keys.RecordingMinutes] = value.coerceIn(
            SesameSettings.MIN_RECORDING_MINUTES,
            SesameSettings.MAX_RECORDING_MINUTES,
        )
    }

    suspend fun setLocationIntervalSeconds(value: Int) = edit {
        it[Keys.LocationInterval] = value.coerceIn(
            SesameSettings.MIN_LOCATION_INTERVAL_SECONDS,
            SesameSettings.MAX_LOCATION_INTERVAL_SECONDS,
        )
    }

    suspend fun setLocationPriority(value: LocationPriority) = edit {
        it[Keys.LocationPriorityKey] = value.name
    }

    suspend fun setLocationMinDisplacementMeters(value: Int) = edit {
        it[Keys.LocationDisplacement] =
            value.coerceIn(0, SesameSettings.MAX_LOCATION_DISPLACEMENT_METERS)
    }

    suspend fun setAccelerometerHz(value: Int) = edit {
        it[Keys.AccelerometerHz] = value.coerceIn(
            SesameSettings.MIN_ACCELEROMETER_HZ,
            SesameSettings.MAX_ACCELEROMETER_HZ,
        )
    }

    suspend fun setBarometerHz(value: Int) = edit {
        it[Keys.BarometerHz] = value.coerceIn(
            SesameSettings.MIN_BAROMETER_HZ,
            SesameSettings.MAX_BAROMETER_HZ,
        )
    }

    suspend fun setPhoneAccountId(value: String?) = edit {
        if (value.isNullOrBlank()) it.remove(Keys.PhoneAccountId) else it[Keys.PhoneAccountId] = value
    }

    suspend fun setActiveSession(id: Long?, plannedEndAt: Long?) = edit {
        if (id == null) {
            it.remove(Keys.ActiveSessionId)
            it.remove(Keys.ActiveSessionPlannedEnd)
        } else {
            it[Keys.ActiveSessionId] = id
            it[Keys.ActiveSessionPlannedEnd] = plannedEndAt ?: 0L
        }
    }

    suspend fun markBarriersSeeded() = edit { it[Keys.BarriersSeeded] = true }

    /** Сброс только параметров сбора (§4.4) — остальные настройки не трогает. */
    suspend fun resetCollectionParams() = edit {
        it.remove(Keys.LocationInterval)
        it.remove(Keys.LocationPriorityKey)
        it.remove(Keys.LocationDisplacement)
        it.remove(Keys.AccelerometerHz)
        it.remove(Keys.BarometerHz)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun Preferences.toSettings(): SesameSettings {
        val defaults = SesameSettings()
        return SesameSettings(
            cancelTimeoutSeconds = this[Keys.CancelTimeout] ?: defaults.cancelTimeoutSeconds,
            recordingDurationMinutes = this[Keys.RecordingMinutes] ?: defaults.recordingDurationMinutes,
            locationIntervalSeconds = this[Keys.LocationInterval] ?: defaults.locationIntervalSeconds,
            locationPriority = this[Keys.LocationPriorityKey]
                ?.let { name -> runCatching { LocationPriority.valueOf(name) }.getOrNull() }
                ?: defaults.locationPriority,
            locationMinDisplacementMeters = this[Keys.LocationDisplacement]
                ?: defaults.locationMinDisplacementMeters,
            accelerometerHz = this[Keys.AccelerometerHz] ?: defaults.accelerometerHz,
            barometerHz = this[Keys.BarometerHz] ?: defaults.barometerHz,
            phoneAccountId = this[Keys.PhoneAccountId],
            activeSessionId = this[Keys.ActiveSessionId],
            activeSessionPlannedEndAt = this[Keys.ActiveSessionPlannedEnd]?.takeIf { it > 0 },
            barriersSeeded = this[Keys.BarriersSeeded] ?: false,
        )
    }
}
