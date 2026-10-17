package ru.promcalc.phonegate.data.sms

import android.content.Context
import android.provider.Telephony
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository

/**
 * Чтение SMS из системного провайдера.
 *
 * Читаются все сообщения начиная с указанного момента; отбор «только входящие» делает
 * вызывающая сторона по полю [Row.type], чтобы курсор по `_id` двигался и по пропущенным
 * строкам (иначе они будут читаться в каждом цикле).
 */
class SmsReader(
    private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val log: LogRepository,
) {

    data class Row(
        val id: Long,
        val address: String,
        val body: String,
        val dateMs: Long,
        val type: Int,
        val simSlot: Int,
    )

    private val projectionWithSubscription = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        SUBSCRIPTION_ID_COLUMN,
    )

    private val projectionWithoutSubscription = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
    )

    fun readSince(sinceMs: Long): List<Row> {
        val withSubscription = query(projectionWithSubscription, sinceMs, hasSubscriptionColumn = true)
        return withSubscription ?: query(projectionWithoutSubscription, sinceMs, hasSubscriptionColumn = false) ?: emptyList()
    }

    private fun query(projection: Array<String>, sinceMs: Long, hasSubscriptionColumn: Boolean): List<Row>? {
        val resolver = context.contentResolver
        return try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(sinceMs.toString()),
                "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val subscriptionIndex = if (hasSubscriptionColumn) {
                    cursor.getColumnIndex(SUBSCRIPTION_ID_COLUMN)
                } else {
                    -1
                }

                val rows = ArrayList<Row>(cursor.count)
                while (cursor.moveToNext()) {
                    try {
                        val subscriptionId = if (subscriptionIndex >= 0 && !cursor.isNull(subscriptionIndex)) {
                            cursor.getInt(subscriptionIndex)
                        } else {
                            -1
                        }
                        rows += Row(
                            id = cursor.getLong(idIndex),
                            address = cursor.getString(addressIndex).orEmpty(),
                            body = cursor.getString(bodyIndex).orEmpty(),
                            dateMs = cursor.getLong(dateIndex),
                            type = cursor.getInt(typeIndex),
                            simSlot = if (subscriptionId >= 0) {
                                subscriptions.slotOfSubscription(subscriptionId)
                            } else {
                                SubscriptionRepository.UNKNOWN_SLOT
                            },
                        )
                    } catch (e: Exception) {
                        log.warn(LogCategory.SMS, "Не удалось прочитать строку журнала SMS", e.toString())
                    }
                }
                rows
            }
        } catch (e: SecurityException) {
            log.error(LogCategory.SMS, "Нет доступа к SMS (READ_SMS)", e.toString())
            emptyList()
        } catch (e: IllegalArgumentException) {
            // Провайдер не знает колонку sub_id — повторяем запрос без неё.
            if (hasSubscriptionColumn) {
                log.debug(LogCategory.SMS, "Провайдер SMS не поддерживает колонку подписки, читаем без неё")
                null
            } else {
                log.error(LogCategory.SMS, "Не удалось прочитать журнал SMS", e.toString())
                emptyList()
            }
        } catch (e: Exception) {
            log.error(LogCategory.SMS, "Не удалось прочитать журнал SMS", e.toString())
            emptyList()
        }
    }

    companion object {
        /** Скрытая, но присутствующая на большинстве устройств колонка: к какой подписке относится SMS. */
        private const val SUBSCRIPTION_ID_COLUMN = "sub_id"
    }
}
