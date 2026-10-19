package ru.promcalc.phonegate

import android.app.Application
import android.content.Context
import ru.promcalc.phonegate.core.CrashLog
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.outgoing.OutgoingJournal
import ru.promcalc.phonegate.data.remote.SyncCoordinator
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.data.state.SyncStateStore
import ru.promcalc.phonegate.data.tasks.TaskExecutor
import ru.promcalc.phonegate.data.tasks.TaskStore

/**
 * Точка входа приложения и ручное внедрение зависимостей.
 *
 * Репозитории создаются лениво и живут столько же, сколько процесс: настройки и служебное
 * состояние читаются из SharedPreferences, поэтому отдельного контейнера DI не требуется.
 */
class PhoneGateApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val syncStateStore: SyncStateStore by lazy { SyncStateStore(this) }

    val logRepository: LogRepository by lazy { LogRepository(this) }

    val taskStore: TaskStore by lazy { TaskStore(this, logRepository) }

    val outgoingJournal: OutgoingJournal by lazy { OutgoingJournal(this, logRepository) }

    val syncCoordinator: SyncCoordinator by lazy { SyncCoordinator(this) }

    val taskExecutor: TaskExecutor by lazy { TaskExecutor(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        // Прогреваем настройки: на первом запуске фиксируется точка начала сбора данных.
        settingsRepository.current()
        reportPreviousCrash()
        logRepository.info(LogCategory.SERVICE, "Приложение запущено")
    }

    /**
     * Перед системным обработчиком (он показывает диалог «Приложение остановлено») сохраняем
     * трейс локально: отчёт из системного диалога уходит в Google и нам недоступен.
     */
    private fun installCrashHandler() {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.write(this, thread, error)
            systemHandler?.uncaughtException(thread, error)
        }
    }

    private fun reportPreviousCrash() {
        val trace = CrashLog.readAndDelete(this) ?: return
        logRepository.error(
            LogCategory.CRASH,
            "Приложение завершилось с ошибкой в прошлый раз",
            trace,
        )
    }

    companion object {
        private lateinit var instance: PhoneGateApp

        fun get(): PhoneGateApp = instance
    }
}

val Context.phoneGateApp: PhoneGateApp get() = PhoneGateApp.get()

val Context.settingsRepository: SettingsRepository get() = phoneGateApp.settingsRepository

val Context.syncStateStore: SyncStateStore get() = phoneGateApp.syncStateStore

val Context.logRepository: LogRepository get() = phoneGateApp.logRepository

val Context.taskStore: TaskStore get() = phoneGateApp.taskStore

val Context.outgoingJournal: OutgoingJournal get() = phoneGateApp.outgoingJournal

val Context.syncCoordinator: SyncCoordinator get() = phoneGateApp.syncCoordinator

val Context.taskExecutor: TaskExecutor get() = phoneGateApp.taskExecutor
