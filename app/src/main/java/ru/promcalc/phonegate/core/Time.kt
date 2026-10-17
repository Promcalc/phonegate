package ru.promcalc.phonegate.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Работа со временем в формате, требуемом ТЗ: `YYYY-MM-DDTHH:mm:ss+HH:mm`
 * (локальное время устройства с указанием смещения зоны).
 *
 * Реализовано на [Calendar], а не на `java.time`, чтобы не тянуть desugaring при minSdk 24.
 */
object Time {

    private val ISO_PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""",
    )

    /** Время с оффсетом: `2026-10-17T14:05:00+03:00`. */
    fun formatIso(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = epochMs }
        val offsetMinutes = tz.getOffset(epochMs) / 60_000
        val sign = if (offsetMinutes < 0) "-" else "+"
        val absOffset = abs(offsetMinutes)
        return String.format(
            Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            sign,
            absOffset / 60,
            absOffset % 60,
        )
    }

    /**
     * Разбор времени из ответа сервера. Понимает оффсет (`+03:00`), `Z` и время без зоны
     * (трактуется как локальное). Возвращает `null`, если строку разобрать не удалось.
     */
    fun parseIsoOrNull(text: String): Long? {
        val m = ISO_PATTERN.matchEntire(text.trim()) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        val hour = m.groupValues[4].toIntOrNull() ?: return null
        val minute = m.groupValues[5].toIntOrNull() ?: return null
        val second = m.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        // Группа 7 — зона: суффикс дробных секунд объявлен non-capturing и групп не создаёт.
        val zone = m.groupValues[7]

        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear() }
            calendar.set(year, month - 1, day, hour, minute, second)
            var millis = calendar.timeInMillis

            when {
                zone.isEmpty() -> {
                    val local = Calendar.getInstance().apply { clear() }
                    local.set(year, month - 1, day, hour, minute, second)
                    millis = local.timeInMillis
                }

                zone == "Z" -> Unit

                else -> {
                    val sign = if (zone[0] == '-') -1 else 1
                    val digits = zone.substring(1).replace(":", "")
                    if (digits.length != 4) return null
                    val offsetMinutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
                    millis -= sign * offsetMinutes * 60_000L
                }
            }
            millis
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Локальная дата `ГГГГ-ММ-ДД` — для имён файлов логов. */
    fun formatDate(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Человекочитаемое локальное время для интерфейса и логов. */
    fun formatLocal(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return String.format(
            Locale.US,
            "%02d.%02d.%04d %02d:%02d:%02d",
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    }
}
