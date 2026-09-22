package com.vlad230596.sesame.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText

/**
 * Оболочка вложенного экрана: заголовок и стрелка «назад».
 *
 * Вкладки живут в [com.vlad230596.sesame.ui.MainActivity] без NavHost, поэтому
 * вложенные экраны настроек — это состояние внутри вкладки, а не отдельный
 * маршрут. Для двух экранов это честнее целого графа навигации.
 *
 * Шапка своя, а не `TopAppBar`: у материальной свои высота, кегль и фон с
 * тональной подсветкой при прокрутке — на макете шапка это просто строка
 * на фоне экрана.
 */
@Composable
fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Dimens.SpaceS, end = Dimens.ScreenPadding, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Palette.TextPrimary,
                    modifier = Modifier.size(Dimens.NavIcon),
                )
            }
            Text(
                text = title,
                style = SesameText.ScreenTitle,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content(Modifier.padding(horizontal = Dimens.ScreenPadding))
    }
}

/** Заголовок корневого экрана вкладки — тот же, что «Сезам» на главной. */
@Composable
fun TabHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = 24.dp,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SesameText.ScreenTitle, color = Palette.TextPrimary)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) { actions() }
    }
}
