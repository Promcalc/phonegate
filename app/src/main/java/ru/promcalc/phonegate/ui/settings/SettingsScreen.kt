package ru.promcalc.phonegate.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.promcalc.phonegate.core.Files
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.records.RecordingInfo
import ru.promcalc.phonegate.ui.common.IntField
import ru.promcalc.phonegate.ui.common.SectionTitle
import ru.promcalc.phonegate.ui.common.showDateTimePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val context = LocalContext.current

    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pendingTasks by viewModel.pendingTasks.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        // Пока экран открыт, обновляем состояние: фоновый процесс мог отправить данные
        // самостоятельно, и «Последняя успешная отправка» должна это показать.
        while (true) {
            viewModel.refreshSyncState()
            delay(STATUS_REFRESH_MS)
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val pickSim1Message = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachMessage(context, viewModel, simSlot = 0, uri = uri)
    }
    val pickSim2Message = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachMessage(context, viewModel, simSlot = 1, uri = uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                actions = {
                    TextButton(onClick = { viewModel.save() }, enabled = dirty) {
                        Text("Сохранить")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (errors.isNotEmpty()) {
                item { ErrorsCard(errors) }
            }

            item { StatusCard(status, pendingTasks) }

            item { SectionTitle("Обмен с сервером") }
            item {
                Text(
                    text = if (dirty) {
                        "Есть несохранённые изменения — сначала сохраните настройки"
                    } else {
                        "Собрать данные за прошедшее время и отправить их на сервер прямо сейчас"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = { viewModel.sendNow() },
                    enabled = !dirty && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (sending) "Отправка…" else "Отправить сейчас")
                }
            }

            item { SectionTitle("Фоновый процесс") }
            item {
                Text(
                    text = if (draft.serviceEnabled) {
                        "Процесс запущен: обмен идёт с интервалом ${draft.intervalSeconds} с"
                    } else {
                        "Процесс остановлен: обмен не выполняется"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(
                    onClick = { viewModel.setServiceEnabled(!draft.serviceEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (draft.serviceEnabled) "Остановить фоновый процесс" else "Запустить фоновый процесс")
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Запускать при включении телефона")
                        Text(
                            text = if (draft.serviceEnabled) {
                                "После перезагрузки процесс поднимется сам"
                            } else {
                                "Доступно, когда процесс запущен"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.startOnBoot,
                        enabled = draft.serviceEnabled,
                        onCheckedChange = { checked -> viewModel.update { it.copy(startOnBoot = checked) } },
                    )
                }
            }
            item {
                IntField(
                    label = "Интервал запросов, секунд",
                    value = draft.intervalSeconds,
                    onValueChange = { value -> viewModel.update { it.copy(intervalSeconds = value) } },
                    supportingText = "Минимум ${SettingsViewModel.MIN_INTERVAL_SECONDS} секунд",
                )
            }

            item { SectionTitle("Сервер") }
            item {
                OutlinedTextField(
                    value = draft.exchangeUrl,
                    onValueChange = { value -> viewModel.update { it.copy(exchangeUrl = value.trim()) } },
                    label = { Text("URL обмена (приём SMS и звонков, команды)") },
                    supportingText = { Text("POST. В ответ приходят SMS и звонки к исполнению") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.outgoingReportUrl,
                    onValueChange = { value -> viewModel.update { it.copy(outgoingReportUrl = value.trim()) } },
                    label = { Text("URL отчёта об исходящих") },
                    supportingText = { Text("Если пусто — отчёт о том, что сделали сами, не отправляется") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.authHeaderName,
                    onValueChange = { value -> viewModel.update { it.copy(authHeaderName = value.trim()) } },
                    label = { Text("Имя заголовка авторизации") },
                    supportingText = { Text("Пусто — запросы уходят без авторизации") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.authHeaderValue,
                    onValueChange = { value -> viewModel.update { it.copy(authHeaderValue = value) } },
                    label = { Text("Значение заголовка") },
                    supportingText = { Text("Например: Bearer abc123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle("SIM-карты") }
            item {
                OutlinedTextField(
                    value = draft.sim1Phone,
                    onValueChange = { value -> viewModel.update { it.copy(sim1Phone = value.trim()) } },
                    label = { Text("Номер телефона первой SIM") },
                    supportingText = { Text("Используется как ключ отчёта для первой SIM") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.sim2Phone,
                    onValueChange = { value -> viewModel.update { it.copy(sim2Phone = value.trim()) } },
                    label = { Text("Номер телефона второй SIM") },
                    supportingText = { Text("Пусто — вторая SIM в отчёте не участвует") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle("Входящие и исходящие звонки") }
            item {
                IntField(
                    label = "Секунд до снятия трубки (входящий)",
                    value = draft.answerAfterSeconds,
                    onValueChange = { value -> viewModel.update { it.copy(answerAfterSeconds = value) } },
                    supportingText = "0 — снять трубку сразу; счёт идёт от начала вызова",
                )
            }
            item {
                IntField(
                    label = "Максимальная длительность записи, секунд",
                    value = draft.maxRecordSeconds,
                    onValueChange = { value -> viewModel.update { it.copy(maxRecordSeconds = value) } },
                    supportingText = "0 — после сообщения сразу положить трубку",
                )
            }
            item {
                IntField(
                    label = "Секунд до сброса (исходящий)",
                    value = draft.outgoingHangupAfterSeconds,
                    onValueChange = { value -> viewModel.update { it.copy(outgoingHangupAfterSeconds = value) } },
                )
            }

            item { SectionTitle("Сообщения для входящих") }
            item {
                MessageFileRow(
                    title = "mp3 для первой SIM",
                    fileName = draft.sim1MessageName,
                    hasFile = draft.sim1MessageUri.isNotBlank(),
                    onPick = { pickSim1Message.launch(arrayOf("audio/*")) },
                    onClear = { viewModel.clearMessageForSlot(0) },
                )
            }
            item {
                MessageFileRow(
                    title = "mp3 для второй SIM",
                    fileName = draft.sim2MessageName,
                    hasFile = draft.sim2MessageUri.isNotBlank(),
                    onPick = { pickSim2Message.launch(arrayOf("audio/*")) },
                    onClear = { viewModel.clearMessageForSlot(1) },
                )
            }

            item { SectionTitle("Данные") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Начало сбора данных")
                        Text(
                            text = Time.formatLocal(draft.historyStartAtMs),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Что отправлять на сервер: SMS и звонки с этого момента. " +
                                "Изменение сбрасывает курсор и переотправляет данные с новой точки",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showDateTimePicker(context, draft.historyStartAtMs) { picked ->
                                viewModel.setHistoryStartTo(picked)
                            }
                        },
                    ) {
                        Text("Изменить")
                    }
                    TextButton(onClick = { viewModel.setHistoryStartToNow() }) {
                        Text("Сейчас")
                    }
                }
            }
            item {
                IntField(
                    label = "Хранить записей разговора",
                    value = draft.keepRecordings,
                    onValueChange = { value -> viewModel.update { it.copy(keepRecordings = value) } },
                    supportingText = "Старые записи удаляются автоматически",
                )
            }

            item { SectionTitle("Записи разговоров") }
            item {
                Text(
                    text = when {
                        recordings.isEmpty() -> "Записей пока нет"
                        else ->
                            "Всего: ${recordings.size}. Последняя: " +
                                Time.formatLocal(recordings.first().modifiedAtMs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recordings.take(RECORDINGS_PREVIEW).forEach { recording ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recording.file.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${Time.formatLocal(recording.modifiedAtMs)} · ${formatSize(recording.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { openRecording(context, viewModel, recording) }) {
                            Text("Открыть")
                        }
                    }
                }
            }
            if (recordings.size > RECORDINGS_PREVIEW) {
                item {
                    Text(
                        text = "Показаны последние $RECORDINGS_PREVIEW записей из ${recordings.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Сколько записей показывать в списке настроек. */
private const val RECORDINGS_PREVIEW = 5

/** Период обновления карточки «Состояние», пока экран настроек открыт. */
private const val STATUS_REFRESH_MS = 5_000L

private fun openRecording(context: Context, viewModel: SettingsViewModel, recording: RecordingInfo) {
    try {
        val uri = viewModel.recordingUri(recording)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        viewModel.showMessage("Не удалось открыть запись: нужен установленный плеер, поддерживающий m4a")
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= MEGABYTE -> "%.1f МБ".format(bytes.toDouble() / MEGABYTE)
    bytes >= KILOBYTE -> "${bytes / KILOBYTE} КБ"
    else -> "$bytes Б"
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L

private fun attachMessage(context: Context, viewModel: SettingsViewModel, simSlot: Int, uri: Uri) {
    val persisted = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (e: SecurityException) {
        // Некоторые провайдеры не дают постоянный доступ: файл останется доступен,
        // пока приложение не будет перезапущено. Сообщаем пользователю через поле имени.
        android.util.Log.w("PhoneGate.Settings", "Не удалось закрепить доступ к $uri", e)
        false
    }
    val name = Files.displayNameOf(context, uri).let {
        if (persisted) it else "$it (доступ не закреплён)"
    }
    viewModel.setMessageForSlot(simSlot, uri.toString(), name)
}

@Composable
private fun ErrorsCard(problems: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Проверьте настройки",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            problems.forEach { problem ->
                Text(
                    text = "• $problem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: SyncStatus, pendingTasks: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Состояние", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (status.lastSuccessAtMs > 0L) {
                    "Последняя успешная отправка: ${Time.formatLocal(status.lastSuccessAtMs)}"
                } else {
                    "Успешных отправок ещё не было"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (status.windowStartAtMs > 0L) {
                    "Следующая отправка возьмёт данные с ${Time.formatLocal(status.windowStartAtMs)}"
                } else {
                    "Точка начала сбора данных ещё не задана"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Курсоры: SMS №${status.lastSmsId}, звонки №${status.lastCallId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Задач в очереди: $pendingTasks",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MessageFileRow(
    title: String,
    fileName: String,
    hasFile: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title)
        Text(
            text = if (hasFile) fileName else "файл не выбран — входящий звонок будет сброшен",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPick) { Text("Выбрать") }
            if (hasFile) {
                TextButton(onClick = onClear) { Text("Очистить") }
            }
        }
    }
}
