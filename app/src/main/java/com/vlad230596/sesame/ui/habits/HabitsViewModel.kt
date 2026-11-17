package com.vlad230596.sesame.ui.habits

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.habits.Habit
import com.vlad230596.sesame.habits.HabitRepository
import com.vlad230596.sesame.habits.HabitSuggester
import com.vlad230596.sesame.habits.HabitSuggestion
import com.vlad230596.sesame.habits.RouteLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.coroutines.resume

data class HabitsUiState(
    val habits: List<Habit> = emptyList(),
    val suggestions: List<HabitSuggestion> = emptyList(),
)

/**
 * Привычки: список и правка в настройках, подсказка на главной.
 *
 * Подсказка пересчитывается раз в минуту — окна заданы с точностью до минуты.
 * Маршрут открывается только по кнопке: автоматический запуск Карт владельцу
 * не нужен, да и Android из фона его не даст.
 */
@HiltViewModel
class HabitsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HabitRepository,
    private val journal: CollectorJournal,
) : ViewModel() {

    private val clock = MutableStateFlow(LocalDateTime.now())

    val state: StateFlow<HabitsUiState> = combine(repository.habits, clock) { habits, now ->
        HabitsUiState(habits = habits, suggestions = HabitSuggester.suggest(habits, now))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState())

    init {
        viewModelScope.launch {
            while (true) {
                clock.value = LocalDateTime.now()
                delay(30_000)
            }
        }
    }

    fun refreshClock() {
        clock.value = LocalDateTime.now()
    }

    fun save(habit: Habit) {
        viewModelScope.launch { repository.save(habit) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** Кнопка «Маршрут». Нажатие пишется в журнал: по нему видно, нужна ли подсказка. */
    fun openRoute(habit: Habit, startsInMinutes: Int?) {
        val opened = RouteLauncher.open(context, habit.lat, habit.lon, habit.name)
        journal.log(
            LogEventType.HABIT_ROUTE_OPENED,
            mapOf(
                "habitId" to habit.id,
                "name" to habit.name,
                "startsInMinutes" to startsInMinutes,
                "opened" to opened,
            ),
        )
    }

    /** Последняя известная координата — «Текущее место» в редакторе. Без нового фикса. */
    fun currentLocation(onResult: (Double?, Double?) -> Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            onResult(null, null)
            return
        }
        viewModelScope.launch {
            val location = runCatching {
                suspendCancellableCoroutine { continuation ->
                    @Suppress("MissingPermission")
                    LocationServices.getFusedLocationProviderClient(context).lastLocation
                        .addOnSuccessListener { continuation.resume(it?.let { l -> l.latitude to l.longitude }) }
                        .addOnFailureListener { continuation.resume(null) }
                }
            }.getOrNull()
            onResult(location?.first, location?.second)
        }
    }
}
