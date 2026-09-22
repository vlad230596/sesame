package com.vlad230596.sesame.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Шлагбаум (§5).
 *
 * Номер телефона живёт только на устройстве и в репозиторий не попадает (§4.6).
 * UI в v0 правит две заранее созданные записи, модель поддерживает любое количество.
 */
@Entity(tableName = "barrier")
data class Barrier(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val phoneNumber: String?,
    val lat: Double?,
    val lon: Double?,
    val radiusMeters: Float = DEFAULT_RADIUS_METERS,
    val orderIndex: Int = 0,
) {
    companion object {
        /** §4.4: по 100 м вокруг каждого шлагбаума. */
        const val DEFAULT_RADIUS_METERS: Float = 100f
    }
}
