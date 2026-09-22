package com.vlad230596.sesame.call

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.PassageLabelDao
import com.vlad230596.sesame.data.entity.LogEvent
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Запрос на открытие шлагбаума (§4.1).
 *
 * @param barrierId     шлагбаум из настроек;
 * @param phoneNumber   номер шлагбаума; `null`/пусто означает [CallOutcome.NO_NUMBER];
 * @param phoneAccount  явный звонящий аккаунт. Заполняется, только если в системе
 *                      больше одного `PhoneAccount`: уход звонка не с той SIM означает,
 *                      что шлагбаум не откроется. `null` — разрешить использовать
 *                      выбранный в настройках;
 * @param source        откуда пришло нажатие — приложение или виджет.
 */
data class CallBarrierRequest(
    val barrierId: Long,
    val phoneNumber: String?,
    val phoneAccount: PhoneAccountHandle? = null,
    val source: LabelSource = LabelSource.APP,
)

/**
 * Результат попытки. Пишется в журнал независимо от исхода (§4.1).
 */
data class CallBarrierResult(
    val outcome: CallOutcome,
    val attemptedAt: Long,
    val error: Throwable? = null,
    /** Идентификатор созданной метки проезда — её можно поправить в истории. */
    val labelId: Long = 0,
)

/**
 * Единая точка «открыть шлагбаум» для приложения и для виджета.
 *
 * Полный контракт (§4.1):
 * - `Intent(ACTION_CALL)` с разрешением `CALL_PHONE`, без экрана набора;
 * - при нескольких звонящих аккаунтах в интент кладётся
 *   `android.telecom.extra.PHONE_ACCOUNT_HANDLE`;
 * - программное завершение звонка не делается — шлагбаум сбрасывает вызов сам;
 * - молчаливый отказ недопустим: нет разрешения / нет сети / звонок не удался —
 *   открывается `ACTION_DIAL` с введённым номером ([CallOutcome.DIALER_FALLBACK]);
 * - номер не задан — исход [CallOutcome.NO_NUMBER], UI ведёт в настройки шлагбаумов;
 * - отмена по таймеру подтверждения — [CallOutcome.CANCELLED_BY_USER].
 *
 * Каждая попытка, чем бы она ни кончилась, порождает [PassageLabel] с
 * `confirmed = false` и запись в журнале: разметка не должна зависеть от того,
 * удалось ли дозвониться.
 *
 * `direction` и `mode` здесь всегда [Direction.UNKNOWN] / [TravelMode.UNKNOWN]:
 * догадка по геофенсу двора и по состоянию Bluetooth появится вместе с пассивным
 * слоем (§4.4). До тех пор их проставляет пользователь в истории — в один тап.
 */
@Singleton
class CallBarrierUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val passageLabelDao: PassageLabelDao,
    private val logEventDao: LogEventDao,
    private val settings: SettingsRepository,
) {

    suspend operator fun invoke(request: CallBarrierRequest): CallBarrierResult {
        val number = request.phoneNumber?.trim().orEmpty()
        if (number.isEmpty()) return finish(request, CallOutcome.NO_NUMBER, null)

        val uri = Uri.fromParts("tel", number, null)
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        if (canCall) {
            val attempt = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putPhoneAccount(request.phoneAccount ?: selectedPhoneAccount()),
                )
            }
            if (attempt.isSuccess) return finish(request, CallOutcome.CALLED, null)
            Log.w(TAG, "ACTION_CALL не удался, уходим в звонилку", attempt.exceptionOrNull())
        }

        // Молчаливый отказ недопустим: открываем звонилку с уже введённым номером.
        val dial = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val error = dial.exceptionOrNull()
        if (error is ActivityNotFoundException) {
            Log.w(TAG, "На устройстве нет звонилки", error)
        }
        return finish(request, CallOutcome.DIALER_FALLBACK, error)
    }

    /**
     * Пользователь нажал «Отмена» до истечения таймера подтверждения (§4.1).
     * Отмена фиксируется отдельным исходом, а не отсутствием события: иначе
     * в датасете «передумал» и «не нажимал» выглядят одинаково.
     */
    suspend fun recordCancelled(request: CallBarrierRequest): CallBarrierResult =
        finish(request, CallOutcome.CANCELLED_BY_USER, null)

    private suspend fun finish(
        request: CallBarrierRequest,
        outcome: CallOutcome,
        error: Throwable?,
    ): CallBarrierResult {
        val now = System.currentTimeMillis()
        val labelId = passageLabelDao.insert(
            PassageLabel(
                timestamp = now,
                barrierId = request.barrierId.takeIf { it > 0 },
                direction = Direction.UNKNOWN,
                mode = TravelMode.UNKNOWN,
                source = request.source,
                outcome = outcome,
                // Отменённое нажатие подтверждать нечего: проезда не было.
                confirmed = outcome == CallOutcome.CANCELLED_BY_USER,
            ),
        )
        logEventDao.insert(
            LogEvent(
                type = LogEventType.BARRIER_CALL_ATTEMPT,
                eventTime = now,
                receivedTime = now,
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                payloadJson = """{"barrierId":${request.barrierId},""" +
                    """"outcome":"${outcome.name}","source":"${request.source.name}"}""",
            ),
        )
        return CallBarrierResult(outcome = outcome, attemptedAt = now, error = error, labelId = labelId)
    }

    /**
     * Звонящий аккаунт из настроек. Значим только при нескольких SIM; если
     * сохранённый аккаунт исчез (сменили SIM), возвращается `null` и звонок
     * уходит системным выбором по умолчанию — это лучше, чем не уйти вовсе.
     */
    private suspend fun selectedPhoneAccount(): PhoneAccountHandle? {
        val id = settings.current().phoneAccountId ?: return null
        return runCatching {
            context.getSystemService<TelecomManager>()
                ?.callCapablePhoneAccounts
                ?.firstOrNull { it.id == id }
        }.getOrNull()
    }

    private fun Intent.putPhoneAccount(handle: PhoneAccountHandle?): Intent = apply {
        if (handle != null) putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
    }

    private companion object {
        const val TAG = "CallBarrierUseCase"
    }
}
