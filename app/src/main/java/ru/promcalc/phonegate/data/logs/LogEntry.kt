package ru.promcalc.phonegate.data.logs

import kotlinx.serialization.Serializable

/** Уровень записи в логе. */
@Serializable
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Категория записи — используется для фильтра на экране логов. */
@Serializable
enum class LogCategory(val title: String) {
    SERVICE("Служба"),
    HTTP("Обмен"),
    SMS("SMS"),
    CALL_IN("Входящие звонки"),
    CALL_OUT("Исходящие звонки"),
}

/**
 * Одна запись лога.
 *
 * [details] содержит подробности (JSON запроса/ответа, текст ошибки) и может быть многострочным.
 */
@Serializable
data class LogEntry(
    val id: Long = 0L,
    val atMs: Long = 0L,
    val level: LogLevel = LogLevel.INFO,
    val category: LogCategory = LogCategory.SERVICE,
    val message: String = "",
    val details: String? = null,
)
