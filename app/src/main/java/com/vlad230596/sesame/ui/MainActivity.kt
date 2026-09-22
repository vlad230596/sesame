package com.vlad230596.sesame.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.vlad230596.sesame.ui.common.SesameBottomBar
import com.vlad230596.sesame.ui.common.SesameBottomBarItem
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.history.HistoryScreen
import com.vlad230596.sesame.ui.home.HomeScreen
import com.vlad230596.sesame.ui.settings.SettingsScreen
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственная Activity приложения. Три вкладки (ТЗ §4.7): Главная, История,
 * Настройки.
 *
 * Этот файл держит только навигационную оболочку и ничего не знает о содержимом
 * вкладок: каждый экран живёт в своём пакете, сам рисует свой заголовок и сам
 * разбирается со своими отступами.
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
        // Тема у приложения одна — тёмная (см. SesameTheme), поэтому системные
        // полосы задаются явно светлым содержимым на прозрачном фоне, а не
        // отдаются на откуп ночному режиму системы.
        val bars = SystemBarStyle.dark(Palette.Background.toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
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
    HOME("Главная", SesameIcons.Home),
    HISTORY("История", SesameIcons.History),
    SETTINGS("Настройки", SesameIcons.Settings),
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

        when (selected) {
            SesameTab.HOME -> HomeScreen(
                modifier = contentModifier,
                startSessionRequest = startSessionRequest,
                onStartSessionHandled = onStartSessionHandled,
            )
            SesameTab.HISTORY -> HistoryScreen(contentModifier)
            SesameTab.SETTINGS -> SettingsScreen(contentModifier)
        }

        SesameBottomBar {
            SesameTab.entries.forEach { tab ->
                SesameBottomBarItem(
                    icon = tab.icon,
                    label = tab.title,
                    selected = tab == selected,
                    onClick = { selected = tab },
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
