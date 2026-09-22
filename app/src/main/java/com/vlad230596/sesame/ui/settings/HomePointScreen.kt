package com.vlad230596.sesame.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.NoticeBanner
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Координата дома (§4.4: геофенс 500 м вокруг дома).
 *
 * Дома нет в модели данных §5 — там только шлагбаумы, у которых есть номер и
 * кнопка. Заводить ради одной точки запись в таблице шлагбаумов значило бы
 * поселить в списке нечто, чему нельзя звонить и что обязано не появиться на
 * главном экране. Поэтому дом — три настройки, и правятся они здесь.
 *
 * Экрана «Дом» на макете нет; он сделан по образцу экрана шлагбаума, потому что
 * это ровно тот же геофенс с теми же тремя полями. Без него настройка из §4.4
 * стала бы недоступной.
 */
@Composable
internal fun HomePointContent(
    settings: SesameSettings,
    onPickCurrent: ((Double?, Double?) -> Unit) -> Unit,
    onSave: (Double?, Double?, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lat by remember(settings.homeLat) { mutableStateOf(settings.homeLat?.toString().orEmpty()) }
    var lon by remember(settings.homeLon) { mutableStateOf(settings.homeLon?.toString().orEmpty()) }
    var radius by remember(settings.homeRadiusMeters) {
        mutableIntStateOf(settings.homeRadiusMeters)
    }

    val parsedLat = lat.trim().toDoubleOrNull()
    val parsedLon = lon.trim().toDoubleOrNull()
    val dirty = parsedLat != settings.homeLat ||
        parsedLon != settings.homeLon ||
        radius != settings.homeRadiusMeters

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        NoticeBanner(
            title = "Зачем это нужно",
            text = "Геофенс дома в v0 не даёт функциональности. По нему проверяется " +
                "единственный вопрос, на который нельзя ответить оффлайн: усыпляет ли " +
                "One UI приложение и с какой задержкой Android доставляет фоновые события.",
            accent = Palette.Teal,
            container = Tones.TealSurface,
            borderColor = Tones.TealBorder,
            textColor = Tones.TealText,
            icon = SesameIcons.Question,
        )

        GeofenceCard(
            title = "Геофенс дома",
            lat = lat,
            lon = lon,
            radius = radius,
            accent = Palette.Teal,
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
            onClick = { onSave(parsedLat, parsedLon, radius) },
        )

        SesameButton(
            text = "Очистить координату",
            modifier = Modifier.fillMaxWidth(),
            container = Palette.SurfaceHigh,
            content = Tones.TextSoft,
            border = Palette.BorderStrong,
            enabled = settings.homeLat != null || settings.homeLon != null,
            onClick = {
                lat = ""
                lon = ""
                onSave(null, null, radius)
            },
        )

        FootNote(
            "Пустая координата — валидное состояние: геофенс дома просто " +
                "не регистрируется. По умолчанию радиус 500 м (§4.4).",
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomePointPreview() {
    SesameTheme {
        Box(Modifier.background(Palette.Background).padding(20.dp)) {
            HomePointContent(
                settings = SesameSettings(homeLat = 55.75124, homeLon = 37.61841),
                onPickCurrent = {},
                onSave = { _, _, _ -> },
            )
        }
    }
}
