package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText

/**
 * Управляющие элементы макета: сегменты, степперы, тумблеры, группы строк.
 *
 * Собственные, а не material-овские, по той же причине, по которой собственные
 * [SesameSurface] и [SesameBottomBar]: `FilterChip`, `Switch`, `Slider` и
 * `OutlinedTextField` приносят с собой пилюли, тени, тональные подсветки и свою
 * высоту. Перекрасить их до макета дороже, чем нарисовать плоский прямоугольник.
 *
 * Все элементы здесь — «одно значение = один тап»: за рулём и на ходу не работает
 * ничего сложнее. Ползунков нет специально: попасть пальцем в «120 с» на шкале
 * 10…900 невозможно, а именно эти значения предстоит крутить по одному шагу,
 * глядя на расход батареи.
 */

/**
 * Оттенки макета, которых нет в [Palette].
 *
 * Лежат здесь, а не в теме: это подтона конкретных элементов второго экрана
 * (текст на неактивной плитке, дорожка тумблера, зелёная сводка «сколько это
 * стоит»), а не роли, по которым красится приложение.
 */
internal object Tones {
    /** Подпись на неактивной плитке и в строке группы. */
    val TextSoft = Color(0xFFC6CBD2)

    /** Дорожка выключенного тумблера. */
    val ToggleTrack = Color(0xFF3A3F46)

    /** Погашенная строка истории: отменённая, ошибочная. */
    val SurfaceLow = Color(0xFF15171B)

    /** Полоска шлагбаума у строки без шлагбаума. */
    val StripNeutral = Color(0xFF3A3F46)

    /** Тревожная плашка «не хватает разрешений». */
    val AlertSurface = Color(0xFF2A1517)

    /** Текст внутри янтарной плашки-предупреждения. */
    val AmberText = Color(0xFFE8C88F)

    /** Зелёная сводка «сколько это стоит в сутки». */
    val TealSurface = Color(0xFF12251F)
    val TealBorder = Color(0xFF1E4239)
    val TealText = Color(0xFFA7D6CD)
    val TealDim = Color(0xFF86B0A8)
}

/** Заголовок раздела: «ШЛАГБАУМЫ», «ДАННЫЕ». */
@Composable
fun GroupCaption(text: String, modifier: Modifier = Modifier, top: Dp = 12.dp) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 2.dp, end = 2.dp, top = top, bottom = 2.dp),
        style = SesameText.Caption.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        color = Palette.TextDim,
    )
}

/**
 * Группа строк: одна карточка, строки разделены волосяной чертой.
 *
 * Так на макете собраны и настройки, и выданные разрешения: отдельные карточки
 * на каждую строку рассыпали бы экран на десяток плиток.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = Palette.Surface,
        borderColor = Palette.Border,
        corner = 16.dp,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Черта между строками группы. */
@Composable
fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.HairlineWidth)
            .background(Palette.Border),
    )
}

/**
 * Строка группы: слот слева, содержимое, слот справа.
 *
 * Высота 58 dp с макета — заметно выше минимальной цели касания: в строку
 * настроек попадают не глядя, ровно как в кнопку шлагбаума.
 */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    minHeight: Dp = 58.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var row = modifier.fillMaxWidth()
    if (onClick != null) row = row.clickable(onClick = onClick)
    Row(
        modifier = row
            .heightIn(min = minHeight)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        content = content,
    )
}

/** Строка-переход: подпись, значение, шеврон. */
@Composable
fun NavGroupRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueMono: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    GroupRow(modifier = modifier, onClick = if (enabled) onClick else null) {
        leading?.invoke()
        Text(
            text = title,
            style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
            color = Palette.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                text = value,
                style = if (valueMono) SesameText.Mono13 else SesameText.Caption,
                color = Palette.TextMuted,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = SesameIcons.ChevronRight,
            contentDescription = null,
            tint = Palette.TextDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Числовая настройка шагами: «− 120 с +».
 *
 * Значение моноширинным и фиксированной ширины — иначе строка дёргается на
 * каждом шаге, а именно по ней и следят, что именно выставлено.
 */
@Composable
fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    valueWidth: Dp = 58.dp,
    minHeight: Dp = 58.dp,
) {
    GroupRow(modifier = modifier, minHeight = minHeight) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SesameText.Caption.copy(fontSize = 12.sp),
                    color = Palette.TextDim,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            StepButton("−", enabled = minusEnabled, onClick = onMinus, description = "Уменьшить")
            Text(
                text = value,
                style = SesameText.Mono14,
                color = Palette.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(valueWidth),
            )
            StepButton("+", enabled = plusEnabled, onClick = onPlus, description = "Увеличить")
        }
    }
}

@Composable
private fun StepButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(Dimens.HairlineWidth, Palette.BorderStrong, shape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // «−» и «+» сами по себе немые: screen reader обязан прочитать,
            // что именно уменьшается.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = SesameText.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
            color = Tones.TextSoft,
        )
    }
}

/**
 * Ряд взаимоисключающих вариантов: направление, режим, приоритет локации.
 *
 * Активный вариант — светлая заливка, как на макете: на чёрном экране это
 * единственное, что читается боковым зрением без чтения подписи.
 */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    corner: Dp = 14.dp,
    weights: (T) -> Float = { 1f },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        options.forEach { option ->
            SegmentButton(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                height = height,
                corner = corner,
                modifier = Modifier.weight(weights(option)),
            )
        }
    }
}

