package ru.promcalc.phonegate.data.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Проигрывание mp3-сообщения в разговор.
 *
 * Звук направляется в голосовой поток (`USAGE_VOICE_COMMUNICATION`): вместе с включённой
 * громкой связью абонент слышит сообщение через микрофон телефона. Прямая подмешка звука
 * в исходящий канал без root невозможна — это компромисс, зафиксированный в плане (D4).
 */
class AudioMessagePlayer(
    context: Context,
    private val log: LogRepository,
) {

    data class Result(val completed: Boolean, val error: String? = null)

    /**
     * Проиграть файл до конца.
     *
     * @param shouldStop вызывается постоянно: если вернёт `true` (абонент положил трубку),
     *   воспроизведение прерывается.
     */
    fun play(file: File, shouldStop: () -> Boolean): Result {
        val player = MediaPlayer()
        var completed = false

        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()

            val deadlineMs = System.currentTimeMillis() + playbackLimitMs(player)
            while (true) {
                if (!player.isPlaying) {
                    completed = true
                    break
                }
                if (shouldStop() || System.currentTimeMillis() >= deadlineMs) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Result(completed = completed)
        } catch (e: Exception) {
            log.error(LogCategory.CALL_IN, "Не удалось проиграть сообщение", e.toString())
            Result(completed = false, error = e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
                // Воспроизведение уже остановлено — это не ошибка.
            }
            try {
                player.release()
            } catch (_: Exception) {
                // Ничего не делаем.
            }
        }
    }

    /** Ограничение по времени: длина файла плюс запас, но не больше [MAX_PLAYBACK_MS]. */
    private fun playbackLimitMs(player: MediaPlayer): Long {
        val duration = try {
            player.duration.toLong()
        } catch (_: Exception) {
            -1L
        }
        return when {
            duration <= 0L -> DEFAULT_PLAYBACK_LIMIT_MS
            duration + PLAYBACK_RESERVE_MS > MAX_PLAYBACK_MS -> MAX_PLAYBACK_MS
            else -> duration + PLAYBACK_RESERVE_MS
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val DEFAULT_PLAYBACK_LIMIT_MS = 60_000L
        const val PLAYBACK_RESERVE_MS = 1_000L
        const val MAX_PLAYBACK_MS = 5L * 60 * 1000
    }
}
