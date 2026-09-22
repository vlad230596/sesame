package com.vlad230596.sesame.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.vlad230596.sesame.call.CallBarrierRequest
import com.vlad230596.sesame.call.CallBarrierUseCase
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.StorageUsageRepository
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.ui.common.ArchiveResult
import com.vlad230596.sesame.ui.common.ArchiveShareUseCase
import com.vlad230596.sesame.ui.common.ShareRequest
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
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
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/** Звонящий аккаунт (§4.1). Выбор появляется, только когда их больше одного. */
data class PhoneAccountOption(
    val id: String,
    val label: String,
)

data class SettingsUiState(
    val barriers: List<Barrier> = emptyList(),
    val settings: SesameSettings = SesameSettings(),
    val phoneAccounts: List<PhoneAccountOption> = emptyList(),
    /** Сколько разрешений отвалилось — для красной плашки в шапке экрана (§8). */
    val missingPermissions: Int = 0,
    /** Первое отвалившееся разрешение: плашка называет его по имени. */
    val missingHint: String? = null,
    val usedBytes: Long = 0,
    val unsharedCount: Int = 0,
    val preparingArchive: Boolean = false,
    val shareRequest: ShareRequest? = null,
    val message: String? = null,
)

/**
 * Настройки (§4.6). Все значения пишутся в DataStore сразу: «Сохранить» здесь
 * нужен только формам шлагбаума и дома, где текст редактируется посимвольно.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val barrierRepository: BarrierRepository,
    private val settingsRepository: SettingsRepository,
    private val widgets: WidgetCallCoordinator,
    private val permissions: PermissionsChecker,
    private val archive: ArchiveShareUseCase,
    private val callBarrier: CallBarrierUseCase,
    storage: StorageUsageRepository,
) : ViewModel() {

    private data class Local(
        val phoneAccounts: List<PhoneAccountOption> = emptyList(),
        val missingPermissions: Int = 0,
        val missingHint: String? = null,
        val unsharedCount: Int = 0,
        val preparingArchive: Boolean = false,
        val shareRequest: ShareRequest? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsUiState> = combine(
        barrierRepository.observeAll(),
        settingsRepository.settings,
        storage.observeUsedBytes(),
        local,
    ) { barriers, settings, usedBytes, l ->
        SettingsUiState(
            barriers = barriers,
            settings = settings,
            phoneAccounts = l.phoneAccounts,
            missingPermissions = l.missingPermissions,
            missingHint = l.missingHint,
            usedBytes = usedBytes,
            unsharedCount = l.unsharedCount,
            preparingArchive = l.preparingArchive,
            shareRequest = l.shareRequest,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { barrierRepository.ensureSeeded() }
        refresh()
    }

    /**
     * Перечитывается при каждом входе на экран: разрешение `READ_PHONE_STATE`
     * могли выдать только что (до него список аккаунтов пуст), а сами разрешения
     * система отзывает у неиспользуемых приложений — экран обязан показывать
     * текущее положение дел, а не то, что было при первом открытии.
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

        val missing = permissions.missing()
        local.update {
            it.copy(
                phoneAccounts = accounts,
                missingPermissions = missing.size,
                missingHint = missing.firstOrNull()?.title,
            )
        }
        viewModelScope.launch {
            local.update { it.copy(unsharedCount = runCatching { archive.unsharedCount() }.getOrDefault(0)) }
        }
    }

    fun saveBarrier(barrier: Barrier) {
        viewModelScope.launch {
            barrierRepository.save(barrier)
            // §4.2: подписи кнопок виджета берутся из настроек — виджет обязан
            // узнать о правке сразу, а не при следующем нажатии.
            widgets.refresh()
            local.update { it.copy(message = "«${barrier.displayName}» сохранён") }
        }
    }

    /**
     * Проверочный звонок с экрана шлагбаума.
     *
     * Идёт через тот же [CallBarrierUseCase], что и кнопка на главном экране:
     * проверять надо ровно тот путь, которым пойдёт настоящее открытие, включая
     * выбор SIM и фолбэк в звонилку. Метка проезда и запись в журнале при этом
     * создаются как обычно — проверочный звонок физически открывает шлагбаум и
     * обязан остаться в датасете.
     */
    fun testCall(barrier: Barrier) {
        viewModelScope.launch {
            val result = callBarrier(
                CallBarrierRequest(
                    barrierId = barrier.id,
                    phoneNumber = barrier.phoneNumber,
                    source = LabelSource.APP,
                ),
            )
            local.update {
                it.copy(
                    message = when (result.outcome) {
                        CallOutcome.CALLED -> "Звонок ушёл — проверьте шлагбаум"
                        CallOutcome.DIALER_FALLBACK -> "Звонок не ушёл: открыта звонилка с номером"
                        CallOutcome.NO_NUMBER -> "Номер не задан"
                        CallOutcome.CANCELLED_BY_USER -> "Отменено"
                    },
                )
            }
        }
    }

    /**
     * «Взять текущую» на экране шлагбаума и дома.
     *
     * Берётся последняя известная координата, а не свежий запрос: экран
     * настраивают, стоя у шлагбаума, где координата минуту назад и координата
     * сейчас — одно и то же, а ожидание фикса GPS под аркой может не кончиться
     * никогда. Нет разрешения или нет последней координаты — возвращается `null`,
     * и UI говорит об этом словами.
     */
    fun currentLocation(onResult: (Double?, Double?) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            local.update { it.copy(message = "Нет разрешения на локацию — выдайте его в «Разрешениях»") }
            onResult(null, null)
            return
        }
        viewModelScope.launch {
            val location = runCatching { lastLocation() }.getOrNull()
            if (location == null) {
                local.update {
                    it.copy(message = "Последняя координата неизвестна. Выйдите на улицу и повторите")
                }
            }
            onResult(location?.first, location?.second)
        }
    }

    private suspend fun lastLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { continuation ->
            @Suppress("MissingPermission")
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { continuation.resume(null) }
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

    /** «Экспорт в архив» (§7) — тот же архив, что кнопка в истории. */
    fun requestShare() {
        if (local.value.preparingArchive) return
        viewModelScope.launch {
            local.update { it.copy(preparingArchive = true) }
            when (val result = archive.prepare()) {
                ArchiveResult.Empty -> local.update {
                    it.copy(
                        preparingArchive = false,
                        message = "Нечего выгружать: всё уже отмечено как выгруженное",
                    )
                }

                ArchiveResult.NoFiles -> local.update {
                    it.copy(
                        preparingArchive = false,
                        message = "Файлы сессий не найдены. Проверьте Documents/Sesame/",
                    )
                }

                is ArchiveResult.Ready -> local.update {
                    it.copy(preparingArchive = false, shareRequest = result.request)
                }
            }
        }
    }

    fun onShareLaunched(sessionIds: List<Long>) {
        viewModelScope.launch {
            archive.markShared(sessionIds)
            local.update {
                it.copy(
                    shareRequest = null,
                    unsharedCount = 0,
                    message = "Отправлено и отмечено выгруженным: ${sessionIds.size}",
                )
            }
        }
    }

    fun onShareFailed() {
        local.update { it.copy(shareRequest = null, message = "Не нашлось приложения для отправки") }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
