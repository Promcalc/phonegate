package ru.promcalc.phonegate.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранилище настроек: SharedPreferences без внешних зависимостей.
 *
 * Изменения доступны подписчикам через [settings]; слушатель SharedPreferences нужен, чтобы
 * подхватить правки, сделанные другим процессом/компонентом.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Первый запуск: точкой начала сбора данных считаем текущий момент,
        // чтобы не выгружать на сервер всю историю телефона.
        if (_settings.value.historyStartAtMs == 0L) {
            save(_settings.value.copy(historyStartAtMs = System.currentTimeMillis()))
        }
    }

    fun current(): Settings = _settings.value

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_SERVICE_ENABLED, settings.serviceEnabled)
            .putBoolean(KEY_START_ON_BOOT, settings.startOnBoot)
            .putInt(KEY_INTERVAL_SECONDS, settings.intervalSeconds)
            .putString(KEY_EXCHANGE_URL, settings.exchangeUrl)
            .putString(KEY_OUTGOING_REPORT_URL, settings.outgoingReportUrl)
            .putString(KEY_AUTH_HEADER_NAME, settings.authHeaderName)
            .putString(KEY_AUTH_HEADER_VALUE, settings.authHeaderValue)
            .putBoolean(KEY_SKIP_INVALID_NUMBERS, settings.skipInvalidNumbers)
            .putString(KEY_SIM1_PHONE, settings.sim1Phone)
            .putString(KEY_SIM2_PHONE, settings.sim2Phone)
            .putInt(KEY_ANSWER_AFTER_SECONDS, settings.answerAfterSeconds)
            .putInt(KEY_MAX_RECORD_SECONDS, settings.maxRecordSeconds)
            .putInt(KEY_OUTGOING_HANGUP_SECONDS, settings.outgoingHangupAfterSeconds)
            .putString(KEY_SIM1_MESSAGE_URI, settings.sim1MessageUri)
            .putString(KEY_SIM1_MESSAGE_NAME, settings.sim1MessageName)
            .putString(KEY_SIM2_MESSAGE_URI, settings.sim2MessageUri)
            .putString(KEY_SIM2_MESSAGE_NAME, settings.sim2MessageName)
            .putLong(KEY_HISTORY_START_AT, settings.historyStartAtMs)
            .putInt(KEY_KEEP_RECORDINGS, settings.keepRecordings)
            .apply()
        _settings.value = settings
    }

    private fun read(): Settings {
        val defaults = Settings()
        return Settings(
            serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, defaults.serviceEnabled),
            startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, defaults.startOnBoot),
            intervalSeconds = prefs.getInt(KEY_INTERVAL_SECONDS, defaults.intervalSeconds),
            exchangeUrl = prefs.getString(KEY_EXCHANGE_URL, defaults.exchangeUrl).orEmpty(),
            outgoingReportUrl = prefs.getString(KEY_OUTGOING_REPORT_URL, defaults.outgoingReportUrl).orEmpty(),
            authHeaderName = prefs.getString(KEY_AUTH_HEADER_NAME, defaults.authHeaderName).orEmpty(),
            authHeaderValue = prefs.getString(KEY_AUTH_HEADER_VALUE, defaults.authHeaderValue).orEmpty(),
            skipInvalidNumbers = prefs.getBoolean(KEY_SKIP_INVALID_NUMBERS, defaults.skipInvalidNumbers),
            sim1Phone = prefs.getString(KEY_SIM1_PHONE, defaults.sim1Phone).orEmpty(),
            sim2Phone = prefs.getString(KEY_SIM2_PHONE, defaults.sim2Phone).orEmpty(),
            answerAfterSeconds = prefs.getInt(KEY_ANSWER_AFTER_SECONDS, defaults.answerAfterSeconds),
            maxRecordSeconds = prefs.getInt(KEY_MAX_RECORD_SECONDS, defaults.maxRecordSeconds),
            outgoingHangupAfterSeconds = prefs.getInt(KEY_OUTGOING_HANGUP_SECONDS, defaults.outgoingHangupAfterSeconds),
            sim1MessageUri = prefs.getString(KEY_SIM1_MESSAGE_URI, defaults.sim1MessageUri).orEmpty(),
            sim1MessageName = prefs.getString(KEY_SIM1_MESSAGE_NAME, defaults.sim1MessageName).orEmpty(),
            sim2MessageUri = prefs.getString(KEY_SIM2_MESSAGE_URI, defaults.sim2MessageUri).orEmpty(),
            sim2MessageName = prefs.getString(KEY_SIM2_MESSAGE_NAME, defaults.sim2MessageName).orEmpty(),
            historyStartAtMs = prefs.getLong(KEY_HISTORY_START_AT, defaults.historyStartAtMs),
            keepRecordings = prefs.getInt(KEY_KEEP_RECORDINGS, defaults.keepRecordings),
        )
    }

    private companion object {
        const val PREFS_NAME = "phonegate.settings"

        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val KEY_EXCHANGE_URL = "exchange_url"
        const val KEY_OUTGOING_REPORT_URL = "outgoing_report_url"
        const val KEY_AUTH_HEADER_NAME = "auth_header_name"
        const val KEY_AUTH_HEADER_VALUE = "auth_header_value"
        const val KEY_SKIP_INVALID_NUMBERS = "skip_invalid_numbers"
        const val KEY_SIM1_PHONE = "sim1_phone"
        const val KEY_SIM2_PHONE = "sim2_phone"
        const val KEY_ANSWER_AFTER_SECONDS = "answer_after_seconds"
        const val KEY_MAX_RECORD_SECONDS = "max_record_seconds"
        const val KEY_OUTGOING_HANGUP_SECONDS = "outgoing_hangup_seconds"
        const val KEY_SIM1_MESSAGE_URI = "sim1_message_uri"
        const val KEY_SIM1_MESSAGE_NAME = "sim1_message_name"
        const val KEY_SIM2_MESSAGE_URI = "sim2_message_uri"
        const val KEY_SIM2_MESSAGE_NAME = "sim2_message_name"
        const val KEY_HISTORY_START_AT = "history_start_at"
        const val KEY_KEEP_RECORDINGS = "keep_recordings"
    }
}
