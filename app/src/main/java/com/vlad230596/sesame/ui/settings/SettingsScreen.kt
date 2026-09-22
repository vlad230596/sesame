package com.vlad230596.sesame.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.ChipRow
import com.vlad230596.sesame.ui.common.SectionCard
import com.vlad230596.sesame.ui.common.SectionTitle
import com.vlad230596.sesame.ui.common.SettingTextField
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.TabHeader
import com.vlad230596.sesame.ui.common.formatMinutes
import com.vlad230596.sesame.ui.permissions.PermissionsScreen
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.SesameTheme
import kotlin.math.roundToInt

/** Вложенные экраны настроек (§4.6). */
private enum class SettingsRoute { ROOT, COLLECTION, PERMISSIONS }

/**
 * Вкладка «Настройки» (§4.6).
 *
 * Два вложенных экрана — «Параметры сбора» и «Разрешения» — живут состоянием
 * внутри вкладки: NavHost ради двух переходов не окупается, а нижняя навигация
 * при этом остаётся на месте.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(SettingsRoute.ROOT) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    BackHandler(enabled = route != SettingsRoute.ROOT) { route = SettingsRoute.ROOT }

    when (route) {
        SettingsRoute.ROOT -> SettingsContent(
            state = state,
            onSaveBarrier = viewModel::saveBarrier,
            onCancelTimeout = viewModel::setCancelTimeout,
            onRecordingMinutes = viewModel::setRecordingMinutes,
            onPhoneAccount = viewModel::setPhoneAccount,
            onOpenCollection = { route = SettingsRoute.COLLECTION },
            onOpenPermissions = { route = SettingsRoute.PERMISSIONS },
            onMessageShown = viewModel::dismissMessage,
            modifier = modifier,
        )

        SettingsRoute.COLLECTION -> SubScreenScaffold(
            title = "Параметры сбора",
            onBack = { route = SettingsRoute.ROOT },
            modifier = modifier,
        ) { _ ->
            CollectionParamsContent(
                settings = state.settings,
                onLocationInterval = viewModel::setLocationInterval,
                onLocationPriority = viewModel::setLocationPriority,
                onLocationDisplacement = viewModel::setLocationDisplacement,
                onAccelerometerHz = viewModel::setAccelerometerHz,
                onBarometerHz = viewModel::setBarometerHz,
                onReset = viewModel::resetCollectionParams,
            )
        }

        SettingsRoute.PERMISSIONS -> SubScreenScaffold(
            title = "Разрешения",
            onBack = { route = SettingsRoute.ROOT },
            modifier = modifier,
        ) { _ ->
            PermissionsScreen()
        }
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onSaveBarrier: (Barrier) -> Unit,
    onCancelTimeout: (Int) -> Unit,
    onRecordingMinutes: (Int) -> Unit,
    onPhoneAccount: (String?) -> Unit,
    onOpenCollection: () -> Unit,
    onOpenPermissions: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

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
            TabHeader("Настройки")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.SpaceXl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                item { SectionTitle("Шлагбаумы") }

                items(state.barriers, key = { it.id }) { barrier ->
                    BarrierEditor(barrier = barrier, onSave = onSaveBarrier)
                }

                item { SectionTitle("Звонок") }

                item {
                    SectionCard {
                        SliderSetting(
                            title = "Таймаут отмены",
                            description = "Пока идёт отсчёт, звонок ещё не ушёл. " +
                                "0 — звонить сразу, без подтверждения.",
                            value = state.settings.cancelTimeoutSeconds,
                            range = 0..SesameSettings.MAX_CANCEL_TIMEOUT_SECONDS,
                            valueLabel = { if (it == 0) "без подтверждения" else "$it с" },
                            onChange = onCancelTimeout,
                        )
                    }
                }

                // §4.1: выбор SIM показывается, только если звонящих аккаунтов
                // больше одного. На одной SIM он был бы выбором из одного варианта.
                if (state.phoneAccounts.size > 1) {
                    item {
                        SectionCard(title = "SIM для звонка") {
                            Text(
                                "Шлагбаум пускает по Caller ID: звонок не с той SIM " +
                                    "его не откроет.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ChipRow(
                                options = listOf<String?>(null) + state.phoneAccounts.map { it.id },
                                selected = state.settings.phoneAccountId,
                                onSelect = onPhoneAccount,
                                label = { id ->
                                    if (id == null) {
                                        "По умолчанию"
                                    } else {
                                        state.phoneAccounts.first { it.id == id }.label
                                    }
                                },
                            )
                        }
                    }
                }

                item { SectionTitle("Интенсивная запись") }

                item {
                    SectionCard {
                        SliderSetting(
                            title = "Длительность записи",
                            description = "Жёсткий предохранитель: сессия остановится сама.",
                            value = state.settings.recordingDurationMinutes,
                            range = SesameSettings.MIN_RECORDING_MINUTES..
                                SesameSettings.MAX_RECORDING_MINUTES,
                            valueLabel = { formatMinutes(it) },
                            onChange = onRecordingMinutes,
                        )
                    }
                }

                item { SectionTitle("Сбор данных") }

                item {
                    NavigationRow(
                        title = "Параметры сбора",
                        subtitle = "Интервалы и частоты — крутятся без пересборки",
                        onClick = onOpenCollection,
                    )
                }

                item {
                    NavigationRow(
                        title = "Разрешения",
                        subtitle = "Что выдано, что отвалилось, оптимизация батареи",
                        onClick = onOpenPermissions,
                    )
                }
            }
        }
    }
}

/**
 * Форма одного шлагбаума. Сохранение явной кнопкой: поля вводятся посимвольно,
 * и писать в базу каждое нажатие клавиши здесь незачем.
 */
