package com.vlad230596.sesame.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.CardHeadRow
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.GroupCaption
import com.vlad230596.sesame.ui.common.GroupDivider
import com.vlad230596.sesame.ui.common.GroupRow
import com.vlad230596.sesame.ui.common.NavGroupRow
import com.vlad230596.sesame.ui.common.SegmentedRow
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SettingsGroup
import com.vlad230596.sesame.ui.common.StatusDot
import com.vlad230596.sesame.ui.common.StepperRow
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.TabHeader
import com.vlad230596.sesame.ui.common.ThinProgress
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.common.formatBytes
import com.vlad230596.sesame.ui.common.maskPhone
import com.vlad230596.sesame.ui.permissions.PermissionsScreen
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameAccentColors
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme

/** Вложенные экраны настроек (§4.6). */
private sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object Collection : SettingsRoute
    data object Permissions : SettingsRoute
    data object Home : SettingsRoute
    data class BarrierEdit(val barrierId: Long) : SettingsRoute
}

/**
 * Вкладка «Настройки» (§4.6), по утверждённому макету.
 *
 * Корневой экран — только список: что настроено и куда зайти. Формы вынесены на
 * вложенные экраны, потому что форма шлагбаума это семь полей, и в общем списке
 * она превращала экран в простыню, по которой нельзя пробежать глазами.
 *
 * Вложенные экраны живут состоянием внутри вкладки: NavHost ради четырёх
 * переходов не окупается, а нижняя навигация при этом остаётся на месте.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Маршрут переживает поворот экрана и смерть процесса: править номер
    // шлагбаума с открытой клавиатурой и вылететь из-за этого в корень настроек —
    // ровно тот случай, ради которого rememberSaveable и существует.
    //
    // Кодируется одним Long: id шлагбаума положителен всегда (Room раздаёт их
    // с единицы), поэтому ноль и отрицательные значения свободны под остальные
    // экраны.
    var route by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = {
                when (it) {
                    SettingsRoute.Root -> 0L
                    SettingsRoute.Collection -> -1L
                    SettingsRoute.Permissions -> -2L
                    SettingsRoute.Home -> -3L
                    is SettingsRoute.BarrierEdit -> it.barrierId
                }
            },
            restore = {
                when (it) {
                    0L -> SettingsRoute.Root
                    -1L -> SettingsRoute.Collection
                    -2L -> SettingsRoute.Permissions
                    -3L -> SettingsRoute.Home
                    else -> SettingsRoute.BarrierEdit(it)
                }
            },
        ),
    ) { mutableStateOf<SettingsRoute>(SettingsRoute.Root) }

    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

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

    BackHandler(enabled = route != SettingsRoute.Root) { route = SettingsRoute.Root }

    // Снекбар висит над всей вкладкой, а не над корневым экраном. Сообщения
    // («шлагбаум сохранён», «нет разрешения на локацию», исход проверочного
    // звонка) рождаются как раз на вложенных экранах, и показать их надо там,
    // где человек находится.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Box(modifier.fillMaxSize()) {
    when (val current = route) {
        SettingsRoute.Root -> SettingsContent(
            state = state,
            onCancelTimeout = viewModel::setCancelTimeout,
            onRecordingMinutes = viewModel::setRecordingMinutes,
            onPhoneAccount = viewModel::setPhoneAccount,
            onExport = viewModel::requestShare,
            onOpenBarrier = { route = SettingsRoute.BarrierEdit(it.id) },
            onOpenHome = { route = SettingsRoute.Home },
            onOpenCollection = { route = SettingsRoute.Collection },
            onOpenPermissions = { route = SettingsRoute.Permissions },
        )

        SettingsRoute.Collection -> {
            val sensors = rememberSensorAvailability()
            SubScreenScaffold(
                title = "Параметры сбора",
                onBack = { route = SettingsRoute.Root },
            ) { contentModifier ->
                CollectionParamsContent(
                    modifier = contentModifier,
                    settings = state.settings,
                    hasBarometer = sensors.barometer,
                    hasStepCounter = sensors.stepCounter,
                    onLocationInterval = viewModel::setLocationInterval,
                    onLocationPriority = viewModel::setLocationPriority,
                    onLocationDisplacement = viewModel::setLocationDisplacement,
                    onAccelerometerHz = viewModel::setAccelerometerHz,
                    onBarometerHz = viewModel::setBarometerHz,
                    onReset = viewModel::resetCollectionParams,
                )
            }
        }

        SettingsRoute.Permissions -> SubScreenScaffold(
            title = "Разрешения",
            onBack = { route = SettingsRoute.Root },
        ) { contentModifier ->
            PermissionsScreen(contentModifier)
        }

        SettingsRoute.Home -> SubScreenScaffold(
            title = "Дом",
            onBack = { route = SettingsRoute.Root },
        ) { contentModifier ->
            HomePointContent(
                modifier = contentModifier,
                settings = state.settings,
                onPickCurrent = viewModel::currentLocation,
                onSave = viewModel::saveHome,
            )
        }

        is SettingsRoute.BarrierEdit -> {
            val barrier = state.barriers.firstOrNull { it.id == current.barrierId }
            val index = state.barriers.indexOfFirst { it.id == current.barrierId }.coerceAtLeast(0)
            if (barrier == null) {
                // Запись исчезла, пока экран был открыт, — возвращаемся в список,
                // а не показываем пустую форму.
                LaunchedEffect(current.barrierId) { route = SettingsRoute.Root }
            } else {
                SubScreenScaffold(
                    title = barrier.displayName,
                    onBack = { route = SettingsRoute.Root },
                ) { contentModifier ->
                    BarrierContent(
                        modifier = contentModifier,
                        barrier = barrier,
                        accent = SesameAccentColors.current.barrier(index),
                        position = index,
                        onPickCurrent = viewModel::currentLocation,
                        onSave = viewModel::saveBarrier,
                        onTestCall = viewModel::testCall,
                    )
                }
            }
        }
    }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onCancelTimeout: (Int) -> Unit,
    onRecordingMinutes: (Int) -> Unit,
    onPhoneAccount: (String?) -> Unit,
    onExport: () -> Unit,
    onOpenBarrier: (Barrier) -> Unit,
    onOpenHome: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {
            TabHeader("Настройки")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                if (state.missingPermissions > 0) {
                    PermissionAlert(
                        hint = state.missingHint,
                        count = state.missingPermissions,
                        onClick = onOpenPermissions,
                    )
                }

                GroupCaption("Шлагбаумы", top = Dimens.SpaceXs)
                SettingsGroup {
                    if (state.barriers.isEmpty()) {
                        GroupRow {
                            Text(
                                "Шлагбаумов нет — они создаются при первом запуске",
                                style = SesameText.Caption,
                                color = Palette.TextMuted,
                            )
                        }
                    }
                    state.barriers.forEachIndexed { index, barrier ->
                        if (index > 0) GroupDivider()
                        NavGroupRow(
                            title = barrier.displayName,
                            value = maskPhone(barrier.phoneNumber) ?: "номер не задан",
                            valueMono = barrier.phoneNumber != null,
                            leading = {
                                StatusDot(
                                    color = SesameAccentColors.current.barrier(index).container,
                                    size = 10.dp,
                                )
                            },
                            onClick = { onOpenBarrier(barrier) },
                        )
                    }
                }

                GroupCaption("Звонок и запись")
                SettingsGroup {
                    // §4.1: 0 — звонить сразу, без подтверждения. Это не «выключено»,
                    // а отдельный осмысленный режим, поэтому он стоит шагом шкалы,
                    // а не тумблером рядом.
                    StepperRow(
                        title = "Отсчёт перед звонком",
                        value = if (state.settings.cancelTimeoutSeconds == 0) {
                            "сразу"
                        } else {
                            "${state.settings.cancelTimeoutSeconds} с"
                        },
                        minusEnabled = state.settings.cancelTimeoutSeconds > 0,
                        plusEnabled = state.settings.cancelTimeoutSeconds <
                            SesameSettings.MAX_CANCEL_TIMEOUT_SECONDS,
                        onMinus = { onCancelTimeout(state.settings.cancelTimeoutSeconds - 1) },
                        onPlus = { onCancelTimeout(state.settings.cancelTimeoutSeconds + 1) },
                        valueWidth = 62.dp,
                    )
                    GroupDivider()
                    StepperRow(
                        title = "Длительность записи",
                        value = "${state.settings.recordingDurationMinutes} мин",
                        minusEnabled = state.settings.recordingDurationMinutes >
                            SesameSettings.MIN_RECORDING_MINUTES,
                        plusEnabled = state.settings.recordingDurationMinutes <
                            SesameSettings.MAX_RECORDING_MINUTES,
                        onMinus = {
                            onRecordingMinutes(
                                RecordingMinuteSteps.previous(state.settings.recordingDurationMinutes),
                            )
                        },
                        onPlus = {
                            onRecordingMinutes(
                                RecordingMinuteSteps.next(state.settings.recordingDurationMinutes),
                            )
                        },
                        valueWidth = 62.dp,
                    )
                    GroupDivider()
                    NavGroupRow(
                        title = "Параметры сбора",
                        value = "лок ${state.settings.locationIntervalSeconds} с · " +
                            "акс ${state.settings.accelerometerHz} Гц",
                        onClick = onOpenCollection,
                    )
                }

                // §4.1: выбор SIM показывается, только если звонящих аккаунтов
                // больше одного. На одной SIM он был бы выбором из одного варианта.
                if (state.phoneAccounts.size > 1) {
                    GroupCaption("SIM для звонка")
                    SettingsGroup {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Шлагбаум пускает по Caller ID: звонок не с той SIM " +
                                    "его не откроет.",
                                style = SesameText.Caption,
                                color = Palette.TextMuted,
                            )
                            SegmentedRow(
                                options = listOf<String?>(null) + state.phoneAccounts.map { it.id },
                                selected = state.settings.phoneAccountId,
                                onSelect = onPhoneAccount,
                                label = { id ->
                                    if (id == null) {
                                        "Авто"
                                    } else {
                                        state.phoneAccounts.first { it.id == id }.label
                                    }
                                },
                                height = 44.dp,
                                corner = 13.dp,
                            )
                        }
                    }
                }

                GroupCaption("Геофенсы")
                SettingsGroup {
                    NavGroupRow(
                        title = "Дом",
                        value = if (state.settings.homeLat == null || state.settings.homeLon == null) {
                            "не задан"
                        } else {
                            "${state.settings.homeRadiusMeters} м"
                        },
                        valueMono = state.settings.homeLat != null,
                        onClick = onOpenHome,
                    )
                }

                GroupCaption("Данные")
                SettingsGroup {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    ) {
                        CardHeadRow(
                            title = "Хранилище",
                            value = "${formatBytes(state.usedBytes)} / " +
                                formatBytes(SesameSettings.STORAGE_LIMIT_BYTES),
                        )
                        ThinProgress(
                            progress = state.usedBytes.toFloat() /
                                SesameSettings.STORAGE_LIMIT_BYTES,
                            color = if (state.usedBytes >
                                SesameSettings.STORAGE_LIMIT_BYTES * 0.9
                            ) {
                                Palette.Record
                            } else {
                                Palette.Teal
                            },
                        )
                    }
                    GroupDivider()
                    GroupRow(onClick = if (state.preparingArchive) null else onExport) {
                        Text(
                            text = if (state.preparingArchive) "Собираю архив…" else "Экспорт в архив",
                            style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                            color = Palette.TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.unsharedCount > 0) {
                            Text(
                                text = "${state.unsharedCount}",
                                style = SesameText.Mono13,
                                color = Palette.Amber,
                            )
                        }
                        Icon(
                            imageVector = SesameIcons.Export,
                            contentDescription = null,
                            tint = Palette.TextDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                GroupCaption("Система")
                SettingsGroup {
                    NavGroupRow(
                        title = "Разрешения",
                        value = if (state.missingPermissions == 0) {
                            "всё выдано"
                        } else {
                            "не выдано: ${state.missingPermissions}"
                        },
                        onClick = onOpenPermissions,
                    )
                }

                FootNote(
                    "Выбор SIM появится здесь, если звонящих аккаунтов станет больше " +
                        "одного. Номера хранятся только на устройстве.",
                )
                Spacer(Modifier.height(Dimens.SpaceS))
            }
        }
    }
}

/**
 * Красная плашка «не хватает разрешений».
 *
 * Стоит первой строкой экрана и называет разрешение по имени: «что-то не так»
 * без имени заставляет заходить внутрь и сравнивать список глазами.
 */
