package com.vlad230596.sesame.ui.settings

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.GroupCaption
import com.vlad230596.sesame.ui.common.GroupDivider
import com.vlad230596.sesame.ui.common.GroupRow
import com.vlad230596.sesame.ui.common.SegmentedRow
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SettingsGroup
import com.vlad230596.sesame.ui.common.StepperRow
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme
import kotlin.math.roundToInt

/** Какие датчики физически есть на этом устройстве (§6: манифест сессии). */
@Immutable
internal data class SensorAvailability(
    val barometer: Boolean,
    val stepCounter: Boolean,
)

/**
 * Опрашивается один раз на вход: список датчиков у устройства не меняется, а
 * строка «датчика нет» должна быть правдой, а не предположением по модели.
 */
@Composable
internal fun rememberSensorAvailability(): SensorAvailability {
    val context: Context = LocalContext.current
    return remember(context) {
        val manager = runCatching { context.getSystemService<SensorManager>() }.getOrNull()
        SensorAvailability(
            barometer = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null,
            stepCounter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
        )
    }
}

/**
 * «Параметры сбора» (§4.4, §4.6), по утверждённому макету.
 *
 * Вынесено отдельным экраном сознательно: эти значения предстоит крутить прямо
 * на телефоне, глядя на расход батареи и объём за сутки, и пересобирать
 * приложение ради каждого шага нельзя.
 *
 * Шаги, а не ползунки: шкала интервала локации идёт от 10 с до 15 мин, и попасть
 * пальцем в «120 с» на такой шкале невозможно — а именно это значение и есть
 * рабочее. Гироскоп и магнитометр в пассивном слое не опрашиваются вовсе,
 * поэтому их здесь нет.
 */
