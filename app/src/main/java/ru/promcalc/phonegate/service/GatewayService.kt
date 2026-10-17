package ru.promcalc.phonegate.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.remote.SyncOutcome
import ru.promcalc.phonegate.data.sim.SubscriptionRepository
import ru.promcalc.phonegate.data.telephony.IncomingCallHandler
import ru.promcalc.phonegate.data.telephony.IncomingCallWatcher
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.settingsRepository
import ru.promcalc.phonegate.syncCoordinator
import ru.promcalc.phonegate.taskExecutor

/**
 * Фоновый процесс: раз в заданный интервал выполняет обмен с сервером и исполняет задачи,
 * время которых наступило.
 *
 * Служба постоянная (`START_STICKY`), с постоянным уведомлением. Момент следующего обмена
 * считается по часам, поэтому длительность самого обмена не сдвигает расписание; время
 * пробуждения — минимум из «пора на обмен» и «пора исполнять задачу». Будильник
 * переставляется на каждое пробуждение: если систему убьёт процесс, она поднимет службу.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var nextSyncAtMs = 0L

    private val taskExecutor get() = applicationContext.taskExecutor

    private val incomingCallHandler by lazy { IncomingCallHandler(this) }
    private val incomingCallWatcher by lazy {
        IncomingCallWatcher(this, SubscriptionRepository(this), logRepository)
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundWith("подготовка")
        logRepository.info(LogCategory.SERVICE, "Фоновый процесс создан")
        startIncomingCallTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GatewayCommandReceiver.ACTION_STOP) {
            GatewayServiceControl.setEnabled(this, enabled = false)
            return START_NOT_STICKY
        }
        startLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Пользователь убрал приложение из списка задач — процесс продолжает работать. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        logRepository.info(LogCategory.SERVICE, "Приложение закрыто пользователем, фоновый процесс продолжает работу")
        SyncAlarmScheduler.schedule(this, nextWakeUpAtMs())
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        stopIncomingCallTracking()
        logRepository.info(LogCategory.SERVICE, "Фоновый процесс остановлен")
        super.onDestroy()
    }

    /**
     * Слежение за входящими звонками включается из `onCreate`, то есть из главного потока:
     * `PhoneStateListener` на старых Android требует поток с очередью сообщений.
     */
    private fun startIncomingCallTracking() {
        try {
            incomingCallWatcher.start(incomingCallHandler)
        } catch (e: Exception) {
            logRepository.error(
                LogCategory.CALL_IN,
                "Не удалось включить слежение за входящими звонками",
                e.toString(),
            )
        }
    }

    private fun stopIncomingCallTracking() {
        try {
            incomingCallWatcher.stop()
        } catch (e: Exception) {
            logRepository.debug(LogCategory.CALL_IN, "Ошибка при отключении слежения за звонками", e.toString())
        }
        incomingCallHandler.stop()
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) {
            logRepository.debug(LogCategory.SERVICE, "Цикл уже работает")
            return
        }
        loopJob = scope.launch {
            logRepository.info(LogCategory.SERVICE, "Цикл запущен")
            while (isActive) {
                if (System.currentTimeMillis() >= nextSyncAtMs) {
                    runExchange()
                    nextSyncAtMs = System.currentTimeMillis() + currentIntervalMs()
                }
                runDueTasks()

                val wakeAtMs = nextWakeUpAtMs()
                SyncAlarmScheduler.schedule(this@GatewayService, wakeAtMs)
                val sleepMs = (wakeAtMs - System.currentTimeMillis()).coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
                // Просыпаемся раньше, если пришёл сигнал «появилась задача».
                withTimeoutOrNull(sleepMs) { wakeUpRequests.receive() }
            }
        }
    }

    /** Ближайшее пробуждение: пора на обмен или пора исполнять задачу. */
    private fun nextWakeUpAtMs(): Long {
        val nowMs = System.currentTimeMillis()
        var earliest = if (nextSyncAtMs > 0L) nextSyncAtMs else nowMs + currentIntervalMs()
        taskExecutor.nextPendingAtMs()?.let { taskAtMs -> if (taskAtMs < earliest) earliest = taskAtMs }
        return earliest.coerceAtLeast(nowMs + MIN_SLEEP_MS)
    }

    private fun runExchange() {
        val startedAtMs = System.currentTimeMillis()
        val outcome = withWakeLock(WAKE_LOCK_TIMEOUT_MS) {
            try {
                applicationContext.syncCoordinator.syncNow()
            } catch (e: Exception) {
                logRepository.error(LogCategory.SERVICE, "Цикл обмена прерван ошибкой", e.toString())
                null
            }
        }
        Notifications.update(this, notificationText(outcome))
        logRepository.debug(
            LogCategory.SERVICE,
            "Обмен завершён за ${System.currentTimeMillis() - startedAtMs} мс",
        )
    }

    private fun runDueTasks() {
        withWakeLock(WAKE_LOCK_TIMEOUT_MS) {
            try {
                taskExecutor.runDueTasks()
            } catch (e: Exception) {
                logRepository.error(LogCategory.SERVICE, "Исполнение задач прервано ошибкой", e.toString())
            }
        }
    }

    private fun notificationText(outcome: SyncOutcome?): String = when {
        outcome == null -> "ошибка обмена, подробности в логах"
        outcome.exchangeOk -> "последний обмен: ${Time.formatLocal(System.currentTimeMillis())}"
        outcome.error != null -> "обмен не удался: ${outcome.error}"
        else -> "ожидание данных для отправки"
    }

    private fun currentIntervalMs(): Long {
        val seconds = settingsRepository.current().intervalSeconds
            .coerceAtLeast(MIN_INTERVAL_SECONDS)
        return seconds * 1_000L
    }

    private fun startForegroundWith(text: String) {
        val notification = Notifications.buildOngoing(this, text)
        var lastError: Exception? = null

        foregroundServiceTypeCandidates().forEach { type ->
            try {
                ServiceCompat.startForeground(this, Notifications.ONGOING_ID, notification, type)
                logRepository.debug(LogCategory.SERVICE, "Служба переведена в foreground, тип $type")
                return
            } catch (e: Exception) {
                lastError = e
                logRepository.warn(
                    LogCategory.SERVICE,
                    "Система не приняла тип службы $type",
                    "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }

        logRepository.error(
            LogCategory.SERVICE,
            "Не удалось перевести службу в foreground",
            lastError?.let { "${it.javaClass.simpleName}: ${it.message}" }.orEmpty(),
        )
    }

    /**
     * Типы foreground-службы от самого точного к самому простому: если система не принимает
     * микрофонный тип (ограничения Android 11+ на работу в фоне), служба всё равно поднимется
     * как обмен данными — иначе обмен вообще не начнётся.
     */
    private fun foregroundServiceTypeCandidates(): List<Int> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        add(0)
    }

    /** Будим процесс на время работы: без этого таймеры засыпают вместе с устройством. */
    private fun <T> withWakeLock(timeoutMs: Long, block: () -> T): T {
        val powerManager = getSystemService(PowerManager::class.java)
        val lock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        } catch (_: Exception) {
            null
        }
        try {
            lock?.acquire(timeoutMs)
        } catch (_: Exception) {
            // Продолжаем без блокировки: работу всё равно попробуем выполнить.
        }
        return try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
                // Ничего не делаем: блокировку снимет система по таймауту.
            }
        }
    }

    companion object {
        /**
         * Сигнал «проверь очередь задач прямо сейчас»: приходит из ручной отправки, когда
         * сервер вернул команду с временем, которое уже наступило.
         */
        private val wakeUpRequests = Channel<Unit>(Channel.CONFLATED)

        /** Попросить работающий цикл проверить задачи, не дожидаясь планового пробуждения. */
        fun requestWakeUp() {
            wakeUpRequests.trySend(Unit)
        }

        private const val MIN_INTERVAL_SECONDS = 5
        private const val MIN_SLEEP_MS = 1_000L
        private const val MAX_SLEEP_MS = 10L * 60 * 1000
        private const val WAKE_LOCK_TAG = "phonegate:sync"

        /** Звонок может длиться десятки секунд, поэтому блокировка берётся с запасом. */
        private const val WAKE_LOCK_TIMEOUT_MS = 5L * 60 * 1000
    }
}
