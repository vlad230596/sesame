package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.TravelMode
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.SesameTheme

/**
 * Карточка метки проезда (§4.5).
 *
 * Направление и режим правятся в один тап чипами: за рулём их никто не выбирает,
 * и заполнены они догадкой — значит, исправление должно стоить ровно одно
 * касание, иначе метки так и останутся неразмеченными.
 *
 * Используется и в истории, и в плашке «N меток без подтверждения» на главном
 * экране — поведение обязано совпадать.
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
) {
    var noteVisible by rememberSaveable(label.id) { mutableStateOf(!label.note.isNullOrBlank()) }
    var noteText by remember(label.id, label.note) { mutableStateOf(label.note.orEmpty()) }

    SectionCard(
        modifier = modifier,
        containerColor = when {
            label.discarded -> MaterialTheme.colorScheme.surfaceVariant
            !label.confirmed -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            Text(formatTime(label.timestamp), style = MaterialTheme.typography.titleMedium)
            Text(
                text = barrierLabel ?: "Шлагбаум не указан",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (label.suspicious) {
                Text(
                    "далеко от дома",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = buildString {
                append(label.source.title())
                label.outcome?.let { append(" · ").append(it.title()) }
                if (!label.confirmed) append(" · без подтверждения")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChipRow(
            options = listOf(Direction.IN, Direction.OUT, Direction.UNKNOWN),
            selected = label.direction,
            onSelect = onDirection,
            label = { it.title() },
        )
        ChipRow(
            options = listOf(
                TravelMode.DRIVER,
                TravelMode.PASSENGER,
                TravelMode.FOOT,
                TravelMode.UNKNOWN,
            ),
            selected = label.mode,
            onSelect = onMode,
            label = { it.title() },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            Checkbox(checked = label.discarded, onCheckedChange = onToggleDiscarded)
            Text("Ошибочная", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { noteVisible = !noteVisible }) {
                    Text(if (noteVisible) "Скрыть заметку" else "Заметка")
                }
            }
        }

        if (noteVisible) {
            SettingTextField(
                value = noteText,
                onValueChange = {
                    noteText = it
                    onNote(it)
                },
                label = "Заметка",
                singleLine = false,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PassageCardPreview() {
    SesameTheme {
        PassageCard(
            label = PassageLabel(
                id = 1,
                timestamp = System.currentTimeMillis(),
                barrierId = 1,
                direction = Direction.UNKNOWN,
                mode = TravelMode.UNKNOWN,
                source = LabelSource.WIDGET,
                outcome = CallOutcome.CALLED,
                suspicious = true,
            ),
            barrierLabel = "Шлагбаум A",
            onDirection = {},
            onMode = {},
            onToggleDiscarded = {},
            onNote = {},
        )
    }
}
