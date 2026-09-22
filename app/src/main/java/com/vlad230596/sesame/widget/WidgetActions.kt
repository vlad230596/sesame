package com.vlad230596.sesame.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.vlad230596.sesame.call.CallBarrierUseCase
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.prefs.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Точка доступа к графу Hilt из виджета.
 *
 * Виджет — это `BroadcastReceiver` плюс отдельные `ActionCallback`-и, ни во что
 * из этого Hilt из Kotlin инжектить не умеет (инъекция живёт в `super.onReceive()`
 * Hilt-базы, а из Kotlin этот вызов недоступен). Поэтому зависимости берутся
 * через [EntryPointAccessors] — так же, как в `service/BootReceiver`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun coordinator(): WidgetCallCoordinator
    fun barriers(): BarrierRepository
    fun settings(): SettingsRepository
    fun callBarrier(): CallBarrierUseCase
    fun sessions(): RecordingSessionDao

    /** "Последнее фоновое событие" в шапке виджета: сбор жив или нет. */
    fun logEvents(): LogEventDao
}

internal fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/** Идентификатор шлагбаума передаётся в колбэк параметром действия. */
internal val BarrierIdParam = ActionParameters.Key<Long>("barrierId")

/**
 * Нажата кнопка шлагбаума (§4.2). Обработчик ничего не ждёт: таймер отмены
 * крутится в [WidgetCallCoordinator], иначе броадкаст упёрся бы в свой лимит
 * времени — см. описание координатора.
 */
class BarrierTapAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val barrierId = parameters[BarrierIdParam] ?: return
        runCatching { widgetEntryPoint(context).coordinator().onBarrierPressed(barrierId) }
    }
}

/** «Отмена» во время отсчёта: тот же исход, что и в приложении (§4.1). */
class BarrierCancelAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        runCatching { widgetEntryPoint(context).coordinator().onCancelPressed() }
    }
}
