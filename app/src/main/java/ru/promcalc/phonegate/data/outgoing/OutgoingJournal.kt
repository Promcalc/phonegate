package ru.promcalc.phonegate.data.outgoing

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.tasks.TaskKind

/**
 * Запись о действии, которое выполнило приложение.
 *
 * Журнал собственный (а не вычитанный из системных провайдеров), потому что только мы знаем
 * точно, что и когда отправили; отсюда же строится отчёт на второй URL.
 */
@Serializable
data class OutgoingRecord(
    val id: Long,
    /** Время выполнения (мс). */
    val atMs: Long,
    val kind: TaskKind,
    val simSlot: Int,
    val phone: String,
    val text: String = "",
    /** Фактическая длительность разговора в секундах (для звонков). */
    val durationSeconds: Long = 0L,
    val success: Boolean = true,
    val note: String? = null,
)

/**
 * Журнал исходящих действий: `files/outgoing.json`.
 *
 * В отчёт на второй URL попадают только успешные записи (см. docs/api/openapi.yaml);
 * неудачные остаются в журнале и видны в логах.
 */
class OutgoingJournal(context: Context, private val log: LogRepository) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val lock = Any()
    private var records: MutableList<OutgoingRecord> = ArrayList()
    private var nextId: Long = 1L

    init {
        load()
    }

    fun all(): List<OutgoingRecord> = synchronized(lock) { records.toList() }

    /** Записи с идентификатором больше указанного — то, что ещё не уезжало на сервер. */
    fun recordsAfter(id: Long): List<OutgoingRecord> = synchronized(lock) {
        records.filter { it.id > id }
    }

    fun record(
        kind: TaskKind,
        simSlot: Int,
        phone: String,
        text: String,
        atMs: Long,
        durationSeconds: Long = 0L,
        success: Boolean = true,
        note: String? = null,
    ): OutgoingRecord {
        synchronized(lock) {
            val stored = OutgoingRecord(
                id = nextId++,
                atMs = atMs,
                kind = kind,
                simSlot = simSlot,
                phone = phone,
                text = text,
                durationSeconds = durationSeconds,
                success = success,
                note = note,
            )
            records.add(stored)
            pruneLocked()
            persistLocked()
            return stored
        }
    }

    private fun pruneLocked() {
        if (records.size > MAX_RECORDS) {
            records = records.takeLast(MAX_RECORDS).toMutableList()
        }
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            try {
                val parsed = json.decodeFromString<List<OutgoingRecord>>(file.readText())
                records = parsed.toMutableList()
                nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L
            } catch (e: Exception) {
                log.error(LogCategory.SERVICE, "Не удалось прочитать журнал исходящих", e.toString())
                records = ArrayList()
                nextId = 1L
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString<List<OutgoingRecord>>(records))
        } catch (e: Exception) {
            log.error(LogCategory.SERVICE, "Не удалось сохранить журнал исходящих", e.toString())
        }
    }

    private companion object {
        const val FILE_NAME = "outgoing.json"
        const val MAX_RECORDS = 1_000
    }
}
