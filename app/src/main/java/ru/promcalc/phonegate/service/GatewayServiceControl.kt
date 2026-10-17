package ru.promcalc.phonegate.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.settingsRepository

/**
 * Управление фоновым процессом: единственное место, где служба запускается и останавливается.
 *
 * Настройка `serviceEnabled` — сохранённое намерение пользователя «процесс должен работать».
 * Именно она (вместе с `startOnBoot`) решает, поднимать ли службу при включении телефона.
 */
object GatewayServiceControl {

    /** Включить или выключить фон, обновив настройку «процесс должен работать». */
    fun setEnabled(context: Context, enabled: Boolean) {
        val repository = context.settingsRepository
        val current = repository.current()
        if (current.serviceEnabled != enabled) {
            repository.save(current.copy(serviceEnabled = enabled))
        }
        if (enabled) start(context) else stop(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, GatewayService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Android 12+: старт foreground-службы из фона запрещён (ForegroundServiceStartNotAllowedException).
            reportStartFailure(context, e)
        } catch (e: SecurityException) {
            reportStartFailure(context, e)
        } catch (e: Exception) {
            reportStartFailure(context, e)
        }
    }

    fun stop(context: Context) {
        SyncAlarmScheduler.cancel(context)
        try {
            context.stopService(Intent(context, GatewayService::class.java))
        } catch (_: Exception) {
            // Служба уже не запущена — это не ошибка.
        }
    }

    /** Перезапуск после сохранения настроек: цикл должен работать с новыми значениями. */
    fun restart(context: Context) {
        if (!context.settingsRepository.current().serviceEnabled) {
            stop(context)
            return
        }
        stop(context)
        start(context)
    }

    /** Поднять службу, если она должна работать (сработал будильник, пришло время цикла). */
    fun ensureRunning(context: Context) {
        if (context.settingsRepository.current().serviceEnabled) {
            start(context)
        }
    }

    private fun reportStartFailure(context: Context, error: Exception) {
        context.logRepository.error(
            LogCategory.SERVICE,
            "Не удалось запустить фоновый процесс",
            "${error.javaClass.simpleName}: ${error.message}",
        )
        Notifications.notifyProblem(
            context,
            "Система не разрешила запустить фоновый процесс. Откройте приложение и запустите процесс вручную.",
        )
    }
}
