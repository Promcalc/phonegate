@file:Suppress("DEPRECATION") // весь публичный API снятия трубки помечен устаревшим в пользу роли диалера

package ru.promcalc.phonegate.data.telephony

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository

/**
 * Управление вызовом через `TelecomManager`.
 *
 * Снятие трубки (`acceptRingingCall`) требует разрешения `ANSWER_PHONE_CALLS`; на части
 * прошивок оно дополнительно требует роли «Телефон по умолчанию». Программное завершение
 * звонка (`endCall`) появилось в Android 9 — на более старых версиях трубку положить нельзя.
 */
class TelecomCallController(
    context: Context,
    private val log: LogRepository,
) : CallController {

    private val appContext = context.applicationContext

    private val telecom: TelecomManager?
        get() = try {
            appContext.getSystemService(TelecomManager::class.java)
        } catch (_: Exception) {
            null
        }

    private val audioManager: AudioManager?
        get() = try {
            appContext.getSystemService(AudioManager::class.java)
        } catch (_: Exception) {
            null
        }

    override fun acceptRingingCall(): Boolean {
        val manager = telecom ?: run {
            log.error(LogCategory.CALL_IN, "Служба телефонии недоступна, снять трубку нельзя")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.acceptRingingCall(AUDIO_ONLY_VIDEO_STATE)
            } else {
                @Suppress("DEPRECATION") // до Android 8 другого способа снять трубку нет
                manager.acceptRingingCall()
            }
            true
        } catch (e: SecurityException) {
            log.error(
                LogCategory.CALL_IN,
                "Нет разрешения снять трубку: нужно разрешение «Ответ на звонки»",
                e.toString(),
            )
            false
        } catch (e: Exception) {
            log.error(LogCategory.CALL_IN, "Не удалось снять трубку", e.toString())
            false
        }
    }

    override fun rejectRingingCall(): Boolean = endCall(what = "сбросить звонок")

    override fun hangUp(): Boolean = endCall(what = "завершить разговор")

    private fun endCall(what: String): Boolean {
        val manager = telecom ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            log.warn(
                LogCategory.CALL_IN,
                "Программно $what нельзя: нужен Android 9 или новее",
            )
            return false
        }
        return try {
            @Suppress("DEPRECATION") // замена endCall() появилась только в API 34 вместе с ролью диалера
            manager.endCall()
        } catch (e: SecurityException) {
            log.error(LogCategory.CALL_IN, "Нет разрешения на управление вызовом", e.toString())
            false
        } catch (e: Exception) {
            log.error(LogCategory.CALL_IN, "Не удалось $what", e.toString())
            false
        }
    }

    override fun setSpeakerphoneOn(on: Boolean) {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                switchCommunicationDevice(manager, on)
            } else {
                @Suppress("DEPRECATION") // до Android 12 другого способа нет
                manager.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            log.warn(LogCategory.CALL_IN, "Не удалось переключить громкую связь", e.toString())
        }
    }

    /** Android 12+: громкая связь — это выбор устройства связи. */
    private fun switchCommunicationDevice(manager: AudioManager, on: Boolean) {
        if (!on) {
            manager.clearCommunicationDevice()
            return
        }
        val speaker = manager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            log.warn(LogCategory.CALL_IN, "Громкий динамик не найден среди устройств связи")
            return
        }
        val selected = manager.setCommunicationDevice(speaker)
        if (!selected) {
            log.warn(LogCategory.CALL_IN, "Система не переключила звук на громкий динамик")
        }
    }

    private companion object {
        /** `TelecomManager.VIDEO_STATE_AUDIO` — звонок без видео. */
        const val AUDIO_ONLY_VIDEO_STATE = 0
    }
}
