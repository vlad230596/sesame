package com.vlad230596.sesame.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameField
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SquareIconButton
import com.vlad230596.sesame.ui.common.StepperRow
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.common.formatCoordinate
import com.vlad230596.sesame.ui.theme.BarrierAccent
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameBarrierAccents
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Правка одного шлагбаума (§4.6): имя, техническая подпись, номер, геофенс.
 *
 * Имени и подписи два поля, а не одно. На кнопке за рулём читается место
 * («Северный въезд»), а в журнале, истории и уведомлениях нужна однозначная
 * позиция («шлагбаум A») — это разные строки, и склеивать их значило бы либо
 * получить «шлагбаум A» на главной кнопке, либо «Северный въезд» в csv, где
 * завтра будет три северных.
 *
 * Сохранение явной кнопкой: поля вводятся посимвольно, и писать в базу каждое
 * нажатие клавиши здесь незачем — тем более что после записи перерегистрируется
 * геофенс.
 */
@Composable
internal fun BarrierContent(
    barrier: Barrier,
    accent: BarrierAccent,
    position: Int,
    onPickCurrent: ((Double?, Double?) -> Unit) -> Unit,
    onSave: (Barrier) -> Unit,
    onTestCall: (Barrier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(barrier.id) { mutableStateOf(barrier.name) }
    var label by remember(barrier.id) { mutableStateOf(barrier.label) }
    var phone by remember(barrier.id) { mutableStateOf(barrier.phoneNumber.orEmpty()) }
    var lat by remember(barrier.id) { mutableStateOf(barrier.lat?.toString().orEmpty()) }
    var lon by remember(barrier.id) { mutableStateOf(barrier.lon?.toString().orEmpty()) }
    var radius by remember(barrier.id) { mutableIntStateOf(barrier.radiusMeters.toInt()) }
    var phoneVisible by remember(barrier.id) { mutableStateOf(false) }

    val edited = barrier.copy(
        name = name.trim(),
        label = label.trim().ifBlank { barrier.label },
        phoneNumber = phone.trim().takeIf { it.isNotBlank() },
        lat = lat.trim().toDoubleOrNull(),
        lon = lon.trim().toDoubleOrNull(),
        radiusMeters = radius.toFloat(),
    )
    val dirty = edited != barrier

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        SesameField(
            value = name,
            onValueChange = { name = it },
            label = "Имя въезда",
            placeholder = barrier.label,
            hint = "Крупная подпись на кнопке: её читают за рулём",
        )

        SesameField(
            value = label,
            onValueChange = { label = it },
            label = "Техническая подпись",
            hint = "Ею подписаны записи в журнале и в истории — «шлагбаум A»",
        )

        // Номер по умолчанию скрыт: экран настроек тоже видно через плечо, а
        // номер шлагбаума — фактически ключ от двора.
        SesameField(
            value = if (phoneVisible) phone else maskFully(phone),
            onValueChange = { if (phoneVisible) phone = it },
            label = "Номер шлагбаума",
            placeholder = "+7 900 000-00-00",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            hint = if (phoneVisible) {
                "Хранится только на устройстве и в репозиторий не попадает"
            } else {
                "Нажмите «глаз», чтобы показать и править"
            },
            trailing = {
                SquareIconButton(
                    icon = SesameIcons.Eye,
                    contentDescription = if (phoneVisible) "Скрыть номер" else "Показать номер",
                    size = 52.dp,
                    corner = 15.dp,
                    tint = if (phoneVisible) Palette.Amber else Tones.TextSoft,
                    onClick = { phoneVisible = !phoneVisible },
                )
            },
        )

        ButtonColorRow(accent = accent, position = position)

        GeofenceCard(
            lat = lat,
            lon = lon,
            radius = radius,
            accent = accent.container,
            onLat = { lat = it },
            onLon = { lon = it },
            onRadius = { radius = it },
            onPickCurrent = {
                onPickCurrent { pickedLat, pickedLon ->
                    if (pickedLat != null && pickedLon != null) {
                        lat = pickedLat.toString()
                        lon = pickedLon.toString()
                    }
                }
            },
        )

        SesameButton(
            text = if (dirty) "Сохранить" else "Сохранено",
            modifier = Modifier.fillMaxWidth(),
            enabled = dirty,
            onClick = { onSave(edited) },
        )

        SesameButton(
            text = "Позвонить для проверки",
            modifier = Modifier.fillMaxWidth(),
            container = Palette.AmberSurface,
            content = Palette.Amber,
            border = Palette.AmberBorder,
            enabled = barrier.phoneNumber != null,
            onClick = { onTestCall(barrier) },
        )

        FootNote(
            "Проверочный звонок идёт тем же путём, что и кнопка на главном экране, " +
                "и так же попадает в журнал: шлагбаум от него откроется по-настоящему. " +
                "Номер берётся сохранённый — правку сначала надо сохранить.",
        )
    }
}

/**
 * Цвет кнопки — показан, но не выбирается.
 *
 * Он закреплён за позицией шлагбаума на главном экране и повторён в виджете и
 * полоской в истории. Ручной выбор позволил бы сделать обе кнопки одного цвета —
 * а различать их цветом и есть единственный способ не промахнуться, не читая
 * подпись.
 */