@Composable
private fun PermissionAlert(hint: String?, count: Int, onClick: () -> Unit) {
    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Tones.AlertSurface,
        borderColor = Palette.RecordOutline,
        corner = 16.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            Icon(
                imageVector = SesameIcons.Alert,
                contentDescription = null,
                tint = Palette.Record,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Не хватает разрешений: $count",
                    style = SesameText.CardTitle,
                    color = Palette.TextPrimary,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = SesameText.Caption,
                        color = Palette.RecordMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "Исправить",
                style = SesameText.Caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = Palette.RecordChipText,
            )
        }
    }
}

/**
 * Шаги длительности записи.
 *
 * Не равномерная шкала: между 1 и 15 минутами разница смысловая («проверить, что
 * пишется» против «доехать до работы»), а между 90 и 120 — почти никакой.
 */
internal object RecordingMinuteSteps {
    private val values = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)

    fun next(current: Int): Int = values.firstOrNull { it > current } ?: values.last()

    fun previous(current: Int): Int = values.lastOrNull { it < current } ?: values.first()
}

private val previewBarriers = listOf(
    Barrier(id = 1, label = "Шлагбаум A", phoneNumber = "+7 900 000-00-14", lat = 55.75124, lon = 37.61841, name = "Северный въезд"),
    Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = null, lon = null, name = "Южный въезд"),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() {
    SesameTheme {
        SettingsContent(
            state = SettingsUiState(
                barriers = previewBarriers,
                settings = SesameSettings(),
                missingPermissions = 1,
                missingHint = "Локация в фоне",
                usedBytes = 2_400_000_000,
                unsharedCount = 2,
            ),
            onCancelTimeout = {},
            onRecordingMinutes = {},
            onPhoneAccount = {},
            onExport = {},
            onOpenBarrier = {},
            onOpenHome = {},
            onOpenCollection = {},
            onOpenPermissions = {},
        )
    }
}
