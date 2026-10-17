package ru.promcalc.phonegate.data.state

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Служебное состояние обмена, которое должно переживать перезагрузку:
 * курсоры «с момента последней успешной отправки» и идентификатор окна синхронизации.
 *
 * Курсоры двигаются только после успешного POST (см. docs/decisions.md, D12).
 */
data class SyncState(
    /** Время начала последнего успешно отправленного цикла (мс). */
    val lastSuccessSyncAtMs: Long = 0L,
    /** Максимальный `_id` SMS, вошедший в успешный батч. */
    val lastSmsId: Long = 0L,
    /** Максимальный `_id` записи журнала вызовов, вошедший в успешный батч. */
    val lastCallId: Long = 0L,
    /** Идентификатор текущего окна обмена. */
    val syncId: String = "",
    /** Время последнего успешного отчёта об исходящих (мс). */
    val lastSuccessOutgoingAtMs: Long = 0L,
    /** Идентификатор текущего окна отчёта об исходящих. */
    val outgoingSyncId: String = "",
)

class SyncStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): SyncState = SyncState(
        lastSuccessSyncAtMs = prefs.getLong(KEY_LAST_SUCCESS_SYNC_AT, 0L),
        lastSmsId = prefs.getLong(KEY_LAST_SMS_ID, 0L),
        lastCallId = prefs.getLong(KEY_LAST_CALL_ID, 0L),
        syncId = prefs.getString(KEY_SYNC_ID, "").orEmpty(),
        lastSuccessOutgoingAtMs = prefs.getLong(KEY_LAST_SUCCESS_OUTGOING_AT, 0L),
        outgoingSyncId = prefs.getString(KEY_OUTGOING_SYNC_ID, "").orEmpty(),
    )

    /** Идентификатор окна обмена: существующий или новый, если его ещё нет. */
    fun exchangeSyncId(): String {
        val existing = prefs.getString(KEY_SYNC_ID, "").orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SYNC_ID, fresh).apply()
        return fresh
    }

    /** Идентификатор окна отчёта об исходящих. */
    fun outgoingSyncId(): String {
        val existing = prefs.getString(KEY_OUTGOING_SYNC_ID, "").orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_OUTGOING_SYNC_ID, fresh).apply()
        return fresh
    }

    /** Успешная отправка обмена: сдвигаем курсоры и начинаем новое окно. */
    fun onExchangeSuccess(syncStartAtMs: Long, lastSmsId: Long, lastCallId: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_SYNC_AT, syncStartAtMs)
            .putLong(KEY_LAST_SMS_ID, lastSmsId)
            .putLong(KEY_LAST_CALL_ID, lastCallId)
            .putString(KEY_SYNC_ID, UUID.randomUUID().toString())
            .apply()
    }

    /** Успешный отчёт об исходящих. */
    fun onOutgoingSuccess(syncStartAtMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_OUTGOING_AT, syncStartAtMs)
            .putString(KEY_OUTGOING_SYNC_ID, UUID.randomUUID().toString())
            .apply()
    }

    /**
     * Ручной сдвиг точки отсчёта (настройка «Начало сбора данных»):
     * курсоры обнуляются, окно начинается заново от нового момента.
     */
    fun resetCursors() {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_SYNC_AT, 0L)
            .putLong(KEY_LAST_SMS_ID, 0L)
            .putLong(KEY_LAST_CALL_ID, 0L)
            .putString(KEY_SYNC_ID, UUID.randomUUID().toString())
            .putLong(KEY_LAST_SUCCESS_OUTGOING_AT, 0L)
            .putString(KEY_OUTGOING_SYNC_ID, UUID.randomUUID().toString())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "phonegate.state"

        const val KEY_LAST_SUCCESS_SYNC_AT = "last_success_sync_at"
        const val KEY_LAST_SMS_ID = "last_sms_id"
        const val KEY_LAST_CALL_ID = "last_call_id"
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_LAST_SUCCESS_OUTGOING_AT = "last_success_outgoing_at"
        const val KEY_OUTGOING_SYNC_ID = "outgoing_sync_id"
    }
}
