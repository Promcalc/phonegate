package ru.promcalc.phonegate.data.sim

import android.content.Context
import android.telephony.SubscriptionManager

/**
 * Сведения о SIM-картах.
 *
 * Номера телефонов SIM обычно недоступны для чтения, поэтому привязка «слот → номер»
 * берётся из настроек, а здесь мы получаем только сопоставление подписки и слота.
 */
class SubscriptionRepository(private val context: Context) {

    data class SimInfo(
        val subscriptionId: Int,
        val simSlotIndex: Int,
        val displayName: String?,
        val number: String?,
    )

    private val manager: SubscriptionManager?
        get() = try {
            context.getSystemService(SubscriptionManager::class.java)
        } catch (_: Exception) {
            null
        }

    /** Активные подписки; без разрешения READ_PHONE_STATE список пуст. */
    @Suppress("DEPRECATION") // number() недоступен на новых Android, но иногда полезен как подсказка
    fun active(): List<SimInfo> {
        val subscriptionManager = manager ?: return emptyList()
        return try {
            (subscriptionManager.activeSubscriptionInfoList ?: emptyList()).map { info ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    simSlotIndex = info.simSlotIndex,
                    displayName = info.displayName?.toString(),
                    number = info.number,
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Индекс слота для идентификатора подписки (0 — первая SIM). */
    fun slotOfSubscription(subscriptionId: Int): Int {
        if (subscriptionId < 0) return UNKNOWN_SLOT
        val subscriptionManager = manager ?: return UNKNOWN_SLOT
        return try {
            subscriptionManager.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex ?: UNKNOWN_SLOT
        } catch (_: SecurityException) {
            UNKNOWN_SLOT
        } catch (_: Exception) {
            UNKNOWN_SLOT
        }
    }

    /**
     * Слот по идентификатору телефонного аккаунта из журнала вызовов.
     *
     * Штатные аккаунты операторов называются `SIM1`, `SIM2` и т.д., поэтому берём
     * последнюю цифру идентификатора. Если разобрать не удалось — [UNKNOWN_SLOT].
     */
    fun slotOfPhoneAccountId(phoneAccountId: String?): Int {
        if (phoneAccountId.isNullOrBlank()) return UNKNOWN_SLOT
        val trailingDigits = phoneAccountId.takeLastWhile { it.isDigit() }
        val position = trailingDigits.toIntOrNull() ?: return UNKNOWN_SLOT
        if (position !in 1..MAX_SLOTS) return UNKNOWN_SLOT
        return position - 1
    }

    companion object {
        const val UNKNOWN_SLOT = -1
        private const val MAX_SLOTS = 8
    }
}
