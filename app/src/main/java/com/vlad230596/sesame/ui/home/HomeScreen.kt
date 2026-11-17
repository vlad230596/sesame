package com.vlad230596.sesame.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.car.CarUiState
import com.vlad230596.sesame.ui.car.CarViewModel
import com.vlad230596.sesame.ui.common.AccentStrip
import com.vlad230596.sesame.ui.common.CardHeadRow
import com.vlad230596.sesame.ui.common.SesameCard
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.StatusDot
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.ThinProgress
import com.vlad230596.sesame.ui.common.formatAgoShort
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.maskPhone
import com.vlad230596.sesame.ui.common.plural
import com.vlad230596.sesame.ui.permissions.PermissionsScreen
import com.vlad230596.sesame.ui.theme.BarrierAccent
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameAccentColors
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Вкладка «Главная» (§4.7), по утверждённому макету.
 *
 * Главное решение раскладки: **кнопки шлагбаумов прижаты к низу экрана**.
 * Открывают шлагбаум с телефоном в одной руке, не глядя, часто на ходу или из
 * машины — до верха экрана большой палец не достаёт, не перехватив телефон.
 * Поэтому сверху остаётся то, что читают («сбор идёт», «что-то не так»), а внизу
 * то, во что попадают.
 *
 * Второе решение: **на главной только то, чем пользуются каждый день** — две
 * кнопки шлагбаумов и «Где машина». Интенсивной записи больше нет вовсе, а
 * журнал проездов спрятан в настройки: размечать его не обязательно, и ничто на
 * главной к этому не подталкивает.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenCar: () -> Unit = {},
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Та же ViewModel, что у экрана карты (владелец — Activity): строка на
    // главной и карточка на карте не разойдутся.
    val carViewModel: CarViewModel = hiltViewModel()
    val carState by carViewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HomeContent(
        state = state,
        onBarrierPressed = viewModel::onBarrierPressed,
        onCancelCountdown = viewModel::cancelCountdown,
        onStartService = viewModel::startService,
        onStopService = viewModel::stopService,
        onMessageShown = viewModel::dismissMessage,
        modifier = modifier,
        carLine = carLine(carState),
        onOpenCar = onOpenCar,
    )
}

