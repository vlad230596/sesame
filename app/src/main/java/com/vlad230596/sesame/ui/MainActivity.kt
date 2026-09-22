package com.vlad230596.sesame.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /**
     * Запрос с виджета (§4.2): кнопка записи открывает экран выбора метки
     * сессии (§4.3). Активность `singleTask`, поэтому повторное нажатие приходит
     * в [onNewIntent], а не в [onCreate], и обрабатывать надо оба пути.
     */
    private var startSessionRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consume(intent)
        setContent {
            SesameTheme {
                SesameApp(
                    startSessionRequest = startSessionRequests,
                    onStartSessionHandled = { startSessionRequests = 0 },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * Интент обезвреживается сразу: иначе поворот экрана или возврат из фона
     * снова открывал бы выбор метки, хотя пользователь его уже закрыл.
     */
    private fun consume(intent: Intent?) {
        if (intent?.action != ACTION_START_SESSION) return
        intent.action = null
        startSessionRequests++
    }

    companion object {
        /** Открыть выбор метки интенсивной сессии (§4.3). Шлёт виджет. */
        const val ACTION_START_SESSION = "com.vlad230596.sesame.action.OPEN_SESSION_PICKER"
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
private fun SesameApp(
    startSessionRequest: Int = 0,
    onStartSessionHandled: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(SesameTab.HOME) }

    // Выбор метки живёт на главной: переключаем вкладку, иначе запрос с виджета
    // ушёл бы в невидимую композицию, если приложение свернули на «Истории».
    LaunchedEffect(startSessionRequest) {
        if (startSessionRequest > 0) selected = SesameTab.HOME
    }

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
            SesameTab.HOME -> HomeScreen(
                modifier = contentModifier,
                startSessionRequest = startSessionRequest,
                onStartSessionHandled = onStartSessionHandled,
            )
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
