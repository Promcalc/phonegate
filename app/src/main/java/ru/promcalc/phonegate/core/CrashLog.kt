package ru.promcalc.phonegate.core

import android.content.Context
import java.io.File

/**
 * Локальное сохранение необработанных исключений.
 *
 * Системный диалог «Приложение остановлено» отправляет отчёт в сервис Google, а не разработчику,
 * и после перезапуска трейс уже не посмотреть. Поэтому перед передачей ошибки системному
 * обработчику (он показывает диалог) мы пишем её в `files/crash/last-crash.txt`. При следующем
 * запуске файл попадает в лог приложения под категорией «Сбои» и удаляется.
 */
object CrashLog {

    private const val DIRECTORY = "crash"
    private const val FILE_NAME = "last-crash.txt"

    fun file(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    fun write(context: Context, thread: Thread, error: Throwable) {
        try {
            val file = file(context)
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    appendLine("Время: ${Time.formatLocal(System.currentTimeMillis())}")
                    appendLine("Поток: ${thread.name}")
                    appendLine("Android: ${android.os.Build.VERSION.SDK_INT}, устройство: ${android.os.Build.MODEL}")
                    appendLine()
                    appendLine(error.stackTraceToString())
                },
            )
        } catch (_: Throwable) {
            // Сбой при записи отчёта не должен подменять исходную ошибку.
        }
    }

    /** Отдать сохранённый трейс и удалить файл: показываем его в логах один раз. */
    fun readAndDelete(context: Context): String? {
        val file = file(context)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            null
        }
        file.delete()
        return text
    }
}
