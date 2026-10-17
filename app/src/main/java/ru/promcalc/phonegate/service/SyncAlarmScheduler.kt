package ru.promcalc.phonegate.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.logRepository

/**
 * Страховочный будильник: если система убьёт процесс, будильник снова поднимет службу.
 *
 * Обычно цикл обмена живёт внутри службы; будильник — вторая линия обороны для прошивок,
 * которые агрессивно выгружают фон. Если точные будильники запрещены, ставим неточный.
 */
object SyncAlarmScheduler {

    private const val REQUEST_CODE = 20
    private const val MIN_DELAY_MS = 5_000L

    fun schedule(context: Context, atMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = atMs.coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MS)
        val pendingIntent = pendingIntent(context)

        try {
            val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            context.logRepository.warn(
                LogCategory.SERVICE,
                "Точные будильники запрещены, ставлю неточный",
                e.toString(),
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } catch (inner: Exception) {
                context.logRepository.error(LogCategory.SERVICE, "Не удалось поставить будильник", inner.toString())
            }
        } catch (e: Exception) {
            context.logRepository.error(LogCategory.SERVICE, "Не удалось поставить будильник", e.toString())
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.cancel(pendingIntent(context))
        } catch (_: Exception) {
            // Отменять нечего — это не ошибка.
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, GatewayCommandReceiver::class.java)
            .setAction(GatewayCommandReceiver.ACTION_SYNC_NOW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
