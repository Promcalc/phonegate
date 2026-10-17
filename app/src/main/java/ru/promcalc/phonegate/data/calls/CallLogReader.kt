package ru.promcalc.phonegate.data.calls

import android.content.Context
import android.provider.CallLog
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository

/** Направление звонка в отчёте (значения поля `direction` из api/openapi.yaml). */
object CallDirection {
    const val IN = "in"
    const val OUT = "out"
    const val MISSED = "missed"
}

/**
 * Чтение журнала вызовов.
 *
 * В отчёт попадают все типы звонков (см. docs/decisions.md, D13): входящие, исходящие и
 * пропущенные. Тип переводится в `direction`, а SIM определяется по подписке, а если её
 * в журнале нет — по идентификатору телефонного аккаунта (`SIM1`, `SIM2`).
 */
class CallLogReader(
    private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val log: LogRepository,
) {

    data class Row(
        val id: Long,
        val number: String,
        val dateMs: Long,
        val durationSeconds: Long,
        val type: Int,
        val direction: String,
        val simSlot: Int,
    )

    private val projectionWithSubscription = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.PHONE_ACCOUNT_ID,
        SUBSCRIPTION_ID_COLUMN,
    )

    private val projectionWithoutSubscription = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.PHONE_ACCOUNT_ID,
    )

    fun readSince(sinceMs: Long): List<Row> {
        val withSubscription = query(projectionWithSubscription, sinceMs, hasSubscriptionColumn = true)
        return withSubscription ?: query(projectionWithoutSubscription, sinceMs, hasSubscriptionColumn = false) ?: emptyList()
    }

    private fun query(projection: Array<String>, sinceMs: Long, hasSubscriptionColumn: Boolean): List<Row>? {
        val resolver = context.contentResolver
        return try {
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(sinceMs.toString()),
                "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val accountIndex = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val subscriptionIndex = if (hasSubscriptionColumn) cursor.getColumnIndex(SUBSCRIPTION_ID_COLUMN) else -1

                val rows = ArrayList<Row>(cursor.count)
                while (cursor.moveToNext()) {
                    try {
                        val type = cursor.getInt(typeIndex)
                        val phoneAccountId = if (accountIndex >= 0 && !cursor.isNull(accountIndex)) {
                            cursor.getString(accountIndex)
                        } else {
                            null
                        }
                        val subscriptionId = if (subscriptionIndex >= 0 && !cursor.isNull(subscriptionIndex)) {
                            cursor.getInt(subscriptionIndex)
                        } else {
                            -1
                        }

                        val slotBySubscription = if (subscriptionId >= 0) {
                            subscriptions.slotOfSubscription(subscriptionId)
                        } else {
                            SubscriptionRepository.UNKNOWN_SLOT
                        }
                        val slot = if (slotBySubscription != SubscriptionRepository.UNKNOWN_SLOT) {
                            slotBySubscription
                        } else {
                            subscriptions.slotOfPhoneAccountId(phoneAccountId)
                        }

                        rows += Row(
                            id = cursor.getLong(idIndex),
                            number = cursor.getString(numberIndex).orEmpty(),
                            dateMs = cursor.getLong(dateIndex),
                            durationSeconds = cursor.getLong(durationIndex),
                            type = type,
                            direction = directionOf(type),
                            simSlot = slot,
                        )
                    } catch (e: Exception) {
                        log.warn(LogCategory.CALL_OUT, "Не удалось прочитать строку журнала вызовов", e.toString())
                    }
                }
                rows
            }
        } catch (e: SecurityException) {
            log.error(LogCategory.CALL_OUT, "Нет доступа к журналу вызовов (READ_CALL_LOG)", e.toString())
            emptyList()
        } catch (e: IllegalArgumentException) {
            if (hasSubscriptionColumn) {
                log.debug(LogCategory.CALL_OUT, "Провайдер вызовов не поддерживает колонку подписки, читаем без неё")
                null
            } else {
                log.error(LogCategory.CALL_OUT, "Не удалось прочитать журнал вызовов", e.toString())
                emptyList()
            }
        } catch (e: Exception) {
            log.error(LogCategory.CALL_OUT, "Не удалось прочитать журнал вызовов", e.toString())
            emptyList()
        }
    }

    private fun directionOf(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE,
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE,
        VOICEMAIL_TYPE,
        -> CallDirection.IN

        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUT

        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.BLOCKED_TYPE,
        -> CallDirection.MISSED

        else -> {
            log.warn(LogCategory.CALL_OUT, "Неизвестный тип звонка $type, относим к пропущенным")
            CallDirection.MISSED
        }
    }

    private companion object {
        /** Скрытая колонка журнала вызовов с идентификатором подписки. */
        const val SUBSCRIPTION_ID_COLUMN = "subscription_id"

        /** `CallLog.Calls.VOICEMAIL_TYPE` есть не во всех SDK — используем значение напрямую. */
        const val VOICEMAIL_TYPE = 4
    }
}
