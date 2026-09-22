package com.vlad230596.sesame.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.AccentStrip
import com.vlad230596.sesame.ui.common.CardHeadRow
import com.vlad230596.sesame.ui.common.PassageCard
import com.vlad230596.sesame.ui.common.SESSION_LABEL_CHOICES
import com.vlad230596.sesame.ui.common.SesameCard
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.StatusDot
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.ThinProgress
import com.vlad230596.sesame.ui.common.formatAgoShort
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.formatDuration
import com.vlad230596.sesame.ui.common.maskPhone
import com.vlad230596.sesame.ui.common.plural
import com.vlad230596.sesame.ui.common.title
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
 * Второе решение: **«Интенсивная запись» — тонкая строка, а не заливная кнопка**.
 * Рядом с двумя огромными цветными кнопками третья такая же читалась как третий
 * шлагбаум, и промах стоил пятнадцатиминутной сессии.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    startSessionRequest: Int = 0,
    onStartSessionHandled: () -> Unit = {},
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HomeContent(
        state = state,
        startSessionRequest = startSessionRequest,
        onStartSessionHandled = onStartSessionHandled,
        onBarrierPressed = viewModel::onBarrierPressed,
        onCancelCountdown = viewModel::cancelCountdown,
        onStartSession = viewModel::startSession,
        onExtendSession = viewModel::extendSession,
        onStopSession = viewModel::stopSession,
        onStartService = viewModel::startService,
        onStopService = viewModel::stopService,
        onDirection = viewModel::setDirection,
        onMode = viewModel::setMode,
        onDiscarded = viewModel::setDiscarded,
        onNote = viewModel::setNote,
        onConfirmAll = viewModel::confirmAll,
        onMessageShown = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onBarrierPressed: (Barrier) -> Unit,
    onCancelCountdown: () -> Unit,
    onStartSession: (SessionLabel) -> Unit,
    onExtendSession: () -> Unit,
    onStopSession: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onDirection: (Long, Direction) -> Unit,
    onMode: (Long, TravelMode) -> Unit,
    onDiscarded: (Long, Boolean) -> Unit,
    onNote: (Long, String) -> Unit,
    onConfirmAll: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    startSessionRequest: Int = 0,
    onStartSessionHandled: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var labelPickerVisible by remember { mutableStateOf(false) }
    var permissionsVisible by remember { mutableStateOf(false) }
    var unconfirmedVisible by remember { mutableStateOf(false) }

    // §4.2: кнопка записи в виджете открывает экран выбора метки сессии. Если
    // сессия уже идёт, открывать нечего — вторую запускать нельзя, и на экране
    // и так виден её таймер.
    LaunchedEffect(startSessionRequest, state.session != null) {
        if (startSessionRequest <= 0) return@LaunchedEffect
        if (state.session == null) labelPickerVisible = true
        onStartSessionHandled()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Box(modifier = modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {

            HomeHeader(lastEventAt = state.lastEventAt, now = state.now)

            // Верхняя половина — то, что читают. Прокручивается: карточка идущей
            // сессии и две плашки вместе в 844 dp не всегда помещаются.
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

                val session = state.session
                if (session != null) {
                    SessionCard(
                        label = session.label,
                        elapsedMillis = state.now - session.startedAt,
                        remainingMillis = session.plannedEndAt - state.now,
                        totalMillis = (session.plannedEndAt - session.startedAt).coerceAtLeast(1),
                        extendMinutes = state.settings.recordingDurationMinutes,
                        onExtend = onExtendSession,
                        onStop = onStopSession,
                    )
                }

                StatusCard(
                    state = state,
                    onStartService = onStartService,
                    onStopService = onStopService,
                )

                if (state.unconfirmedCount > 0) {
                    AccentStrip(
                        text = "${state.unconfirmedCount} " +
                            plural(state.unconfirmedCount.toLong(), "метка", "метки", "меток") +
                            " без подтверждения",
                        action = "Открыть",
                        dotColor = Palette.Amber,
                        accentColor = Palette.Amber,
                        containerColor = Palette.AmberSurface,
                        borderColor = Palette.AmberBorder,
                        onClick = { unconfirmedVisible = true },
                    )
                }

                Spacer(Modifier.height(Dimens.SpaceS))
            }

            // Нижняя половина — то, во что попадают.
            Column(
                modifier = Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.StackGap),
            ) {
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
                        compact = state.session != null,
                        onClick = { onBarrierPressed(barrier) },
                    )
                }

                if (state.session == null) {
                    RecordRow(
                        minutes = state.settings.recordingDurationMinutes,
                        onClick = { labelPickerVisible = true },
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

    if (labelPickerVisible) {
        SessionLabelDialog(
            onPick = { label ->
                labelPickerVisible = false
                onStartSession(label)
            },
            onDismiss = {
                // Закрытие окна — это отмена, и ничего больше.
                //
                // §4.3 говорит «тап по любой другой области запускает запись без
                // метки», и буквально так и было сделано. На устройстве это
                // оказалось ловушкой: и тап мимо окна, и кнопка «Назад» — в
                // Android универсальные жесты отмены, а здесь они запускали
                // пятнадцатиминутную сессию. Запустить запись случайно легко,
                // заметить это — нет.
                //
                // Намерение §4.3 («не заставлять выбирать метку») закрыто явной
                // кнопкой «Начать без метки» внутри окна. Подпись в окне говорит
                // ровно то, что происходит, — макет на этот счёт написан до того,
                // как баг нашёлся, и его текст здесь не повторяется.
                labelPickerVisible = false
            },
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

    if (unconfirmedVisible) {
        ModalBottomSheet(
            onDismissRequest = { unconfirmedVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                Text("Метки без подтверждения", style = SesameText.DialogTitle.copy(fontSize = 24.sp))
                Text(
                    "Направление и режим заполнены догадкой. Один тап по чипу — " +
                        "и метка подтверждена.",
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                )
                state.unconfirmed.forEach { label ->
                    PassageCard(
                        label = label,
                        barrierLabel = state.barrierLabel(label.barrierId),
                        onDirection = { onDirection(label.id, it) },
                        onMode = { onMode(label.id, it) },
                        onToggleDiscarded = { onDiscarded(label.id, it) },
                        onNote = { onNote(label.id, it) },
                    )
                }
                TextButton(
                    onClick = {
                        onConfirmAll()
                        unconfirmedVisible = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Подтвердить все как есть") }
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
    compact: Boolean,
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
            .height(if (compact) Dimens.BarrierButtonHeightCompact else Dimens.BarrierButtonHeight),
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
                modifier = Modifier.size(
                    if (compact) Dimens.BarrierIconSmall else Dimens.BarrierIcon,
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = barrier.displayName,
                    style = if (compact) {
                        SesameText.BarrierName.copy(fontSize = 24.sp)
                    } else {
                        SesameText.BarrierName
                    },
                    color = accent.onContainer,
                    maxLines = 1,
                )
                if (!compact) {
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
}

/**
 * «Интенсивная запись» — строка, а не кнопка (макет, 64 dp).
 *
 * Цветная точка вместо заливки: смысл «сейчас можно начать запись» остаётся
 * читаемым, а вес на экране — на порядок меньше, чем у открытия шлагбаума.
 */
@Composable
private fun RecordRow(minutes: Int, onClick: () -> Unit) {
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
            StatusDot(Palette.Record, Dimens.DotLarge)
            Text(
                text = "Интенсивная запись",
                style = SesameText.RowTitle,
                color = Palette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text("$minutes мин", style = SesameText.Mono13, color = Palette.TextMuted)
        }
    }
}

/** Карточка идущей сессии: сколько осталось, «Продлить» и «Стоп» (§4.3). */
@Composable
private fun SessionCard(
    label: SessionLabel,
    elapsedMillis: Long,
    remainingMillis: Long,
    totalMillis: Long,
    extendMinutes: Int,
    onExtend: () -> Unit,
    onStop: () -> Unit,
) {
    SesameCard(
        color = Palette.RecordSurface,
        borderColor = Palette.RecordBorder,
        corner = Dimens.ButtonCorner,
        horizontalPadding = 18.dp,
        verticalPadding = 18.dp,
        gap = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(Palette.Record, Dimens.DotLarge)
            Text(
                "Запись идёт",
                style = SesameText.RowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = Palette.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = label.title(),
                style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = Palette.RecordChipText,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Palette.RecordChip)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = formatDuration(remainingMillis.coerceAtLeast(0)),
                style = SesameText.MonoTimer,
                color = Palette.TextPrimary,
            )
            Text(
                text = "осталось из ${formatDuration(totalMillis)}",
                style = SesameText.BarrierSub.copy(fontWeight = FontWeight.Normal),
                color = Palette.RecordMuted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        ThinProgress(
            progress = elapsedMillis.toFloat() / totalMillis,
            color = Palette.Record,
            trackColor = Palette.RecordChip,
            thickness = Dimens.ProgressThick,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatButton(
                text = "Продлить +$extendMinutes мин",
                modifier = Modifier.weight(1f),
                container = Color.Transparent,
                content = Palette.RecordOutlineText,
                border = Palette.RecordOutline,
                onClick = onExtend,
            )
            FlatButton(
                text = "Стоп",
                modifier = Modifier.weight(1f),
                container = Palette.Record,
                content = Palette.OnRecord,
                onClick = onStop,
            )
        }

        Text(
            "Первые 60 с взяты из кольцевого буфера — момент посадки не потерян.",
            style = SesameText.Caption.copy(fontSize = 12.sp),
            color = Palette.RecordDim,
        )
    }
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
                Text(
                    text = barrier?.displayName ?: countdown.barrierLabel,
                    style = SesameText.DialogTitle,
                    color = accent.container,
                    maxLines = 1,
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

/**
 * Выбор метки сессии (§4.3): четыре крупные плитки два на два.
 *
 * Окно перекрывает экран почти целиком, но не до краёв: тап по полю вокруг —
 * отмена, как и «Назад». Записать пятнадцать минут случайно нельзя.
 */
@Composable
private fun SessionLabelDialog(onPick: (SessionLabel) -> Unit, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Background.copy(alpha = 0.97f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                Text("Метка сессии", style = SesameText.DialogTitle, color = Palette.TextPrimary)
                Text(
                    "Выберите, что происходит, — или начните без метки: её можно " +
                        "проставить позже в истории. Закрыть окно значит не начинать запись.",
                    style = SesameText.Body,
                    color = Palette.TextMuted,
                )
                Spacer(Modifier.height(Dimens.SpaceS))

                SESSION_LABEL_CHOICES.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { label ->
                            LabelTile(
                                label = label,
                                modifier = Modifier.weight(1f),
                                onClick = { onPick(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                Spacer(Modifier.height(Dimens.SpaceS))
                FlatButton(
                    text = "Начать без метки",
                    modifier = Modifier.fillMaxWidth(),
                    container = Color.Transparent,
                    content = Palette.TextMuted,
                    border = Palette.BorderStrong,
                    height = 60.dp,
                    corner = Dimens.RowCorner,
                    onClick = { onPick(SessionLabel.NONE) },
                )
            }
        }
    }
}

@Composable
private fun LabelTile(label: SessionLabel, modifier: Modifier, onClick: () -> Unit) {
    val (icon, tint) = labelIcon(label)
    SesameSurface(
        modifier = modifier.height(Dimens.LabelTileHeight),
        color = Palette.Surface,
        borderColor = Palette.BorderStrong,
        borderWidth = Dimens.BorderWidth,
        corner = Dimens.ButtonCorner,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
            Text(label.title(), style = SesameText.TileTitle, color = Palette.TextPrimary)
        }
    }
}

/**
 * Цвет иконки метки повторяет смысл направления: «домой» янтарный, «из дома»
 * бирюзовый — та же пара, что у шлагбаумов, и то же «туда/обратно».
 */
private fun labelIcon(label: SessionLabel): Pair<ImageVector, Color> = when (label) {
    SessionLabel.GOING_HOME -> SesameIcons.HouseIn to Palette.Amber
    SessionLabel.LEAVING_HOME -> SesameIcons.HouseOut to Palette.Teal
    SessionLabel.ON_FOOT -> SesameIcons.Walk to Color(0xFFB9C0CA)
    SessionLabel.OTHER -> SesameIcons.Question to Color(0xFFB9C0CA)
    SessionLabel.NONE -> SesameIcons.Question to Palette.TextMuted
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
                unconfirmed = listOf(
                    PassageLabel(
                        id = 5,
                        timestamp = System.currentTimeMillis() - 400_000,
                        barrierId = 1,
                        source = com.vlad230596.sesame.data.LabelSource.WIDGET,
                        outcome = com.vlad230596.sesame.data.CallOutcome.CALLED,
                    ),
                ),
                lastEventAt = System.currentTimeMillis() - 4 * 60_000,
                usedBytes = 2_400_000_000,
                serviceRunning = true,
            ),
            onBarrierPressed = {},
            onCancelCountdown = {},
            onStartSession = {},
            onExtendSession = {},
            onStopSession = {},
            onStartService = {},
            onStopService = {},
            onDirection = { _, _ -> },
            onMode = { _, _ -> },
            onDiscarded = { _, _ -> },
            onNote = { _, _ -> },
            onConfirmAll = {},
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeRecordingPreview() {
    val now = System.currentTimeMillis()
    SesameTheme {
        HomeContent(
            state = HomeUiState(
                barriers = previewBarriers,
                session = com.vlad230596.sesame.session.ActiveSession(
                    id = 1,
                    label = SessionLabel.GOING_HOME,
                    startedAt = now - 4 * 60_000,
                    plannedEndAt = now + 11 * 60_000,
                ),
                lastEventAt = now,
                usedBytes = 2_400_000_000,
                serviceRunning = true,
                now = now,
            ),
            onBarrierPressed = {},
            onCancelCountdown = {},
            onStartSession = {},
            onExtendSession = {},
            onStopSession = {},
            onStartService = {},
            onStopService = {},
            onDirection = { _, _ -> },
            onMode = { _, _ -> },
            onDiscarded = { _, _ -> },
            onNote = { _, _ -> },
            onConfirmAll = {},
            onMessageShown = {},
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

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SessionLabelDialogPreview() {
    SesameTheme {
        SessionLabelDialog(onPick = {}, onDismiss = {})
    }
}
