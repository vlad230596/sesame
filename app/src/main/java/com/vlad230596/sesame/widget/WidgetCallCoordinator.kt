package com.vlad230596.sesame.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.vlad230596.sesame.call.CallBarrierRequest
import com.vlad230596.sesame.call.CallBarrierUseCase
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Ключи состояния виджета. Живут в Glance-хранилище, а не в памяти процесса. */
object WidgetKeys {
    /** Шлагбаум, по которому идёт отсчёт; отсутствует — отсчёта нет. */
    val PendingBarrierId = longPreferencesKey("pending_barrier_id")
    val PendingDeadline = longPreferencesKey("pending_deadline")
    val PendingTotal = longPreferencesKey("pending_total")
}

/**
 * Логика кнопок виджета (§4.2): та же самая, что и в приложении, включая таймер
 * подтверждения — только прогресс отмены рисуется в самом виджете.
 *
 * **Почему отдельный синглтон, а не работа внутри `ActionCallback`.** Обработчик
 * нажатия в виджете — это обычный броадкаст, и у него есть жёсткий бюджет
 * времени; `delay` на таймер отмены (а он настраивается вплоть до 10 секунд)
 * этот бюджет проедает целиком и кончается ANR. Поэтому обработчик только
 * сообщает сюда о нажатии и сразу возвращается, а отсчёт идёт в скоупе
 * приложения. Процесс на эти секунды заведомо жив: постоянный foreground
 * service (§9) и так его держит.
 *
 * **Состояние отсчёта лежит в Glance-хранилище.** Не в памяти: рисует виджет
 * система, по нашим данным, и после смерти процесса перерисовка обязана застать
 * согласованную картинку, а не пустой экран с застывшим прогрессом.
 */
@Singleton
class WidgetCallCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val barriers: BarrierRepository,
    private val settings: SettingsRepository,
    private val callBarrier: CallBarrierUseCase,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val widget = SesameWidget()
    private var countdownJob: Job? = null

    /**
     * Нажали кнопку шлагбаума.
     *
     * Повторное нажатие той же кнопки во время отсчёта — отмена: это самое
     * быстрое движение, если кнопку задели в кармане или случайно на домашнем
     * экране (§11, «случайное нажатие в виджете»).
     */
    fun onBarrierPressed(barrierId: Long) {
        scope.launch {
            val pending = pendingBarrierId()
            if (pending == barrierId) {
                cancelPending()
                return@launch
            }
            countdownJob?.cancel()
            val barrier = runCatching { barriers.byId(barrierId) }.getOrNull() ?: return@launch
            val timeoutSeconds = runCatching { settings.current().cancelTimeoutSeconds }
                .getOrDefault(0)

            if (timeoutSeconds <= 0) {
                // §4.1: 0 — звонить немедленно, без подтверждения.
                clearPending()
                call(barrier)
                return@launch
            }

            val total = timeoutSeconds * 1000L
            val deadline = System.currentTimeMillis() + total
            setPending(barrier, deadline, total)

            countdownJob = scope.launch {
                while (isActive) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    delay(minOf(TICK_MILLIS, remaining))
                    refreshWidgets()
                }
                clearPending()
                call(barrier)
            }
        }
    }

    /**
     * «Отмена» в виджете. Пишется отдельным исходом, а не отсутствием события
     * (§4.1): в датасете «передумал» и «не нажимал» — разные вещи.
     */
    fun onCancelPressed() {
        scope.launch { cancelPending() }
    }

    /** Перерисовать виджет: поменялись подписи шлагбаумов или состояние записи. */
    fun refresh() {
        scope.launch { refreshWidgets() }
    }

    /**
     * Имя не `cancel`: внутри `scope.launch { ... }` ближайший неявный получатель
     * — `CoroutineScope`, и вызов `cancel()` оттуда ушёл бы в расширение
     * `CoroutineScope.cancel()`, то есть тихо прибил бы скоуп координатора
     * вместо отмены звонка. Компилятор на это не ругается.
     */
    private suspend fun cancelPending() {
        countdownJob?.cancel()
        countdownJob = null
        val pending = pendingBarrierId()
        clearPending()
        if (pending == null) return
        runCatching {
            callBarrier.recordCancelled(
                CallBarrierRequest(
                    barrierId = pending,
                    phoneNumber = null,
                    source = LabelSource.WIDGET,
                ),
            )
        }.onFailure { Log.w(TAG, "Не удалось записать отмену", it) }
    }

    private suspend fun call(barrier: Barrier) {
        runCatching {
            callBarrier(
                CallBarrierRequest(
                    barrierId = barrier.id,
                    phoneNumber = barrier.phoneNumber,
                    source = LabelSource.WIDGET,
                ),
            )
        }.onFailure { Log.w(TAG, "Звонок из виджета не удался", it) }
    }

    // --- состояние виджета ----------------------------------------------------------

    private suspend fun pendingBarrierId(): Long? =
        runCatching { firstState()?.get(WidgetKeys.PendingBarrierId) }.getOrNull()

    private suspend fun setPending(barrier: Barrier, deadline: Long, total: Long) {
        updateState { prefs ->
            prefs[WidgetKeys.PendingBarrierId] = barrier.id
            prefs[WidgetKeys.PendingDeadline] = deadline
            prefs[WidgetKeys.PendingTotal] = total
        }
    }

    private suspend fun clearPending() {
        updateState { prefs ->
            prefs.remove(WidgetKeys.PendingBarrierId)
            prefs.remove(WidgetKeys.PendingDeadline)
            prefs.remove(WidgetKeys.PendingTotal)
        }
    }

    private suspend fun firstState(): Preferences? {
        val ids = glanceIds()
        if (ids.isEmpty()) return null
        return runCatching {
            androidx.glance.appwidget.state.getAppWidgetState(
                context,
                androidx.glance.state.PreferencesGlanceStateDefinition,
                ids.first(),
            )
        }.getOrNull()
    }

    private suspend fun updateState(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        glanceIds().forEach { id ->
            runCatching { updateAppWidgetState(context, id) { prefs -> block(prefs) } }
                .onFailure { Log.w(TAG, "Не удалось обновить состояние виджета", it) }
        }
        refreshWidgets()
    }

    private suspend fun refreshWidgets() {
        runCatching { widget.updateAll(context) }
            .onFailure { Log.w(TAG, "Не удалось перерисовать виджет", it) }
    }

    private suspend fun glanceIds() = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(SesameWidget::class.java)
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "WidgetCoordinator"

        /**
         * Полсекунды: прогресс в виджете обновляется рывками по полсекунды, и это
         * честная цена. Каждая перерисовка — IPC в домашний экран, и частить ею
         * ради плавности, которую на 3-секундном отсчёте никто не разглядит,
         * незачем.
         */
        const val TICK_MILLIS = 500L
    }
}
