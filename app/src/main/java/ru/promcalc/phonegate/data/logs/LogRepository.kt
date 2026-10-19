package ru.promcalc.phonegate.data.logs

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.promcalc.phonegate.core.Time

/**
 * Журнал работы приложения: кольцевой буфер в памяти для интерфейса плюс дозапись в файл.
 *
 * Формат файла — JSONL (`gateway-ГГГГ-ММ-ДД.jsonl`), по объекту на строку: так его можно
 * читать глазами и без потерь восстанавливать в память после перезапуска. Файлы старше
 * [KEEP_FILES_DAYS] дней удаляются.
 *
 * Запись в файл идёт в отдельном потоке, поэтому логирование не тормозит службу.
 */
class LogRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val logDir = File(appContext.filesDir, DIRECTORY)

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Записи по возрастанию времени. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonegate-logs").apply { isDaemon = true }
    }

    private var nextId: Long = 1L

    init {
        ioExecutor.execute {
            pruneOldFiles()
            prefill()
            publish()
        }
    }

    fun debug(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.DEBUG, category, message, details)

    fun info(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.INFO, category, message, details)

    fun warn(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.WARN, category, message, details)

    fun error(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.ERROR, category, message, details)

    fun log(level: LogLevel, category: LogCategory, message: String, details: String? = null) {
        val now = System.currentTimeMillis()
        val entry: LogEntry
        synchronized(lock) {
            entry = LogEntry(
                id = nextId++,
                atMs = now,
                level = level,
                category = category,
                message = message.take(MAX_MESSAGE_LENGTH),
                // Помечаем обрезку: иначе в интерфейсе кажется, что в запросе не было данных,
                // хотя на сервер ушёл полный JSON.
                details = details?.let { text ->
                    if (text.length > MAX_DETAILS_LENGTH) {
                        text.take(MAX_DETAILS_LENGTH) + "\n… (подробности обрезаны)"
                    } else {
                        text
                    }
                },
            )
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES_IN_MEMORY) {
                buffer.removeFirst()
            }
        }
        publish()
        appendToFile(entry)
    }

    /** Очистка: и память, и файлы. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        publish()
        ioExecutor.execute {
            logDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun publish() {
        val snapshot = synchronized(lock) { buffer.toList() }
        _entries.value = snapshot
    }

    private fun appendToFile(entry: LogEntry) {
        ioExecutor.execute {
            try {
                if (!logDir.exists()) logDir.mkdirs()
                fileFor(entry.atMs).appendText(json.encodeToString(entry) + "\n")
            } catch (_: Exception) {
                // Логирование не должно ломать работу приложения: при сбое записи просто
                // теряем строку в файле, запись остаётся в памяти.
            }
        }
    }

    private fun fileFor(epochMs: Long): File = File(logDir, "gateway-${Time.formatDate(epochMs)}.jsonl")

    private fun prefill() {
        val files = logDir.listFiles()?.sortedBy { it.name } ?: return
        val lastFile = files.lastOrNull() ?: return
        val tail = try {
            lastFile.readLines().takeLast(PREFILL_LINES)
        } catch (_: Exception) {
            emptyList()
        }
        val restored = tail.mapNotNull { line ->
            try {
                json.decodeFromString<LogEntry>(line)
            } catch (_: Exception) {
                null
            }
        }
        if (restored.isEmpty()) return

        synchronized(lock) {
            // Восстановленным записям выдаём отрицательные идентификаторы: пока файл читался,
            // счётчик новых записей уже мог выдать положительные номера (первая запись
            // «Приложение запущено» пишется сразу после создания репозитория). Совпадение
            // идентификаторов ломало ключи списка на экране логов и роняло приложение.
            val restoredWithLocalIds = restored.mapIndexed { index, entry ->
                entry.copy(id = -(index + 1L))
            }
            // В буфере записи лежат от старых к новым, поэтому восстановленные идут в начало.
            buffer.addAll(0, restoredWithLocalIds)
            while (buffer.size > MAX_ENTRIES_IN_MEMORY) {
                buffer.removeFirst()
            }
        }
    }

    private fun pruneOldFiles() {
        val threshold = System.currentTimeMillis() - KEEP_FILES_DAYS * MILLIS_PER_DAY
        logDir.listFiles()?.forEach { file ->
            if (file.lastModified() < threshold) file.delete()
        }
    }

    private companion object {
        const val DIRECTORY = "logs"
        const val MAX_ENTRIES_IN_MEMORY = 1_000
        const val PREFILL_LINES = 300
        const val MAX_MESSAGE_LENGTH = 500
        const val MAX_DETAILS_LENGTH = 16_384
        const val KEEP_FILES_DAYS = 7
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
