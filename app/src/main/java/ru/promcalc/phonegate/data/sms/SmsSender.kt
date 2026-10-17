package ru.promcalc.phonegate.data.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository

/**
 * Отправка SMS от имени приложения.
 *
 * Сообщение отправляется с выбранной SIM (`createForSubscriptionId`), длинный текст режется
 * на части. Успехом считается передача сообщения в телефонию: подтверждение о доставке
 * (отчёт оператора) в первой версии не отслеживается — см. docs/decisions.md, D15.
 */
class SmsSender(
    private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val log: LogRepository,
) {

    data class Result(val success: Boolean, val error: String? = null)

    fun send(phone: String, text: String, simSlot: Int): Result {
        if (phone.isBlank()) {
            log.error(LogCategory.SMS, "Не отправляю SMS: пустой номер получателя")
            return Result(success = false, error = "пустой номер получателя")
        }

        val subscriptionId = subscriptions.subscriptionIdForSlot(simSlot)
        val manager = if (subscriptionId == SubscriptionRepository.INVALID_SUBSCRIPTION) {
            log.warn(
                LogCategory.SMS,
                "SIM ${simSlot + 1} не найдена, отправляю с SIM по умолчанию",
            )
            defaultManager()
        } else {
            managerFor(subscriptionId)
        }
        return try {
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(phone, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(phone, null, parts, null, null)
            }
            log.info(
                LogCategory.SMS,
                "SMS передана оператору: $phone (частей: ${parts.size}, SIM ${simSlot + 1})",
                text.take(LOG_TEXT_LIMIT),
            )
            Result(success = true)
        } catch (e: SecurityException) {
            log.error(LogCategory.SMS, "Нет разрешения на отправку SMS", e.toString())
            Result(success = false, error = "нет разрешения SEND_SMS")
        } catch (e: IllegalArgumentException) {
            log.error(LogCategory.SMS, "Телефония отклонила SMS на $phone", e.toString())
            Result(success = false, error = e.message ?: "некорректный номер")
        } catch (e: Exception) {
            log.error(LogCategory.SMS, "Не удалось отправить SMS на $phone", e.toString())
            Result(success = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun managerFor(subscriptionId: Int): SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private fun defaultManager(): SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }

    private companion object {
        const val LOG_TEXT_LIMIT = 200
    }
}
