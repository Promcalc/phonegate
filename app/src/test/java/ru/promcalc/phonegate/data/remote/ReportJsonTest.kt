package ru.promcalc.phonegate.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {

    @Test
    fun `отчёт сериализуется по контракту`() {
        val payload = ReportPayload(
            syncId = "6f1c1e2a-6f0b-4a4e-9d1e-1f2a3b4c5d6e",
            perSim = linkedMapOf(
                "+79001234567" to SimPayloadDto(
                    sms = listOf(
                        SmsReportDto(
                            time = "2026-10-17T14:05:00+03:00",
                            text = "Привет",
                            phone = "+79007654321",
                        ),
                    ),
                    call = listOf(
                        CallReportDto(
                            time = "2026-10-17T14:01:12+03:00",
                            text = "",
                            phone = "+79007654321",
                            duration = 47,
                            direction = "in",
                        ),
                    ),
                ),
                "+79009999999" to SimPayloadDto(),
            ),
        )

        val actual = Json.parseToJsonElement(ReportJson.encodeReport(payload))

        val expected = Json.parseToJsonElement(
            """
            {
              "sync_id": "6f1c1e2a-6f0b-4a4e-9d1e-1f2a3b4c5d6e",
              "+79001234567": {
                "sms": [
                  {
                    "time": "2026-10-17T14:05:00+03:00",
                    "text": "Привет",
                    "phone": "+79007654321"
                  }
                ],
                "call": [
                  {
                    "time": "2026-10-17T14:01:12+03:00",
                    "text": "",
                    "phone": "+79007654321",
                    "duration": 47,
                    "direction": "in"
                  }
                ]
              },
              "+79009999999": {
                "sms": [],
                "call": []
              }
            }
            """.trimIndent(),
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `ключи идут в порядке номеров SIM`() {
        val encoded = ReportJson.encodeReport(
            ReportPayload(
                syncId = "id",
                perSim = linkedMapOf("первый" to SimPayloadDto(), "второй" to SimPayloadDto()),
            ),
        )
        assertTrue(encoded.indexOf("первый") < encoded.indexOf("второй"))
        assertTrue(encoded.startsWith("{\"sync_id\":\"id\","))
    }

    @Test
    fun `ответ разбирается в команды`() {
        val body = """
            {
              "+79001234567": {
                "sms": [
                  {"time": "2026-10-17T15:00:00+03:00", "text": "Ваш заказ готов", "phone": "+79007654321"}
                ],
                "call": [
                  {"time": "2026-10-17T15:05:00+03:00", "text": "", "phone": "+79007654321"}
                ]
              }
            }
        """.trimIndent()

        val root = ReportJson.parseRoot(body)
        assertNotNull(root)

        val commands = ReportJson.decodeSimCommands(root!!.getValue("+79001234567"))
        assertNotNull(commands)
        assertEquals(1, commands!!.sms.size)
        assertEquals("Ваш заказ готов", commands.sms[0].text)
        assertEquals(1, commands.call.size)
        assertEquals("+79007654321", commands.call[0].phone)
    }

    @Test
    fun `лишние поля в ответе не ломают разбор`() {
        val root = ReportJson.parseRoot(
            """{"+79001234567": {"sms": [], "call": [], "comment": "ignore me"}}""",
        )
        assertNotNull(root)
        assertNotNull(ReportJson.decodeSimCommands(root!!.getValue("+79001234567")))
    }

    @Test
    fun `некорректный JSON не разбирается`() {
        assertNull(ReportJson.parseRoot("не json"))
        assertNull(ReportJson.parseRoot(""))
    }
}
