package ru.promcalc.phonegate.ui.access

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Разрешение, которое запрашивается у пользователя обычным диалогом. */
data class PermissionItem(
    val permission: String,
    val title: String,
    val explanation: String,
    val granted: Boolean,
)

/** Особый доступ: выдаётся не диалогом, а переходом в системные настройки. */
data class SpecialAccessItem(
    val key: String,
    val title: String,
    val explanation: String,
    val granted: Boolean,
    val settingsIntent: Intent?,
)

/**
 * Что приложению нужно от системы.
 *
 * Список собран по факту использования: чтение SMS и журнала вызовов, отправка SMS, звонки,
 * определение SIM и состояния звонка, микрофон для записи ответа, уведомление о работе
 * фонового процесса.
 */
object AccessChecks {

    fun runtimePermissions(context: Context): List<PermissionItem> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                item(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                    "Уведомления",
                    "Постоянное уведомление о работе процесса и сообщения о проблемах",
                ),
            )
        }
        add(
            item(
                context,
                Manifest.permission.READ_SMS,
                "Чтение SMS",
                "Входящие сообщения уходят на сервер",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.SEND_SMS,
                "Отправка SMS",
                "Выполнение команд сервера «отправить SMS»",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.READ_CALL_LOG,
                "Журнал вызовов",
                "Входящие, исходящие и пропущенные звонки уходят на сервер",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.READ_PHONE_STATE,
                "Состояние телефона",
                "Определение SIM и слежение за входящим звонком",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.READ_PHONE_NUMBERS,
                "Номера телефонов",
                "Подсказка номера SIM в настройках, если оператор его записал",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS,
                "Ответ на звонки",
                "Автоматическое снятие трубки на входящем звонке",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.CALL_PHONE,
                "Звонки",
                "Выполнение команд сервера «позвонить»",
            ),
        )
        add(
            item(
                context,
                Manifest.permission.RECORD_AUDIO,
                "Микрофон",
                "Запись ответа абонента после приветствия",
            ),
        )
    }

    fun specialAccess(context: Context): List<SpecialAccessItem> = buildList {
        add(batteryOptimization(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(exactAlarms(context))
        }
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Не выдано что-то из нужного: с этого экрана начинается первый запуск. */
    fun hasMissingCritical(context: Context): Boolean =
        runtimePermissions(context).any { !it.granted } || specialAccess(context).any { !it.granted }

    private fun item(context: Context, permission: String, title: String, explanation: String) =
        PermissionItem(
            permission = permission,
            title = title,
            explanation = explanation,
            granted = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )

    private fun batteryOptimization(context: Context): SpecialAccessItem {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
        return SpecialAccessItem(
            key = KEY_BATTERY,
            title = "Свободная работа в фоне",
            explanation = "Без исключения из оптимизации батареи система может останавливать обмен данными",
            granted = ignoring,
            settingsIntent = intent.takeIf { !ignoring },
        )
    }

    private fun exactAlarms(context: Context): SpecialAccessItem {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        val allowed = alarmManager?.canScheduleExactAlarms() == true
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.fromParts("package", context.packageName, null))
        return SpecialAccessItem(
            key = KEY_EXACT_ALARM,
            title = "Точные будильники",
            explanation = "Нужны, чтобы задачи сервера исполнялись в назначенное время, а процесс " +
                "поднимался после остановки системой",
            granted = allowed,
            settingsIntent = intent.takeIf { !allowed },
        )
    }

    const val KEY_BATTERY = "battery"
    const val KEY_EXACT_ALARM = "exact_alarm"
}