@Composable
fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    corner: Dp = 14.dp,
) {
    SesameSurface(
        modifier = modifier.height(height),
        color = if (selected) Palette.TextPrimary else Palette.SurfaceHigh,
        borderColor = if (selected) Color.Transparent else Palette.BorderStrong,
        borderWidth = if (selected) 0.dp else Dimens.HairlineWidth,
        corner = corner,
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = SesameText.Body.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (selected) Palette.OnAmber else Tones.TextSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** Фильтр-пилюля в шапке истории. */
@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Palette.TextPrimary else Color.Transparent)
            .then(
                if (selected) Modifier else Modifier.border(Dimens.HairlineWidth, Palette.BorderStrong, shape),
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = SesameText.Caption.copy(
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (selected) Palette.OnAmber else Tones.TextSoft,
            maxLines = 1,
        )
    }
}

/**
 * Тумблер макета: дорожка 48×28, шарик 22.
 *
 * Material-овский `Switch` в M3 крупнее, с тенью и «галочкой» внутри шарика —
 * рядом с плоскими строками группы он выглядит вставкой из другого приложения.
 */
@Composable
fun SesameToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) Palette.Amber else Tones.ToggleTrack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Palette.OnAmber else Palette.TextMuted),
        )
    }
}

/** Строка с тумблером: «Случайное нажатие». */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    color: Color = Palette.SurfaceHigh,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        borderColor = Color.Transparent,
        borderWidth = 0.dp,
        corner = 14.dp,
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = SesameText.Caption.copy(fontSize = 12.sp),
                        color = Palette.TextDim,
                    )
                }
            }
            SesameToggle(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Поле ввода макета: плоский прямоугольник с границей, без «плавающей» подписи.
 *
 * Подпись стоит над полем отдельной строкой, как на макете, — иначе она ездит
 * при фокусе и меняет высоту строки.
 */
@Composable
fun SesameField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = Palette.TextMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            SesameSurface(
                modifier = Modifier.weight(1f),
                color = Palette.Surface,
                borderColor = Palette.BorderStrong,
                borderWidth = Dimens.HairlineWidth,
                corner = 15.dp,
            ) {
                val style = if (mono) {
                    SesameText.Mono14.copy(fontSize = 16.sp)
                } else {
                    SesameText.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                // Своя палитра выделения: material-овская по умолчанию розовато-
                // фиолетовая и на янтарном экране выглядит чужой.
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = Palette.Amber,
                        backgroundColor = Palette.Amber.copy(alpha = 0.3f),
                    ),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        textStyle = style.copy(color = Palette.TextPrimary),
                        singleLine = singleLine,
                        keyboardOptions = keyboardOptions,
                        cursorBrush = SolidColor(Palette.Amber),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty() && placeholder != null) {
                                    Text(placeholder, style = style, color = Palette.TextDim)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            trailing?.invoke()
        }
        if (hint != null) {
            Text(
                text = hint,
                style = SesameText.Caption.copy(fontSize = 12.sp),
                color = Palette.TextDim,
            )
        }
    }
}

/** Прямоугольная кнопка макета: заливка или контур, без теней и пилюль Material. */
@Composable
fun SesameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Palette.Amber,
    content: Color = Palette.OnAmber,
    border: Color? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    corner: Dp = 18.dp,
    style: TextStyle = SesameText.RowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
) {
    SesameSurface(
        modifier = modifier.height(height).alpha(if (enabled) 1f else 0.4f),
        color = container,
        borderColor = border ?: Color.Transparent,
        borderWidth = if (border != null) Dimens.BorderWidth else 0.dp,
        corner = corner,
        contentColor = content,
        onClick = if (enabled) onClick else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Квадратная кнопка-иконка в шапке экрана. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    corner: Dp = 14.dp,
    enabled: Boolean = true,
    tint: Color = Palette.TextPrimary,
) {
    SesameSurface(
        modifier = modifier.size(size).alpha(if (enabled) 1f else 0.4f),
        color = Palette.Surface,
        borderColor = Palette.BorderStrong,
        borderWidth = Dimens.HairlineWidth,
        corner = corner,
        onClick = if (enabled) onClick else null,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Плашка-предупреждение: тревожная или спокойная.
 *
 * Тем же блоком на макете нарисованы «Сбор данных неполный» на экране разрешений
 * и «нажато в 4,2 км от дома» в правке метки.
 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = Palette.Record,
    container: Color = Tones.AlertSurface,
    borderColor: Color = Palette.RecordOutline,
    textColor: Color = Palette.RecordMuted,
    icon: ImageVector = SesameIcons.Alert,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        borderColor = borderColor,
        borderWidth = Dimens.HairlineWidth,
        corner = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp).padding(top = 1.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                if (title != null) {
                    Text(title, style = SesameText.CardTitle, color = Palette.TextPrimary)
                }
                Text(text, style = SesameText.Caption, color = textColor)
            }
        }
    }
}

/** Мелкая подпись-сноска под разделом. */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 2.dp, vertical = Dimens.SpaceS),
        style = SesameText.Caption.copy(fontSize = 12.sp),
        color = Palette.TextDim,
    )
}
