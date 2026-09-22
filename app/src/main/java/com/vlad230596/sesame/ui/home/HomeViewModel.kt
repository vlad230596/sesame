package com.vlad230596.sesame.ui.home

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vlad230596.sesame.call.CallBarrierRequest
import com.vlad230596.sesame.call.CallBarrierUseCase
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.PassageLabelRepository
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.service.CollectorService
import com.vlad230596.sesame.session.ActiveSession
import com.vlad230596.sesame.session.RecordingSessionController
import com.vlad230596.sesame.ui.common.title
import com.vlad230596.sesame.ui.permissions.PermissionSpec
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние таймера отмены (§4.1).
 *
 * Обратный отсчёт — не украшение, а единственная защита от случайного нажатия
 * в виджете и в кармане: пока он идёт, звонок ещё не ушёл.
 */
data class CountdownState(
    val barrierId: Long,
    val barrierLabel: String,
    val totalMillis: Long,
    val remainingMillis: Long,
) {
    /** Убывающий прогресс: 1 в начале, 0 в момент звонка. */
    val progress: Float
        get() = if (totalMillis <= 0) 0f else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    val remainingSeconds: Int
        get() = ((remainingMillis + 999) / 1000).toInt()
}

data class HomeUiState(
    val barriers: List<Barrier> = emptyList(),
    val settings: SesameSettings = SesameSettings(),
    val countdown: CountdownState? = null,
    val session: ActiveSession? = null,
    val unconfirmed: List<PassageLabel> = emptyList(),
    val lastEventAt: Long? = null,
    val usedBytes: Long = 0,
    val serviceRunning: Boolean = false,
    val missingPermissions: List<PermissionSpec> = emptyList(),
    val now: Long = System.currentTimeMillis(),
    val message: String? = null,
) {
    val unconfirmedCount: Int get() = unconfirmed.size
    fun barrierLabel(id: Long?): String? = barriers.firstOrNull { it.id == id }?.label
}

