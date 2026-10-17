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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.promcalc.phonegate.core.Time
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.remote.SyncOutcome
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.settingsRepository
import ru.promcalc.phonegate.syncCoordinator

/**
 * Фоновый процесс: раз в заданный интервал выполняет обмен с сервером.
 *
 * Служба постоянная (`START_STICKY`), с постоянным уведомлением. Пауза между циклами
 * отсчитывается по часам, поэтому длительность самого обмена не сдвигает расписание.
 * Будильник переставляется на каждый следующий цикл — если систему убьёт процесс, она
 * поднимет службу по будильнику.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundWith("подготовка")
        logRepository.info(LogCategory.SERVICE, "Фоновый процесс создан")
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
        val intervalMs = currentIntervalMs()
        SyncAlarmScheduler.schedule(this, System.currentTimeMillis() + intervalMs)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        logRepository.info(LogCategory.SERVICE, "Фоновый процесс остановлен")
        super.onDestroy()
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) {
            logRepository.debug(LogCategory.SERVICE, "Цикл обмена уже работает")
            return
        }
        loopJob = scope.launch {
            logRepository.info(LogCategory.SERVICE, "Цикл обмена запущен")
            while (isActive) {
                val intervalMs = currentIntervalMs()
                runCycle()
                SyncAlarmScheduler.schedule(this@GatewayService, System.currentTimeMillis() + intervalMs)
                delay(intervalMs)
            }
        }
    }

    private fun runCycle() {
        val startedAtMs = System.currentTimeMillis()
        val outcome = withWakeLock {
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
            "Цикл завершён за ${System.currentTimeMillis() - startedAtMs} мс",
        )
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

    /** Будим процесс на время обмена: без этого таймеры засыпают вместе с устройством. */
    private fun <T> withWakeLock(block: () -> T): T {
        val powerManager = getSystemService(PowerManager::class.java)
        val lock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        } catch (_: Exception) {
            null
        }
        try {
            lock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (_: Exception) {
            // Продолжаем без блокировки: обмен всё равно попробуем выполнить.
        }
        return try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
                // Ничего не делаем: снять блокировку не удалось, система снимет сама по таймауту.
            }
        }
    }

    private companion object {
        const val MIN_INTERVAL_SECONDS = 5
        const val WAKE_LOCK_TAG = "phonegate:sync"
        const val WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 1000
    }
}
