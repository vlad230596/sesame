package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText

/**
 * Поверхности макета.
 *
 * Карточка в макете — это не Material `Card` с тенью, а плоский прямоугольник
 * фоном #191B20 и волосяной границей #22252B: на чёрном фоне тень не видна, а
 * граница видна. Поэтому свои примитивы, а не `elevation`.
 */

/** Плоская карточка макета: фон, волосяная граница, скругление. */
@Composable
fun SesameSurface(
    modifier: Modifier = Modifier,
    color: Color = Palette.Surface,
    borderColor: Color = Palette.Border,
    borderWidth: Dp = Dimens.HairlineWidth,
    corner: Dp = Dimens.CardCorner,
    contentColor: Color = Palette.TextPrimary,
    onClick: (() -> Unit)? = null,
    // Выравнивание содержимого внутри поверхности. Нужно кнопке: она бывает и
    // растянутой на всю ширину, и по размеру подписи, и центрировать текст
    // изнутри — через `fillMaxSize` — нельзя: в `Row` такой ребёнок забирает всю
    // оставшуюся ширину и вытесняет соседей в ноль.
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    var box = modifier
        .clip(shape)
        .background(color)
    if (borderWidth > 0.dp) box = box.border(borderWidth, borderColor, shape)
    if (onClick != null) box = box.clickable(onClick = onClick)

    Box(box, contentAlignment = contentAlignment) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Карточка со стандартными внутренними отступами и вертикальным ритмом. */
@Composable
fun SesameCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.Surface,
    borderColor: Color = Palette.Border,
    corner: Dp = Dimens.CardCorner,
    contentColor: Color = Palette.TextPrimary,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 14.dp,
    gap: Dp = Dimens.SpaceS + 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        borderColor = borderColor,
        corner = corner,
        contentColor = contentColor,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

/**
 * Цветная точка состояния.
 *
 * Зелёная — сбор жив, красная — идёт запись, янтарная — есть что разобрать.
 * Единственный элемент интерфейса, который читается боковым зрением.
 */
@Composable
fun StatusDot(color: Color, size: Dp = Dimens.DotSmall, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** Тонкая полоса прогресса макета: свой трек, свой цвет, без анимации Material. */
@Composable
fun ThinProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Palette.Teal,
    trackColor: Color = Palette.SurfaceHigh,
    thickness: Dp = Dimens.ProgressThin,
) {
    val shape = RoundedCornerShape(thickness / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(shape)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(thickness)
                .clip(shape)
                .background(color),
        )
    }
}

/**
 * Строка-плашка: точка слева, текст, действие и стрелка справа.
 *
 * Так на макете выглядят «N меток без подтверждения» и «не хватает разрешений».
 * Высокая, потому что в неё надо попадать, но заметно тише кнопок шлагбаумов:
 * это сообщение, а не то, ради чего приложение открывают.
 */
@Composable
fun AccentStrip(
    text: String,
    action: String,
    dotColor: Color,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SesameSurface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        borderColor = borderColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            StatusDot(dotColor, Dimens.DotMedium)
            Text(
                text = text,
                style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = action,
                style = SesameText.Caption.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accentColor,
            )
            Icon(
                imageVector = SesameIcons.ChevronRight,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Строка «подпись слева — число справа» внутри карточки. */
@Composable
fun CardHeadRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Palette.TextMuted,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SesameText.CardTitle, color = Palette.TextPrimary)
        extra()
        Box(Modifier.weight(1f))
        Text(value, style = SesameText.Mono13, color = valueColor)
    }
}

