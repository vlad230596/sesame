package com.vlad230596.sesame.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.PassageLabelRepository
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.PassageLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Строка журнала (§4.5): метка проезда и человеческое имя шлагбаума. */
data class HistoryItem(
    val label: PassageLabel,
    /** Человеческое имя въезда: то же, что на кнопке главного экрана. */
    val barrierName: String?,
    /**
     * Позиция шлагбаума в списке — ею определяется цвет полоски слева.
     *
     * Именно позиция, а не id: цвет закреплён за позицией кнопки во всём
     * приложении (см. `SesameAccents.barrier`), и полоска в журнале обязана
     * совпадать с кнопкой, в которую человек попадал.
     */
    val barrierIndex: Int,
) {
    val timestamp: Long get() = label.timestamp
    val key: String get() = "passage-${label.id}"
}

/**
 * Журнал проездов. Разметка в нём необязательна: ни фильтров «без
 * подтверждения», ни счётчиков, которые подталкивали бы её доделывать, — только
 * лента и правка по желанию.
 */
data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val barriers: List<Barrier> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val passageLabels: PassageLabelRepository,
    barrierRepository: BarrierRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<HistoryUiState> = combine(
        passageLabels.observeAll(),
        barrierRepository.observeAll(),
        message,
    ) { labels, barriers, msg ->
        val indexById = barriers.withIndex().associate { (index, barrier) -> barrier.id to index }
        val nameById = barriers.associate { it.id to it.displayName }
        HistoryUiState(
            items = labels
                .map { label ->
                    HistoryItem(
                        label = label,
                        barrierName = nameById[label.barrierId],
                        barrierIndex = indexById[label.barrierId] ?: 0,
                    )
                }
                .sortedByDescending { it.timestamp },
            barriers = barriers,
            message = msg,
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
            message.value = "Проезд добавлен"
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}
