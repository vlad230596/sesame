package com.vlad230596.sesame.ui.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.GroupCaption
import com.vlad230596.sesame.ui.common.GroupDivider
import com.vlad230596.sesame.ui.common.GroupRow
import com.vlad230596.sesame.ui.common.NoticeBanner
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameIcons
import com.vlad230596.sesame.ui.common.SesameSurface
import com.vlad230596.sesame.ui.common.SettingsGroup
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import com.vlad230596.sesame.ui.theme.SesameTheme

/** Строка экрана состояния (§8). */
data class PermissionRow(
    val spec: PermissionSpec,
    val granted: Boolean,
)

/**
 * Экран состояния разрешений (§8), по утверждённому макету.
 *
 * **Не визард первого запуска.** Android отзывает разрешения у неиспользуемых
 * приложений, а One UI возвращает приложение в «спящие» после обновлений
 * системы. Сюда заходят через месяц, чтобы увидеть, что именно отвалилось, —
 * поэтому наверху список того, что требует внимания, с кнопкой «Выдать» напротив
 * каждого пункта, а выданное сложено ниже одной спокойной группой.
 */
@Composable
fun PermissionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var systemScreenHint by remember { mutableStateOf<String?>(null) }

    // Состояние перечитывается при каждом возврате на экран: разрешение могли
    // выдать в системных настройках, мимо нашего диалога.
    LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }

    val rows = remember(refreshKey) {
        SesamePermissions.map { spec ->
            PermissionRow(spec = spec, granted = isGranted(context, spec.permission))
        }
    }
    val batteryOk = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    PermissionsContent(
        rows = rows,
        batteryOptimizationDisabled = batteryOk,
        systemScreenHint = systemScreenHint,
        onRequest = { spec ->
            // Диалог может и не появиться: `ACCESS_BACKGROUND_LOCATION` на
            // Android 11+ система показывает только со своего экрана настроек,
            // а дважды отклонённое разрешение она больше не спрашивает вовсе.
            // Поэтому осечка запуска — не ошибка, а повод открыть настройки.
            val launched = runCatching { requestLauncher.launch(spec.permission) }.isSuccess
            if (!launched) {
                val opened = SystemScreens.launchFirstAvailable(
                    context,
                    listOf(SystemScreens.appSettings(context.packageName)),
                )
                systemScreenHint = if (opened) {
                    "Найдите «${spec.title}» в разрешениях приложения."
                } else {
                    "Системный диалог не открылся. Настройки → Приложения → Сезам → Разрешения."
                }
            }
        },
        onOpenBatteryOptimization = {
            val opened = SystemScreens.launchFirstAvailable(
                context,
                SystemScreens.batteryOptimizationIntents(context.packageName),
            )
            systemScreenHint = if (opened) {
                null
            } else {
                "Экран оптимизации батареи не открылся. Откройте вручную: " +
                    "Настройки → Приложения → Сезам → Батарея → Без ограничений."
            }
        },
        onOpenSleepingApps = {
            val opened = SystemScreens.launchFirstAvailable(
                context,
                SystemScreens.sleepingAppsIntents(context.packageName),
            )
            systemScreenHint = if (opened) {
                "Найдите «Спящие приложения» и убедитесь, что Сезама в списке нет."
            } else {
                "Экран Samsung не открылся. Откройте вручную: Настройки → Обслуживание " +
                    "устройства → Батарея → Ограничения фонового использования → Спящие приложения."
            }
        },
        onOpenAppSettings = {
            val opened = SystemScreens.launchFirstAvailable(
                context,
                listOf(SystemScreens.appSettings(context.packageName)),
            )
            if (!opened) systemScreenHint = "Экран настроек приложения недоступен."
        },
        modifier = modifier,
    )
}

