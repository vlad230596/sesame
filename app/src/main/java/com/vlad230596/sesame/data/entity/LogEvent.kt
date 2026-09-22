package com.vlad230596.sesame.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vlad230596.sesame.data.LogEventType

/**
 * Событие журнала (§5).
 *
 * Три времени сразу (§6): [eventTime] — когда произошло, [receivedTime] — когда
 * приложение узнало, [elapsedRealtimeNanos] — сырое монотонное время для сопоставления
 * с потоками датчиков. Разница между первыми двумя и есть правда о задержках доставки
 * фоновых событий.
 */
@Entity(tableName = "log_event")
data class LogEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: LogEventType,
    val eventTime: Long,
    val receivedTime: Long,
    val elapsedRealtimeNanos: Long,
    /** Специфика события: SSID, имя Bluetooth-устройства, идентификатор геофенса и т. д. */
    val payloadJson: String? = null,
)
