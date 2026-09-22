package com.vlad230596.sesame.data

import com.vlad230596.sesame.data.dao.BarrierDao
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шлагбаумы (§4.6).
 *
 * Модель поддерживает любое количество записей; UI v0 правит две заранее
 * созданные. Засев делается один раз при первом запуске — пустые номера и
 * подписи по умолчанию, чтобы на главном экране сразу были две кнопки, а не
 * пустой экран с предложением что-то настроить.
 *
 * Номера телефонов в репозиторий не попадают — только сюда, на устройство.
 */
@Singleton
class BarrierRepository @Inject constructor(
    private val dao: BarrierDao,
    private val settings: SettingsRepository,
) {

    fun observeAll(): Flow<List<Barrier>> = dao.observeAll()

    suspend fun byId(id: Long): Barrier? = dao.byId(id)

    suspend fun save(barrier: Barrier) = dao.upsert(barrier)

    /**
     * Идемпотентно: досоздаёт недостающие записи до двух. Проверяется и флагом в
     * настройках, и фактическим количеством строк — флаг один раз выставится и
     * при ручном удалении записи ничего не восстановит.
     */
    suspend fun ensureSeeded() {
        val existing = dao.count()
        if (existing >= DEFAULT_LABELS.size) {
            if (!settings.current().barriersSeeded) settings.markBarriersSeeded()
            return
        }
        DEFAULT_LABELS.drop(existing).forEachIndexed { index, label ->
            val position = existing + index
            dao.insert(
                Barrier(
                    label = label,
                    phoneNumber = null,
                    lat = null,
                    lon = null,
                    radiusMeters = Barrier.DEFAULT_RADIUS_METERS,
                    orderIndex = position,
                    name = DEFAULT_NAMES.getOrElse(position) { label },
                ),
            )
        }
        settings.markBarriersSeeded()
    }

    companion object {
        val DEFAULT_LABELS = listOf("Шлагбаум A", "Шлагбаум B")

        /**
         * Имя въезда по умолчанию. Это заведомо угадка — правится в настройках, —
         * но пустая крупная строка на главной кнопке хуже неточной: кнопка должна
         * читаться местом, а не буквой.
         */
        val DEFAULT_NAMES = listOf("Северный въезд", "Южный въезд")
    }
}
