package com.vlad230596.sesame.di

import android.content.Context
import androidx.room.Room
import com.vlad230596.sesame.data.AppDatabase
import com.vlad230596.sesame.data.dao.BarrierDao
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.PassageLabelDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        // Деструктивного отката здесь нет намеренно: в базе лежит датасет,
        // который собирается месяцами.
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()

    @Provides
    fun provideBarrierDao(db: AppDatabase): BarrierDao = db.barrierDao()

    @Provides
    fun providePassageLabelDao(db: AppDatabase): PassageLabelDao = db.passageLabelDao()

    @Provides
    fun provideRecordingSessionDao(db: AppDatabase): RecordingSessionDao = db.recordingSessionDao()

    @Provides
    fun provideLogEventDao(db: AppDatabase): LogEventDao = db.logEventDao()
}
