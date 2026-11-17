package com.vlad230596.sesame.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.vlad230596.sesame.ui.car.CarScreen
import com.vlad230596.sesame.ui.common.SesameBottomBar
import com.vlad230596.sesame.ui.common.SesameBottomBarItem
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.home.HomeScreen
import com.vlad230596.sesame.ui.settings.SettingsScreen
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственная Activity приложения. Две вкладки: Главная и Настройки. Журнал
 * проездов («История» по ТЗ §4.7) живёт внутри настроек: разметка стала
 * необязательной, и место в нижней навигации ему больше не нужно.
 *
 * Этот файл держит только навигационную оболочку и ничего не знает о содержимом
 * вкладок: каждый экран живёт в своём пакете, сам рисует свой заголовок и сам
 * разбирается со своими отступами.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема у приложения одна — тёмная (см. SesameTheme), поэтому системные
        // полосы задаются явно светлым содержимым на прозрачном фоне, а не
        // отдаются на откуп ночному режиму системы.
        val bars = SystemBarStyle.dark(Palette.Background.toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        setContent {
            SesameTheme {
                SesameApp()
            }
        }
    }
}

private enum class SesameTab(
    val title: String,
    val icon: ImageVector,
) {
    HOME("Главная", SesameIcons.Home),
    SETTINGS("Настройки", SesameIcons.Settings),
}

@Composable
private fun SesameApp() {
    var selected by rememberSaveable { mutableStateOf(SesameTab.HOME) }

    // «Где машина» (§4.1) открывается и с главной, и из настроек, поэтому живёт
    // здесь, поверх вкладок, а не вложенным экраном одной из них. Нижняя полоса
    // остаётся: тап по любой вкладке закрывает карту.
    var carVisible by rememberSaveable { mutableStateOf(false) }

    // Scaffold здесь больше не нужен: своя нижняя полоса и экраны, которые сами
    // считают свои отступы, дешевле, чем перекрывать material-овские умолчания.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background),
    ) {
        val contentModifier = Modifier
            .fillMaxWidth()
            .weight(1f)

        if (carVisible) {
            CarScreen(onBack = { carVisible = false }, modifier = contentModifier)
        } else {
            when (selected) {
                SesameTab.HOME -> HomeScreen(
                    modifier = contentModifier,
                    onOpenCar = { carVisible = true },
                )
                SesameTab.SETTINGS -> SettingsScreen(contentModifier, onOpenCar = { carVisible = true })
            }
        }

        SesameBottomBar {
            SesameTab.entries.forEach { tab ->
                SesameBottomBarItem(
                    icon = tab.icon,
                    label = tab.title,
                    selected = tab == selected,
                    onClick = {
                        selected = tab
                        carVisible = false
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SesameAppPreview() {
    SesameTheme {
        SesameApp()
    }
}
