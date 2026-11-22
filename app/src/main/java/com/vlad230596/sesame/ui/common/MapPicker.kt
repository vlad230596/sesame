package com.vlad230596.sesame.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vlad230596.sesame.ui.car.OsmSetup
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** Метки для UI-тестов. */
internal object MapPickerTags {
    const val MAP = "mapPicker.map"
    const val COORDINATE = "mapPicker.coordinate"
}

/** Куда смотреть, когда точки ещё нет: центр Москвы, масштаб города. */
private val FALLBACK_CENTER = GeoPoint(55.7512, 37.6184)

/**
 * Выбор точки тапом по карте — для шлагбаумов, дома и мест привычек.
 *
 * Вводить координаты цифрами неудобно, а «Взять текущую» требует стоять в
 * нужном месте. Здесь точку ставят пальцем: тап — пин, пин можно переставлять
 * сколько угодно, «Выбрать» отдаёт координату.
 *
 * @param initial текущая точка поля, если задана: с неё карта и начинается.
 */
@Composable
fun MapPickerDialog(
    title: String,
    initial: Pair<Double, Double>?,
    onPick: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(initial?.let { GeoPoint(it.first, it.second) }) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.Background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = SesameText.CardTitle, color = Palette.TextPrimary)
                Text(
                    text = picked?.let { formatCoordinate(it.latitude, it.longitude) }
                        ?: "Тапните по карте, чтобы поставить точку",
                    style = if (picked != null) SesameText.Mono14 else SesameText.Caption,
                    color = if (picked != null) Palette.TextPrimary else Palette.TextMuted,
                    modifier = Modifier.testTag(MapPickerTags.COORDINATE),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .testTag(MapPickerTags.MAP),
            ) {
                PickerMap(
                    center = picked ?: FALLBACK_CENTER,
                    zoom = if (picked != null) 17.0 else 11.0,
                    picked = picked,
                    onTap = { picked = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = "© OpenStreetMap",
                    style = SesameText.Caption.copy(fontSize = 10.sp),
                    color = Palette.TextMuted,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Palette.Background.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SesameButton(
                    text = "Отмена",
                    onClick = onDismiss,
                    container = Palette.Surface,
                    content = Palette.TextPrimary,
                    border = Palette.BorderStrong,
                    height = 48.dp,
                    corner = 14.dp,
                    modifier = Modifier.weight(1f),
                )
                SesameButton(
                    text = "Выбрать",
                    onClick = { picked?.let { onPick(it.latitude, it.longitude) } },
                    enabled = picked != null,
                    height = 48.dp,
                    corner = 14.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Карта с одним пином; центр задаётся только при создании — дальше её двигает палец. */
@Composable
private fun PickerMap(
    center: GeoPoint,
    zoom: Double,
    picked: GeoPoint?,
    onTap: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val mapView = remember {
        OsmSetup.ensure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            controller.setZoom(zoom)
            controller.setCenter(center)
        }
    }
    val events = remember {
        MapEventsOverlay(
            object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    tap(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean {
                    tap(p)
                    return true
                }
            },
        )
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()
            map.overlays.add(events)
            if (picked != null) {
                map.overlays.add(
                    Marker(map).apply {
                        position = picked
                        icon = pinIcon(map)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> false }
                    },
                )
            }
            map.invalidate()
        },
    )
}

private fun pinIcon(map: MapView): BitmapDrawable {
    val res = map.context.resources
    val density = res.displayMetrics.density
    val size = (28 * density).toInt()
    val c = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawCircle(c, c, 12 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.Amber.toArgb() })
    canvas.drawCircle(
        c,
        c,
        12 * density,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.Background.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        },
    )
    canvas.drawCircle(c, c, 3.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.OnAmber.toArgb() })
    return BitmapDrawable(res, bitmap)
}