@Composable
internal fun CollectionParamsContent(
    settings: SesameSettings,
    hasBarometer: Boolean,
    hasStepCounter: Boolean,
    onLocationInterval: (Int) -> Unit,
    onLocationPriority: (LocationPriority) -> Unit,
    onLocationDisplacement: (Int) -> Unit,
    onAccelerometerHz: (Int) -> Unit,
    onBarometerHz: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        GroupCaption("Локация", top = Dimens.SpaceXs)
        SettingsGroup {
            StepperRow(
                title = "Интервал",
                value = formatSeconds(settings.locationIntervalSeconds),
                minusEnabled = settings.locationIntervalSeconds >
                    SesameSettings.MIN_LOCATION_INTERVAL_SECONDS,
                plusEnabled = settings.locationIntervalSeconds <
                    SesameSettings.MAX_LOCATION_INTERVAL_SECONDS,
                onMinus = { onLocationInterval(IntervalSteps.previous(settings.locationIntervalSeconds)) },
                onPlus = { onLocationInterval(IntervalSteps.next(settings.locationIntervalSeconds)) },
            )
            GroupDivider()
            StepperRow(
                title = "Мин. смещение",
                value = "${settings.locationMinDisplacementMeters} м",
                minusEnabled = settings.locationMinDisplacementMeters > 0,
                plusEnabled = settings.locationMinDisplacementMeters <
                    SesameSettings.MAX_LOCATION_DISPLACEMENT_METERS,
                onMinus = {
                    onLocationDisplacement(
                        DisplacementSteps.previous(settings.locationMinDisplacementMeters),
                    )
                },
                onPlus = {
                    onLocationDisplacement(
                        DisplacementSteps.next(settings.locationMinDisplacementMeters),
                    )
                },
            )
            GroupDivider()
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Приоритет",
                    style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary,
                )
                // Четыре варианта, а не три с макета: `PASSIVE` («только чужие
                // запросы локации») — отдельный режим, которым проверяют, сколько
                // данных приходит даром. Убрать его значило бы потерять настройку.
                SegmentedRow(
                    options = LocationPriority.entries.toList(),
                    selected = settings.locationPriority,
                    onSelect = onLocationPriority,
                    label = { it.shortTitle() },
                    height = 44.dp,
                    corner = 13.dp,
                )
                Text(
                    text = settings.locationPriority.hint,
                    style = SesameText.Caption.copy(fontSize = 12.sp),
                    color = Palette.TextDim,
                )
            }
        }

        GroupCaption("Датчики в фоне")
        SettingsGroup {
            // Подписей у строк с крутилкой нет: две строки текста рядом с
            // «− 5 Гц +» делают строку вдвое выше, и группа перестаёт читаться
            // как список одинаковых настроек.
            StepperRow(
                title = "Акселерометр",
                value = "${settings.accelerometerHz} Гц",
                minusEnabled = settings.accelerometerHz > SesameSettings.MIN_ACCELEROMETER_HZ,
                plusEnabled = settings.accelerometerHz < SesameSettings.MAX_ACCELEROMETER_HZ,
                onMinus = { onAccelerometerHz(AccelerometerSteps.previous(settings.accelerometerHz)) },
                onPlus = { onAccelerometerHz(AccelerometerSteps.next(settings.accelerometerHz)) },
            )
            GroupDivider()
            if (hasBarometer) {
                StepperRow(
                    title = "Барометр",
                    value = "${settings.barometerHz} Гц",
                    minusEnabled = settings.barometerHz > SesameSettings.MIN_BAROMETER_HZ,
                    plusEnabled = settings.barometerHz < SesameSettings.MAX_BAROMETER_HZ,
                    onMinus = { onBarometerHz(settings.barometerHz - 1) },
                    onPlus = { onBarometerHz(settings.barometerHz + 1) },
                )
            } else {
                // Настройка остаётся в DataStore: датчик может появиться на другом
                // устройстве, а обнулять её из-за этого незачем. Но крутить то,
                // чего физически нет, экран не предлагает.
                ReadOnlyRow(
                    title = "Барометр",
                    subtitle = "Датчика нет на этом устройстве",
                    value = "—",
                    dimmed = true,
                )
            }
            GroupDivider()
            ReadOnlyRow(
                title = "Шагомер",
                subtitle = if (hasStepCounter) null else "Датчика нет на этом устройстве",
                value = if (hasStepCounter) "аппаратный" else "—",
                dimmed = !hasStepCounter,
            )
            GroupDivider()
            // §4.3: кольцевой буфер на 60 с — не настройка, а свойство записи.
            // Показан здесь, потому что это тот же «сколько мы храним в фоне»,
            // и его всё равно ищут на этом экране.
            ReadOnlyRow(
                title = "Кольцевой буфер",
                subtitle = "Первые секунды сессии берутся из него",
                value = "60 с",
            )
        }

        Spacer(Modifier.height(Dimens.SpaceXs))

        CostCard(settings = settings)

        SesameButton(
            text = "Сбросить к значениям по умолчанию",
            modifier = Modifier.fillMaxWidth(),
            container = Palette.SurfaceHigh,
            content = Tones.TextSoft,
            border = Palette.BorderStrong,
            onClick = onReset,
        )

        FootNote(
            "Гироскоп и магнитометр в фоне не опрашиваются: на низкой частоте " +
                "бесполезны, а энергию расходуют наравне с полной.",
        )
    }
}

/** Строка, которую нельзя покрутить: датчика нет либо значение зашито. */
@Composable
private fun ReadOnlyRow(
    title: String,
    subtitle: String?,
    value: String,
    dimmed: Boolean = false,
) {
    GroupRow {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                color = if (dimmed) Palette.TextDim else Palette.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SesameText.Caption.copy(fontSize = 12.sp),
                    color = Palette.TextDim,
                )
            }
        }
        Text(
            text = value,
            style = SesameText.Mono13,
            color = if (dimmed) Palette.TextDim else Palette.TextMuted,
        )
    }
}

/**
 * Во что обходятся текущие значения.
 *
 * Оценка, а не измерение, и подписана как оценка: измерять расход батареи изнутри
 * приложения нечем, а порядок величины — это ровно то, ради чего эти шаги и
 * крутят. Ориентир §4.4: 5–7 % батареи и около 2 МБ в сутки на значениях
 * по умолчанию.
 */
