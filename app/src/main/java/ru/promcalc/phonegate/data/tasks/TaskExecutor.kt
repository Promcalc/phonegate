package ru.promcalc.phonegate.data.tasks

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.calls.CallPlacer
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.outgoing.OutgoingJournal
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository
import ru.promcalc.phonegate.data.sms.SmsSender
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.outgoingJournal
import ru.promcalc.phonegate.settingsRepository
import ru.promcalc.phonegate.taskStore

/**
 * Исполнение задач от сервера: отправка SMS и исходящие звонки.
 *
 * Задача исполняется один раз; при неудаче она возвращается в очередь и повторяется
 * (не больше [MAX_ATTEMPTS] попыток), потому что причина обычно временная — нет сети,
 * идёт другой разговор, не выдано разрешение. Результат каждой попытки попадает в журнал
 * исходящих, из которого затем строится отчёт на второй URL.
 */
class TaskExecutor(context: Context) {

    private val appContext = context.applicationContext
    private val log: LogRepository = appContext.logRepository
    private val taskStore: TaskStore = appContext.taskStore
    private val journal: OutgoingJournal = appContext.outgoingJournal
    private val settingsRepository: SettingsRepository = appContext.settingsRepository

    private val subscriptions = SubscriptionRepository(appContext)
    private val smsSender = SmsSender(appContext, subscriptions, log)
    private val callPlacer = CallPlacer(appContext, subscriptions, log)

    private val busy = AtomicBoolean(false)

    /** Время ближайшей ожидающей задачи или `null`, если очередь пуста. */
    fun nextPendingAtMs(): Long? = taskStore.nextPending()?.atMs

    /**
     * Исполнить задачи, время которых наступило. Возвращает число исполненных задач.
     *
     * Повторный вход запрещён: цикл службы и ручная отправка могут прийти одновременно,
     * а одну и ту же задачу нельзя выполнить дважды.
     */
    fun runDueTasks(nowMs: Long = System.currentTimeMillis()): Int {
        if (!busy.compareAndSet(false, true)) {
            log.debug(LogCategory.SERVICE, "Задачи уже исполняются, повторный запуск пропущен")
            return 0
        }
        return try {
            executeDueTasks(nowMs)
        } finally {
            busy.set(false)
        }
    }

    private fun executeDueTasks(nowMs: Long): Int {
        val due = taskStore.pending()
            .filter { it.atMs <= nowMs }
            .sortedBy { it.atMs }

        if (due.isEmpty()) return 0

        log.info(LogCategory.SERVICE, "К исполнению задач: ${due.size}")
        due.forEach { task ->
            when (task.kind) {
                TaskKind.SEND_SMS -> executeSms(task)
                TaskKind.MAKE_CALL -> executeCall(task)
            }
        }
        return due.size
    }

    private fun executeSms(task: OutgoingTask) {
        val result = smsSender.send(phone = task.phone, text = task.text, simSlot = task.simSlot)
        finish(
            task = task,
            success = result.success,
            error = result.error,
            durationSeconds = 0L,
            atMs = System.currentTimeMillis(),
            successMessage = "SMS на ${task.phone} отправлена",
            failureMessage = "SMS на ${task.phone} не отправлена",
        )
    }

    private fun executeCall(task: OutgoingTask) {
        val hangUpAfterSeconds = settingsRepository.current().outgoingHangupAfterSeconds
        val startedAtMs = System.currentTimeMillis()
        val result = callPlacer.placeAndHangUp(
            phone = task.phone,
            simSlot = task.simSlot,
            hangUpAfterSeconds = hangUpAfterSeconds,
        )

        finish(
            task = task,
            success = result.placed,
            error = result.error,
            durationSeconds = result.durationSeconds,
            atMs = startedAtMs,
            successMessage = if (result.connected) {
                "Звонок на ${task.phone} завершён, длительность ${result.durationSeconds} с"
            } else {
                "Звонок на ${task.phone} не соединился, трубка положена"
            },
            failureMessage = "Звонок на ${task.phone} не удался",
        )
    }

    private fun finish(
        task: OutgoingTask,
        success: Boolean,
        error: String?,
        durationSeconds: Long,
        atMs: Long,
        successMessage: String,
        failureMessage: String,
    ) {
        val finishedAtMs = System.currentTimeMillis()

        journal.record(
            kind = task.kind,
            simSlot = task.simSlot,
            phone = task.phone,
            text = task.text,
            atMs = atMs,
            durationSeconds = durationSeconds,
            success = success,
            note = error,
        )

        if (success) {
            taskStore.markDone(task.id, finishedAtMs)
            log.info(categoryOf(task), successMessage)
        } else {
            val attempts = task.attempts + 1
            val message = error ?: "неизвестная ошибка"
            log.error(categoryOf(task), failureMessage, message)
            if (attempts < MAX_ATTEMPTS) {
                taskStore.markPending(task.id, "попытка $attempts: $message")
                log.warn(
                    categoryOf(task),
                    "Задача «${describe(task)}» вернётся в очередь, попытка $attempts из $MAX_ATTEMPTS",
                )
            } else {
                taskStore.markFailed(task.id, finishedAtMs, message)
                log.error(
                    categoryOf(task),
                    "Задача «${describe(task)}» не выполнена после $MAX_ATTEMPTS попыток",
                )
            }
        }
    }

    private fun describe(task: OutgoingTask): String = when (task.kind) {
        TaskKind.SEND_SMS -> "SMS на ${task.phone} в ${Time.formatLocal(task.atMs)}"
        TaskKind.MAKE_CALL -> "звонок на ${task.phone} в ${Time.formatLocal(task.atMs)}"
    }

    private fun categoryOf(task: OutgoingTask): LogCategory = when (task.kind) {
        TaskKind.SEND_SMS -> LogCategory.SMS
        TaskKind.MAKE_CALL -> LogCategory.CALL_OUT
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
