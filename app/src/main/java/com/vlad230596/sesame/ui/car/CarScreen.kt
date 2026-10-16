package com.vlad230596.sesame.ui.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad230596.sesame.parking.ParkedCar
import com.vlad230596.sesame.parking.ParkingAnchor
import com.vlad230596.sesame.parking.ParkingSource
import com.vlad230596.sesame.parking.SpotResult
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameCard
import com.vlad230596.sesame.ui.common.SesameField
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SubScreenScaffold
import com.vlad230596.sesame.ui.common.formatAgo
import com.vlad230596.sesame.ui.common.formatDateTime
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme
import org.osmdroid.util.GeoPoint

/**
 * Экран «Где машина» (NEXT-notification-and-car.md, §4.1).
 *
 * Сверху — ответ словами: его читают, а на карту смотрят, только если ответ
 * вызвал сомнение. Ниже — карта с пином, кругом погрешности и хвостом трека, а
 * под ней две правки: «Машина не здесь» и метки мест.
 */
@Composable
fun CarScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: CarViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mode by rememberSaveable { mutableStateOf(CarMapMode.VIEW) }
    var pending by remember { mutableStateOf<GeoPoint?>(null) }
    var centerRequest by remember { mutableIntStateOf(0) }
    var newAnchorAt by remember { mutableStateOf<GeoPoint?>(null) }
    var editedAnchor by remember { mutableStateOf<ParkingAnchor?>(null) }

    // Разрешение могли выдать или отозвать, пока экран был в фоне.
    var locationPermitted by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        locationPermitted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        onPauseOrDispose { }
    }

    fun leaveMode() {
        mode = CarMapMode.VIEW
        pending = null
    }

    BackHandler {
        if (mode != CarMapMode.VIEW) leaveMode() else onBack()
    }

    SubScreenScaffold(
        title = "Где машина",
        onBack = { if (mode != CarMapMode.VIEW) leaveMode() else onBack() },
        modifier = modifier,
    ) { contentModifier ->
        Column(Modifier.fillMaxSize()) {
            SpotCard(
                car = state.car,
                spot = state.spot,
                now = state.now,
                loaded = state.loaded,
                modifier = contentModifier,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = Dimens.StackGap),
            ) {
                CarMap(
                    state = state,
                    mode = mode,
                    pending = pending,
                    locationPermitted = locationPermitted,
                    centerRequest = centerRequest,
                    onMapTap = { point -> if (mode == CarMapMode.MOVE_CAR) pending = point },
                    onMapLongPress = { point ->
                        when (mode) {
                            CarMapMode.MOVE_CAR -> pending = point
                            CarMapMode.EDIT_ANCHORS -> newAnchorAt = point
                            CarMapMode.VIEW -> Unit
                        }
                    },
                    onAnchorTap = { editedAnchor = it },
                    modifier = Modifier.fillMaxSize(),
                )

                // Лицензия OSM требует подписи на карте.
                Text(
                    text = "© OpenStreetMap",
                    style = SesameText.Caption.copy(fontSize = 10.sp),
                    color = Palette.TextMuted,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Background.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )

                if (state.car != null) {
                    SesameSurface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        color = Palette.Surface.copy(alpha = 0.92f),
                        borderColor = Palette.BorderStrong,
                        corner = 14.dp,
                        onClick = { centerRequest++ },
                    ) {
                        Text(
                            "К машине",
                            style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                            color = Palette.TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            ModePanel(
                mode = mode,
                hasCar = state.car != null,
                hasPending = pending != null,
                usesDefaultAnchors = state.usesDefaultAnchors,
                onMoveCar = {
                    pending = null
                    mode = CarMapMode.MOVE_CAR
                },
                onEditAnchors = { mode = CarMapMode.EDIT_ANCHORS },
                onCancel = ::leaveMode,
                onConfirmMove = {
                    pending?.let { viewModel.correctPosition(it.latitude, it.longitude) }
                    leaveMode()
                },
                modifier = contentModifier.padding(vertical = Dimens.StackGap),
            )
        }
    }

    newAnchorAt?.let { point ->
        AnchorDialog(
            title = "Новая метка",
            initialName = "",
            onSave = { name ->
                viewModel.addAnchor(name, point.latitude, point.longitude)
                newAnchorAt = null
            },
            onDelete = null,
            onDismiss = { newAnchorAt = null },
        )
    }

    editedAnchor?.let { anchor ->
        AnchorDialog(
            title = "Метка «${anchor.name}»",
            initialName = anchor.name,
            onSave = { name ->
                viewModel.renameAnchor(anchor.id, name)
                editedAnchor = null
            },
            onDelete = {
                viewModel.deleteAnchor(anchor.id)
                editedAnchor = null
            },
            onDismiss = { editedAnchor = null },
        )
    }
}

/** Ответ словами: где, когда, насколько точно и откуда известно. */
@Composable
private fun SpotCard(
    car: ParkedCar?,
    spot: SpotResult?,
    now: Long,
    loaded: Boolean,
    modifier: Modifier = Modifier,
) {
    SesameCard(modifier = modifier) {
        if (car == null) {
            Text(
                text = if (loaded) "Парковок ещё не было" else "Загружаю…",
                style = SesameText.RowTitle,
                color = Palette.TextPrimary,
            )
            if (loaded) {
                Text(
                    "Точка появится, когда машина отключится от телефона по Bluetooth. " +
                        "Машину выбирают в Настройках → «Подсказка у шлагбаума».",
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                )
            }
            return@SesameCard
        }
        Text(
            text = spot?.let(::spotPhrase)?.replaceFirstChar { it.uppercase() } ?: "Место не определено",
            style = SesameText.RowTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
            color = Palette.TextPrimary,
        )
        val details = buildList {
            if (car.at > 0) add(formatAgo(car.at, now))
            if (car.accuracyMeters > 0f) add("±${Math.round(car.accuracyMeters)} м")
            add(sourceTitle(car.source))
        }
        Text(details.joinToString(" · "), style = SesameText.Caption, color = Palette.TextMuted)
        if (car.at > 0) {
            Text("Отключилась ${formatDateTime(car.at)}", style = SesameText.Caption, color = Palette.TextDim)
        }
        if (spot == SpotResult.None) {
            Text(
                "Не от чего отвечать: задайте координаты шлагбаумов или поставьте метки мест.",
                style = SesameText.Caption,
                color = Palette.AmberTextSoft,
            )
        }
    }
}

@Composable
private fun ModePanel(
    mode: CarMapMode,
    hasCar: Boolean,
    hasPending: Boolean,
    usesDefaultAnchors: Boolean,
    onMoveCar: () -> Unit,
    onEditAnchors: () -> Unit,
    onCancel: () -> Unit,
    onConfirmMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (mode) {
            CarMapMode.VIEW -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SesameButton(
                    text = "Машина не здесь",
                    onClick = onMoveCar,
                    enabled = hasCar,
                    container = Palette.Surface,
                    content = Palette.TextPrimary,
                    border = Palette.BorderStrong,
                    height = 48.dp,
                    corner = 14.dp,
                    modifier = Modifier.weight(1f),
                )
                SesameButton(
                    text = "Метки мест",
                    onClick = onEditAnchors,
                    container = Palette.Surface,
                    content = Palette.TextPrimary,
                    border = Palette.BorderStrong,
                    height = 48.dp,
                    corner = 14.dp,
                    modifier = Modifier.weight(1f),
                )
            }

            CarMapMode.MOVE_CAR -> {
                Text(
                    if (hasPending) "Новая точка поставлена. Сохранить?" else "Тапните по карте там, где стоит машина",
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SesameButton(
                        text = "Отмена",
                        onClick = onCancel,
                        container = Palette.Surface,
                        content = Palette.TextPrimary,
                        border = Palette.BorderStrong,
                        height = 48.dp,
                        corner = 14.dp,
                        modifier = Modifier.weight(1f),
                    )
                    SesameButton(
                        text = "Машина здесь",
                        onClick = onConfirmMove,
                        enabled = hasPending,
                        height = 48.dp,
                        corner = 14.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            CarMapMode.EDIT_ANCHORS -> {
                Text(
                    "Долгое нажатие — новая метка, тап по метке — переименовать или удалить." +
                        if (usesDefaultAnchors) " Пока своих меток нет, метки — это шлагбаумы." else "",
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                )
                SesameButton(
                    text = "Готово",
                    onClick = onCancel,
                    height = 48.dp,
                    corner = 14.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Имя метки; у существующей — ещё и «Удалить». */
@Composable
private fun AnchorDialog(
    title: String,
    initialName: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SesameField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Ш1, центр, гипермаркет",
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = Palette.Record) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun SpotCardPreview() {
    SesameTheme {
        Column(
            Modifier.background(Palette.Background).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            val sh1 = ParkingAnchor("1", "Ш1", 55.75, 37.62)
            val center = ParkingAnchor("2", "центр", 55.7503, 37.62)
            val now = System.currentTimeMillis()
            SpotCard(
                car = ParkedCar(now - 3 * 3_600_000L, 55.7502, 37.62, 8f, ParkingSource.TAIL, bearingDeg = 40f),
                spot = SpotResult.Between(sh1, center),
                now = now,
                loaded = true,
            )
            SpotCard(car = null, spot = null, now = now, loaded = true)
            ModePanel(CarMapMode.VIEW, true, false, true, {}, {}, {}, {})
        }
    }
}