/**
 * Главный экран (§4.7).
 *
 * Вся «живость» экрана — таймер отмены, оставшееся время сессии и давность
 * последнего фонового события — считается здесь: композиции остаётся только
 * рисовать.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val barrierRepository: BarrierRepository,
    private val passageLabels: PassageLabelRepository,
    private val settingsRepository: SettingsRepository,
    private val sessions: RecordingSessionController,
    private val callBarrier: CallBarrierUseCase,
    private val permissionsChecker: PermissionsChecker,
    logEventDao: LogEventDao,
    sessionDao: RecordingSessionDao,
) : ViewModel() {

    /** Часть состояния, которой нет в базе: таймер, часы, разрешения, сообщение. */
    private data class Local(
        val countdown: CountdownState? = null,
        val now: Long = System.currentTimeMillis(),
        val serviceRunning: Boolean = false,
        val missingPermissions: List<PermissionSpec> = emptyList(),
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var countdownJob: Job? = null
    private var lastAutoStoppedSessionId: Long? = null

    val state: StateFlow<HomeUiState> = combine(
        barrierRepository.observeAll(),
        settingsRepository.settings,
        sessions.active,
        passageLabels.observeUnconfirmed(),
        combine(
            logEventDao.observeLastReceivedTime(),
            sessionDao.observeTotalSizeBytes(),
        ) { lastEvent, size -> lastEvent to size },
    ) { barriers, settings, session, unconfirmed, (lastEvent, size) ->
        HomeUiState(
            barriers = barriers,
            settings = settings,
            session = session,
            unconfirmed = unconfirmed,
            lastEventAt = lastEvent,
            usedBytes = size,
        )
    }.combine(local) { base, l ->
        base.copy(
            countdown = l.countdown,
            now = l.now,
            serviceRunning = l.serviceRunning,
            missingPermissions = l.missingPermissions,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { barrierRepository.ensureSeeded() }
        viewModelScope.launch {
            // Секунда — достаточная частота: на экране обновляются «N минут назад»
            // и обратный отсчёт сессии, оба в минутах и секундах.
            while (true) {
                local.update { it.copy(now = System.currentTimeMillis()) }
                stopSessionIfExpired()
                delay(1_000)
            }
        }
        refresh()
    }

    /** Вызывается при каждом возврате на экран: разрешения и сервис могли измениться. */
    fun refresh() {
        local.update {
            it.copy(
                serviceRunning = isCollectorRunning(),
                missingPermissions = permissionsChecker.missing(),
            )
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // --- Открытие шлагбаума (§4.1) ------------------------------------------------

    fun onBarrierPressed(barrier: Barrier) {
        val timeoutSeconds = state.value.settings.cancelTimeoutSeconds
        val running = local.value.countdown
        when {
            // Повторное нажатие той же кнопки во время отсчёта — отмена: это
            // самое быстрое движение, если кнопку задели случайно.
            running?.barrierId == barrier.id -> {
                cancelCountdown()
                return
            }
            running != null -> cancelCountdown()
        }

        if (timeoutSeconds <= 0) {
            viewModelScope.launch { performCall(barrier) }
            return
        }

        val total = timeoutSeconds * 1000L
        local.update {
            it.copy(
                countdown = CountdownState(
                    barrierId = barrier.id,
                    barrierLabel = barrier.label,
                    totalMillis = total,
                    remainingMillis = total,
                ),
            )
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(COUNTDOWN_TICK_MILLIS)
                val remaining = total - (System.currentTimeMillis() - startedAt)
                if (remaining <= 0) break
                local.update { st ->
                    val current = st.countdown ?: return@update st
                    if (current.barrierId != barrier.id) return@update st
                    st.copy(countdown = current.copy(remainingMillis = remaining))
                }
            }
            local.update { it.copy(countdown = null) }
            performCall(barrier)
        }
    }

    /**
     * «Отмена». Пишется отдельным исходом [CallOutcome.CANCELLED_BY_USER], а не
     * отсутствием события: в датасете «передумал» и «не нажимал» — разные вещи.
     */
    fun cancelCountdown() {
        val countdown = local.value.countdown ?: return
        countdownJob?.cancel()
        countdownJob = null
        local.update { it.copy(countdown = null) }
        viewModelScope.launch {
            callBarrier.recordCancelled(
                CallBarrierRequest(
                    barrierId = countdown.barrierId,
                    phoneNumber = null,
                    source = LabelSource.APP,
                ),
            )
            local.update { it.copy(message = "Отменено: ${countdown.barrierLabel}") }
        }
    }

    private suspend fun performCall(barrier: Barrier) {
        val result = callBarrier(
            CallBarrierRequest(
                barrierId = barrier.id,
                phoneNumber = barrier.phoneNumber,
                source = LabelSource.APP,
            ),
        )
        val message = when (result.outcome) {
            CallOutcome.CALLED -> "Звоним: ${barrier.label}"
            CallOutcome.DIALER_FALLBACK ->
                "Звонок не ушёл — открыта звонилка с номером. Проверьте разрешение «Звонки»"
            CallOutcome.NO_NUMBER ->
                "У «${barrier.label}» не задан номер. Настройки → Шлагбаумы"
            CallOutcome.CANCELLED_BY_USER -> "Отменено"
        }
        local.update { it.copy(message = message) }
    }

    // --- Интенсивная запись (§4.3) ------------------------------------------------

    fun startSession(label: SessionLabel) {
        viewModelScope.launch {
            sessions.start(label)
            local.update {
                it.copy(
                    message = if (label == SessionLabel.NONE) {
                        "Запись начата без метки — проставьте её потом в истории"
                    } else {
                        "Запись начата: ${label.title()}"
                    },
                )
            }
        }
    }

    fun extendSession() {
        viewModelScope.launch {
            val minutes = state.value.settings.recordingDurationMinutes
            sessions.extend(minutes)
            local.update { it.copy(message = "Запись продлена на $minutes мин") }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            sessions.stop()
            local.update { it.copy(message = "Запись остановлена") }
        }
    }

    /**
     * Жёсткий предохранитель (§4.3): сессия не может идти дольше запланированного.
     *
     * Запоминаем последнюю остановленную сессию: пока на состояние никто не
     * подписан, [state] отдаёт последнее значение, и без этой защиты тикер
     * пытался бы останавливать уже закрытую сессию каждую секунду.
     */
    private suspend fun stopSessionIfExpired() {
        val session = state.value.session ?: return
        if (session.id == lastAutoStoppedSessionId) return
        if (System.currentTimeMillis() >= session.plannedEndAt) {
            lastAutoStoppedSessionId = session.id
            sessions.stop()
            local.update { it.copy(message = "Запись остановлена по таймеру") }
        }
    }

    // --- Правка меток из плашки «без подтверждения» (§4.5) ------------------------

    fun setDirection(id: Long, direction: Direction) {
        viewModelScope.launch { passageLabels.setDirection(id, direction) }
    }

    fun setMode(id: Long, mode: TravelMode) {
        viewModelScope.launch { passageLabels.setMode(id, mode) }
    }

    fun setDiscarded(id: Long, discarded: Boolean) {
        viewModelScope.launch { passageLabels.setDiscarded(id, discarded) }
    }

    fun setNote(id: Long, note: String) {
        viewModelScope.launch { passageLabels.setNote(id, note) }
    }

    fun confirmAll() {
        viewModelScope.launch {
            state.value.unconfirmed.forEach { passageLabels.confirm(it.id) }
        }
    }

    // --- Сервис сбора (§9) --------------------------------------------------------

    fun startService() {
        runCatching { CollectorService.start(context) }
            .onFailure { local.update { st -> st.copy(message = "Сервис не запустился: ${it.message}") } }
        refresh()
    }

    fun stopService() {
        runCatching { CollectorService.stop(context) }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun isCollectorRunning(): Boolean = runCatching {
        context.getSystemService<ActivityManager>()
            ?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == CollectorService::class.java.name }
    }.getOrNull() ?: false

    private companion object {
        /** 60 мс — прогресс-бар идёт плавно, а лишней работы почти нет. */
        const val COUNTDOWN_TICK_MILLIS = 60L
    }
}
