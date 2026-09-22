package com.vlad230596.sesame.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
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
import com.vlad230596.sesame.ui.common.ChipRow
import com.vlad230596.sesame.ui.common.PassageCard
import com.vlad230596.sesame.ui.common.SectionCard
import com.vlad230596.sesame.ui.common.SectionTitle
import com.vlad230596.sesame.ui.common.SettingTextField
import com.vlad230596.sesame.ui.common.TabHeader
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.formatDateTime
import com.vlad230596.sesame.ui.common.formatDayHeader
import com.vlad230596.sesame.ui.common.formatDuration
import com.vlad230596.sesame.ui.common.formatTime
import com.vlad230596.sesame.ui.common.localDateOf
import com.vlad230596.sesame.ui.common.title
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.SesameTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Вкладка «История» (§4.5).
 *
 * Общая лента меток проездов и сессий записи. Карточка метки правится в один
 * тап: направление и режим заполнены догадкой, и если исправление будет стоить
 * дороже касания, датасет останется неразмеченным.
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
        onDirection = viewModel::setDirection,
        onMode = viewModel::setMode,
        onDiscarded = viewModel::setDiscarded,
        onNote = viewModel::setNote,
        onShare = viewModel::requestShare,
        onAddManual = viewModel::addManual,
        onMessageShown = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@Composable
private fun HistoryContent(
    state: HistoryUiState,
    onDirection: (Long, Direction) -> Unit,
    onMode: (Long, TravelMode) -> Unit,
    onDiscarded: (Long, Boolean) -> Unit,
    onNote: (Long, String) -> Unit,
    onShare: () -> Unit,
    onAddManual: (Long, Long?, Direction, TravelMode, String?) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var manualVisible by remember { mutableStateOf(false) }

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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabHeader("История") {
                IconButton(onClick = onShare, enabled = !state.preparing) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться непошаренным")
                }
                IconButton(onClick = { manualVisible = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить проезд вручную")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.SpaceXl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    ) {
                        OutlinedButton(
                            onClick = onShare,
                            enabled = !state.preparing,
                            modifier = Modifier.weight(1f),
                        ) {
                            // §7: архив собирается из файлов, а не из описи, и на
                            // сотнях мегабайт это заметное время — кнопка обязана
                            // сказать, что она занята.
                            Text(
                                if (state.preparing) {
                                    "Собираю архив…"
                                } else {
                                    "Поделиться (${state.unsharedCount})"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = { manualVisible = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Добавить проезд") }
                    }
                }

                if (state.items.isEmpty()) {
                    item {
                        SectionCard {
                            Text("Пока ничего нет. Метки появятся после нажатия кнопки " +
                                "шлагбаума или запуска интенсивной записи.")
                        }
                    }
                }

                var lastDay: String? = null
                state.items.forEach { entry ->
                    val day = formatDayHeader(entry.timestamp)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "header-$day-${entry.key}") { SectionTitle(day) }
                    }
                    item(key = entry.key) {
                        when (entry) {
                            is HistoryItem.Passage -> PassageCard(
                                label = entry.label,
                                barrierLabel = entry.barrierLabel,
                                onDirection = { onDirection(entry.label.id, it) },
                                onMode = { onMode(entry.label.id, it) },
                                onToggleDiscarded = { onDiscarded(entry.label.id, it) },
                                onNote = { onNote(entry.label.id, it) },
                            )

                            is HistoryItem.Session -> SessionCard(entry.session)
                        }
                    }
                }
            }
        }
    }

    if (manualVisible) {
        ManualPassageDialog(
            barriers = state.barriers,
            onDismiss = { manualVisible = false },
            onAdd = { timestamp, barrierId, direction, mode, note ->
                manualVisible = false
                onAddManual(timestamp, barrierId, direction, mode, note)
            },
        )
    }
}

@Composable
private fun SessionCard(session: RecordingSession) {
    SectionCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            Text(formatTime(session.startedAt), style = MaterialTheme.typography.titleMedium)
            Text(
                "Запись · ${session.label.title()}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
        }
        val ended = session.endedAt
        Text(
            text = buildString {
                if (ended == null) {
                    append("идёт сейчас")
                } else {
                    append(formatDuration(ended - session.startedAt))
                }
                append(" · ").append(formatBytes(session.sizeBytes))
                append(" · ").append(if (session.shared) "выгружено" else "не выгружено")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        session.fileDir?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ретро-метка (§4.5). Время по умолчанию — «сейчас», потому что типичный случай
 * это «только что проехали, открыл не я».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualPassageDialog(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проезд вручную") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                Text(
                    "Например, шлагбаум открыл кто-то другой из машины.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatDateTime(timestamp), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    TextButton(onClick = { timestamp = System.currentTimeMillis() }) {
                        Text("Сейчас")
                    }
                    TextButton(onClick = { timestamp -= 5 * 60_000 }) { Text("−5 мин") }
                    TextButton(onClick = { timestamp -= 60 * 60_000 }) { Text("−1 ч") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    OutlinedButton(onClick = { datePickerVisible = true }) { Text("Дата") }
                    OutlinedButton(onClick = { timePickerVisible = true }) { Text("Время") }
                }

                if (barriers.isNotEmpty()) {
                    Text("Шлагбаум", style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        options = barriers.map { it.id },
                        selected = barrierId ?: -1L,
                        onSelect = { barrierId = it },
                        label = { id -> barriers.first { it.id == id }.label },
                    )
                }

                Text("Направление", style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = listOf(Direction.IN, Direction.OUT, Direction.UNKNOWN),
                    selected = direction,
                    onSelect = { direction = it },
                    label = { it.title() },
                )

                Text("Режим", style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = listOf(
                        TravelMode.DRIVER,
                        TravelMode.PASSENGER,
                        TravelMode.FOOT,
                        TravelMode.UNKNOWN,
                    ),
                    selected = mode,
                    onSelect = { mode = it },
                    label = { it.title() },
                )

                SettingTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "Заметка",
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(timestamp, barrierId, direction, mode, note) }) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )

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

@Preview(showBackground = true, heightDp = 1000)
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
                            source = LabelSource.WIDGET,
                            outcome = CallOutcome.CALLED,
                        ),
                        "Шлагбаум A",
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
                        "Шлагбаум B",
                    ),
                ),
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = null, lat = null, lon = null),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null),
                ),
                unsharedCount = 1,
            ),
            onDirection = { _, _ -> },
            onMode = { _, _ -> },
            onDiscarded = { _, _ -> },
            onNote = { _, _ -> },
            onShare = {},
            onAddManual = { _, _, _, _, _ -> },
            onMessageShown = {},
        )
    }
}