@Composable
private fun ButtonColorRow(accent: BarrierAccent, position: Int) {
    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Surface,
        borderColor = Palette.Border,
        corner = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            Box(
                Modifier
                    .size(width = 52.dp, height = 44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.container),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Цвет кнопки",
                    style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary,
                )
                Text(
                    text = "Закреплён за ${position + 1}-й кнопкой главного экрана",
                    style = SesameText.Caption.copy(fontSize = 12.sp),
                    color = Palette.TextDim,
                )
            }
        }
    }
}

/**
 * Геофенс шлагбаума (§4.4: по 100 м вокруг каждого).
 *
 * Схема слева — не карта: карты в v0 нет и не будет, а показать надо ровно одно —
 * что радиус это круг вокруг точки. Пустая координата валидна: геофенс просто
 * не регистрируется, и это не ошибка.
 */
@Composable
internal fun GeofenceCard(
    lat: String,
    lon: String,
    radius: Int,
    accent: Color,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
    onRadius: (Int) -> Unit,
    onPickCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Геофенс",
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = Palette.Surface,
        borderColor = Palette.Border,
        corner = Dimens.ButtonCorner,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = SesameText.CardTitle, color = Palette.TextPrimary)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            ) {
                GeofenceDiagram(
                    accent = accent,
                    filled = lat.trim().toDoubleOrNull() != null && lon.trim().toDoubleOrNull() != null,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Координата",
                        style = SesameText.Caption.copy(fontSize = 12.sp),
                        color = Palette.TextMuted,
                    )
                    Text(
                        text = formatCoordinate(lat.trim().toDoubleOrNull(), lon.trim().toDoubleOrNull())
                            ?: "не задана",
                        style = SesameText.Mono14,
                        color = Palette.TextPrimary,
                    )
                    SesameButton(
                        text = "Взять текущую",
                        container = Palette.SurfaceHigh,
                        content = Palette.TextPrimary,
                        border = Palette.BorderStrong,
                        height = 40.dp,
                        corner = 13.dp,
                        style = SesameText.Caption.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        onClick = onPickCurrent,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                SesameField(
                    value = lat,
                    onValueChange = onLat,
                    label = "Широта",
                    placeholder = "—",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                SesameField(
                    value = lon,
                    onValueChange = onLon,
                    label = "Долгота",
                    placeholder = "—",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            StepperRow(
                title = "Радиус",
                value = "$radius м",
                minusEnabled = radius > SesameSettings.MIN_GEOFENCE_RADIUS_METERS,
                plusEnabled = radius < SesameSettings.MAX_GEOFENCE_RADIUS_METERS,
                onMinus = { onRadius(RadiusSteps.previous(radius)) },
                onPlus = { onRadius(RadiusSteps.next(radius)) },
                minHeight = 44.dp,
            )
        }
    }
}

/** Шаги радиуса геофенса: от 50 м (двор) до 2 км (район). */
internal object RadiusSteps {
    private val values = listOf(50, 75, 100, 150, 200, 300, 500, 750, 1_000, 1_500, 2_000)

    fun next(current: Int): Int = values.firstOrNull { it > current } ?: values.last()

    fun previous(current: Int): Int = values.lastOrNull { it < current } ?: values.first()
}

/** Схема геофенса: круг радиуса вокруг точки, пунктир — «примерно столько же». */
@Composable
private fun GeofenceDiagram(accent: Color, filled: Boolean) {
    Canvas(Modifier.size(112.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2 - 10.dp.toPx()
        val inner = size.minDimension / 2 - 26.dp.toPx()

        drawCircle(
            color = Palette.BorderStrong,
            radius = outer,
            center = center,
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            ),
        )
        if (filled) {
            drawCircle(color = accent.copy(alpha = 0.14f), radius = inner, center = center)
        }
        drawCircle(
            color = if (filled) accent else Palette.BorderStrong,
            radius = inner,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCircle(
            color = if (filled) accent else Palette.TextDim,
            radius = 4.dp.toPx(),
            center = center,
        )

        val tick = 10.dp.toPx()
        listOf(
            Offset(center.x, 0f) to Offset(center.x, tick),
            Offset(center.x, size.height) to Offset(center.x, size.height - tick),
            Offset(0f, center.y) to Offset(tick, center.y),
            Offset(size.width, center.y) to Offset(size.width - tick, center.y),
        ).forEach { (from, to) ->
            drawLine(Palette.BorderStrong, from, to, strokeWidth = 1.5.dp.toPx())
        }
    }
}

private fun maskFully(phone: String): String =
    if (phone.isEmpty()) "" else "·".repeat(phone.length.coerceAtMost(16))

@Preview(showBackground = true, widthDp = 390, heightDp = 1100)
@Composable
private fun BarrierPreview() {
    SesameTheme {
        Box(Modifier.background(Palette.Background).padding(20.dp)) {
            BarrierContent(
                barrier = Barrier(
                    id = 1,
                    label = "Шлагбаум A",
                    phoneNumber = "+7 900 000-00-14",
                    lat = 55.75124,
                    lon = 37.61841,
                    name = "Северный въезд",
                ),
                accent = SesameBarrierAccents.barrier(0),
                position = 0,
                onPickCurrent = {},
                onSave = {},
                onTestCall = {},
            )
        }
    }
}
