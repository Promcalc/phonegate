package ru.promcalc.phonegate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.promcalc.phonegate.R
import ru.promcalc.phonegate.ui.MainActivity

/** Уведомления: постоянное уведомление службы и сообщения о проблемах. */
object Notifications {

    const val CHANNEL_SERVICE = "phonegate_service"
    const val CHANNEL_EVENTS = "phonegate_events"

    const val ONGOING_ID = 1001
    const val PROBLEM_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_SERVICE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "Фоновый процесс",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Постоянное уведомление о работе обмена с сервером"
                    setShowBadge(false)
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_EVENTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS,
                    "События",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Проблемы, из-за которых обмен не работает"
                },
            )
        }
    }

    fun buildOngoing(context: Context, text: String): Notification {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, GatewayCommandReceiver::class.java)
                .setAction(GatewayCommandReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_phonegate)
            .setContentTitle("Phonegate")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "Остановить", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Обновить текст постоянного уведомления. */
    fun update(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(ONGOING_ID, buildOngoing(context, text))
        } catch (_: Exception) {
            // Без разрешения POST_NOTIFICATIONS уведомление просто не показывается.
        }
    }

    /** Сообщить о проблеме, которую пользователь должен решить вручную. */
    fun notifyProblem(context: Context, message: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_phonegate)
            .setContentTitle("Phonegate: нужно внимание")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        try {
            manager.notify(PROBLEM_ID, notification)
        } catch (_: Exception) {
            // См. комментарий выше.
        }
    }

    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_STOP = 11
}
