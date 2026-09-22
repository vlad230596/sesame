package com.vlad230596.sesame.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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

        /**
         * 1 → 2: у шлагбаума появилось человеческое имя въезда (`Barrier.name`).
         *
         * Миграция, а не `fallbackToDestructiveMigration()`: в базе лежит весь
         * размеченный датасет — метки проходов, сессии записи, журнал событий. Он
         * собирается месяцами и восстановлению не подлежит; уронить его ради
         * одной текстовой колонки нельзя.
         *
         * Засеянным по умолчанию шлагбаумам сразу проставляются осмысленные
         * имена; всё, что пользователь успел переименовать сам, переносится в имя
         * как есть — это ровно то, что он писал на кнопке.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE barrier ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE barrier SET name = CASE
                        WHEN label = 'Шлагбаум A' THEN 'Северный въезд'
                        WHEN label = 'Шлагбаум B' THEN 'Южный въезд'
                        ELSE label
                    END
                    """.trimIndent(),
                )
            }
        }
    }
}
