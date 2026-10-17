package ru.promcalc.phonegate.data.calls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository

/**
 * Исходящий звонок с выбранной SIM и завершение его через заданное время.
 *
 * Звонок ставится через `TelecomManager.placeCall` (API 26+) — он не поднимает активити
 * дозвона, а значит не упирается в запрет запуска активити из фона. На более старых версиях
 * используется `ACTION_CALL`.
 *
 * Определение «абонент ответил» (роль диалера) в этой фазе не делается: мы ждём заданное
 * количество секунд и кладём трубку, а длительность считаем по факту (см. docs/decisions.md, D16).
 */
class CallPlacer(
    private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val log: LogRepository,
) {

    data class Result(
        val placed: Boolean,
        val connected: Boolean,
        val durationSeconds: Long,
        val hungUp: Boolean,
        val error: String? = null,
    )

    fun placeAndHangUp(phone: String, simSlot: Int, hangUpAfterSeconds: Int): Result {
        if (phone.isBlank()) {
            log.error(LogCategory.CALL_OUT, "Не звоню: пустой номер")
            return Result(false, false, 0L, false, "пустой номер")
        }

        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return Result(false, false, 0L, false, "телефония недоступна")

        val handle = phoneAccountFor(simSlot)
        val uri = Uri.fromParts("tel", phone, null)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val extras = Bundle().apply {
                    if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
                telecom.placeCall(uri, extras)
            } else {
                val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (handle != null) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
                context.startActivity(intent)
            }
        } catch (e: SecurityException) {
            log.error(LogCategory.CALL_OUT, "Нет разрешения на звонок", e.toString())
            return Result(false, false, 0L, false, "нет разрешения CALL_PHONE")
        } catch (e: Exception) {
            log.error(LogCategory.CALL_OUT, "Не удалось начать звонок на $phone", e.toString())
            return Result(false, false, 0L, false, e.message ?: e.javaClass.simpleName)
        }

        log.info(
            LogCategory.CALL_OUT,
            "Звонок на $phone начат (SIM ${simSlot + 1}, трубку положим через $hangUpAfterSeconds с)",
        )

        val connected = waitForConnection(telecom)
        val talkStartedAtMs = SystemClock.elapsedRealtime()
        val hangUpAtMs = talkStartedAtMs + hangUpAfterSeconds.coerceAtLeast(0) * 1_000L

        while (SystemClock.elapsedRealtime() < hangUpAtMs) {
            if (!isInCall(telecom)) break
            SystemClock.sleep(POLL_INTERVAL_MS)
        }

        val durationSeconds = if (connected) {
            ((SystemClock.elapsedRealtime() - talkStartedAtMs) / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val hungUp = endCall(telecom)
        if (!hungUp) {
            log.warn(
                LogCategory.CALL_OUT,
                "Не удалось завершить звонок на $phone программно — положите трубку вручную",
            )
        }

        return Result(
            placed = true,
            connected = connected,
            durationSeconds = durationSeconds,
            hungUp = hungUp,
        )
    }

    /** Ждём, пока звонок начнётся: если не соединился за отведённое время — считаем, что не ответили. */
    private fun waitForConnection(telecom: TelecomManager): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isInCall(telecom)) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        log.warn(LogCategory.CALL_OUT, "Звонок не соединился за ${CONNECT_TIMEOUT_MS / 1000} с")
        return false
    }

    private fun isInCall(telecom: TelecomManager): Boolean = try {
        telecom.isInCall
    } catch (_: Exception) {
        // Без READ_PHONE_STATE состояние не узнать: считаем, что разговор идёт,
        // и полагаемся на таймер.
        true
    }

    private fun endCall(telecom: TelecomManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            log.warn(
                LogCategory.CALL_OUT,
                "Программное завершение звонка доступно с Android 9 — на этом устройстве трубку нужно положить вручную",
            )
            return false
        }
        return try {
            @Suppress("DEPRECATION") // Замена endCall появилась только в API 34 (роль диалера)
            telecom.endCall()
        } catch (e: SecurityException) {
            log.error(LogCategory.CALL_OUT, "Нет разрешения завершить звонок", e.toString())
            false
        } catch (e: Exception) {
            log.error(LogCategory.CALL_OUT, "Не удалось завершить звонок", e.toString())
            false
        }
    }

    /**
     * Телефонный аккаунт нужной SIM.
     *
     * Штатные аккаунты операторов имеют идентификаторы `SIM1`, `SIM2`; если совпадения нет,
     * пробуем найти по идентификатору подписки, а затем по порядку — с предупреждением в лог.
     */
    private fun phoneAccountFor(simSlot: Int): PhoneAccountHandle? {
        val accounts = try {
            context.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts.orEmpty()
        } catch (e: SecurityException) {
            log.error(LogCategory.CALL_OUT, "Нет доступа к списку телефонных аккаунтов", e.toString())
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (accounts.isEmpty()) {
            log.warn(LogCategory.CALL_OUT, "Телефонные аккаунты недоступны, звонок пойдёт с SIM по умолчанию")
            return null
        }

        val expectedId = "SIM${simSlot + 1}"
        accounts.firstOrNull { it.id.equals(expectedId, ignoreCase = true) }?.let { return it }

        val subscriptionId = subscriptions.subscriptionIdForSlot(simSlot)
        if (subscriptionId != SubscriptionRepository.INVALID_SUBSCRIPTION) {
            accounts.firstOrNull { it.id == subscriptionId.toString() }?.let { return it }
        }

        val byIndex = accounts.getOrNull(simSlot)
        if (byIndex != null) {
            log.warn(
                LogCategory.CALL_OUT,
                "Аккаунт SIM ${simSlot + 1} не опознан, беру «${byIndex.id}»",
            )
        } else {
            log.warn(LogCategory.CALL_OUT, "Аккаунт для SIM ${simSlot + 1} не найден, звонок пойдёт с SIM по умолчанию")
        }
        return byIndex
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val CONNECT_TIMEOUT_MS = 45_000L
    }
}
