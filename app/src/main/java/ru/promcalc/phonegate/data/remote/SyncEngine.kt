package ru.promcalc.phonegate.data.remote

import android.provider.Telephony
import ru.promcalc.phonegate.core.PhoneNumbers
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.calls.CallDirection
import ru.promcalc.phonegate.data.calls.CallLogReader
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.outgoing.OutgoingJournal
import ru.promcalc.phonegate.data.settings.Settings
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.data.sms.SmsReader
import ru.promcalc.phonegate.data.state.SyncState
import ru.promcalc.phonegate.data.state.SyncStateStore
import ru.promcalc.phonegate.data.tasks.TaskKind

/** Команда сервера, приведённая к нашим типам. */
data class ReceivedCommand(
    val kind: TaskKind,
    val simSlot: Int,
    val phone: String,
    val text: String,
    val atMs: Long,
)

/** Итог одного цикла обмена. */
data class SyncOutcome(
    val exchangeAttempted: Boolean = false,
    val exchangeOk: Boolean = false,
    val smsCount: Int = 0,
    val callCount: Int = 0,
    val commands: List<ReceivedCommand> = emptyList(),
    val outgoingAttempted: Boolean = false,
    val outgoingOk: Boolean? = null,
    val error: String? = null,
) {
    fun describe(): String {
        val parts = ArrayList<String>(4)
        parts += when {
            !exchangeAttempted -> "обмен не выполнялся"
            exchangeOk && smsCount == 0 && callCount == 0 ->
                "обмен выполнен: новых данных для отправки нет"
            exchangeOk -> "обмен выполнен (SMS: $smsCount, звонков: $callCount)"
            else -> "обмен не удался"
        }
        if (outgoingAttempted) {
            parts += if (outgoingOk == true) "отчёт об исходящих отправлен" else "отчёт об исходящих не отправлен"
        }
        if (commands.isNotEmpty()) {
            parts += "получено команд: ${commands.size}"
        }
        if (!exchangeOk && error != null) {
            parts += error
        }
        return parts.joinToString(separator = ", ")
    }
}

/**
 * Один цикл обмена с сервером.
 *
 * Порядок: собираем данные окна → отправляем отчёт на URL обмена → при успехе двигаем курсоры
 * и разбираем команды → отдельно отправляем отчёт об исходящих на второй URL.
 * Курсоры двигаются только после успешного ответа (docs/decisions.md, D12), поэтому при ошибке
 * накопленное окно уйдёт в следующем цикле целиком.
 */