@Composable
private fun PermissionsContent(
    rows: List<PermissionRow>,
    batteryOptimizationDisabled: Boolean,
    systemScreenHint: String?,
    onRequest: (PermissionSpec) -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenSleepingApps: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = rows.filter { it.spec.runtime && !it.granted }
    val granted = rows.filter { it.granted }

    // Разрешения, которые выдаются при установке. Если их нет — это не «забыли
    // выдать», а собранный не тот APK, и кнопка «Выдать» тут не поможет.
    val install = rows.filter { !it.spec.runtime && !it.granted }
    val everythingFine = missing.isEmpty() && batteryOptimizationDisabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        if (everythingFine) {
            NoticeBanner(
                title = "Сбор полный",
                text = "Все нужные разрешения выданы, оптимизация батареи отключена. " +
                        "Экран стоит перепроверять после каждого обновления One UI: система " +
                        "отзывает разрешения у неиспользуемых приложений.",
                accent = Palette.Live,
                container = Tones.TealSurface,
                borderColor = Tones.TealBorder,
                textColor = Tones.TealText,
                icon = SesameIcons.Check,
            )
        } else {
            NoticeBanner(
                title = "Сбор данных неполный",
                text = if (missing.any { it.spec.separateStep }) {
                    "Без фоновой геолокации геофенсы не сработают — а именно они " +
                        "проверяют, усыпляет ли One UI приложение."
                } else {
                    "Пока чего-то не хватает, в данных появляются дыры, которые " +
                        "потом необъяснимы."
                },
            )
        }

        if (missing.isNotEmpty() || !batteryOptimizationDisabled) {
            GroupCaption("Требует внимания")

            missing.forEach { row ->
                AttentionRow(
                    title = row.spec.title,
                    subtitle = row.spec.currently?.let { "Сейчас: $it" } ?: row.spec.reason,
                    action = "Выдать",
                    onClick = { onRequest(row.spec) },
                )
            }

            if (!batteryOptimizationDisabled) {
                AttentionRow(
                    title = "Работа без ограничений",
                    subtitle = "Оптимизация батареи включена",
                    action = "Выдать",
                    onClick = onOpenBatteryOptimization,
                )
            }
        }

        if (granted.isNotEmpty()) {
            GroupCaption("Выдано")
            SettingsGroup {
                granted.forEachIndexed { index, row ->
                    if (index > 0) GroupDivider()
                    GrantedRow(row.spec)
                }
            }
        }

        if (install.isNotEmpty()) {
            GroupCaption("Выдаётся при установке")
            SettingsGroup {
                install.forEachIndexed { index, row ->
                    if (index > 0) GroupDivider()
                    GroupRow(minHeight = 54.dp) {
                        Icon(
                            imageVector = SesameIcons.Alert,
                            contentDescription = null,
                            tint = Palette.Record,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = row.spec.title,
                            style = SesameText.Body,
                            color = Palette.TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(row.spec.code, style = SesameText.Mono11, color = Palette.TextDim)
                    }
                }
            }
            FootNote(
                "Эти разрешения система выдаёт при установке. Если их нет — собран " +
                    "не тот APK, и кнопкой это не чинится.",
            )
        }

        GroupCaption("Системные экраны")
        SettingsGroup {
            SystemScreenRow(
                title = "Оптимизация батареи",
                subtitle = if (batteryOptimizationDisabled) {
                    "Отключена — сбор не усыпляется"
                } else {
                    "Включена: One UI усыпляет сбор, и в данных появляются дыры (§11)"
                },
                onClick = onOpenBatteryOptimization,
            )
            GroupDivider()
            SystemScreenRow(
                title = "Спящие приложения Samsung",
                subtitle = "Состояние системой не отдаётся — проверяется только глазами. " +
                    "Сезама в списке быть не должно",
                onClick = onOpenSleepingApps,
            )
            GroupDivider()
            SystemScreenRow(
                title = "Настройки приложения",
                subtitle = "Общий экран Android — на случай, если остальное не открылось",
                onClick = onOpenAppSettings,
            )
        }

        if (systemScreenHint != null) {
            NoticeBanner(
                text = systemScreenHint,
                accent = Palette.Amber,
                container = Palette.AmberSurface,
                borderColor = Palette.AmberBorder,
                textColor = Tones.AmberText,
            )
        }

        FootNote("Уведомление сервиса убирать нельзя: это и есть признак того, что сбор жив.")
    }
}

/**
 * Строка «отвалилось» с кнопкой «Выдать».
 *
 * Отдельной карточкой, а не строкой группы: у неё красная граница и кнопка, и в
 * общей группе она бы потерялась именно там, где её надо заметить в первую
 * очередь.
 */
@Composable
private fun AttentionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    SesameSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Surface,
        borderColor = Palette.RecordOutline,
        corner = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                Text(title, style = SesameText.CardTitle, color = Palette.TextPrimary)
                // Две строки максимум: у «Устройств поблизости» объяснение длиной
                // в абзац, и строка «отвалилось» из-за него перестаёт читаться
                // как строка списка.
                Text(
                    text = subtitle,
                    style = SesameText.Caption,
                    color = Palette.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SesameButton(
                text = action,
                container = Palette.Record,
                content = Palette.OnRecord,
                height = 40.dp,
                corner = 13.dp,
                style = SesameText.Caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun GrantedRow(spec: PermissionSpec) {
    GroupRow(minHeight = 54.dp) {
        Icon(
            imageVector = SesameIcons.Check,
            contentDescription = null,
            tint = Palette.Live,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = spec.title,
            style = SesameText.Body,
            color = Palette.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(spec.code, style = SesameText.Mono11, color = Palette.TextDim)
    }
}

@Composable
private fun SystemScreenRow(title: String, subtitle: String, onClick: () -> Unit) {
    GroupRow(onClick = onClick, minHeight = 62.dp) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = SesameText.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary,
            )
            Text(
                text = subtitle,
                style = SesameText.Caption.copy(fontSize = 12.sp),
                color = Palette.TextDim,
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

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName)
}.getOrNull() ?: false

@Preview(showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
private fun PermissionsPreview() {
    SesameTheme {
        Box(Modifier.background(Palette.Background).padding(horizontal = 20.dp)) {
            PermissionsContent(
                rows = SesamePermissions.mapIndexed { index, spec ->
                    PermissionRow(spec = spec, granted = index != 3)
                },
                batteryOptimizationDisabled = false,
                systemScreenHint = null,
                onRequest = {},
                onOpenBatteryOptimization = {},
                onOpenSleepingApps = {},
                onOpenAppSettings = {},
            )
        }
    }
}
