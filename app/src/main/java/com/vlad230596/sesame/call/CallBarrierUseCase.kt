package com.vlad230596.sesame.call

import android.telecom.PhoneAccountHandle
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.LabelSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Запрос на открытие шлагбаума (§4.1).
 *
 * @param barrierId     шлагбаум из настроек;
 * @param phoneNumber   номер шлагбаума; `null`/пусто означает [CallOutcome.NO_NUMBER];
 * @param phoneAccount  явный звонящий аккаунт. Заполняется, только если в системе
 *                      больше одного `PhoneAccount`: уход звонка не с той SIM означает,
 *                      что шлагбаум не откроется;
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
 * - номер не задан — открывается экран настроек шлагбаумов ([CallOutcome.NO_NUMBER]);
 * - отмена по таймеру подтверждения — [CallOutcome.CANCELLED_BY_USER].
 *
 * TODO(v0): реализовать. Сейчас заглушка, сохраняющая только сигнатуру.
 */
@Singleton
class CallBarrierUseCase @Inject constructor() {

    suspend operator fun invoke(request: CallBarrierRequest): CallBarrierResult {
        TODO("Реализация открытия шлагбаума появится вместе с UI подтверждения (§4.1)")
    }
}
