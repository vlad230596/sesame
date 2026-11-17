package com.vlad230596.sesame.habits

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.habitsDataStore: DataStore<Preferences> by preferencesDataStore(name = "habits")

/**
 * Привычки хранятся одной JSON-строкой в собственном DataStore: их единицы, а
 * таблица в Room потребовала бы миграции схемы ради списка из трёх строк.
 */
@Singleton
class HabitRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("habits_json")

    val habits: Flow<List<Habit>> = context.habitsDataStore.data.map { Habit.listFromJson(it[key]) }

    suspend fun save(habit: Habit) = update { list ->
        val index = list.indexOfFirst { it.id == habit.id }
        if (index >= 0) list.toMutableList().apply { set(index, habit) } else list + habit
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(transform: (List<Habit>) -> List<Habit>) {
        context.habitsDataStore.edit { prefs ->
            prefs[key] = Habit.listToJson(transform(Habit.listFromJson(prefs[key])))
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
