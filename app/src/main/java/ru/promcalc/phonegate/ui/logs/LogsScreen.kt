package ru.promcalc.phonegate.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogEntry
import ru.promcalc.phonegate.data.logs.LogLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val viewModel: LogsViewModel = viewModel()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val selectedCategories by viewModel.selectedCategories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи") },
                actions = {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(buildClipboardText(entries))) },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Text("Копировать")
                    }
                    TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                        Text("Очистить")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Поиск по сообщениям и подробностям") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategories.isEmpty(),
                    onClick = { viewModel.showAllCategories() },
                    label = { Text("Все") },
                )
                LogCategory.entries.forEach { category ->
                    FilterChip(
                        selected = category in selectedCategories,
                        onClick = { viewModel.toggleCategory(category) },
                        label = { Text(category.title) },
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Записей нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить логи?") },
            text = { Text("Будут удалены и записи в памяти, и файлы логов на устройстве.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val hasDetails = !entry.details.isNullOrEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Time.formatLocal(entry.atMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  ${levelLabel(entry.level)} · ${entry.category.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = levelColor(entry.level),
                )
            }
            Text(text = entry.message, style = MaterialTheme.typography.bodyMedium)
            if (hasDetails && expanded) {
                Text(
                    text = entry.details.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            } else if (hasDetails) {
                Text(
                    text = "Показать подробности",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun levelLabel(level: LogLevel): String = when (level) {
    LogLevel.DEBUG -> "DEBUG"
    LogLevel.INFO -> "INFO"
    LogLevel.WARN -> "WARN"
    LogLevel.ERROR -> "ОШИБКА"
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun buildClipboardText(entries: List<LogEntry>): String =
    entries.asReversed().joinToString(separator = "\n") { entry ->
        val header = "${Time.formatLocal(entry.atMs)} [${levelLabel(entry.level)}] ${entry.category.title}: ${entry.message}"
        val details = entry.details
        if (details.isNullOrEmpty()) {
            header
        } else {
            header + "\n" + details.lines().joinToString(separator = "\n") { "    $it" }
        }
    }
