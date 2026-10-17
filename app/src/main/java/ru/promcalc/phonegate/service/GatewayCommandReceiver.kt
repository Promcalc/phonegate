package ru.promcalc.phonegate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.logRepository

/**
 * Команды службе, приходящие извне её цикла: подъём по будильнику и остановка из
 * уведомления.
 *
 * Остановка сделана приёмником, а не `Intent` в службу: запуск службы из фона ограничен,
 * а остановка разрешена всегда.
 */
class GatewayCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SYNC_NOW -> {
                context.logRepository.debug(
                    LogCategory.SERVICE,
                    "Сработал будильник, проверяю состояние службы",
                )
                GatewayServiceControl.ensureRunning(context)
            }

            ACTION_STOP -> {
                context.logRepository.info(LogCategory.SERVICE, "Остановка из уведомления")
                GatewayServiceControl.setEnabled(context, enabled = false)
            }
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "ru.promcalc.phonegate.action.SYNC_NOW"
        const val ACTION_STOP = "ru.promcalc.phonegate.action.STOP"
    }
}
