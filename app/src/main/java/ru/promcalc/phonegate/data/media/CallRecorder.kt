package ru.promcalc.phonegate.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Запись ответа абонента через микрофон.
 *
 * Пишем `MIC`: модем во время разговора микрофон обычно отдаёт приложению (в отличие от
 * `VOICE_CALL`, который закрыт с Android 10). Если `MIC` занят, пробуем
 * `VOICE_RECOGNITION`, затем `DEFAULT` — вариант из плана (раздел рисков).
 *
 * Запись ведётся в `m4a` (AAC), чтобы файл занимал мало места и проигрывался любым плеером.
 */
class CallRecorder(
    context: Context,
    private val log: LogRepository,
) {

    private val appContext = context.applicationContext

    data class Result(val recorded: Boolean, val durationSeconds: Long, val error: String? = null)

    fun record(target: File, maxDurationMs: Long, shouldStop: () -> Boolean): Result {
        target.parentFile?.mkdirs()

        var lastError = "не удалось начать запись"
        for (source in SOURCES) {
            val recorder = newRecorder()
            try {
                recorder.setAudioSource(source)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(SAMPLE_RATE)
                recorder.setAudioEncodingBitRate(BIT_RATE)
                recorder.setOutputFile(target.absolutePath)
                recorder.prepare()
                recorder.start()
            } catch (e: Exception) {
                lastError = "${sourceName(source)}: ${e.message ?: e.javaClass.simpleName}"
                log.warn(LogCategory.CALL_IN, "Источник звука «${sourceName(source)}» недоступен", e.toString())
                releaseQuietly(recorder)
                continue
            }

            return runRecording(recorder, target, maxDurationMs, shouldStop, source)
        }

        log.error(LogCategory.CALL_IN, "Запись разговора не началась", lastError)
        return Result(recorded = false, durationSeconds = 0L, error = lastError)
    }

    private fun runRecording(
        recorder: MediaRecorder,
        target: File,
        maxDurationMs: Long,
        shouldStop: () -> Boolean,
        source: Int,
    ): Result {
        val startedAtMs = System.currentTimeMillis()
        try {
            log.debug(LogCategory.CALL_IN, "Запись начата (${sourceName(source)})")
            while (true) {
                if (shouldStop()) break
                if (System.currentTimeMillis() - startedAtMs >= maxDurationMs) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (e: Exception) {
            log.error(LogCategory.CALL_IN, "Запись разговора прервана", e.toString())
            releaseQuietly(recorder)
            target.delete()
            return Result(recorded = false, durationSeconds = 0L, error = e.message)
        }

        val durationSeconds = (System.currentTimeMillis() - startedAtMs) / 1_000L
        val stopped = try {
            recorder.stop()
            true
        } catch (e: Exception) {
            // stop() падает, если запись не успела получить ни одного кадра.
            log.warn(LogCategory.CALL_IN, "Не удалось корректно завершить запись", e.toString())
            false
        }
        releaseQuietly(recorder)

        if (!stopped || !target.exists() || target.length() <= 0L) {
            target.delete()
            return Result(recorded = false, durationSeconds = 0L, error = "запись пустая")
        }

        log.info(
            LogCategory.CALL_IN,
            "Запись сохранена: ${target.name}, длительность $durationSeconds с",
        )
        return Result(recorded = true, durationSeconds = durationSeconds)
    }

    @Suppress("DEPRECATION") // MediaRecorder(context) доступен с Android 12
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(appContext)
    } else {
        MediaRecorder()
    }

    private fun releaseQuietly(recorder: MediaRecorder) {
        try {
            recorder.reset()
        } catch (_: Exception) {
            // Ничего не делаем.
        }
        try {
            recorder.release()
        } catch (_: Exception) {
            // Ничего не делаем.
        }
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "микрофон"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "распознавание речи"
        MediaRecorder.AudioSource.DEFAULT -> "по умолчанию"
        else -> "источник $source"
    }

    private companion object {
        val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT,
        )

        const val POLL_INTERVAL_MS = 200L
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
    }
}