@Composable
private fun CostCard(settings: SesameSettings) {
    val defaults = SesameSettings()
    val locationFactor = defaults.locationIntervalSeconds.toFloat() /
        settings.locationIntervalSeconds.coerceAtLeast(1)
    val accelFactor = settings.accelerometerHz.toFloat() /
        defaults.accelerometerHz.coerceAtLeast(1)
    val priorityFactor = when (settings.locationPriority) {
        LocationPriority.HIGH_ACCURACY -> 2.5f
        LocationPriority.BALANCED -> 1f
        LocationPriority.LOW_POWER -> 0.6f
        LocationPriority.PASSIVE -> 0.2f
    }

    // Локация и приоритет тянут батарею, акселерометр — и батарею, и объём.
    val battery = (2f * locationFactor * priorityFactor + 4f * accelFactor).coerceIn(1f, 60f)
    val megabytes = (0.4f * locationFactor + 1.6f * accelFactor).coerceIn(0.2f, 60f)

    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Tones.TealSurface,
        borderColor = Tones.TealBorder,
        corner = Dimens.CardCorner,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "~${battery.roundToInt()} %",
                    style = SesameText.Mono14.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Palette.Teal,
                )
                Text(
                    text = "батареи в сутки",
                    style = SesameText.Caption.copy(fontSize = 14.sp),
                    color = Tones.TealText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "~${formatMegabytes(megabytes)} МБ",
                    style = SesameText.Mono14.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Palette.Teal,
                )
            }
            Text(
                text = "Оценка по текущим значениям, а не измерение. Ориентир §4.4 — " +
                    "5–7 % и около 2 МБ в сутки на значениях по умолчанию.",
                style = SesameText.Caption.copy(fontSize = 12.sp),
                color = Tones.TealDim,
            )
        }
    }
}

private fun formatMegabytes(value: Float): String =
    if (value >= 10f) value.roundToInt().toString() else "%.1f".format(value).replace('.', ',')

/**
 * Интервал всегда в секундах, даже когда он кратен минуте.
 *
 * «2 мин» и «120 с» — одно и то же число, но сводка на экране настроек, журнал и
 * этот экран обязаны называть его одинаково, иначе сверять их глазами нельзя.
 */
private fun formatSeconds(seconds: Int): String = "$seconds с"

/** Человеческая подпись приоритета в три-четыре буквы — на кнопку сегмента. */
private fun LocationPriority.shortTitle(): String = when (this) {
    LocationPriority.HIGH_ACCURACY -> "Высокий"
    LocationPriority.BALANCED -> "Баланс"
    LocationPriority.LOW_POWER -> "Низкий"
    LocationPriority.PASSIVE -> "Пассив"
}

internal object IntervalSteps {
    private val values = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 600, 900)

    fun next(current: Int): Int = values.firstOrNull { it > current } ?: values.last()

    fun previous(current: Int): Int = values.lastOrNull { it < current } ?: values.first()
}

internal object DisplacementSteps {
    private val values = listOf(0, 5, 10, 15, 25, 50, 75, 100, 150, 200)

    fun next(current: Int): Int = values.firstOrNull { it > current } ?: values.last()

    fun previous(current: Int): Int = values.lastOrNull { it < current } ?: values.first()
}

internal object AccelerometerSteps {
    private val values = listOf(1, 2, 3, 5, 10, 15, 20, 25, 33, 50)

    fun next(current: Int): Int = values.firstOrNull { it > current } ?: values.last()

    fun previous(current: Int): Int = values.lastOrNull { it < current } ?: values.first()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun CollectionParamsPreview() {
    SesameTheme {
        Box(Modifier.background(Palette.Background).padding(horizontal = 20.dp)) {
            CollectionParamsContent(
                settings = SesameSettings(locationPriority = LocationPriority.BALANCED),
                hasBarometer = false,
                hasStepCounter = true,
                onLocationInterval = {},
                onLocationPriority = {},
                onLocationDisplacement = {},
                onAccelerometerHz = {},
                onBarometerHz = {},
                onReset = {},
            )
        }
    }
}
