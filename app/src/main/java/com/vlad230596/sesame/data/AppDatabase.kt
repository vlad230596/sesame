package com.vlad230596.sesame.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vlad230596.sesame.data.dao.BarrierDao
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.PassageLabelDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.LogEvent
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.entity.RecordingSession

@Database(
    entities = [
        Barrier::class,
        PassageLabel::class,
        RecordingSession::class,
        LogEvent::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun barrierDao(): BarrierDao

    abstract fun passageLabelDao(): PassageLabelDao

    abstract fun recordingSessionDao(): RecordingSessionDao

    abstract fun logEventDao(): LogEventDao

    companion object {
        const val NAME = "sesame.db"
    }
}
