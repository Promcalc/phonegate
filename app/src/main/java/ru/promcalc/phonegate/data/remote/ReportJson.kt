package ru.promcalc.phonegate.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Кодирование отчёта и разбор ответа.
 *
 * В отчёте ключи верхнего уровня — номера телефонов SIM, поэтому JSON собирается вручную:
 * динамические ключи моделями kotlinx.serialization не выразить.
 */
object ReportJson {

    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** `{"sync_id": "...", "<номер>": {"sms": [...], "call": [...]}}`. */
    fun encodeReport(payload: ReportPayload): String {
        val root = buildJsonObject {
            put("sync_id", payload.syncId)
            payload.perSim.forEach { (phone, simPayload) ->
                put(phone, json.encodeToJsonElement(simPayload))
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Корневой объект ответа или `null`, если тело не разбирается. */
    fun parseRoot(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** Команды одного номера; `null`, если структура не подходит. */
    fun decodeSimCommands(element: JsonElement): SimCommandsDto? = try {
        json.decodeFromJsonElement<SimCommandsDto>(element)
    } catch (_: Exception) {
        null
    }
}
