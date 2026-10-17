package ru.promcalc.phonegate.data.remote

import android.content.Context
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.calls.CallLogReader
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.outgoing.OutgoingJournal
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository
import ru.promcalc.phonegate.data.sms.SmsReader
import ru.promcalc.phonegate.data.state.SyncStateStore
import ru.promcalc.phonegate.data.tasks.TaskKind
import ru.promcalc.phonegate.data.tasks.TaskStore
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.outgoingJournal
import ru.promcalc.phonegate.settingsRepository
import ru.promcalc.phonegate.syncStateStore
import ru.promcalc.phonegate.taskStore

/**
 * Точка входа обмена для вызывающей стороны (кнопка «Отправить сейчас», служба).
 *
 * Держит в одном месте синхронизацию и постановку полученных команд в очередь задач.
 * Одновременно выполняется только один обмен.
 */
class SyncCoordinator(context: Context) {

    private val appContext = context.applicationContext

    private val log: LogRepository = appContext.logRepository
    private val stateStore: SyncStateStore = appContext.syncStateStore
    private val taskStore: TaskStore = appContext.taskStore
    private val outgoingJournal: OutgoingJournal = appContext.outgoingJournal

    private val engine = SyncEngine(
        settingsRepository = appContext.settingsRepository,
        stateStore = stateStore,
        smsReader = SmsReader(appContext, SubscriptionRepository(appContext), log),
        callLogReader = CallLogReader(appContext, SubscriptionRepository(appContext), log),
        outgoingJournal = outgoingJournal,
        api = PhoneGateApi(log),
        log = log,
    )

    private val lock = Any()

    fun syncNow(): SyncOutcome {
        synchronized(lock) {
            val outcome = try {
                engine.syncOnce()
            } catch (e: Exception) {
                log.error(LogCategory.HTTP, "Обмен прерван ошибкой", e.toString())
                return SyncOutcome(error = e.message ?: e.javaClass.simpleName)
            }
            if (outcome.exchangeOk) {
                enqueueCommands(outcome.commands)
            }
            return outcome
        }
    }

    private fun enqueueCommands(commands: List<ReceivedCommand>) {
        if (commands.isEmpty()) return
        val now = System.currentTimeMillis()
        var added = 0
        commands.forEach { command ->
            val isNew = taskStore.addIfAbsent(
                kind = command.kind,
                simSlot = command.simSlot,
                phone = command.phone,
                text = command.text,
                atMs = command.atMs,
                createdAtMs = now,
            )
            if (isNew) {
                added++
                log.info(
                    category = categoryFor(command.kind),
                    message = when (command.kind) {
                        TaskKind.SEND_SMS ->
                            "Задача: отправить SMS на ${command.phone} в ${Time.formatLocal(command.atMs)}"

                        TaskKind.MAKE_CALL ->
                            "Задача: позвонить ${command.phone} в ${Time.formatLocal(command.atMs)}"
                    },
                    details = command.text.takeIf { it.isNotBlank() },
                )
            } else {
                log.debug(
                    categoryFor(command.kind),
                    "Команда для ${command.phone} уже была в очереди, дубль пропущен",
                )
            }
        }
        log.info(
            LogCategory.SERVICE,
            "Принято команд: ${commands.size}, новых задач в очереди: $added",
        )
    }

    private fun categoryFor(kind: TaskKind): LogCategory = when (kind) {
        TaskKind.SEND_SMS -> LogCategory.SMS
        TaskKind.MAKE_CALL -> LogCategory.CALL_OUT
    }
}
