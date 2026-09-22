package com.vlad230596.sesame.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.tooling.preview.Preview
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
import com.vlad230596.sesame.ui.common.InfoRow
import com.vlad230596.sesame.ui.common.PassageCard
import com.vlad230596.sesame.ui.common.SectionCard
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.TabHeader
import com.vlad230596.sesame.ui.common.SESSION_LABEL_CHOICES
import com.vlad230596.sesame.ui.common.formatAgo
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.formatDuration
import com.vlad230596.sesame.ui.common.plural
import com.vlad230596.sesame.ui.common.title
import com.vlad230596.sesame.ui.permissions.PermissionsScreen
import com.vlad230596.sesame.ui.theme.BarrierAccent
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.SesameAccentColors
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Вкладка «Главная» (§4.7).
 *
 * Порядок блоков сверху вниз повторяет порядок важности за рулём: сначала то,
 * что мешает (баннер разрешений), потом две кнопки открытия, потом запись, и
 * только в самом низу — статус. Строка «последнее фоновое событие» при этом
 * остаётся на экране без прокрутки: это единственный индикатор того, что сбор
 * не умер.
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

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        // Верхний отступ под статус-бар добавляет TabHeader (TopAppBar), нижний —
        // MainActivity: Scaffold не должен приставлять к ним свой.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            TabHeader("Сезам")

            if (state.missingPermissions.isNotEmpty()) {
                PermissionsBanner(
                    missing = state.missingPermissions.size,
                    onOpen = { permissionsVisible = true },
                )
            }

            if (state.unconfirmedCount > 0) {
                UnconfirmedBanner(
                    count = state.unconfirmedCount,
                    onOpen = { unconfirmedVisible = true },
                )
            }

            // Цвет закреплён за позицией кнопки, а не за id шлагбаума: верхняя
            // всегда одна и та же, и именно это запоминает рука за рулём.
            state.barriers.forEachIndexed { index, barrier ->
                val accent = SesameAccentColors.current.barrier(index)
                val countdown = state.countdown?.takeIf { it.barrierId == barrier.id }
                if (countdown != null) {
                    CountdownCard(
                        countdown = countdown,
                        accent = accent,
                        onCancel = onCancelCountdown,
                    )
                } else {
                    BarrierButton(
                        barrier = barrier,
                        accent = accent,
                        immediate = state.settings.cancelTimeoutSeconds == 0,
                        onClick = { onBarrierPressed(barrier) },
                    )
                }
            }

            if (state.barriers.isEmpty()) {
                SectionCard {
                    Text("Шлагбаумы не настроены. Настройки → Шлагбаумы.")
                }
            }

            val session = state.session
            if (session == null) {
                RecordButton(onClick = { labelPickerVisible = true })
            } else {
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

            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }

    if (labelPickerVisible) {
        SessionLabelDialog(
            onPick = { label ->
                labelPickerVisible = false
                onStartSession(label)
            },
            onDismiss = {
                // §4.3: тап мимо кнопок запускает запись без метки — метку можно
                // проставить позже в истории. Начать запись важнее, чем выбрать метку.
                labelPickerVisible = false
                onStartSession(SessionLabel.NONE)
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
                color = MaterialTheme.colorScheme.background,
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
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                Text(
                    "Метки без подтверждения",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Направление и режим заполнены догадкой. Один тап по чипу — " +
                        "и метка подтверждена.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun PermissionsBanner(missing: Int, onOpen: () -> Unit) {
    SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        InfoRow(
            text = "Не хватает разрешений: $missing",
            icon = Icons.Filled.Warning,
            emphasised = true,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "Без них сбор данных идёт с дырами, а звонок уходит через звонилку.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть разрешения")
        }
    }
}

@Composable
private fun UnconfirmedBanner(count: Int, onOpen: () -> Unit) {
    SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        InfoRow(
            text = "$count ${plural(count.toLong(), "метка", "метки", "меток")} без подтверждения",
            icon = Icons.Filled.Info,
            emphasised = true,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("Разобрать")
        }
    }
}

/**
 * Кнопка открытия. Цвет берётся не из схемы, а из [BarrierAccent]: в схеме лежит
 * нейтральная сталь, и если красить кнопку от `primary`, красным станет весь
 * интерфейс, а две кнопки окажутся неразличимы между собой.
 */
