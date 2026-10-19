package ru.promcalc.phonegate.ui.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var confirmClear by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Копируем только видимые записи: если фильтр или поиск скрыл часть выбранных,
    // в буфер не попадёт то, чего пользователь сейчас не видит.
    val selectedEntries = entries.filter { it.id in selectedIds }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun copyEntries(toCopy: List<LogEntry>) {
        if (toCopy.isEmpty()) return
        clipboard.setText(AnnotatedString(buildClipboardText(toCopy)))
        scope.launch {
            snackbarHostState.showSnackbar("Скопировано записей: ${toCopy.size}")
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("Выбрано: ${selectedEntries.size}") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Отменить выбор")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { selectedIds = entries.map { it.id }.toSet() },
                            enabled = entries.isNotEmpty(),
                        ) {
                            Text("Все")
                        }
                        TextButton(
                            onClick = {
                                copyEntries(selectedEntries)
                                exitSelection()
                            },
                            enabled = selectedEntries.isNotEmpty(),
                        ) {
                            Text("Копировать")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Логи") },
                    actions = {
                        TextButton(
                            onClick = { selectionMode = true },
                            enabled = entries.isNotEmpty(),
                        ) {
                            Text("Выбрать")
                        }
                        TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                            Text("Очистить")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        LogRow(
                            entry = entry,
                            selectionMode = selectionMode,
                            selected = entry.id in selectedIds,
                            onToggleSelection = {
                                selectedIds = if (entry.id in selectedIds) {
                                    selectedIds - entry.id
                                } else {
                                    selectedIds + entry.id
                                }
                            },
                            onLongPress = {
                                selectionMode = true
                                selectedIds = selectedIds + entry.id
                            },
                            onCopy = { copyEntries(listOf(entry)) },
                        )
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
                    exitSelection()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    entry: LogEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val hasDetails = !entry.details.isNullOrEmpty()

    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelection()
                    } else if (hasDetails) {
                        expanded = !expanded
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCopy) {
                            Text("Копировать запись")
                        }
                    }
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
