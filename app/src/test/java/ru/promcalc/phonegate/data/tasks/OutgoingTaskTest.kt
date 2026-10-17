package ru.promcalc.phonegate.data.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingTaskTest {

    private fun task(
        kind: TaskKind = TaskKind.SEND_SMS,
        simSlot: Int = 0,
        phone: String = "+79001234567",
        text: String = "привет",
        atMs: Long = 1_000L,
    ) = OutgoingTask(
        id = 1L,
        kind = kind,
        simSlot = simSlot,
        phone = phone,
        text = text,
        atMs = atMs,
        createdAtMs = 0L,
    )

    @Test
    fun `одинаковые команды распознаются как дубли`() {
        assertTrue(task().sameRequestAs(task()))
    }

    @Test
    fun `разное время или номер делает команды разными`() {
        assertFalse(task().sameRequestAs(task(atMs = 2_000L)))
        assertFalse(task().sameRequestAs(task(phone = "+79007654321")))
        assertFalse(task().sameRequestAs(task(text = "другой текст")))
        assertFalse(task().sameRequestAs(task(simSlot = 1)))
        assertFalse(task().sameRequestAs(task(kind = TaskKind.MAKE_CALL)))
    }
}
