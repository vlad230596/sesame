package com.vlad230596.sesame.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.ui.MainActivity
import com.vlad230596.sesame.ui.theme.SesameBarrierAccents
import kotlinx.coroutines.flow.first

/**
 * Виджет на домашний экран (§4.2): шлагбаум A, шлагбаум B, интенсивная запись.
 *
 * **Цвета берутся из схемы приложения, а не из системной палитры.** `GlanceTheme`
 * по умолчанию тянет динамические цвета Material You, и на домашнем экране
 * кнопки оказывались покрашены обоями. Для §4.2 это прямая потеря смысла: цвет
 * закреплён за позицией кнопки — верхняя красная, нижняя малиновая, — и именно
 * это запоминает рука за рулём. Поэтому акценты берутся из того же
 * [SesameBarrierAccents], что и в приложении, а `GlanceTheme` здесь не
 * используется вовсе.
 *
 * Подписи шлагбаумов — из настроек (§4.2). Состояние отсчёта — из Glance-
 * хранилища, его ведёт [WidgetCallCoordinator].
 */
class SesameWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Читается один раз на обновление: виджет перерисовывается по команде
        // (нажатие, смена настроек, старт записи), а не живёт на подписке —
        // подписка в фоне стоила бы процесса, который системе не нужен.
        val entryPoint = runCatching { widgetEntryPoint(context) }.getOrNull()
        val barriers = runCatching { entryPoint?.barriers()?.observeAll()?.first() }
            .getOrNull()
            .orEmpty()
        val recording = runCatching { entryPoint?.sessions()?.observeActive()?.first() != null }
            .getOrDefault(false)

        provideContent {
            WidgetContent(barriers = barriers, recording = recording)
        }
    }

    @Composable
    private fun WidgetContent(barriers: List<Barrier>, recording: Boolean) {
        val context = LocalContext.current
        val state = currentState<Preferences>()
        val pendingId = state[WidgetKeys.PendingBarrierId]
        val deadline = state[WidgetKeys.PendingDeadline] ?: 0L
        val total = state[WidgetKeys.PendingTotal] ?: 0L

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.Background)
                .padding(WidgetPalette.Gap),
        ) {
            if (barriers.isEmpty()) {
                // Пустой виджет молчать не должен: до засева шлагбаумов и до
                // ввода номеров нажимать тут нечего, и это надо сказать словами.
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .cornerRadius(WidgetPalette.Corner)
                        .background(WidgetPalette.Neutral)
                        .clickable(actionStartActivity(openAppIntent(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Сезам: настройте шлагбаумы",
                        style = TextStyle(
                            color = WidgetPalette.OnNeutral,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }

            barriers.forEachIndexed { index, barrier ->
                // Цвет закреплён за позицией, а не за id: верхняя кнопка всегда
                // красная. Та же логика, что на главном экране приложения.
                val accent = SesameBarrierAccents.barrier(index)
                val container = ColorProvider(accent.container)
                val onContainer = ColorProvider(accent.onContainer)

                // defaultWeight() — расширение ColumnScope, поэтому размер
                // кнопки задаётся здесь, а не внутри её composable.
                val slot = GlanceModifier.fillMaxWidth().defaultWeight()
                if (pendingId == barrier.id && deadline > 0L) {
                    CountdownButton(
                        label = barrier.label,
                        deadline = deadline,
                        total = total,
                        container = container,
                        onContainer = onContainer,
                        modifier = slot,
                    )
                } else {
                    BarrierButton(
                        barrier = barrier,
                        container = container,
                        onContainer = onContainer,
                        modifier = slot,
                    )
                }
                Spacer(GlanceModifier.height(WidgetPalette.Gap))
            }

            RecordButton(recording = recording)
        }
    }

    @Composable
    private fun BarrierButton(
        barrier: Barrier,
        container: ColorProvider,
        onContainer: ColorProvider,
        modifier: GlanceModifier,
    ) {
        Box(
            modifier = modifier
                .cornerRadius(WidgetPalette.Corner)
                .background(container)
                .clickable(
                    actionRunCallback<BarrierTapAction>(
                        actionParametersOf(BarrierIdParam to barrier.id),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = barrier.label,
                style = TextStyle(
                    color = onContainer,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
            )
        }
    }

    /**
     * Отсчёт занимает место самой кнопки — виджет не прыгает по сетке домашнего
     * экрана. Цвет остаётся тот же: видно, по какому шлагбауму идёт отсчёт, не
     * вчитываясь в подпись. Тап по карточке — отмена (§4.1).
     */
    @Composable
    private fun CountdownButton(
        label: String,
        deadline: Long,
        total: Long,
        container: ColorProvider,
        onContainer: ColorProvider,
        modifier: GlanceModifier,
    ) {
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        val progress = if (total <= 0L) 0f else (remaining.toFloat() / total).coerceIn(0f, 1f)
        val seconds = ((remaining + 999) / 1000).toInt()

        Column(
            modifier = modifier
                .cornerRadius(WidgetPalette.Corner)
                .background(container)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(actionRunCallback<BarrierCancelAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label · $seconds",
                style = TextStyle(
                    color = onContainer,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = onContainer,
                backgroundColor = WidgetPalette.ProgressTrack,
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "Нажмите, чтобы отменить",
                style = TextStyle(
                    color = onContainer,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }

    /**
     * Кнопка записи ведёт в приложение, на экран выбора метки сессии (§4.3):
     * метка выбирается крупными кнопками, и в виджете для этого нет места.
     * Во время записи она же показывает состояние и открывает главный экран
     * с таймером сессии.
     */
    @Composable
    private fun RecordButton(recording: Boolean) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(WidgetPalette.RecordHeight)
                .cornerRadius(WidgetPalette.Corner)
                .background(if (recording) WidgetPalette.Recording else WidgetPalette.Neutral)
                .clickable(
                    actionStartActivity(
                        if (recording) openAppIntent(context) else startSessionIntent(context),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (recording) "Идёт запись" else "Интенсивная запись",
                style = TextStyle(
                    color = if (recording) WidgetPalette.OnRecording else WidgetPalette.OnNeutral,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

private fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun startSessionIntent(context: Context): Intent =
    openAppIntent(context).setAction(MainActivity.ACTION_START_SESSION)

/**
 * Хром виджета. Смысловые цвета сюда не переезжают — они берутся из
 * [SesameBarrierAccents], единственного их источника. Здесь только фон и
 * нейтральная кнопка: виджет стоит на чужом фоне (обои), и повторять поверхности
 * приложения ему незачем.
 */
private object WidgetPalette {
    val Background = ColorProvider(Color(0xE60E1820))
    val Neutral = ColorProvider(Color(0xFF2F4553))
    val OnNeutral = ColorProvider(Color(0xFFD3E2EE))

    /** Янтарный «идёт запись» — тот же, что `tertiary` в схеме приложения. */
    val Recording = ColorProvider(Color(0xFFE8A33D))
    val OnRecording = ColorProvider(Color(0xFF0E1820))

    val ProgressTrack = ColorProvider(Color(0x55FFFFFF))

    val Corner = 16.dp
    val Gap = 6.dp
    val RecordHeight = 44.dp
}
