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
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vlad230596.sesame.R
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.ui.MainActivity
import com.vlad230596.sesame.ui.common.formatAgoShort
import com.vlad230596.sesame.ui.theme.SesameBarrierAccents
import kotlinx.coroutines.flow.first

/**
 * Виджет на домашний экран (§4.2): шлагбаум A, шлагбаум B, интенсивная запись.
 *
 * **Цвета берутся из схемы приложения, а не из системной палитры.** `GlanceTheme`
 * по умолчанию тянет динамические цвета Material You, и на домашнем экране
 * кнопки оказывались покрашены обоями. Для §4.2 это прямая потеря смысла: цвет
 * закреплён за позицией кнопки — левая янтарная, правая бирюзовая, — и именно
 * это запоминает рука за рулём. Поэтому акценты берутся из того же
 * [SesameBarrierAccents], что и в приложении, а `GlanceTheme` здесь не
 * используется вовсе.
 *
 * Раскладка повторяет макет виджета 4×2: строка состояния, две кнопки рядом,
 * узкая строка записи под ними. Рядом, а не одна под другой, потому что на
 * домашнем экране виджет платит за каждую клетку высоты, а две цветные кнопки
 * различаются цветом и без вертикального порядка.
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
        val lastEventAt = runCatching { entryPoint?.logEvents()?.observeLastReceivedTime()?.first() }
            .getOrNull()

        provideContent {
            WidgetContent(barriers = barriers, recording = recording, lastEventAt = lastEventAt)
        }
    }

    @Composable
    private fun WidgetContent(barriers: List<Barrier>, recording: Boolean, lastEventAt: Long?) {
        val context = LocalContext.current
        val state = currentState<Preferences>()
        val pendingId = state[WidgetKeys.PendingBarrierId]
        val deadline = state[WidgetKeys.PendingDeadline] ?: 0L
        val total = state[WidgetKeys.PendingTotal] ?: 0L
        val counting = pendingId != null && deadline > 0L

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(WidgetPalette.OuterCorner)
                .background(WidgetPalette.Background)
                .padding(WidgetPalette.Pad),
        ) {
            HeaderRow(
                counting = counting,
                countingLabel = barriers.firstOrNull { it.id == pendingId }?.displayName,
                deadline = deadline,
                lastEventAt = lastEventAt,
            )
            Spacer(GlanceModifier.height(WidgetPalette.Gap))

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
            } else if (counting) {
                CountdownRow(
                    deadline = deadline,
                    total = total,
                    modifier = GlanceModifier.defaultWeight(),
                )
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    barriers.forEachIndexed { index, barrier ->
                        if (index > 0) Spacer(GlanceModifier.width(WidgetPalette.Gap))
                        // Цвет закреплён за позицией, а не за id: левая кнопка
                        // всегда янтарная. Та же логика, что на главном экране.
                        val accent = SesameBarrierAccents.barrier(index)
                        BarrierButton(
                            barrier = barrier,
                            container = ColorProvider(accent.container),
                            onContainer = ColorProvider(accent.onContainer),
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                    }
                }
            }

            Spacer(GlanceModifier.height(WidgetPalette.Gap))
            RecordRow(recording = recording)
        }
    }

    /** Строка состояния: имя, признак живого сбора и давность события. */
    @Composable
    private fun HeaderRow(
        counting: Boolean,
        countingLabel: String?,
        deadline: Long,
        lastEventAt: Long?,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (counting) (countingLabel ?: "Звоню") else "Сезам",
                style = TextStyle(
                    color = if (counting) WidgetPalette.Amber else WidgetPalette.OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (!counting) {
                // Зелёная точка «сбор жив» — то же, что в шапке приложения.
                Spacer(GlanceModifier.width(8.dp))
                Box(
                    modifier = GlanceModifier
                        .size(6.dp)
                        .cornerRadius(3.dp)
                        .background(WidgetPalette.Live),
                ) {}
            }
            Spacer(GlanceModifier.defaultWeight())
            val seconds = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            Text(
                text = if (counting) "звоню через $seconds с" else formatAgoShort(lastEventAt),
                style = TextStyle(
                    color = if (counting) WidgetPalette.AmberSoft else WidgetPalette.Dim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun BarrierButton(
        barrier: Barrier,
        container: ColorProvider,
        onContainer: ColorProvider,
        modifier: GlanceModifier,
    ) {
        Column(
            modifier = modifier
                .cornerRadius(WidgetPalette.Corner)
                .background(container)
                .padding(12.dp)
                .clickable(
                    actionRunCallback<BarrierTapAction>(
                        actionParametersOf(BarrierIdParam to barrier.id),
                    ),
                ),
            verticalAlignment = Alignment.Vertical.Bottom,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_boom_gate),
                contentDescription = null,
                colorFilter = androidx.glance.ColorFilter.tint(onContainer),
                modifier = GlanceModifier.size(24.dp),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = barrier.displayName,
                style = TextStyle(
                    color = onContainer,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 2,
            )
        }
    }

    /**
     * Отсчёт занимает место обеих кнопок — виджет не прыгает по сетке домашнего
     * экрана. Секунды и полоса слева, «Отмена» справа: отменять надо не целясь.
     */
    @Composable
    private fun CountdownRow(deadline: Long, total: Long, modifier: GlanceModifier) {
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        val progress = if (total <= 0L) 0f else (remaining.toFloat() / total).coerceIn(0f, 1f)
        val seconds = ((remaining + 999) / 1000).toInt()

        Row(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .cornerRadius(WidgetPalette.Corner)
                    .background(WidgetPalette.AmberSurface)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = seconds.toString(),
                    style = TextStyle(
                        color = WidgetPalette.Amber,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(12.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = GlanceModifier.defaultWeight().height(6.dp),
                    color = WidgetPalette.Amber,
                    backgroundColor = WidgetPalette.AmberTrack,
                )
            }
            Spacer(GlanceModifier.width(WidgetPalette.Gap))
            Box(
                modifier = GlanceModifier
                    .width(116.dp)
                    .fillMaxHeight()
                    .cornerRadius(WidgetPalette.Corner)
                    .background(WidgetPalette.OnSurface)
                    .clickable(actionRunCallback<BarrierCancelAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Отмена",
                    style = TextStyle(
                        color = WidgetPalette.Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * Строка записи ведёт в приложение, на экран выбора метки сессии (§4.3):
     * метка выбирается крупными кнопками, и в виджете для этого нет места.
     * Во время записи она же показывает состояние и открывает главный экран
     * с таймером сессии.
     */
    @Composable
    private fun RecordRow(recording: Boolean) {
        val context = LocalContext.current
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(WidgetPalette.RecordHeight)
                .cornerRadius(WidgetPalette.RowCorner)
                .background(WidgetPalette.Neutral)
                .clickable(
                    actionStartActivity(
                        if (recording) openAppIntent(context) else startSessionIntent(context),
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(10.dp)
                    .cornerRadius(5.dp)
                    .background(WidgetPalette.Record),
            ) {}
            Spacer(GlanceModifier.width(9.dp))
            Text(
                text = if (recording) "Идёт запись" else "Интенсивная запись",
                style = TextStyle(
                    color = WidgetPalette.OnNeutral,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
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
 * Хром виджета. Смысловые цвета кнопок сюда не переезжают — они берутся из
 * [SesameBarrierAccents], единственного их источника. Здесь только фон,
 * нейтральная строка и янтарь отсчёта.
 */
private object WidgetPalette {
    val Background = ColorProvider(Color(0xF2191B20))
    val Neutral = ColorProvider(Color(0xFF22252B))
    val OnNeutral = ColorProvider(Color(0xFFF4F5F7))
    val OnSurface = ColorProvider(Color(0xFFF4F5F7))
    val Dim = ColorProvider(Color(0xFF7D858F))
    val Ink = ColorProvider(Color(0xFF14100A))

    /** Тот же янтарный, что у шлагбаума A и у акцента приложения. */
    val Amber = ColorProvider(Color(0xFFF2A63E))
    val AmberSoft = ColorProvider(Color(0xFFC9A46A))
    val AmberSurface = ColorProvider(Color(0xFF2A2013))
    val AmberTrack = ColorProvider(Color(0xFF4A3718))

    /** «Сбор жив» — зелёная точка, как в шапке приложения. */
    val Live = ColorProvider(Color(0xFF5ED39A))

    /** «Идёт запись» — коралловая точка, как в приложении. */
    val Record = ColorProvider(Color(0xFFFF6B5E))

    val OuterCorner = 26.dp
    val Corner = 20.dp
    val RowCorner = 16.dp
    val Pad = 14.dp
    val Gap = 10.dp
    val RecordHeight = 48.dp
}
