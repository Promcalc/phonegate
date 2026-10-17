package ru.promcalc.phonegate.data.tasks

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Очередь задач из ответа сервера.
 *
 * Хранится в `files/tasks.json`. Задача не добавляется, если такая же (тот же тип, номер,
 * текст и время) уже есть в очереди или уже выполнена: сервер может повторять свои команды
 * в каждом ответе, а одно и то же SMS нельзя отправить дважды.
 */
class TaskStore(context: Context, private val log: LogRepository) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val lock = Any()
    private var tasks: MutableList<OutgoingTask> = ArrayList()
    private var nextId: Long = 1L

    init {
        load()
    }

    /** Все задачи, свежие в конце. */
    fun all(): List<OutgoingTask> = synchronized(lock) { tasks.toList() }

    fun pending(): List<OutgoingTask> = synchronized(lock) { tasks.filter { it.state == TaskState.PENDING } }

    /** Ближайшая по времени ожидающая задача или `null`. */
    fun nextPending(): OutgoingTask? = synchronized(lock) {
        tasks.filter { it.state == TaskState.PENDING }.minByOrNull { it.atMs }
    }

    fun byId(id: Long): OutgoingTask? = synchronized(lock) { tasks.firstOrNull { it.id == id } }

    /**
     * Добавить задачу, если такой ещё не было.
     * @return `true`, если задача добавлена.
     */
    fun addIfAbsent(
        kind: TaskKind,
        simSlot: Int,
        phone: String,
        text: String,
        atMs: Long,
        createdAtMs: Long,
    ): Boolean {
        val candidate = OutgoingTask(
            id = 0L,
            kind = kind,
            simSlot = simSlot,
            phone = phone,
            text = text,
            atMs = atMs,
            createdAtMs = createdAtMs,
        )
        synchronized(lock) {
            // Проваленные задачи в проверке не участвуют: если сервер пришлёт ту же команду
            // повторно, у пользователя появится шанс исполнить её после исправления помехи.
            if (tasks.any { it.state != TaskState.FAILED && it.sameRequestAs(candidate) }) return false
            val stored = candidate.copy(id = nextId++)
            tasks.add(stored)
            persistLocked()
            return true
        }
    }

    fun markDone(id: Long, finishedAtMs: Long) = update(id) { task ->
        task.copy(state = TaskState.DONE, finishedAtMs = finishedAtMs, attempts = task.attempts + 1, lastError = null)
    }

    fun markFailed(id: Long, finishedAtMs: Long, error: String) = update(id) { task ->
        task.copy(state = TaskState.FAILED, finishedAtMs = finishedAtMs, attempts = task.attempts + 1, lastError = error)
    }

    /** Вернуть задачу в очередь: сеть/условия не позволили исполнить её сейчас. */
    fun markPending(id: Long, error: String) = update(id) { task ->
        task.copy(state = TaskState.PENDING, attempts = task.attempts + 1, lastError = error)
    }

    private fun update(id: Long, transform: (OutgoingTask) -> OutgoingTask) {
        synchronized(lock) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            tasks[index] = transform(tasks[index])
            pruneLocked()
            persistLocked()
        }
    }

    /** Старые завершённые задачи не нужны: держим только историю последних дней. */
    private fun pruneLocked() {
        val threshold = System.currentTimeMillis() - KEEP_FINISHED_DAYS * MILLIS_PER_DAY
        tasks.removeAll { task ->
            task.state != TaskState.PENDING && (task.finishedAtMs ?: 0L) < threshold
        }
        if (tasks.size > MAX_TASKS) {
            val finished = tasks.filter { it.state != TaskState.PENDING }.sortedBy { it.finishedAtMs ?: 0L }
            val toRemove = tasks.size - MAX_TASKS
            val removeIds = finished.take(toRemove).map { it.id }.toSet()
            tasks.removeAll { it.id in removeIds }
        }
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            try {
                val parsed = json.decodeFromString<List<OutgoingTask>>(file.readText())
                tasks = parsed.toMutableList()
                nextId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
            } catch (e: Exception) {
                log.error(LogCategory.SERVICE, "Не удалось прочитать очередь задач, начинаем с пустой", e.toString())
                tasks = ArrayList()
                nextId = 1L
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString<List<OutgoingTask>>(tasks))
        } catch (e: Exception) {
            log.error(LogCategory.SERVICE, "Не удалось сохранить очередь задач", e.toString())
        }
    }

    private companion object {
        const val FILE_NAME = "tasks.json"
        const val KEEP_FINISHED_DAYS = 7
        const val MAX_TASKS = 500
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
