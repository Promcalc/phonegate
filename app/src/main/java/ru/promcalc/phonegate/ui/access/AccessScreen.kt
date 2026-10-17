package ru.promcalc.phonegate.ui.access

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.promcalc.phonegate.ui.common.SectionTitle

/**
 * Экран доступа: показывает, что приложению уже разрешено, и даёт выдать остальное.
 *
 * Состояние перечитывается при возвращении в окно — системные настройки закрываются не нами,
 * и без этого список остался бы устаревшим.
 */
@Composable
fun AccessScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshToken++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = remember(refreshToken) { AccessChecks.runtimePermissions(context) }
    val special = remember(refreshToken) { AccessChecks.specialAccess(context) }
    val notificationsEnabled = remember(refreshToken) { AccessChecks.notificationsEnabled(context) }
    val missing = permissions.filterNot { it.granted }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = when {
                    missing.isEmpty() && special.all { it.granted } && notificationsEnabled ->
                        "Все нужные доступы выданы"

                    else -> "Приложению нужны доступы, иначе часть сценариев работать не будет"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (missing.isNotEmpty()) {
            item {
                Button(
                    onClick = { permissionLauncher.launch(missing.map { it.permission }.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Выдать разрешения (${missing.size})")
                }
            }
        }

        item { SectionTitle("Разрешения") }
        items(permissions.size) { index ->
            val permission = permissions[index]
            AccessRow(
                title = permission.title,
                explanation = permission.explanation,
                granted = permission.granted,
                actionTitle = null,
                onAction = null,
            )
        }

        if (!notificationsEnabled) {
            item {
                AccessRow(
                    title = "Уведомления выключены",
                    explanation = "Постоянное уведомление о работе процесса не показывается, " +
                        "проблемы с обменом остаются только в логах",
                    granted = false,
                    actionTitle = "Открыть настройки",
                    onAction = { context.openAppNotificationSettings() },
                )
            }
        }

        item { SectionTitle("Особый доступ") }
        items(special.size) { index ->
            val item = special[index]
            AccessRow(
                title = item.title,
                explanation = item.explanation,
                granted = item.granted,
                actionTitle = if (item.granted) null else "Открыть настройки",
                onAction = item.settingsIntent?.let { intent -> { context.startSafely(intent) } },
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("О чём стоит знать", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Приложение не просит роль «Телефон по умолчанию»: без собственного " +
                            "экранного интерфейса звонка это сломало бы обычные звонки. Снятие трубки " +
                            "работает через разрешение «Ответ на звонки».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "На телефонах Xiaomi, Huawei, Samsung и подобных включите автозапуск " +
                            "и снимите ограничения батареи для Phonegate в настройках прошивки — " +
                            "иначе система остановит фоновый процесс.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessRow(
    title: String,
    explanation: String,
    granted: Boolean,
    actionTitle: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Clear,
            contentDescription = if (granted) "Есть" else "Нет",
            tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionTitle != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionTitle) }
            }
        }
    }
}

private fun Context.startSafely(intent: android.content.Intent) {
    try {
        startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // На части прошивок такого экрана нет — ничего не поделать.
    } catch (_: Exception) {
        // Ничего не делаем: экран настроек просто не открылся.
    }
}

private fun Context.openAppNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
    }
    startSafely(intent)
}