/** «у Ш2, 3 ч назад»; `null` — пока не загрузилось. */
private fun carLine(car: CarUiState): String? {
    if (!car.loaded) return null
    val parked = car.car ?: return "парковок ещё не было"
    val ago = if (parked.at > 0) formatAgoShort(parked.at, car.now) else null
    return listOfNotNull(car.spotPhrase, ago).joinToString(", ").ifEmpty { "на карте" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onBarrierPressed: (Barrier) -> Unit,
    onCancelCountdown: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    carLine: String? = null,
    onOpenCar: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Box(modifier = modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {

            HomeHeader(lastEventAt = state.lastEventAt, now = state.now)

            // Верхняя половина — то, что читают. Прокручивается: на маленьком
            // экране плашка разрешений вместе с кнопками может не поместиться.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.StackGap),
            ) {
                if (state.missingPermissions.isNotEmpty()) {
                    AccentStrip(
                        text = "Не хватает разрешений: ${state.missingPermissions.size}",
                        action = "Открыть",
                        dotColor = Palette.Record,
                        accentColor = Palette.Record,
                        containerColor = Palette.RecordSurface,
                        borderColor = Palette.RecordBorder,
                        onClick = { permissionsVisible = true },
                    )
                }

                StatusCard(
                    state = state,
                    onStartService = onStartService,
                    onStopService = onStopService,
                )

                Spacer(Modifier.height(Dimens.SpaceS))
            }

            // Нижняя половина — то, во что попадают. «Где машина» тоже здесь, а
            // не в прокрутке сверху: там её вытесняли кнопки, и на телефоне
            // строки не было видно вовсе.
            Column(
                modifier = Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.StackGap),
            ) {
                CarRow(text = carLine ?: "…", onClick = onOpenCar)

                if (state.barriers.isEmpty()) {
                    SesameCard {
                        Text(
                            "Шлагбаумы не настроены. Настройки → Шлагбаумы.",
                            style = SesameText.Body,
                            color = Palette.TextMuted,
                        )
                    }
                }

                // Цвет закреплён за позицией кнопки, а не за id шлагбаума: верхняя
                // всегда янтарная, и именно это запоминает рука за рулём.
                state.barriers.forEachIndexed { index, barrier ->
                    BarrierButton(
                        barrier = barrier,
                        position = index,
                        accent = SesameAccentColors.current.barrier(index),
                        immediate = state.settings.cancelTimeoutSeconds == 0,
                        onClick = { onBarrierPressed(barrier) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Отсчёт перед звонком занимает весь экран (§4.1): пока он идёт, ничего
    // другого делать не надо, а «Отмена» должна быть размером с ладонь.
    val countdown = state.countdown
    if (countdown != null) {
        val index = state.barriers.indexOfFirst { it.id == countdown.barrierId }.coerceAtLeast(0)
        CountdownOverlay(
            countdown = countdown,
            barrier = state.barriers.firstOrNull { it.id == countdown.barrierId },
            accent = SesameAccentColors.current.barrier(index),
            onCancel = onCancelCountdown,
        )
    }

    if (permissionsVisible) {
        Dialog(
            onDismissRequest = { permissionsVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Palette.Background,
            ) {
                SubScreenScaffold(
                    title = "Разрешения",
                    onBack = { permissionsVisible = false },
                ) { _ -> PermissionsScreen() }
            }
        }
    }
}

/**
 * Шапка: имя приложения и признак живого сбора.
 *
 * Зелёная точка и давность события стоят здесь, а не только в карточке, потому
 * что это единственное, что нужно увидеть, открыв приложение «просто проверить».
 */
@Composable
private fun HomeHeader(lastEventAt: Long?, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Сезам", style = SesameText.ScreenTitle, color = Palette.TextPrimary)
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(liveColor(lastEventAt, now))
            Text(
                text = formatAgoShort(lastEventAt, now),
                style = SesameText.Mono12,
                color = Palette.TextMuted,
            )
        }
    }
}

/**
 * Цвет точки живости. Час без единого фонового события ночью в покое — норма,
 * сутки — уже нет: цвет меняется на этой границе, а не на «давно».
 */
private fun liveColor(lastEventAt: Long?, now: Long): Color = when {
    lastEventAt == null || lastEventAt <= 0 -> Palette.TextDim
    now - lastEventAt < 6 * 60 * 60_000L -> Palette.Live
    now - lastEventAt < 24 * 60 * 60_000L -> Palette.Amber
    else -> Palette.Record
}

/**
 * «Где машина: у Ш2, 3 ч назад» (§4.1). Одна строка, а не карта: ответ словами
 * чаще всего достаточен, карта — по тапу.
 *
 * Строка высотой с прежнюю кнопку записи и с контуром, как у всего, что
 * нажимают: это ежедневное действие, но не открытие — заливки у неё нет.
 */
@Composable
private fun CarRow(text: String, onClick: () -> Unit) {
    SesameSurface(
        modifier = Modifier.fillMaxWidth().height(Dimens.RecordButtonHeight),
        color = Palette.Surface,
        borderColor = Palette.BorderStrong,
        borderWidth = Dimens.BorderWidth,
        corner = Dimens.RowCorner,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            Text(
                text = "Где машина",
                style = SesameText.RowTitle,
                color = Palette.TextPrimary,
            )
            Text(
                text = text,
                style = SesameText.Caption,
                color = Palette.TextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = SesameIcons.ChevronRight,
                contentDescription = null,
                tint = Palette.TextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Компактная карточка статуса: состояние сбора, занятый объём, давность события.
 *
 * Стоит сверху и занимает четыре строки вместо прежнего пол-экрана: это то, что
 * проверяют взглядом, а не то, чем пользуются.
 */
@Composable
private fun StatusCard(
    state: HomeUiState,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val limit = SesameSettings.STORAGE_LIMIT_BYTES
    SesameCard {
        CardHeadRow(
            title = if (state.serviceRunning) "Сбор идёт" else "Сбор остановлен",
            value = "${formatBytes(state.usedBytes)} / ${formatBytes(limit)}",
        )
        ThinProgress(
            progress = state.usedBytes.toFloat() / limit,
            color = if (state.serviceRunning) Palette.Teal else Palette.TextDim,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Самая важная строка экрана (§4.7): единственный признак того, что
            // фоновый сбор жив.
            Text(
                text = "Последнее фоновое событие — ${formatAgoShort(state.lastEventAt, state.now)}",
                style = SesameText.Caption.copy(fontSize = 12.sp),
                color = Palette.TextMuted,
                modifier = Modifier.weight(1f),
            )
            // Кнопка нарочно мелкая и без заливки: останавливать сбор — редкое и
            // осознанное действие, случайно попасть в него с главного экрана
            // нельзя.
            TextButton(
                onClick = if (state.serviceRunning) onStopService else onStartService,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.SpaceXs),
            ) {
                Text(
                    text = if (state.serviceRunning) "Остановить" else "Запустить",
                    style = SesameText.Caption.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Palette.Amber,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Кнопка открытия.
 *
 * Крупное имя места и мелкая вторая строка: за рулём читают «Северный», а буква
 * шлагбаума и номер нужны, только когда что-то настраивают. **Номер замаскирован**
 * — домашний экран видно посторонним, а номер шлагбаума это ключ от двора.
 *
 * Цвет берётся не из схемы, а из [BarrierAccent]: рядом стоят две кнопки, и из
 * одной роли Material их не покрасить.
 */
@Composable
private fun BarrierButton(
    barrier: Barrier,
    position: Int,
    accent: BarrierAccent,
    immediate: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = buildString {
        append(barrier.label.replaceFirstChar { it.lowercase() })
        append(" · ")
        append(
            when {
                immediate -> "звонок сразу"
                else -> maskPhone(barrier.phoneNumber) ?: "номер не задан"
            },
        )
    }

    SesameSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.BarrierButtonHeight),
        color = accent.container,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        corner = Dimens.BarrierCorner,
        contentColor = accent.onContainer,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Icon(
                imageVector = SesameIcons.BoomGate,
                contentDescription = null,
                tint = accent.onContainer,
                modifier = Modifier.size(Dimens.BarrierIcon),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // Имя ужимается шрифтом, а не обрезается. С обычным Text и
                // maxLines = 1 длинное «Шлагбаум Северный въезд» переносилось
                // по пробелу, вторая строка отбрасывалась — и на кнопке
                // оставалось одно «Шлагбаум».
                FittedName(
                    text = barrier.displayName,
                    style = SesameText.BarrierName,
                    color = accent.onContainer,
                )
                Text(
                    text = subtitle,
                    style = SesameText.BarrierSub,
                    color = accent.onContainerMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Имя шлагбаума в одну строку: если не влезает, шрифт уменьшается до 16 sp, и
 * только потом — многоточие. Обрезать по слову нельзя: у имён общее начало
 * («Шлагбаум …»), и различающая часть пропадала бы первой.
 */
@Composable
private fun FittedName(text: String, style: TextStyle, color: Color) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 16.sp,
            maxFontSize = style.fontSize,
            stepSize = 1.sp,
        ),
    )
}

/** Прямоугольная кнопка макета: заливка или контур, без теней и пилюль Material. */
@Composable
private fun FlatButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: Color? = null,
    height: androidx.compose.ui.unit.Dp = 56.dp,
    corner: androidx.compose.ui.unit.Dp = 18.dp,
    style: androidx.compose.ui.text.TextStyle = SesameText.RowTitle.copy(fontSize = 16.sp),
) {
    SesameSurface(
        modifier = modifier.height(height),
        color = container,
        borderColor = border ?: Color.Transparent,
        borderWidth = if (border != null) Dimens.BorderWidth else 0.dp,
        corner = corner,
        contentColor = content,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = style, color = content, maxLines = 1)
        }
    }
}

/**
 * Отсчёт перед звонком (§4.1) во весь экран.
 *
 * Пока он идёт, звонок ещё не ушёл, и единственное осмысленное действие —
 * отмена. Поэтому экран закрывается целиком: кнопка «Отмена» получается
 * во всю ширину и в 96 dp высотой, промахнуться мимо неё нельзя. Кнопка «Назад»
 * и системный жест закрывают окно и тоже отменяют звонок.
 */
@Composable
private fun CountdownOverlay(
    countdown: CountdownState,
    barrier: Barrier?,
    accent: BarrierAccent,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.CallBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(22.dp))
                Text(
                    "Звоню через",
                    style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.AmberTextSoft,
                )
                Spacer(Modifier.height(6.dp))
                FittedName(
                    text = barrier?.displayName ?: countdown.barrierLabel,
                    style = SesameText.DialogTitle,
                    color = accent.container,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = maskPhone(barrier?.phoneNumber) ?: "номер не задан",
                    style = SesameText.Mono14,
                    color = Palette.CallPhone,
                )

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CountdownRing(
                        progress = countdown.progress,
                        seconds = countdown.remainingSeconds,
                        accent = accent.container,
                    )
                }

                FlatButton(
                    text = "Отмена",
                    modifier = Modifier.fillMaxWidth(),
                    container = Palette.TextPrimary,
                    content = Palette.CallBackground,
                    height = 96.dp,
                    corner = 28.dp,
                    style = SesameText.BarrierName,
                    onClick = onCancel,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Отмена тоже попадёт в журнал — как отдельный исход.",
                    style = SesameText.Caption,
                    color = Palette.CallPhone,
                )
            }
        }
    }
}

/** Кольцо отсчёта: убывающая дуга и крупные секунды в центре. */
@Composable
private fun CountdownRing(progress: Float, seconds: Int, accent: Color) {
    Box(
        modifier = Modifier.size(Dimens.CountdownRing),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Dimens.CountdownRingStroke.toPx()
            val inset = stroke / 2
            val diameter = size.minDimension - inset * 2
            val topLeft = Offset(inset, inset)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = Palette.CallRingTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seconds.toString(),
                style = SesameText.MonoCountdown,
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = plural(seconds.toLong(), "секунда", "секунды", "секунд"),
                style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.AmberTextSoft,
            )
        }
    }
}

private val previewBarriers = listOf(
    Barrier(
        id = 1,
        label = "Шлагбаум A",
        phoneNumber = "+7 900 000-00-14",
        lat = null,
        lon = null,
        name = "Северный въезд",
    ),
    Barrier(
        id = 2,
        label = "Шлагбаум B",
        phoneNumber = "+7 900 000-00-09",
        lat = null,
        lon = null,
        name = "Южный въезд",
    ),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    SesameTheme {
        HomeContent(
            state = HomeUiState(
                barriers = previewBarriers,
                lastEventAt = System.currentTimeMillis() - 4 * 60_000,
                usedBytes = 2_400_000_000,
                serviceRunning = true,
            ),
            onBarrierPressed = {},
            onCancelCountdown = {},
            onStartService = {},
            onStopService = {},
            onMessageShown = {},
            carLine = "у Ш2, 3 ч назад",
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun CountdownPreview() {
    SesameTheme {
        CountdownOverlay(
            countdown = CountdownState(1, "Шлагбаум A", 3000, 1800),
            barrier = previewBarriers.first(),
            accent = com.vlad230596.sesame.ui.theme.SesameBarrierAccents.barrier(0),
            onCancel = {},
        )
    }
}
