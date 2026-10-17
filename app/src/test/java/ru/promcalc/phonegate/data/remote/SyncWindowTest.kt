package ru.promcalc.phonegate.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWindowTest {

    private val historyStart = 1_700_000_000_000L
    private val overlap = SyncWindow.OVERLAP_MS

    @Test
    fun `без успешной отправки окно начинается ровно с точки сбора`() {
        assertEquals(historyStart, SyncWindow.start(historyStart, lastSuccessSyncAtMs = 0L))
    }

    @Test
    fun `после успешной отправки окно захватывает нахлёст`() {
        val lastSuccess = historyStart + 60 * 60 * 1000
        assertEquals(lastSuccess - overlap, SyncWindow.start(historyStart, lastSuccess))
    }

    @Test
    fun `более поздняя точка сбора важнее времени отправки`() {
        val lastSuccess = historyStart + 1_000
        val newHistoryStart = historyStart + 10 * 60 * 1000
        assertEquals(newHistoryStart - overlap, SyncWindow.start(newHistoryStart, lastSuccess))
    }

    @Test
    fun `окно не уходит в отрицательное время`() {
        val small = 60_000L
        assertEquals(0L, SyncWindow.start(small, small))
    }
}
