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
import com.vlad230596.sesame.logging.SessionExporter
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.plural
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Что именно уходит — для подписи под кнопкой и для снекбара. */
    val description: String = "",
)

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val barriers: List<Barrier> = emptyList(),
    val unsharedCount: Int = 0,
    val shareRequest: ShareRequest? = null,
    /** Идёт сборка архива: кнопка «Поделиться» на это время блокируется. */
    val preparing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val passageLabels: PassageLabelRepository,
    private val sessionDao: RecordingSessionDao,
    private val exporter: SessionExporter,
    barrierRepository: BarrierRepository,
) : ViewModel() {

    private data class Local(
        val shareRequest: ShareRequest? = null,
        val preparing: Boolean = false,
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
            preparing = l.preparing,
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
     * «Поделиться непошаренным» (§7) — отправка самих файлов.
     *
     * Основной способ забрать данные остаётся прежним: USB, файлы лежат в
     * `Documents/Sesame/` и видны с компьютера без дополнительных действий.
     * Share sheet — второй путь, и он отдаёт архив с потоками датчиков и
     * манифестами, а не их опись. Сборка архива идёт в IO: сотни мегабайт
     * копируются не мгновенно, и держать на это UI-поток нельзя.
     *
     * Флаг `shared` ставится только в [onShareLaunched], то есть после того, как
     * share sheet действительно открылся. Иначе одна осечка интента навсегда
     * пометила бы сессии выгруженными, и в следующий раз кнопка их не предложила
     * бы — при том, что данные никуда не ушли.
     */
    fun requestShare() {
        if (local.value.preparing) return
        viewModelScope.launch {
            val unshared = sessionDao.unshared()
            if (unshared.isEmpty()) {
                local.update { it.copy(message = "Нечего выгружать: всё уже отмечено как выгруженное") }
                return@launch
            }
            local.update { it.copy(preparing = true, message = "Собираю архив…") }
            val bundle = withContext(Dispatchers.IO) { exporter.buildBundle(unshared) }
            if (bundle == null) {
                local.update {
                    it.copy(
                        preparing = false,
                        message = "Файлы сессий не найдены. Проверьте Documents/Sesame/",
                    )
                }
                return@launch
            }
            local.update {
                it.copy(
                    preparing = false,
                    shareRequest = ShareRequest(
                        intent = exporter.shareIntent(bundle),
                        sessionIds = bundle.sessionIds,
                        description = "${bundle.fileName} · ${bundle.fileCount} " +
                            "${plural(bundle.fileCount.toLong(), "файл", "файла", "файлов")} · " +
                            formatBytes(bundle.sizeBytes),
                    ),
                )
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
