package com.vlad230596.sesame.data

import com.vlad230596.sesame.data.dao.PassageLabelDao
import com.vlad230596.sesame.data.entity.PassageLabel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Правка меток проездов (§4.5).
 *
 * Общая для истории и для плашки «N меток без подтверждения» на главном экране:
 * карточка правится в одном и том же месте кода, чтобы «в один тап» означало
 * одно и то же поведение в обоих местах.
 *
 * Любая правка направления или режима автоматически подтверждает метку: если
 * пользователь дотянулся до карточки и что-то выбрал — она больше не «без
 * подтверждения».
 */
@Singleton
class PassageLabelRepository @Inject constructor(
    private val dao: PassageLabelDao,
) {

    fun observeAll(): Flow<List<PassageLabel>> = dao.observeAll()

    fun observeUnconfirmed(): Flow<List<PassageLabel>> = dao.observeUnconfirmed()

    fun observeUnconfirmedCount(): Flow<Int> = dao.observeUnconfirmedCount()

    suspend fun setDirection(id: Long, direction: Direction) = edit(id) {
        it.copy(direction = direction, confirmed = true)
    }

    suspend fun setMode(id: Long, mode: TravelMode) = edit(id) {
        it.copy(mode = mode, confirmed = true)
    }

    suspend fun setDiscarded(id: Long, discarded: Boolean) = edit(id) {
        // Ошибочная метка не требует подтверждения: вопрос по ней уже закрыт.
        it.copy(discarded = discarded, confirmed = true)
    }

    suspend fun setNote(id: Long, note: String?) = edit(id) {
        it.copy(note = note?.takeIf { text -> text.isNotBlank() })
    }

    suspend fun confirm(id: Long) = edit(id) { it.copy(confirmed = true) }

    /**
     * Ретро-метка (§4.5): шлагбаум открыл кто-то другой из машины или приложение
     * не сработало. Считается подтверждённой сразу — пользователь только что
     * указал и время, и направление, и режим руками.
     */
    suspend fun addManual(
        timestamp: Long,
        barrierId: Long?,
        direction: Direction,
        mode: TravelMode,
        note: String? = null,
    ): Long = dao.insert(
        PassageLabel(
            timestamp = timestamp,
            barrierId = barrierId,
            direction = direction,
            mode = mode,
            source = LabelSource.MANUAL,
            outcome = null,
            note = note?.takeIf { it.isNotBlank() },
            confirmed = true,
        ),
    )

    private suspend fun edit(id: Long, block: (PassageLabel) -> PassageLabel) {
        val current = dao.byId(id) ?: return
        dao.update(block(current))
    }
}
