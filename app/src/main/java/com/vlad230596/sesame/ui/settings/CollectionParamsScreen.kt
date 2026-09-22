package com.vlad230596.sesame.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.ui.common.ChipRow
import com.vlad230596.sesame.ui.common.SectionCard
import com.vlad230596.sesame.ui.common.SectionTitle
import com.vlad230596.sesame.ui.theme.Dimens

/**
 * «Параметры сбора» (§4.4, §4.6).
 *
 * Вынесено отдельным экраном сознательно: эти значения предстоит крутить прямо
 * на телефоне, глядя на расход батареи и объём за сутки, и пересобирать
 * приложение ради каждого шага нельзя.
 *
 * Значения по умолчанию — из таблицы §4.4. Гироскоп и магнитометр в пассивном
 * слое не опрашиваются вовсе, поэтому их здесь нет.
 */
@Composable
internal fun CollectionParamsContent(
    settings: SesameSettings,
    onLocationInterval: (Int) -> Unit,
    onLocationPriority: (LocationPriority) -> Unit,
    onLocationDisplacement: (Int) -> Unit,
    onAccelerometerHz: (Int) -> Unit,
    onBarometerHz: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            bottom = Dimens.SpaceXl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        item {
            SectionCard {
                Text(
                    "Ожидаемая стоимость при значениях по умолчанию — около 5–7 % " +
                        "батареи и около 2 МБ данных в сутки (§4.4).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("Локация") }

        item {
            SectionCard {
                SliderSetting(
                    title = "Интервал",
                    description = "По умолчанию 120 с. В покое обновлений всё равно нет.",
                    value = settings.locationIntervalSeconds,
                    range = SesameSettings.MIN_LOCATION_INTERVAL_SECONDS..
                        SesameSettings.MAX_LOCATION_INTERVAL_SECONDS,
                    step = 10,
                    valueLabel = { "$it с" },
                    onChange = onLocationInterval,
                )
            }
        }

        item {
            SectionCard(title = "Приоритет") {
                Text(
                    LocationPriority.entries
                        .first { it == settings.locationPriority }
                        .hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = LocationPriority.entries.toList(),
                    selected = settings.locationPriority,
                    onSelect = onLocationPriority,
                    label = { it.title },
                )
            }
        }

        item {
            SectionCard {
                SliderSetting(
                    title = "Минимальное смещение",
                    description = "По умолчанию 25 м: мелкое дрожание координаты не пишется.",
                    value = settings.locationMinDisplacementMeters,
                    range = 0..SesameSettings.MAX_LOCATION_DISPLACEMENT_METERS,
                    step = 5,
                    valueLabel = { "$it м" },
                    onChange = onLocationDisplacement,
                )
            }
        }

        item { SectionTitle("Датчики") }

        item {
            SectionCard {
                SliderSetting(
                    title = "Акселерометр",
                    description = "Главный сигнал «началось движение». По умолчанию 5 Гц.",
                    value = settings.accelerometerHz,
                    range = SesameSettings.MIN_ACCELEROMETER_HZ..
                        SesameSettings.MAX_ACCELEROMETER_HZ,
                    valueLabel = { "$it Гц" },
                    onChange = onAccelerometerHz,
                )
            }
        }

        item {
            SectionCard {
                SliderSetting(
                    title = "Барометр",
                    description = "При наличии датчика. По умолчанию 1 Гц — интересует " +
                        "в том числе спуск на лифте.",
                    value = settings.barometerHz,
                    range = SesameSettings.MIN_BAROMETER_HZ..SesameSettings.MAX_BAROMETER_HZ,
                    valueLabel = { "$it Гц" },
                    onChange = onBarometerHz,
                )
            }
        }

        item {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Сбросить к значениям по умолчанию")
            }
        }
    }
}
