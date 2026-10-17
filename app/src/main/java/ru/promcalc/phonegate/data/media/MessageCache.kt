package ru.promcalc.phonegate.data.media

import android.content.Context
import android.net.Uri
import java.io.File
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Локальная копия mp3-сообщения.
 *
 * Файл выбирается пользователем через SAF, и играть прямо из документа провайдера ненадёжно
 * (не все провайдеры отдают дескриптор, пригодный для `MediaPlayer`). Поэтому файл один раз
 * копируется в `files/message-cache`, а рядом лежит отметка, для какого URI сделана копия:
 * если пользователь выберет другой файл, копия обновится.
 */
class MessageCache(
    context: Context,
    private val log: LogRepository,
) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    /** Готовый к воспроизведению файл или `null`, если сообщение недоступно. */
    fun prepare(simSlot: Int, uri: Uri): File? {
        val target = File(dir, "message-sim${simSlot + 1}.mp3")
        val marker = File(dir, "message-sim${simSlot + 1}.uri")

        val cachedFor = if (marker.exists()) {
            runCatching { marker.readText().trim() }.getOrDefault("")
        } else {
            ""
        }
        if (target.exists() && target.length() > 0 && cachedFor == uri.toString()) {
            return target
        }

        return try {
            dir.mkdirs()
            val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false

            if (!copied || target.length() <= 0) {
                log.warn(LogCategory.CALL_IN, "Сообщение для SIM ${simSlot + 1} пустое или недоступно", uri.toString())
                target.delete()
                null
            } else {
                marker.writeText(uri.toString())
                log.debug(
                    LogCategory.CALL_IN,
                    "Сообщение для SIM ${simSlot + 1} подготовлено (${target.length()} байт)",
                )
                target
            }
        } catch (e: Exception) {
            log.error(
                LogCategory.CALL_IN,
                "Не удалось прочитать выбранное сообщение для SIM ${simSlot + 1}",
                e.toString(),
            )
            target.delete()
            null
        }
    }

    private companion object {
        const val DIR_NAME = "message-cache"
    }
}
