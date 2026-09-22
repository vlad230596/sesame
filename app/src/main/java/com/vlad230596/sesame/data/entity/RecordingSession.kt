package com.vlad230596.sesame.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vlad230596.sesame.data.SessionLabel

/**
 * Интенсивная сессия записи (§5).
 *
 * [sensorManifest] — сериализованный `session.json` (§6): фактически доступные
 * датчики с параметрами, метка, версия приложения, модель устройства, версия Android.
 */
@Entity(tableName = "recording_session")
data class RecordingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val label: SessionLabel = SessionLabel.NONE,
    val sensorManifest: String? = null,
    /** Каталог сессии в `Documents/Sesame/sessions/<session-id>/`. */
    val fileDir: String? = null,
    val sizeBytes: Long = 0,
    /** Была ли сессия уже выгружена через «Поделиться непошаренным» (§7). */
    val shared: Boolean = false,
)
