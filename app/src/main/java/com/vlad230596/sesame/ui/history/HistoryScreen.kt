package com.vlad230596.sesame.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.entity.RecordingSession
import com.vlad230596.sesame.ui.common.FilterPill
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.GroupCaption
import com.vlad230596.sesame.ui.common.LabelEditor
import com.vlad230596.sesame.ui.common.SegmentedRow
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameField
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SquareIconButton
import com.vlad230596.sesame.ui.common.TabHeader
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.formatDateTime
import com.vlad230596.sesame.ui.common.formatDayHeader
import com.vlad230596.sesame.ui.common.formatDuration
import com.vlad230596.sesame.ui.common.formatTime
import com.vlad230596.sesame.ui.common.localDateOf
import com.vlad230596.sesame.ui.common.title
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameAccentColors
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Вкладка «История» (§4.5), по утверждённому макету.
 *
 * Лента — это список **строк**, а не карточек-редакторов: за неделю сбора здесь
 * копятся сотни событий, и экран, на котором помещается три карточки, перестаёт
 * быть историей. Правка живёт в окне, которое поднимается по тапу, и там метка
 * правится в один тап — ровно как требует §4.5.
 *
 * Слева у каждой строки полоска цветом шлагбаума: та же пара, что у кнопок на
 * главном экране и в виджете. Цвет закреплён за позицией кнопки, поэтому по
 * полоске видно, в какую именно кнопку человек попадал, не читая подпись.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Share sheet поднимается из композиции: ViewModel не должна знать про Activity.
    LaunchedEffect(state.shareRequest) {
        val request = state.shareRequest ?: return@LaunchedEffect
        val started = runCatching {
            context.startActivity(
                android.content.Intent.createChooser(
                    request.intent,
                    request.description.ifBlank { "Выгрузить сессии" },
                ),
            )
        }.isSuccess
        if (started) viewModel.onShareLaunched(request.sessionIds) else viewModel.onShareFailed()
    }

    HistoryContent(
        state = state,
        onFilter = viewModel::setFilter,
        onDirection = viewModel::setDirection,
        onMode = viewModel::setMode,
        onDiscarded = viewModel::setDiscarded,
        onNote = viewModel::setNote,
        onConfirm = viewModel::confirm,
        onShare = viewModel::requestShare,
        onAddManual = viewModel::addManual,
        onMessageShown = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    state: HistoryUiState,
    onFilter: (HistoryFilter) -> Unit,
    onDirection: (Long, Direction) -> Unit,
    onMode: (Long, TravelMode) -> Unit,
    onDiscarded: (Long, Boolean) -> Unit,
    onNote: (Long, String) -> Unit,
    onConfirm: (Long) -> Unit,
    onShare: () -> Unit,
    onAddManual: (Long, Long?, Direction, TravelMode, String?) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var manualVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var sessionDetails by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Box(modifier = modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {
            TabHeader("История") {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    SquareIconButton(
                        icon = SesameIcons.Export,
                        contentDescription = if (state.preparing) {
                            "Собираю архив"
                        } else {
                            "Выгрузить непошаренное: ${state.unsharedCount}"
                        },
                        enabled = !state.preparing,
                        onClick = onShare,
                        tint = if (state.unsharedCount > 0) Palette.Amber else Palette.TextPrimary,
                    )
                    SquareIconButton(
                        icon = SesameIcons.Plus,
                        contentDescription = "Добавить метку задним числом",
                        onClick = { manualVisible = true },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.StackGap),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                FilterPill(
                    text = "Всё",
                    selected = state.filter == HistoryFilter.ALL,
                    onClick = { onFilter(HistoryFilter.ALL) },
                )
                FilterPill(
                    text = "Без подтверждения · ${state.unconfirmedCount}",
                    selected = state.filter == HistoryFilter.UNCONFIRMED,
                    onClick = { onFilter(HistoryFilter.UNCONFIRMED) },
                )
                FilterPill(
                    text = "Сессии",
                    selected = state.filter == HistoryFilter.SESSIONS,
                    onClick = { onFilter(HistoryFilter.SESSIONS) },
                )
            }

            val items = state.visibleItems
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.SpaceXl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                if (items.isEmpty()) {
                    item {
                        SesameSurface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Tones.SurfaceLow,
                            borderColor = Palette.BorderStrong,
                        ) {
                            Text(
                                text = when (state.filter) {
                                    HistoryFilter.ALL -> "Пока ничего нет. Метки появятся после " +
                                        "нажатия кнопки шлагбаума или запуска интенсивной записи."

                                    HistoryFilter.UNCONFIRMED -> "Всё разобрано: меток без " +
                                        "подтверждения нет."

                                    HistoryFilter.SESSIONS -> "Сессий записи ещё не было."
                                },
                                style = SesameText.Caption,
                                color = Palette.TextMuted,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }

                var lastDay: String? = null
                items.forEach { entry ->
                    val day = formatDayHeader(entry.timestamp)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "header-$day-${entry.key}") {
                            GroupCaption(day, top = Dimens.SpaceXs)
                        }
                    }
                    item(key = entry.key) {
                        when (entry) {
                            is HistoryItem.Passage -> PassageRow(
                                entry = entry,
                                onClick = { editing = entry.label.id },
                            )

                            is HistoryItem.Session -> SessionRow(
                                session = entry.session,
                                onClick = { sessionDetails = entry.session.id },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Правка метки (макет «Правка метки»): окно поверх ленты, лента остаётся
    // видна сверху — так понятно, что правится одна строка, а не открыт новый
    // экран, с которого надо возвращаться.
    val edited = editing?.let { id ->
        state.items.filterIsInstance<HistoryItem.Passage>().firstOrNull { it.label.id == id }
    }
    if (edited != null) {
        val accent = SesameAccentColors.current.barrier(edited.barrierIndex).container
        ModalBottomSheet(
            onDismissRequest = {
                onConfirm(edited.label.id)
                editing = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceL),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            ) {
                LabelEditor(
                    label = edited.label,
                    barrierName = edited.barrierName,
                    accent = accent,
                    onDirection = { onDirection(edited.label.id, it) },
                    onMode = { onMode(edited.label.id, it) },
                    onToggleDiscarded = { onDiscarded(edited.label.id, it) },
                    onNote = { onNote(edited.label.id, it) },
                )
                SesameButton(
                    text = "Готово",
                    modifier = Modifier.fillMaxWidth(),
                    height = 58.dp,
                    onClick = {
                        onConfirm(edited.label.id)
                        editing = null
                    },
                )
            }
        }
    }

    val session = sessionDetails?.let { id ->
        state.items.filterIsInstance<HistoryItem.Session>().firstOrNull { it.session.id == id }
    }
    if (session != null) {
        ModalBottomSheet(
            onDismissRequest = { sessionDetails = null },
            containerColor = Palette.Surface,
        ) {
            SessionDetails(
                session = session.session,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceXl),
            )
        }
    }

    if (manualVisible) {
        ManualPassageSheet(
            barriers = state.barriers,
            onDismiss = { manualVisible = false },
            onAdd = { timestamp, barrierId, direction, mode, note ->
                manualVisible = false
                onAddManual(timestamp, barrierId, direction, mode, note)
            },
        )
    }
}

/**
 * Строка метки проезда.
 *
 * Отменённая до звонка и ошибочная метки нарисованы тише и полоской без цвета:
 * они остаются в ленте (в датасете «передумал» и «не нажимал» — разные события),
 * но глаз по ним не цепляется.
 */
@Composable
private fun PassageRow(entry: HistoryItem.Passage, onClick: () -> Unit) {
    val label = entry.label
    val muted = label.discarded || label.outcome == CallOutcome.CANCELLED_BY_USER
    val accent = SesameAccentColors.current.barrier(entry.barrierIndex).container

    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = if (muted) Tones.SurfaceLow else Palette.Surface,
        borderColor = Palette.BorderStrong,
        corner = Dimens.CardCorner,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            AccentBar(if (muted) Tones.StripNeutral else accent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    Text(
                        text = formatTime(label.timestamp),
                        style = SesameText.Mono14,
                        color = if (muted) Palette.TextMuted else Tones.TextSoft,
                    )
                    Text(
                        text = entry.barrierName ?: "Шлагбаум не указан",
                        style = SesameText.CardTitle,
                        color = if (muted) Tones.TextSoft else Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = passageSubtitle(label),
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.align(Alignment.CenterVertically)) {
                when {
                    // «Подтвердить» важнее «сбоя»: сбой уже описан строкой ниже,
                    // а вот метка, которую ещё не посмотрели глазами, — это
                    // единственное, что от человека требуется.
                    !label.confirmed ->
                        StatusChip("подтвердить", Palette.AmberSurface, Palette.Amber)

                    label.outcome == CallOutcome.DIALER_FALLBACK ||
                        label.outcome == CallOutcome.NO_NUMBER ->
                        StatusChip("сбой", Palette.RecordChip, Palette.RecordChipText)

                    else -> Icon(
                        imageVector = SesameIcons.ChevronRight,
                        contentDescription = null,
                        tint = Palette.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Строка сессии записи. Полоска коралловая — тот же цвет, что у «идёт запись». */
@Composable
private fun SessionRow(session: RecordingSession, onClick: () -> Unit) {
    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Surface,
        borderColor = Palette.BorderStrong,
        corner = Dimens.CardCorner,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            AccentBar(Palette.Record)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    Text(
                        text = formatTime(session.startedAt),
                        style = SesameText.Mono14,
                        color = Tones.TextSoft,
                    )
                    Text(
                        text = "Сессия записи",
                        style = SesameText.CardTitle,
                        color = Palette.TextPrimary,
                    )
                }
                Text(
                    text = sessionSubtitle(session),
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.align(Alignment.CenterVertically)) {
                if (session.endedAt == null) {
                    StatusChip("идёт", Palette.RecordChip, Palette.RecordChipText)
                } else {
                    Icon(
                        imageVector = SesameIcons.ChevronRight,
                        contentDescription = null,
                        tint = Palette.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Цветная полоска слева: цвет шлагбаума, в кнопку которого попадали. */
@Composable
private fun AccentBar(color: Color) {
    Box(
        Modifier
            .width(3.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun StatusChip(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = SesameText.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Вторая строка: что человек разметил и чем кончилась попытка.
 *
 * Исход не вытесняет разметку, а стоит после неё. Иначе метка со сбоем навсегда
 * показывала бы «номер не задан» — и то, что её уже поправили руками, из ленты
 * было бы не видно.
 */
private fun passageSubtitle(label: PassageLabel): String {
    val parts = buildList {
        if (label.direction != Direction.UNKNOWN) add(label.direction.title())
        if (label.mode != TravelMode.UNKNOWN) add(label.mode.title())
        add(
            when (label.outcome) {
                CallOutcome.CANCELLED_BY_USER -> "отменено до звонка"
                CallOutcome.DIALER_FALLBACK -> "звонок не ушёл, открыта звонилка"
                CallOutcome.NO_NUMBER -> "номер не задан"
                CallOutcome.CALLED, null -> label.source.title()
            },
        )
        if (label.discarded) add("ошибочная")
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

private fun sessionSubtitle(session: RecordingSession): String = buildString {
    append("«").append(session.label.title()).append("» · ")
    val ended = session.endedAt
    if (ended == null) append("идёт сейчас") else append(formatDuration(ended - session.startedAt))
    append(" · ").append(formatBytes(session.sizeBytes))
}

@Composable
private fun SessionDetails(session: RecordingSession, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        Text(
            text = "Сессия записи",
            style = SesameText.DialogTitle.copy(fontSize = 22.sp),
            color = Palette.TextPrimary,
        )
        Text(
            text = "«${session.label.title()}» · начата ${formatDateTime(session.startedAt)}",
            style = SesameText.Caption,
            color = Palette.TextMuted,
        )
        Spacer(Modifier.height(Dimens.SpaceXs))
        DetailRow("Длительность", session.endedAt?.let { formatDuration(it - session.startedAt) } ?: "идёт сейчас")
        DetailRow("Объём", formatBytes(session.sizeBytes))
        DetailRow("Выгружено", if (session.shared) "да" else "нет")
        session.fileDir?.let { DetailRow("Файлы", it) }
        FootNote(
            "Файлы забираются с телефона по USB из Documents/Sesame/. Кнопка выгрузки " +
                "в шапке собирает архив из всех ещё не выгруженных сессий.",
        )
    }
}

@Composable
private fun DetailRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
    ) {
        Text(title, style = SesameText.Caption, color = Palette.TextMuted)
        Text(
            text = value,
            style = SesameText.Mono13,
            color = Palette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Ретро-метка (§4.5). Время по умолчанию — «сейчас», потому что типичный случай
 * это «только что проехали, открыл не я».
 *
 * Пресеты «−5 мин» и «−1 ч» стоят перед календарём сознательно: в девяти случаях
 * из десяти правка времени — это «чуть раньше, чем сейчас», и ради неё не надо
 * открывать два системных диалога.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualPassageSheet(
    barriers: List<Barrier>,
    onDismiss: () -> Unit,
    onAdd: (Long, Long?, Direction, TravelMode, String?) -> Unit,
) {
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var barrierId by remember { mutableStateOf(barriers.firstOrNull()?.id) }
    var direction by remember { mutableStateOf(Direction.UNKNOWN) }
    var mode by remember { mutableStateOf(TravelMode.UNKNOWN) }
    var note by remember { mutableStateOf("") }
    var datePickerVisible by remember { mutableStateOf(false) }
    var timePickerVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                Text(
                    text = "Проезд задним числом",
                    style = SesameText.DialogTitle.copy(fontSize = 22.sp),
                    color = Palette.TextPrimary,
                )
                Text(
                    text = "Например, шлагбаум открыл кто-то другой из машины.",
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(
                    text = "Когда",
                    style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.TextMuted,
                )
                Text(
                    text = formatDateTime(timestamp),
                    style = SesameText.Mono14.copy(fontSize = 18.sp),
                    color = Palette.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    TimeShortcut("Сейчас", Modifier.weight(1f)) {
                        timestamp = System.currentTimeMillis()
                    }
                    TimeShortcut("−5 мин", Modifier.weight(1f)) { timestamp -= 5 * 60_000 }
                    TimeShortcut("−1 ч", Modifier.weight(1f)) { timestamp -= 60 * 60_000 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    TimeShortcut("Дата", Modifier.weight(1f)) { datePickerVisible = true }
                    TimeShortcut("Время", Modifier.weight(1f)) { timePickerVisible = true }
                }
            }

            if (barriers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    Text(
                        text = "Шлагбаум",
                        style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                        color = Palette.TextMuted,
                    )
                    SegmentedRow(
                        options = barriers.map { it.id },
                        selected = barrierId ?: -1L,
                        onSelect = { barrierId = it },
                        label = { id -> barriers.first { it.id == id }.displayName },
                        height = 50.dp,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(
                    text = "Направление",
                    style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.TextMuted,
                )
                SegmentedRow(
                    options = listOf(Direction.IN, Direction.OUT, Direction.UNKNOWN),
                    selected = direction,
                    onSelect = { direction = it },
                    label = { if (it == Direction.UNKNOWN) "?" else it.title() },
                    weights = { if (it == Direction.UNKNOWN) 0.45f else 1f },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(
                    text = "Режим",
                    style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.TextMuted,
                )
                SegmentedRow(
                    options = listOf(
                        TravelMode.DRIVER,
                        TravelMode.PASSENGER,
                        TravelMode.FOOT,
                        TravelMode.UNKNOWN,
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                    label = { if (it == TravelMode.UNKNOWN) "?" else it.title() },
                    weights = { if (it == TravelMode.UNKNOWN) 0.4f else 1f },
                )
            }

            SesameField(
                value = note,
                onValueChange = { note = it },
                label = "Заметка",
                placeholder = "Необязательно",
                singleLine = false,
            )

            SesameButton(
                text = "Добавить",
                modifier = Modifier.fillMaxWidth(),
                height = 58.dp,
                onClick = { onAdd(timestamp, barrierId, direction, mode, note) },
            )
        }
    }

    if (datePickerVisible) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = timestamp)
        DatePickerDialog(
            onDismissRequest = { datePickerVisible = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { picked ->
                        timestamp = replaceDate(timestamp, picked)
                    }
                    datePickerVisible = false
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerVisible = false }) { Text("Отмена") }
            },
        ) { DatePicker(state = dateState) }
    }

    if (timePickerVisible) {
        val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val timeState = rememberTimePickerState(
            initialHour = zoned.hour,
            initialMinute = zoned.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { timePickerVisible = false },
            confirmButton = {
                TextButton(onClick = {
                    timestamp = replaceTime(timestamp, timeState.hour, timeState.minute)
                    timePickerVisible = false
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { timePickerVisible = false }) { Text("Отмена") }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun TimeShortcut(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SesameButton(
        text = text,
        modifier = modifier,
        container = Palette.SurfaceHigh,
        content = Tones.TextSoft,
        border = Palette.BorderStrong,
        height = 44.dp,
        corner = 13.dp,
        style = SesameText.Caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        onClick = onClick,
    )
}

/** DatePicker отдаёт полночь UTC — берём из неё только дату. */
private fun replaceDate(timestamp: Long, pickedUtcMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(pickedUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

private fun replaceTime(timestamp: Long, hour: Int, minute: Int): Long {
    val zone = ZoneId.systemDefault()
    return localDateOf(timestamp).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HistoryPreview() {
    val now = System.currentTimeMillis()
    SesameTheme {
        HistoryContent(
            state = HistoryUiState(
                items = listOf(
                    HistoryItem.Passage(
                        PassageLabel(
                            id = 1,
                            timestamp = now - 600_000,
                            barrierId = 1,
                            direction = Direction.IN,
                            mode = TravelMode.DRIVER,
                            source = LabelSource.WIDGET,
                            outcome = CallOutcome.CALLED,
                        ),
                        "Северный въезд",
                        0,
                    ),
                    HistoryItem.Session(
                        RecordingSession(
                            id = 1,
                            startedAt = now - 3_600_000,
                            endedAt = now - 2_700_000,
                            label = SessionLabel.GOING_HOME,
                            sizeBytes = 42_000_000,
                            fileDir = "Documents/Sesame/sessions/1",
                        ),
                    ),
                    HistoryItem.Passage(
                        PassageLabel(
                            id = 2,
                            timestamp = now - 90_000_000,
                            barrierId = 2,
                            direction = Direction.OUT,
                            mode = TravelMode.DRIVER,
                            source = LabelSource.APP,
                            outcome = CallOutcome.DIALER_FALLBACK,
                            confirmed = true,
                        ),
                        "Южный въезд",
                        1,
                    ),
                ),
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = null, lat = null, lon = null, name = "Северный въезд"),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null, name = "Южный въезд"),
                ),
                unconfirmedCount = 1,
                unsharedCount = 1,
            ),
            onFilter = {},
            onDirection = { _, _ -> },
            onMode = { _, _ -> },
            onDiscarded = { _, _ -> },
            onNote = { _, _ -> },
            onConfirm = {},
            onShare = {},
            onAddManual = { _, _, _, _, _ -> },
            onMessageShown = {},
        )
    }
}
