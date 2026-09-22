package com.vlad230596.sesame.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.TravelMode

/**
 * Метка проезда (§4.5). Создаётся при каждой попытке открыть шлагбаум,
 * а также вручную задним числом.
 */
@Entity(tableName = "passage_label")
data class PassageLabel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Когда произошёл проезд. Для ретро-метки задаётся вручную. */
    val timestamp: Long,
    /** Ссылка на [Barrier]; null для метки «проехал, но неизвестно какой». */
    val barrierId: Long?,
    /** Догадка по геофенсу двора, правится в истории. */
    val direction: Direction = Direction.UNKNOWN,
    /** Догадка по состоянию Bluetooth, правится в истории. */
    val mode: TravelMode = TravelMode.UNKNOWN,
    val source: LabelSource,
    val outcome: CallOutcome?,
    /** Ставится вручную, если нажатие было случайным. */
    val discarded: Boolean = false,
    /** Ставится автоматически, если нажатие произошло далеко от дома. */
    val suspicious: Boolean = false,
    val note: String? = null,
    /**
     * Подтверждена ли метка пользователем. За рулём приложение ничего не спрашивает,
     * а при следующем открытии показывает плашку «N меток без подтверждения» (§4.5).
     */
    val confirmed: Boolean = false,
)