@Composable
private fun BarrierEditor(barrier: Barrier, onSave: (Barrier) -> Unit) {
    var label by remember(barrier.id) { mutableStateOf(barrier.label) }
    var phone by remember(barrier.id) { mutableStateOf(barrier.phoneNumber.orEmpty()) }
    var lat by remember(barrier.id) { mutableStateOf(barrier.lat?.toString().orEmpty()) }
    var lon by remember(barrier.id) { mutableStateOf(barrier.lon?.toString().orEmpty()) }
    var radius by remember(barrier.id) { mutableStateOf(barrier.radiusMeters.toInt().toString()) }

    val edited = barrier.copy(
        label = label.ifBlank { barrier.label },
        phoneNumber = phone.trim().takeIf { it.isNotBlank() },
        lat = lat.trim().toDoubleOrNull(),
        lon = lon.trim().toDoubleOrNull(),
        radiusMeters = radius.trim().toFloatOrNull() ?: Barrier.DEFAULT_RADIUS_METERS,
    )
    val dirty = edited != barrier

    SectionCard(title = barrier.label) {
        SettingTextField(value = label, onValueChange = { label = it }, label = "Подпись")
        SettingTextField(
            value = phone,
            onValueChange = { phone = it },
            label = "Номер телефона",
            keyboardType = KeyboardType.Phone,
            supporting = "Хранится только на устройстве",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            SettingTextField(
                value = lat,
                onValueChange = { lat = it },
                label = "Широта",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            SettingTextField(
                value = lon,
                onValueChange = { lon = it },
                label = "Долгота",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
        SettingTextField(
            value = radius,
            onValueChange = { radius = it },
            label = "Радиус геофенса, м",
            keyboardType = KeyboardType.Number,
            supporting = "По умолчанию 100 м (§4.4)",
        )
        Button(
            onClick = { onSave(edited) },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) {
                Text("Открыть")
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/**
 * Числовая настройка ползунком. Значение уезжает в DataStore по отпусканию
 * пальца, а не на каждый пиксель: писать настройку сотню раз за жест незачем.
 */
@Composable
internal fun SliderSetting(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    step: Int = 1,
) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = snap(position, range, step)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel(snapped), style = MaterialTheme.typography.titleMedium)
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(snap(position, range, step)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (((range.last - range.first) / step) - 1).coerceAtLeast(0),
        )
    }
}

private fun snap(value: Float, range: IntRange, step: Int): Int {
    val offset = ((value - range.first) / step).roundToInt() * step
    return (range.first + offset).coerceIn(range.first, range.last)
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun SettingsPreview() {
    SesameTheme {
        SettingsContent(
            state = SettingsUiState(
                barriers = listOf(
                    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = "+7 900 000-00-00", lat = 55.7, lon = 37.6),
                    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null),
                ),
                settings = SesameSettings(),
                phoneAccounts = listOf(
                    PhoneAccountOption("sim1", "SIM 1"),
                    PhoneAccountOption("sim2", "SIM 2"),
                ),
            ),
            onSaveBarrier = {},
            onCancelTimeout = {},
            onRecordingMinutes = {},
            onPhoneAccount = {},
            onOpenCollection = {},
            onOpenPermissions = {},
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun CollectionParamsPreview() {
    SesameTheme {
        CollectionParamsContent(
            settings = SesameSettings(locationPriority = LocationPriority.BALANCED),
            onLocationInterval = {},
            onLocationPriority = {},
            onLocationDisplacement = {},
            onAccelerometerHz = {},
            onBarometerHz = {},
            onReset = {},
        )
    }
}
