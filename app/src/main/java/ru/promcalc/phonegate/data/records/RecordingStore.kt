package ru.promcalc.phonegate.data.records

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Файлы записей разговоров: `files/records/rec-<дата>-sim<N>.m4a`.
 *
 * Хранятся последние `keepRecordings` файлов, остальные удаляются — иначе записи
 * разговоров быстро займут всё свободное место.
 */
class RecordingStore(
    context: Context,
    private val log: LogRepository,
) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    /** Новый файл для записи (файл ещё не создан). */
    fun newFile(simSlot: Int, atMs: Long): File {
        val stamp = SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(atMs))
        return File(dir, "rec-$stamp-sim${simSlot + 1}.m4a")
    }

    /** Записи от старых к новым. */
    fun list(): List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .sortedBy { it.lastModified() }

    /** Удалить старые записи, оставив последние [keep]. Возвращает число удалённых файлов. */
    fun prune(keep: Int): Int {
        if (keep < 0) return 0
        val files = list()
        if (files.size <= keep) return 0

        val toDelete = files.take(files.size - keep)
        var deleted = 0
        toDelete.forEach { file ->
            if (file.delete()) {
                deleted++
            } else {
                log.warn(LogCategory.CALL_IN, "Не удалось удалить старую запись ${file.name}")
            }
        }
        if (deleted > 0) {
            log.info(LogCategory.CALL_IN, "Удалено старых записей: $deleted")
        }
        return deleted
    }

    /** Каталог записей (создаётся при необходимости). */
    fun ensureDir(): File {
        dir.mkdirs()
        return dir
    }

    private companion object {
        const val DIR_NAME = "records"
        const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"
    }
}
