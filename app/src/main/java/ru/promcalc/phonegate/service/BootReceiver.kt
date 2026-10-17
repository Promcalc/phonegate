package ru.promcalc.phonegate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.settingsRepository

/**
 * Автозапуск фонового процесса при включении телефона.
 *
 * Условие ровно как в ТЗ: поднимаем процесс только если включены и «запускать при включении
 * телефона», и сам фоновый процесс. Если пользователь остановил процесс вручную, после
 * перезагрузки он останется остановленным.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val settings = context.settingsRepository.current()
        when {
            settings.startOnBoot && settings.serviceEnabled -> {
                context.logRepository.info(
                    LogCategory.SERVICE,
                    "Автозапуск после включения телефона ($action)",
                )
                GatewayServiceControl.start(context)
            }

            settings.startOnBoot -> {
                context.logRepository.info(
                    LogCategory.SERVICE,
                    "Автозапуск пропущен: фоновый процесс был остановлен пользователем",
                )
            }

            else -> context.logRepository.debug(
                LogCategory.SERVICE,
                "Автозапуск выключен в настройках",
            )
        }
    }

    private companion object {
        /** `QUICKBOOT_POWERON` — расширение некоторых прошивок (HTC, Xiaomi). */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT,
        )
    }
}
