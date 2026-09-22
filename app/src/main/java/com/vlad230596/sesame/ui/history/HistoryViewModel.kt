package com.vlad230596.sesame.ui.history

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
import com.vlad230596.sesame.ui.common.ArchiveResult
import com.vlad230596.sesame.ui.common.ArchiveShareUseCase
import com.vlad230596.sesame.ui.common.ShareRequest
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
        /** Человеческое имя въезда: то же, что на кнопке главного экрана. */
        val barrierName: String?,
        /**
         * Позиция шлагбаума в списке — ею определяется цвет полоски слева.
         *
         * Именно позиция, а не id: цвет закреплён за позицией кнопки во всём
         * приложении (см. `SesameAccents.barrier`), и полоска в истории обязана
         * совпадать с кнопкой, в которую человек попадал.
         */
        val barrierIndex: Int,
    ) : HistoryItem {
        override val timestamp: Long get() = label.timestamp
        override val key: String get() = "passage-${label.id}"
    }

    data class Session(val session: RecordingSession) : HistoryItem {
        override val timestamp: Long get() = session.startedAt
        override val key: String get() = "session-${session.id}"
    }
}

/** Фильтр ленты (макет: три пилюли в шапке). */
enum class HistoryFilter { ALL, UNCONFIRMED, SESSIONS }

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val barriers: List<Barrier> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val unconfirmedCount: Int = 0,
    val unsharedCount: Int = 0,
    val shareRequest: ShareRequest? = null,
    /** Идёт сборка архива: кнопка экспорта на это время блокируется. */
    val preparing: Boolean = false,
    val message: String? = null,
) {
    /** Лента после применения фильтра. */
    val visibleItems: List<HistoryItem> = when (filter) {
        HistoryFilter.ALL -> items
        HistoryFilter.UNCONFIRMED -> items.filterIsInstance<HistoryItem.Passage>()
            .filter { !it.label.confirmed }

        HistoryFilter.SESSIONS -> items.filterIsInstance<HistoryItem.Session>()
    }

    fun barrierIndexOf(barrierId: Long?): Int =
        barriers.indexOfFirst { it.id == barrierId }.coerceAtLeast(0)
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val passageLabels: PassageLabelRepository,
    private val sessionDao: RecordingSessionDao,
    private val archive: ArchiveShareUseCase,
    barrierRepository: BarrierRepository,
) : ViewModel() {

    private data class Local(
        val filter: HistoryFilter = HistoryFilter.ALL,
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
        val indexById = barriers.withIndex().associate { (index, barrier) -> barrier.id to index }
        val nameById = barriers.associate { it.id to it.displayName }
        val items = buildList<HistoryItem> {
            labels.forEach { label ->
                add(
                    HistoryItem.Passage(
                        label = label,
                        barrierName = nameById[label.barrierId],
                        barrierIndex = indexById[label.barrierId] ?: 0,
                    ),
                )
            }
            sessions.forEach { add(HistoryItem.Session(it)) }
        }.sortedByDescending { it.timestamp }

        HistoryUiState(
            items = items,
            barriers = barriers,
            filter = l.filter,
            unconfirmedCount = labels.count { !it.confirmed },
            unsharedCount = sessions.count { !it.shared && it.endedAt != null },
            shareRequest = l.shareRequest,
            preparing = l.preparing,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setFilter(filter: HistoryFilter) = local.update { it.copy(filter = filter) }

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

    /** Закрытие окна правки подтверждает метку: её уже посмотрели глазами. */
    fun confirm(id: Long) {
        viewModelScope.launch { passageLabels.confirm(id) }
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

    /** «Поделиться непошаренным» (§7) — см. [ArchiveShareUseCase]. */
    fun requestShare() {
        if (local.value.preparing) return
        viewModelScope.launch {
            local.update { it.copy(preparing = true) }
            when (val result = archive.prepare()) {
                ArchiveResult.Empty -> local.update {
                    it.copy(
                        preparing = false,
                        message = "Нечего выгружать: всё уже отмечено как выгруженное",
                    )
                }

                ArchiveResult.NoFiles -> local.update {
                    it.copy(
                        preparing = false,
                        message = "Файлы сессий не найдены. Проверьте Documents/Sesame/",
                    )
                }

                is ArchiveResult.Ready -> local.update {
                    it.copy(preparing = false, shareRequest = result.request)
                }
            }
        }
    }

    /** Вызывается после того, как share sheet действительно открылся. */
    fun onShareLaunched(sessionIds: List<Long>) {
        viewModelScope.launch {
            archive.markShared(sessionIds)
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
