package com.vlad230596.sesame.prompt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vlad230596.sesame.call.CallBarrierRequest
import com.vlad230596.sesame.call.CallBarrierUseCase
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.LabelSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Кнопки уведомления со шлагбаумами и его смахивание.
 *
 * **Звонок сразу, без таймера подтверждения (§4.1).** Таймер в приложении и
 * виджете страхует от случайного касания; кнопку в уведомлении случайно не
 * задеть — до неё надо опустить шторку или дождаться всплывающего окна. А
 * лишние секунды на подъезде — ровно то, от чего уведомление избавляет.
 *
 * Звонок идёт через [CallBarrierUseCase] — тот же путь, что у кнопки в
 * приложении: `placeCall`, без Activity, поэтому работает и из фона.
 */
class PromptActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PromptEntryPoint {
        fun callBarrier(): CallBarrierUseCase
        fun barriers(): BarrierRepository
        fun prompts(): PromptController
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, PromptEntryPoint::class.java)
        }.getOrNull() ?: return

        when (intent.action) {
            ACTION_DISMISSED -> entry.prompts().onDismissed()
            ACTION_CALL -> {
                val barrierId = intent.getLongExtra(EXTRA_BARRIER_ID, -1L).takeIf { it > 0 } ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val barrier = entry.barriers().byId(barrierId) ?: return@launch
                        entry.callBarrier()(
                            CallBarrierRequest(
                                barrierId = barrier.id,
                                phoneNumber = barrier.phoneNumber,
                                source = LabelSource.NOTIFICATION,
                            ),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Звонок из уведомления не удался", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PromptActionReceiver"
        const val ACTION_CALL = "com.vlad230596.sesame.action.PROMPT_CALL"
        const val ACTION_DISMISSED = "com.vlad230596.sesame.action.PROMPT_DISMISSED"
        const val EXTRA_BARRIER_ID = "com.vlad230596.sesame.extra.BARRIER_ID"
    }
}
