package com.vlad230596.sesame.ui.car

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vlad230596.sesame.parking.ParkingAnchor
import com.vlad230596.sesame.parking.SpotClassifier
import com.vlad230596.sesame.ui.theme.Palette
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** Что делает тап по карте. */
enum class CarMapMode {
    /** Просто смотреть. */
    VIEW,

    /** «Машина не здесь»: тап ставит новую точку. */
    MOVE_CAR,

    /** «Метки мест»: долгое нажатие — новая метка, тап по метке — правка. */
    EDIT_ANCHORS,
}

/**
 * Настройка osmdroid один раз на процесс (§4.1).
 *
 * User-Agent — имя пакета: этого требует политика тайлового сервера OSM, общий
 * «osmdroid» он режет. Кэш — в `cacheDir` приложения, а не в общем
 * `osmdroid/` на внешней памяти, как по умолчанию: разрешений на неё у
 * приложения нет и не нужно, а система сама подчистит кэш при нехватке места.
 */
internal object OsmSetup {
    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            val config = Configuration.getInstance()
            config.userAgentValue = context.packageName
            val base = File(context.applicationContext.cacheDir, "osmdroid")
            config.osmdroidBasePath = base
            config.osmdroidTileCache = File(base, "tiles")
            // Двор и пара соседних районов — это сотни тайлов, а не тысячи.
            config.tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
            config.tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
            done = true
        }
    }
}

/** Куда смотреть карте при открытии: машина, иначе шлагбаумы, иначе дом. */
private fun initialTarget(state: CarUiState): Pair<GeoPoint, Double>? {
    state.car?.let { return GeoPoint(it.lat, it.lon) to 18.0 }
    val barriers = state.barriers.filter { it.lat != null && it.lon != null }
    if (barriers.isNotEmpty()) {
        return GeoPoint(barriers.map { it.lat!! }.average(), barriers.map { it.lon!! }.average()) to 17.0
    }
    state.home?.let { return GeoPoint(it.first, it.second) to 17.0 }
    return null
}

/**
 * Карта «Где машина»: OSM-тайлы через osmdroid внутри Compose.
 *
 * Слои пересобираются целиком при каждой смене данных: их десяток, и это
 * проще и надёжнее, чем сверять, какой маркер сдвинулся. Постоянны только
 * перехватчик тапов и «моё место» — у последнего своя подписка на локацию.
 *
 * Карта всегда смотрит на север: жест поворота не включён, поэтому стрелку
 * курса можно рисовать уже повёрнутой картинкой.
 *
 * @param centerRequest каждое новое значение — вернуть карту к машине.
 */
