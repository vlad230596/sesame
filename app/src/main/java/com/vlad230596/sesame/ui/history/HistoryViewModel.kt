package com.vlad230596.sesame.ui.history

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.PassageLabelRepository
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.entity.RecordingSession
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.formatDateTime
import com.vlad230596.sesame.ui.common.title
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Элемент общей ленты (§4.5): метки проездов и сессии записи вперемешку,
 * по времени. Разделять их по вкладкам нельзя — важен именно порядок событий
 * дня, а не отдельно взятый тип.
 */
sealed interface HistoryItem {
    val timestamp: Long
    val key: String

    data class Passage(
        val label: PassageLabel,
        val barrierLabel: String?,
    ) : HistoryItem {
        override val timestamp: Long get() = label.timestamp
        override val key: String get() = "passage-${label.id}"
    }

    data class Session(val session: RecordingSession) : HistoryItem {
        override val timestamp: Long get() = session.startedAt
        override val key: String get() = "session-${session.id}"
    }
}

/** Готовый share sheet и список сессий, которые он выгружает (§7). */
data class ShareRequest(
    val intent: Intent,
    val sessionIds: List<Long>,
)

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val barriers: List<Barrier> = emptyList(),
    val unsharedCount: Int = 0,
    val shareRequest: ShareRequest? = null,
    val message: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val passageLabels: PassageLabelRepository,
    private val sessionDao: RecordingSessionDao,
    barrierRepository: BarrierRepository,
) : ViewModel() {

    private data class Local(
        val shareRequest: ShareRequest? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<HistoryUiState> = combine(
        passageLabels.observeAll(),
        sessionDao.observeAll(),
        barrierRepository.observeAll(),
        local,
    ) { labels, sessions, barriers, l ->
        val byId = barriers.associateBy { it.id }
        val items = buildList<HistoryItem> {
            labels.forEach { add(HistoryItem.Passage(it, byId[it.barrierId]?.label)) }
            sessions.forEach { add(HistoryItem.Session(it)) }
        }.sortedByDescending { it.timestamp }

        HistoryUiState(
            items = items,
            barriers = barriers,
            unsharedCount = sessions.count { !it.shared && it.endedAt != null },
            shareRequest = l.shareRequest,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

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

    /**
     * Ретро-метка (§4.5): шлагбаум открыл кто-то другой из машины, либо
     * приложение не сработало.
     */
    fun addManual(
        timestamp: Long,
        barrierId: Long?,
        direction: Direction,
        mode: TravelMode,
        note: String?,
    ) {
        viewModelScope.launch {
            passageLabels.addManual(timestamp, barrierId, direction, mode, note)
            local.update { it.copy(message = "Проезд добавлен") }
        }
    }

    /**
     * «Поделиться непошаренным» (§7).
     *
     * Основной способ забрать данные — USB: файлы лежат в `Documents/Sesame/`
     * и видны с компьютера без дополнительных действий. Поэтому в share sheet
     * уходит текстовая опись незашаренных сессий с путями к их каталогам и
     * объёмом — она отвечает на вопрос «что именно ещё не забрано», а не
     * пытается протащить через мессенджер десятки мегабайт gzip-CSV.
     */
    fun requestShare() {
        viewModelScope.launch {
            val unshared = sessionDao.unshared()
            if (unshared.isEmpty()) {
                local.update { it.copy(message = "Нечего выгружать: всё уже отмечено как выгруженное") }
                return@launch
            }
            val text = buildString {
                appendLine("Сезам: сессии, ещё не выгруженные (${unshared.size})")
                unshared.forEach { session ->
                    append(formatDateTime(session.startedAt))
                    append(" · ").append(session.label.title())
                    append(" · ").append(formatBytes(session.sizeBytes))
                    session.fileDir?.let { append(" · ").append(it) }
                    appendLine()
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Сезам: сессии записи")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            local.update {
                it.copy(shareRequest = ShareRequest(intent, unshared.map { s -> s.id }))
            }
        }
    }

    /** Вызывается после того, как share sheet действительно открылся. */
    fun onShareLaunched(sessionIds: List<Long>) {
        viewModelScope.launch {
            sessionDao.markShared(sessionIds)
            local.update {
                it.copy(
                    shareRequest = null,
                    message = "Отмечено выгруженным: ${sessionIds.size}",
                )
            }
        }
    }

    fun onShareFailed() {
        local.update { it.copy(shareRequest = null, message = "Не нашлось приложения для отправки") }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
