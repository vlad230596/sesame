package com.vlad230596.sesame.ui.permissions

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vlad230596.sesame.ui.common.InfoRow
import com.vlad230596.sesame.ui.common.SectionCard
import com.vlad230596.sesame.ui.common.SectionTitle
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.SesameTheme

/** Строка экрана состояния (§8). */
data class PermissionRow(
    val spec: PermissionSpec,
    val granted: Boolean,
)

/**
 * Экран состояния разрешений (§8).
 *
 * Не визард первого запуска: Android отзывает разрешения у неиспользуемых
 * приложений, а One UI возвращает приложение в «спящие» после обновлений
 * системы. Сюда заходят через месяц, чтобы увидеть, что именно отвалилось, —
 * поэтому здесь список со статусами, а не последовательность шагов.
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
        onRequest = { spec -> requestLauncher.launch(spec.permission) },
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
    val missing = rows.count { it.spec.runtime && !it.granted }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            bottom = Dimens.SpaceXl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        item {
            SectionCard(
                containerColor = if (missing == 0) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                InfoRow(
                    text = if (missing == 0) {
                        "Все нужные разрешения выданы"
                    } else {
                        "Не выдано разрешений: $missing"
                    },
                    icon = if (missing == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    emphasised = true,
                    tint = if (missing == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Text(
                    "Разрешения отзываются системой у неиспользуемых приложений — " +
                        "экран стоит проверять после каждого обновления One UI.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { SectionTitle("Разрешения") }

        items(rows, key = { it.spec.permission }) { row ->
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.spec.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            row.spec.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                row.granted -> "Выдано"
                                !row.spec.runtime -> "Выдаётся при установке — проверьте сборку"
                                else -> "Не выдано"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (!row.granted && row.spec.runtime) {
                        Button(onClick = { onRequest(row.spec) }) { Text("Выдать") }
                    }
                }
            }
        }

        item { SectionTitle("Системные экраны") }

        item {
            SectionCard {
                Text("Оптимизация батареи", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Пока оптимизация включена, One UI усыпляет сбор и в данных " +
                        "появляются дыры (§11).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (batteryOptimizationDisabled) "Отключена" else "Включена",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (batteryOptimizationDisabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Button(
                    onClick = onOpenBatteryOptimization,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (batteryOptimizationDisabled) "Открыть экран" else "Отключить") }
            }
        }

        item {
            SectionCard {
                Text("Спящие приложения Samsung", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Состояние этого списка системой не отдаётся — проверяется только " +
                        "глазами. Сезама в списке быть не должно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenSleepingApps,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Открыть экран Samsung") }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Настройки приложения") }
            }
        }

        if (systemScreenHint != null) {
            item {
                SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(systemScreenHint, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { androidx.compose.foundation.layout.Spacer(Modifier.height(Dimens.SpaceM)) }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    context.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName)
}.getOrNull() ?: false

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PermissionsPreview() {
    SesameTheme {
        PermissionsContent(
            rows = SesamePermissions.mapIndexed { index, spec ->
                PermissionRow(spec = spec, granted = index % 3 != 0)
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