@Composable
private fun BarrierButton(
    barrier: Barrier,
    accent: BarrierAccent,
    immediate: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.BarrierButtonHeight),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.container,
            contentColor = accent.onContainer,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(barrier.label, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when {
                    barrier.phoneNumber.isNullOrBlank() -> "номер не задан"
                    immediate -> "звонок сразу, без подтверждения"
                    else -> barrier.phoneNumber
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Состояние обратного отсчёта занимает место самой кнопки — экран не прыгает.
 * Цвет тоже остаётся от кнопки: отсчёт идёт по конкретному шлагбауму, и на
 * экране должно быть видно по какому, не вчитываясь в подпись.
 */
@Composable
private fun CountdownCard(
    countdown: CountdownState,
    accent: BarrierAccent,
    onCancel: () -> Unit,
) {
    SectionCard(
        containerColor = accent.container,
        contentColor = accent.onContainer,
    ) {
        Text(
            "${countdown.barrierLabel}: звоним через ${countdown.remainingSeconds}…",
            style = MaterialTheme.typography.titleLarge,
            color = accent.onContainer,
        )
        LinearProgressIndicator(
            progress = { countdown.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.SpaceS),
            color = accent.onContainer,
            trackColor = accent.onContainer.copy(alpha = 0.3f),
        )
        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.BarrierButtonHeight - Dimens.SpaceXl),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("Отмена", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.RecordButtonHeight),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
    ) {
        Text("Интенсивная запись", style = MaterialTheme.typography.titleLarge)
    }
}

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
    SectionCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            "Идёт запись · ${label.title()}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            "Прошло ${formatDuration(elapsedMillis)} · осталось ${formatDuration(remainingMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        LinearProgressIndicator(
            progress = { (elapsedMillis.toFloat() / totalMillis).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.SpaceS),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.3f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            OutlinedButton(
                onClick = onExtend,
                modifier = Modifier
                    .weight(1f)
                    .height(Dimens.MinTouchTarget),
            ) { Text("Продлить +$extendMinutes") }
            Button(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(Dimens.MinTouchTarget),
            ) { Text("Стоп") }
        }
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    SectionCard {
        // Самая важная строка экрана (§4.7): единственный признак того, что
        // фоновый сбор жив. Поэтому она крупная и стоит первой в плашке.
        InfoRow(
            text = "Последнее фоновое событие: ${formatAgo(state.lastEventAt, state.now)}",
            icon = Icons.Filled.Info,
            emphasised = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoRow(
                text = if (state.serviceRunning) "Сервис сбора работает" else "Сервис сбора не запущен",
                icon = if (state.serviceRunning) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                tint = if (state.serviceRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = if (state.serviceRunning) onStopService else onStartService) {
                Text(if (state.serviceRunning) "Остановить" else "Запустить")
            }
        }
        val limit = SesameSettings.STORAGE_LIMIT_BYTES
        Text(
            "Занято ${formatBytes(state.usedBytes)} из ${formatBytes(limit)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (state.usedBytes.toFloat() / limit).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Экран выбора метки сессии (§4.3): четыре крупные кнопки. */
@Composable
private fun SessionLabelDialog(onPick: (SessionLabel) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        SectionCard(contentPadding = Dimens.SpaceM) {
            Text("Метка сессии", style = MaterialTheme.typography.titleLarge)
            Text(
                "Тап мимо окна запустит запись без метки — её можно будет " +
                    "проставить в истории.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SESSION_LABEL_CHOICES.forEach { label ->
                Button(
                    onClick = { onPick(label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.RecordButtonHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) {
                    Text(label.title(), style = MaterialTheme.typography.titleLarge)
                }
            }
            TextButton(
                onClick = { onPick(SessionLabel.NONE) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Начать без метки") }
        }
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun HomePreview() {
    SesameTheme {
        HomeContent(
            state = HomeUiState(
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = "+7 900 000-00-00", lat = null, lon = null),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null),
                ),
                unconfirmed = listOf(
                    PassageLabel(
                        id = 5,
                        timestamp = System.currentTimeMillis() - 400_000,
                        barrierId = 1,
                        source = com.vlad230596.sesame.data.LabelSource.WIDGET,
                        outcome = com.vlad230596.sesame.data.CallOutcome.CALLED,
                    ),
                ),
                lastEventAt = System.currentTimeMillis() - 8 * 60_000,
                usedBytes = 1_400_000_000,
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

/**
 * Светлая схема. Смысловые цвета кнопок в ней те же самые — проверяется, что
 * нейтральная механика (чипы, второстепенные кнопки, прогресс) не уехала в
 * красный и на светлом конце шкалы.
 */
@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun HomeLightPreview() {
    SesameTheme(darkTheme = false) {
        HomeContent(
            state = HomeUiState(
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = "+7 900 000-00-00", lat = null, lon = null),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null),
                ),
                lastEventAt = System.currentTimeMillis() - 8 * 60_000,
                usedBytes = 1_400_000_000,
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

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun HomeCountdownPreview() {
    SesameTheme {
        HomeContent(
            state = HomeUiState(
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = "+7 900 000-00-00", lat = null, lon = null),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = "+7 900 000-00-01", lat = null, lon = null),
                ),
                countdown = CountdownState(1, "Шлагбаум A", 3000, 1800),
                lastEventAt = System.currentTimeMillis() - 90 * 60_000,
                serviceRunning = false,
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

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun SessionLabelDialogPreview() {
    SesameTheme {
        Box(modifier = Modifier.padding(Dimens.SpaceM)) {
            SessionLabelDialog(onPick = {}, onDismiss = {})
        }
    }
}
