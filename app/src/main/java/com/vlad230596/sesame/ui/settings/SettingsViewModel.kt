package com.vlad230596.sesame.ui.settings

import android.content.Context
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.widget.WidgetCallCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Звонящий аккаунт (§4.1). Выбор появляется, только когда их больше одного. */
data class PhoneAccountOption(
    val id: String,
    val label: String,
)

data class SettingsUiState(
    val barriers: List<Barrier> = emptyList(),
    val settings: SesameSettings = SesameSettings(),
    val phoneAccounts: List<PhoneAccountOption> = emptyList(),
    val message: String? = null,
)

/**
 * Настройки (§4.6). Все значения пишутся в DataStore сразу: «Сохранить» здесь
 * нужен только формам шлагбаумов, где текст редактируется посимвольно.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val barrierRepository: BarrierRepository,
    private val settingsRepository: SettingsRepository,
    private val widgets: WidgetCallCoordinator,
) : ViewModel() {

    private data class Local(
        val phoneAccounts: List<PhoneAccountOption> = emptyList(),
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsUiState> = combine(
        barrierRepository.observeAll(),
        settingsRepository.settings,
        local,
    ) { barriers, settings, l ->
        SettingsUiState(
            barriers = barriers,
            settings = settings,
            phoneAccounts = l.phoneAccounts,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { barrierRepository.ensureSeeded() }
        refresh()
    }

    /**
     * Список звонящих аккаунтов перечитывается при каждом входе: разрешение
     * `READ_PHONE_STATE` могли выдать только что, и до него список пуст.
     */
    fun refresh() {
        val accounts = runCatching {
            val telecom = context.getSystemService<TelecomManager>() ?: return@runCatching emptyList()
            telecom.callCapablePhoneAccounts.map { handle ->
                val label = runCatching {
                    telecom.getPhoneAccount(handle)?.label?.toString()
                }.getOrNull()
                PhoneAccountOption(id = handle.id, label = label?.takeIf { it.isNotBlank() } ?: handle.id)
            }
        }.getOrElse { emptyList() }
        local.update { it.copy(phoneAccounts = accounts) }
    }

    fun saveBarrier(barrier: Barrier) {
        viewModelScope.launch {
            barrierRepository.save(barrier)
            // §4.2: подписи кнопок виджета берутся из настроек — виджет обязан
            // узнать о правке сразу, а не при следующем нажатии.
            widgets.refresh()
            local.update { it.copy(message = "«${barrier.label}» сохранён") }
        }
    }

    /**
     * Координата дома (§4.4). Дома нет в модели данных §5, поэтому он живёт
     * отдельными настройками — см. [SesameSettings.homeLat].
     */
    fun saveHome(lat: Double?, lon: Double?, radiusMeters: Int) {
        viewModelScope.launch {
            settingsRepository.setHome(lat, lon, radiusMeters)
            local.update {
                it.copy(
                    message = if (lat == null || lon == null) {
                        "Координата дома очищена — геофенс дома не регистрируется"
                    } else {
                        "Дом сохранён, геофенс перерегистрирован"
                    },
                )
            }
        }
    }

    fun setCancelTimeout(seconds: Int) {
        viewModelScope.launch { settingsRepository.setCancelTimeoutSeconds(seconds) }
    }

    fun setRecordingMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setRecordingDurationMinutes(minutes) }
    }

    fun setLocationInterval(seconds: Int) {
        viewModelScope.launch { settingsRepository.setLocationIntervalSeconds(seconds) }
    }

    fun setLocationPriority(priority: LocationPriority) {
        viewModelScope.launch { settingsRepository.setLocationPriority(priority) }
    }

    fun setLocationDisplacement(meters: Int) {
        viewModelScope.launch { settingsRepository.setLocationMinDisplacementMeters(meters) }
    }

    fun setAccelerometerHz(hz: Int) {
        viewModelScope.launch { settingsRepository.setAccelerometerHz(hz) }
    }

    fun setBarometerHz(hz: Int) {
        viewModelScope.launch { settingsRepository.setBarometerHz(hz) }
    }

    fun resetCollectionParams() {
        viewModelScope.launch {
            settingsRepository.resetCollectionParams()
            local.update { it.copy(message = "Параметры сбора сброшены к значениям по умолчанию") }
        }
    }

    fun setPhoneAccount(id: String?) {
        viewModelScope.launch { settingsRepository.setPhoneAccountId(id) }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