class SyncEngine(
    private val settingsRepository: SettingsRepository,
    private val stateStore: SyncStateStore,
    private val smsReader: SmsReader,
    private val callLogReader: CallLogReader,
    private val outgoingJournal: OutgoingJournal,
    private val api: PhoneGateApi,
    private val log: LogRepository,
) {

    fun syncOnce(): SyncOutcome {
        val settings = settingsRepository.current()
        val state = stateStore.load()
        val startedAtMs = System.currentTimeMillis()

        val simPhones = configuredSimPhones(settings)
        if (simPhones.isEmpty()) {
            log.warn(
                LogCategory.HTTP,
                "Обмен отменён: в настройках не заданы номера SIM",
                "Номер SIM — ключ отчёта, без него отчёт собрать нельзя",
            )
            return SyncOutcome(error = "не заданы номера SIM")
        }

        val exchange = sendExchange(settings, state, simPhones, startedAtMs)
        val outgoing = sendOutgoingReport(settings, state, simPhones, startedAtMs)

        return exchange.copy(
            outgoingAttempted = outgoing.outgoingAttempted,
            outgoingOk = outgoing.outgoingOk,
        )
    }

    // region обмен

    private fun sendExchange(
        settings: Settings,
        state: SyncState,
        simPhones: Map<Int, String>,
        startedAtMs: Long,
    ): SyncOutcome {
        val windowStartMs = windowStart(settings, state.lastSuccessSyncAtMs)
        if (settings.exchangeUrl.isBlank()) {
            log.warn(LogCategory.HTTP, "URL обмена не задан — отправка пропущена")
            return SyncOutcome(exchangeAttempted = false, error = "не задан URL обмена")
        }

        val smsRows = smsReader.readSince(windowStartMs)
        val callRows = callLogReader.readSince(windowStartMs)

        val newSms = smsRows.filter { it.id > state.lastSmsId && it.type == Telephony.Sms.MESSAGE_TYPE_INBOX }
        val newCalls = callRows.filter { it.id > state.lastCallId }

        // Курсор по идентификаторам двигаем по всем прочитанным строкам, а не только по
        // отправленным: иначе отфильтрованные строки (например, наши же отправленные SMS)
        // перечитывались бы в каждом цикле.
        val maxSmsId = maxOf(state.lastSmsId, smsRows.maxOfOrNull { it.id } ?: 0L)
        val maxCallId = maxOf(state.lastCallId, callRows.maxOfOrNull { it.id } ?: 0L)

        val smsByPhone = linkedMapOf<String, MutableList<SmsReportDto>>()
        val callsByPhone = linkedMapOf<String, MutableList<CallReportDto>>()
        simPhones.values.forEach { phone ->
            smsByPhone[phone] = mutableListOf()
            callsByPhone[phone] = mutableListOf()
        }

        var unattributed = 0
        val fallbackPhone = simPhones.values.first()
        fun phoneFor(simSlot: Int): String {
            val phone = simPhones[simSlot]
            if (phone != null) return phone
            unattributed++
            return fallbackPhone
        }

        newSms.forEach { row ->
            smsByPhone.getValue(phoneFor(row.simSlot)).add(
                SmsReportDto(
                    time = Time.formatIso(row.dateMs),
                    text = row.body,
                    phone = row.address,
                ),
            )
        }
        newCalls.forEach { row ->
            callsByPhone.getValue(phoneFor(row.simSlot)).add(
                CallReportDto(
                    time = Time.formatIso(row.dateMs),
                    text = "",
                    phone = row.number,
                    duration = row.durationSeconds,
                    direction = row.direction,
                ),
            )
        }

        if (unattributed > 0) {
            log.warn(
                LogCategory.HTTP,
                "У $unattributed записей не определилась SIM — отнесены к $fallbackPhone",
                "Проверьте, что номера SIM в настройках заполнены верно",
            )
        }

        val payload = ReportPayload(
            syncId = stateStore.exchangeSyncId(),
            perSim = simPhones.values.associateWith { phone ->
                SimPayloadDto(
                    sms = smsByPhone.getValue(phone).toList(),
                    call = callsByPhone.getValue(phone).toList(),
                )
            },
        )

        log.info(
            LogCategory.HTTP,
            "Обмен: собрано SMS ${newSms.size}, звонков ${newCalls.size}",
            buildString {
                appendLine("окно с ${Time.formatLocal(windowStartMs)}")
                appendLine(
                    "прочитано строк: SMS ${smsRows.size}, звонков ${callRows.size}; " +
                        "в отчёт не вошло: SMS ${smsRows.size - newSms.size} " +
                        "(не входящие или уже отправленные), " +
                        "звонков ${callRows.size - newCalls.size} (уже отправленные)",
                )
                appendLine(
                    "курсоры до: SMS #${state.lastSmsId}, звонки #${state.lastCallId}; " +
                        "после успеха станут: SMS #$maxSmsId, звонки #$maxCallId",
                )
                simPhones.values.forEach { phone ->
                    appendLine(
                        "$phone: SMS ${smsByPhone.getValue(phone).size}, " +
                            "звонков ${callsByPhone.getValue(phone).size}",
                    )
                }
                append(
                    "начало сбора: ${Time.formatLocal(settings.historyStartAtMs)}, " +
                        "последняя успешная отправка: " +
                        if (state.lastSuccessSyncAtMs > 0L) {
                            Time.formatLocal(state.lastSuccessSyncAtMs)
                        } else {
                            "ещё не было"
                        },
                )
            },
        )

        val result = api.postJson(
            url = settings.exchangeUrl,
            authHeaderName = settings.authHeaderName,
            authHeaderValue = settings.authHeaderValue,
            body = ReportJson.encodeReport(payload),
            operation = "обмен",
        )

        return when (result) {
            is ApiResult.Ok -> {
                val applied = stateStore.onExchangeSuccess(
                    syncStartAtMs = startedAtMs,
                    lastSmsId = maxSmsId,
                    lastCallId = maxCallId,
                    expectedResetVersion = state.resetVersion,
                )
                if (!applied) {
                    log.warn(
                        LogCategory.HTTP,
                        "Во время обмена сброшены курсоры — результат не применён, данные уйдут повторно",
                        "Сервер отчёт принял; повторная отправка безопасна благодаря sync_id",
                    )
                }
                val commands = parseCommands(result.body, simPhones)
                SyncOutcome(
                    exchangeAttempted = true,
                    exchangeOk = true,
                    smsCount = newSms.size,
                    callCount = newCalls.size,
                    commands = commands,
                )
            }

            else -> {
                log.warn(
                    LogCategory.HTTP,
                    "Обмен не выполнен (${result.describe()}), данные накопятся до следующего цикла",
                )
                SyncOutcome(
                    exchangeAttempted = true,
                    exchangeOk = false,
                    error = result.describe(),
                )
            }
        }
    }

    private fun parseCommands(body: String, simPhones: Map<Int, String>): List<ReceivedCommand> {
        if (body.isBlank()) return emptyList()

        val root = ReportJson.parseRoot(body)
        if (root == null) {
            log.warn(LogCategory.HTTP, "Ответ сервера не разобран как JSON, команды пропущены")
            return emptyList()
        }

        val commands = mutableListOf<ReceivedCommand>()
        root.forEach { (key, element) ->
            if (key == SYNC_ID_KEY) return@forEach

            val simSlot = slotForPhone(simPhones, key)
            if (simSlot == null) {
                log.warn(LogCategory.HTTP, "Команда для неизвестного номера «$key» пропущена")
                return@forEach
            }

            val simCommands = ReportJson.decodeSimCommands(element)
            if (simCommands == null) {
                log.warn(LogCategory.HTTP, "Не удалось разобрать команды для «$key»")
                return@forEach
            }

            simCommands.sms.forEach { dto ->
                commands += command(TaskKind.SEND_SMS, simSlot, dto.phone, dto.text, dto.time)
            }
            simCommands.call.forEach { dto ->
                commands += command(TaskKind.MAKE_CALL, simSlot, dto.phone, dto.text, dto.time)
            }
        }
        return commands
    }

    private fun command(kind: TaskKind, simSlot: Int, phone: String, text: String, rawTime: String): ReceivedCommand {
        val atMs = Time.parseIsoOrNull(rawTime)
        if (atMs == null) {
            log.warn(
                LogCategory.HTTP,
                "Не разобрано время команды «$rawTime», исполняю немедленно",
            )
        }
        return ReceivedCommand(
            kind = kind,
            simSlot = simSlot,
            phone = phone,
            text = text,
            atMs = atMs ?: System.currentTimeMillis(),
        )
    }

    // endregion

    // region отчёт об исходящих

    private fun sendOutgoingReport(
        settings: Settings,
        state: SyncState,
        simPhones: Map<Int, String>,
        startedAtMs: Long,
    ): SyncOutcome {
        if (settings.outgoingReportUrl.isBlank()) return SyncOutcome()

        val fresh = outgoingJournal.recordsAfter(state.lastOutgoingRecordId)
        val maxRecordId = fresh.maxOfOrNull { it.id } ?: state.lastOutgoingRecordId

        val smsByPhone = linkedMapOf<String, MutableList<SmsReportDto>>()
        val callsByPhone = linkedMapOf<String, MutableList<CallReportDto>>()
        simPhones.values.forEach { phone ->
            smsByPhone[phone] = mutableListOf()
            callsByPhone[phone] = mutableListOf()
        }

        val fallbackPhone = simPhones.values.first()
        fresh.filter { it.success }.forEach { record ->
            val phone = simPhones[record.simSlot] ?: fallbackPhone
            when (record.kind) {
                TaskKind.SEND_SMS -> smsByPhone.getValue(phone).add(
                    SmsReportDto(
                        time = Time.formatIso(record.atMs),
                        text = record.text,
                        phone = record.phone,
                    ),
                )

                TaskKind.MAKE_CALL -> callsByPhone.getValue(phone).add(
                    CallReportDto(
                        time = Time.formatIso(record.atMs),
                        text = "",
                        phone = record.phone,
                        duration = record.durationSeconds,
                        direction = CallDirection.OUT,
                    ),
                )
            }
        }

        val payload = ReportPayload(
            syncId = stateStore.outgoingSyncId(),
            perSim = simPhones.values.associateWith { phone ->
                SimPayloadDto(
                    sms = smsByPhone.getValue(phone).toList(),
                    call = callsByPhone.getValue(phone).toList(),
                )
            },
        )

        val result = api.postJson(
            url = settings.outgoingReportUrl,
            authHeaderName = settings.authHeaderName,
            authHeaderValue = settings.authHeaderValue,
            body = ReportJson.encodeReport(payload),
            operation = "отчёт об исходящих",
        )

        return when (result) {
            is ApiResult.Ok -> {
                val applied = stateStore.onOutgoingSuccess(
                    syncStartAtMs = startedAtMs,
                    lastRecordId = maxRecordId,
                    expectedResetVersion = state.resetVersion,
                )
                if (!applied) {
                    log.warn(
                        LogCategory.HTTP,
                        "Во время отчёта об исходящих сброшены курсоры — результат не применён",
                    )
                }
                SyncOutcome(outgoingAttempted = true, outgoingOk = true)
            }

            else -> SyncOutcome(
                outgoingAttempted = true,
                outgoingOk = false,
                error = result.describe(),
            )
        }
    }

    // endregion

    private fun configuredSimPhones(settings: Settings): Map<Int, String> {
        val phones = linkedMapOf<Int, String>()
        if (settings.sim1Phone.isNotBlank()) phones[0] = settings.sim1Phone.trim()
        if (settings.sim2Phone.isNotBlank()) phones[1] = settings.sim2Phone.trim()
        return phones
    }

    private fun windowStart(settings: Settings, lastSuccessSyncAtMs: Long): Long =
        SyncWindow.start(
            historyStartAtMs = settings.historyStartAtMs,
            lastSuccessSyncAtMs = lastSuccessSyncAtMs,
        )

    private fun slotForPhone(simPhones: Map<Int, String>, key: String): Int? =
        simPhones.entries.firstOrNull { PhoneNumbers.same(it.value, key) }?.key

    private companion object {
        const val SYNC_ID_KEY = "sync_id"
    }
}