@Composable
fun CarMap(
    state: CarUiState,
    mode: CarMapMode,
    pending: GeoPoint?,
    locationPermitted: Boolean,
    centerRequest: Int,
    onMapTap: (GeoPoint) -> Unit,
    onMapLongPress: (GeoPoint) -> Unit,
    onAnchorTap: (ParkingAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        OsmSetup.ensure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            controller.setZoom(17.0)
        }
    }

    val currentMode by rememberUpdatedState(mode)
    val tap by rememberUpdatedState(onMapTap)
    val longPress by rememberUpdatedState(onMapLongPress)
    val anchorTap by rememberUpdatedState(onAnchorTap)

    val eventsOverlay = remember {
        MapEventsOverlay(
            object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    tap(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean {
                    longPress(p)
                    return true
                }
            },
        )
    }

    // Текущая позиция — только при выданном разрешении: без него провайдер
    // упал бы на подписке. Само разрешение запрашивает экран разрешений.
    val myLocation = remember(locationPermitted) {
        if (locationPermitted) MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) else null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, myLocation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    runCatching { myLocation?.enableMyLocation() }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching { myLocation?.disableMyLocation() }
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { myLocation?.disableMyLocation() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // Центр — один раз, когда данные пришли, и по кнопке «К машине». Ручная
    // правка момент парковки не меняет, поэтому карта после неё не прыгает.
    LaunchedEffect(state.loaded, state.car?.at, centerRequest) {
        if (!state.loaded) return@LaunchedEffect
        val (point, zoom) = initialTarget(state) ?: return@LaunchedEffect
        mapView.controller.setZoom(zoom)
        mapView.controller.setCenter(point)
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            val res = map.context.resources
            val density = res.displayMetrics.density
            map.overlays.clear()
            map.overlays.add(eventsOverlay)

            val car = state.car
            if (car != null && car.track.size >= 2) {
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(car.track.map { GeoPoint(it.lat, it.lon) })
                        outlinePaint.color = Palette.Amber.copy(alpha = 0.75f).toArgb()
                        outlinePaint.strokeWidth = 4 * density
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        infoWindow = null
                        setOnClickListener { _, _, _ -> false }
                    },
                )
            }
            if (car != null && car.accuracyMeters > 0f) {
                map.overlays.add(
                    Polygon(map).apply {
                        points = Polygon.pointsAsCircle(GeoPoint(car.lat, car.lon), car.accuracyMeters.toDouble())
                        fillPaint.color = Palette.Amber.copy(alpha = 0.18f).toArgb()
                        outlinePaint.color = Palette.Amber.copy(alpha = 0.7f).toArgb()
                        outlinePaint.strokeWidth = 1.5f * density
                        infoWindow = null
                        setOnClickListener { _, _, _ -> false }
                    },
                )
            }

            // Пока своих меток нет, метки — это шлагбаумы: рисуем их один раз,
            // шлагбаумом. В режиме правки они же становятся метками.
            val anchorsAsBarriers = state.usesDefaultAnchors && mode != CarMapMode.EDIT_ANCHORS
            if (anchorsAsBarriers || !state.usesDefaultAnchors) {
                val names = barrierNames(state)
                state.barriers.forEach { barrier ->
                    val lat = barrier.lat ?: return@forEach
                    val lon = barrier.lon ?: return@forEach
                    map.overlays.add(
                        labelMarker(map, res, GeoPoint(lat, lon), names[barrier.id] ?: barrier.displayName, Palette.Teal) { false },
                    )
                }
            }
            if (!anchorsAsBarriers) {
                state.effectiveAnchors.forEach { anchor ->
                    map.overlays.add(
                        labelMarker(map, res, GeoPoint(anchor.lat, anchor.lon), anchor.name, Palette.Amber) {
                            if (currentMode == CarMapMode.EDIT_ANCHORS) {
                                anchorTap(anchor)
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
            }

            myLocation?.let { map.overlays.add(it) }

            if (car != null) {
                map.overlays.add(
                    Marker(map).apply {
                        position = GeoPoint(car.lat, car.lon)
                        icon = carIcon(res, car.bearingDeg, Palette.Amber, alpha = if (pending != null) 0.45f else 1f)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> false }
                    },
                )
            }
            if (pending != null) {
                map.overlays.add(
                    Marker(map).apply {
                        position = pending
                        icon = carIcon(res, null, Palette.Teal, alpha = 1f)
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

/** Подписи шлагбаумов на карте — те же имена, что в ответе словами («Ш1»). */
private fun barrierNames(state: CarUiState): Map<Long, String> =
    SpotClassifier.anchorsFromBarriers(state.barriers).associate {
        it.id.removePrefix(SpotClassifier.BARRIER_ANCHOR_PREFIX).toLong() to it.name
    }

/** Маркер-подпись: точка ровно на месте и плашка с именем справа от неё. */
private fun labelMarker(
    map: MapView,
    res: Resources,
    point: GeoPoint,
    text: String,
    dot: Color,
    onClick: () -> Boolean,
): Marker {
    val density = res.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TextPrimary.toArgb()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, res.displayMetrics)
        isFakeBoldText = true
    }
    val dotRadius = 5 * density
    val pad = 5 * density
    val gap = 5 * density
    val height = 22 * density
    val textWidth = textPaint.measureText(text)
    val width = pad + dotRadius * 2 + gap + textWidth + pad * 1.6f
    val bitmap = Bitmap.createBitmap(width.toInt().coerceAtLeast(1), height.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.Background.copy(alpha = 0.85f).toArgb() }
    canvas.drawRoundRect(RectF(0f, 0f, width, height), height / 2, height / 2, bg)
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot.toArgb() }
    canvas.drawCircle(pad + dotRadius, height / 2, dotRadius, dotPaint)
    val baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(text, pad + dotRadius * 2 + gap, baseline, textPaint)

    return Marker(map).apply {
        position = point
        icon = BitmapDrawable(res, bitmap)
        setAnchor((pad + dotRadius) / width, 0.5f)
        infoWindow = null
        setOnMarkerClickListener { _, _ -> onClick() }
    }
}

/**
 * Пин машины: круг, а внутри стрелка курса, если курс известен, или точка.
 * Стрелка поворачивается по часовой от севера — карта не вращается.
 */
private fun carIcon(res: Resources, bearingDeg: Float?, color: Color, alpha: Float): BitmapDrawable {
    val density = res.displayMetrics.density
    val size = (36 * density).toInt()
    val c = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.copy(alpha = alpha).toArgb() }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Palette.Background.copy(alpha = alpha).toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    val radius = 14 * density
    canvas.drawCircle(c, c, radius, fill)
    canvas.drawCircle(c, c, radius, stroke)
    val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Palette.OnAmber.copy(alpha = alpha).toArgb() }
    if (bearingDeg != null) {
        canvas.save()
        canvas.rotate(bearingDeg, c, c)
        val d = density
        val arrow = Path().apply {
            moveTo(c, c - 9 * d)
            lineTo(c + 7 * d, c + 7 * d)
            lineTo(c, c + 3 * d)
            lineTo(c - 7 * d, c + 7 * d)
            close()
        }
        canvas.drawPath(arrow, inner)
        canvas.restore()
    } else {
        canvas.drawCircle(c, c, 4.5f * density, inner)
    }
    return BitmapDrawable(res, bitmap)
}
