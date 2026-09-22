package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText

/**
 * Нижняя навигация макета.
 *
 * Своя, а не `NavigationBar` из Material 3. У материальной полосы есть то, чего
 * на макете нет и что здесь мешает: контейнер-«пилюля» под активной иконкой,
 * собственный фон с тональной подсветкой и высота 80 dp с отступами под неё.
 * Макет требует ровную полосу 76 dp на фоне экрана, отделённую волосяной чертой,
 * с янтарным активным пунктом — это проще нарисовать, чем перекрыть.
 */
@Composable
fun SesameBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(Palette.Background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.HairlineWidth)
                .background(Palette.NavBorder),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(Dimens.NavBarHeight),
            content = content,
        )
    }
}

/** Один пункт: иконка и подпись, активный — янтарный. */
@Composable
fun RowScope.SesameBottomBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) Palette.Amber else Palette.TextDim
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(Dimens.NavIcon),
        )
        Text(
            text = label,
            style = SesameText.NavLabel.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = color,
        )
    }
}
