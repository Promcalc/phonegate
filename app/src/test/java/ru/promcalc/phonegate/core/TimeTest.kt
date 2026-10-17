package ru.promcalc.phonegate.core

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTest {

    private val moscowLike: TimeZone = TimeZone.getTimeZone("GMT+03:00")

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear() }
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis
    }

    @Test
    fun `форматирует время с оффсетом`() {
        val instant = utcMillis(2026, 10, 17, 11, 5, 0)
        assertEquals("2026-10-17T14:05:00+03:00", Time.formatIso(instant, moscowLike))
    }

    @Test
    fun `форматирует отрицательный оффсет`() {
        val instant = utcMillis(2026, 1, 2, 15, 0, 7)
        assertEquals("2026-01-02T10:00:07-05:00", Time.formatIso(instant, TimeZone.getTimeZone("GMT-05:00")))
    }

    @Test
    fun `разбирает время с оффсетом`() {
        val expected = utcMillis(2026, 10, 17, 11, 5, 0)
        assertEquals(expected, Time.parseIsoOrNull("2026-10-17T14:05:00+03:00"))
    }

    @Test
    fun `разбирает время с отрицательным оффсетом без секунд`() {
        val expected = utcMillis(2026, 3, 1, 17, 30, 0)
        assertEquals(expected, Time.parseIsoOrNull("2026-03-01T12:30-05:00"))
    }

    @Test
    fun `разбирает время в UTC`() {
        val expected = utcMillis(2026, 5, 9, 6, 15, 42)
        assertEquals(expected, Time.parseIsoOrNull("2026-05-09T06:15:42Z"))
    }

    @Test
    fun `время без зоны читается как локальное`() {
        val local = Calendar.getInstance().apply { clear(); set(2026, 6, 1, 8, 45, 0) }
        assertEquals(local.timeInMillis, Time.parseIsoOrNull("2026-07-01T08:45:00"))
    }

    @Test
    fun `поддерживает оффсет без двоеточия`() {
        val expected = utcMillis(2026, 10, 17, 11, 5, 0)
        assertEquals(expected, Time.parseIsoOrNull("2026-10-17T14:05:00+0300"))
    }

    @Test
    fun `возвращает null на некорректной строке`() {
        assertNull(Time.parseIsoOrNull("17.10.2026 14:05"))
        assertNull(Time.parseIsoOrNull(""))
        assertNull(Time.parseIsoOrNull("2026-10-17T14:05:00+3"))
    }

    @Test
    fun `формат и разбор согласованы`() {
        val instant = utcMillis(2026, 12, 31, 21, 0, 30)
        val text = Time.formatIso(instant, moscowLike)
        assertEquals(instant, Time.parseIsoOrNull(text))
    }
}
