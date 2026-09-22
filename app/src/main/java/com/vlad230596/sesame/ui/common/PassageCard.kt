package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Правка метки проезда (§4.5), по макету «Правка метки».
 *
 * Направление и режим правятся в один тап сегментами: за рулём их никто не
 * выбирает, и заполнены они догадкой — значит, исправление должно стоить ровно
 * одно касание, иначе метки так и останутся неразмеченными. Любой выбор
 * автоматически снимает «без подтверждения» — этим занимается
 * [com.vlad230596.sesame.data.PassageLabelRepository].
 *
 * Один и тот же блок стоит и в окне истории, и в плашке «N меток без
 * подтверждения» на главном экране: поведение обязано совпадать.
 */
@Composable
fun LabelEditor(
    label: PassageLabel,
    barrierName: String?,
    onDirection: (Direction) -> Unit,
    onMode: (TravelMode) -> Unit,
    onToggleDiscarded: (Boolean) -> Unit,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Amber,
    compact: Boolean = false,
) {
    var noteText by remember(label.id, label.note) { mutableStateOf(label.note.orEmpty()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else Dimens.SpaceM),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(accent, if (compact) Dimens.DotSmall else 10.dp)
            Text(
                text = barrierName ?: "Шлагбаум не указан",
                style = if (compact) {
                    SesameText.CardTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    SesameText.RowTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold)
                },
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDayTime(label.timestamp),
                style = SesameText.Mono13,
                color = Palette.TextMuted,
                maxLines = 1,
            )
        }

        // Строка происхождения метки. Стоит отдельной плашкой, только когда есть
        // что объяснять: «нажато далеко от дома» — единственный признак, который
        // ставит не человек, и на него смотрят в первую очередь.
        if (label.suspicious) {
            NoticeBanner(
                text = "Нажато далеко от дома — помечено как подозрительное. " +
                    describeOrigin(label) + ".",
                accent = Palette.Amber,
                container = Palette.AmberSurface,
                borderColor = Palette.AmberBorder,
                textColor = Tones.AmberText,
            )
        } else {
            Text(
                text = describeOrigin(label).replaceFirstChar { it.uppercase() } +
                    if (!label.confirmed) " · без подтверждения" else "",
                style = SesameText.Caption,
                color = Palette.TextMuted,
            )
        }

        FieldBlock("Направление") {
            SegmentedRow(
                options = listOf(Direction.IN, Direction.OUT, Direction.UNKNOWN),
                selected = label.direction,
                onSelect = onDirection,
                label = { if (it == Direction.UNKNOWN) "?" else it.title() },
                weights = { if (it == Direction.UNKNOWN) 0.45f else 1f },
                height = if (compact) 46.dp else 50.dp,
            )
        }

        FieldBlock("Режим") {
            SegmentedRow(
                options = listOf(
                    TravelMode.DRIVER,
                    TravelMode.PASSENGER,
                    TravelMode.FOOT,
                    TravelMode.UNKNOWN,
                ),
                selected = label.mode,
                onSelect = onMode,
                label = { if (it == TravelMode.UNKNOWN) "?" else it.title() },
                weights = { if (it == TravelMode.UNKNOWN) 0.4f else 1f },
                height = if (compact) 46.dp else 50.dp,
            )
        }

        FieldBlock("Заметка") {
            SesameField(
                value = noteText,
                onValueChange = {
                    noteText = it
                    onNote(it)
                },
                placeholder = "Например: открыл сосед со своего телефона",
            )
        }

        ToggleRow(
            title = "Случайное нажатие",
            checked = label.discarded,
            onCheckedChange = onToggleDiscarded,
        )
    }
}

@Composable
private fun FieldBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(
            text = title,
            style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
            color = Palette.TextMuted,
        )
        content()
    }
}

/** «звонок ушёл, виджет» — откуда метка и чем кончилась попытка. */
private fun describeOrigin(label: PassageLabel): String = buildString {
    label.outcome?.let { append(it.title()).append(", ") }
    append(label.source.title())
}

/**
 * Карточка метки для списка «без подтверждения» на главном экране.
 *
 * Тот же редактор, обёрнутый в поверхность: на главной карточки идут подряд и
 * должны отделяться друг от друга, а в окне истории правится ровно одна метка,
 * и рамка вокруг неё лишняя.
 */
@Composable
fun PassageCard(
    label: PassageLabel,
    barrierLabel: String?,
    onDirection: (Direction) -> Unit,
    onMode: (TravelMode) -> Unit,
    onToggleDiscarded: (Boolean) -> Unit,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Amber,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = if (label.discarded) Tones.SurfaceLow else Palette.Surface,
        borderColor = Palette.BorderStrong,
        corner = Dimens.CardCorner,
    ) {
        LabelEditor(
            label = label,
            barrierName = barrierLabel,
            onDirection = onDirection,
            onMode = onMode,
            onToggleDiscarded = onToggleDiscarded,
            onNote = onNote,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            accent = accent,
            compact = true,
        )
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun PassageCardPreview() {
    SesameTheme {
        PassageCard(
            label = PassageLabel(
                id = 1,
                timestamp = System.currentTimeMillis(),
                barrierId = 1,
                direction = Direction.IN,
                mode = TravelMode.UNKNOWN,
                source = LabelSource.WIDGET,
                outcome = CallOutcome.CALLED,
                suspicious = true,
            ),
            barrierLabel = "Северный въезд",
            onDirection = {},
            onMode = {},
            onToggleDiscarded = {},
            onNote = {},
        )
    }
}
