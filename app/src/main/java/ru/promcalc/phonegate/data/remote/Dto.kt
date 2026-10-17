package ru.promcalc.phonegate.data.remote

import kotlinx.serialization.Serializable

/*
 * Модели обмена с сервером. Полное описание — docs/api/openapi.yaml.
 */

/** SMS в отчёте: полученная или отправленная приложением. */
@Serializable
data class SmsReportDto(
    val time: String,
    val text: String,
    val phone: String,
)

/** Звонок в отчёте. `text` пока всегда пустой — расшифровка появится позже. */
@Serializable
data class CallReportDto(
    val time: String,
    val text: String = "",
    val phone: String,
    val duration: Long = 0L,
    val direction: String,
)

/** Данные одного номера (SIM) в отчёте. */
@Serializable
data class SimPayloadDto(
    val sms: List<SmsReportDto> = emptyList(),
    val call: List<CallReportDto> = emptyList(),
) {
    val isEmpty: Boolean get() = sms.isEmpty() && call.isEmpty()
}

/** Готовый отчёт: идентификатор окна плюс секции по номерам SIM. */
data class ReportPayload(
    val syncId: String,
    val perSim: Map<String, SimPayloadDto>,
)

/** Команда «отправить SMS» из ответа сервера. */
@Serializable
data class SmsCommandDto(
    val time: String,
    val text: String = "",
    val phone: String,
)

/** Команда «совершить звонок» из ответа сервера. */
@Serializable
data class CallCommandDto(
    val time: String,
    val text: String = "",
    val phone: String,
)

/** Команды для одного номера (SIM). */
@Serializable
data class SimCommandsDto(
    val sms: List<SmsCommandDto> = emptyList(),
    val call: List<CallCommandDto> = emptyList(),
)
