package com.vlad230596.sesame.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.vlad230596.sesame.ui.history.HistoryScreen
import com.vlad230596.sesame.ui.home.HomeScreen
import com.vlad230596.sesame.ui.settings.SettingsScreen
import com.vlad230596.sesame.ui.theme.SesameTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственная Activity приложения. Три вкладки (ТЗ §4.7): Главная, История,
 * Настройки.
 *
 * Этот файл держит только навигационную оболочку и ничего не знает о содержимом
 * вкладок: каждый экран живёт в своём пакете, сам рисует свой заголовок и
 * получает Modifier с padding от Scaffold.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
    HOME("Главная", Icons.Filled.Home),
    HISTORY("История", Icons.Filled.DateRange),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

@Composable
private fun SesameApp() {
    var selected by rememberSaveable { mutableStateOf(SesameTab.HOME) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                SesameTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(bottom = innerPadding.calculateBottomPadding())

        when (selected) {
            SesameTab.HOME -> HomeScreen(contentModifier)
            SesameTab.HISTORY -> HistoryScreen(contentModifier)
            SesameTab.SETTINGS -> SettingsScreen(contentModifier)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SesameAppPreview() {
    SesameTheme {
        SesameApp()
    }
}
