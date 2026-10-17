package ru.promcalc.phonegate.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogLevel
import ru.promcalc.phonegate.data.logs.LogRepository

/** Результат HTTP-запроса. */
sealed class ApiResult {
    /** Ответ 2xx. */
    data class Ok(val status: Int, val body: String) : ApiResult()

    /** Ответ не 2xx: данные не приняты, окно придётся отправить повторно. */
    data class HttpError(val status: Int, val body: String) : ApiResult()

    /** Сеть недоступна, таймаут, некорректный адрес и прочие сбои. */
    data class NetworkError(val message: String) : ApiResult()

    val isOk: Boolean get() = this is Ok

    fun describe(): String = when (this) {
        is Ok -> "успешно, код $status"
        is HttpError -> "сервер ответил кодом $status"
        is NetworkError -> message
    }
}

/**
 * Отправка отчётов на сервер.
 *
 * Запрос и ответ целиком попадают в лог (значение заголовка авторизации маскируется).
 * Повторов внутри запроса нет: неудачная отправка просто остаётся в накопленном окне,
 * а следующая попытка будет в следующем цикле.
 */
class PhoneGateApi(private val log: LogRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun postJson(
        url: String,
        authHeaderName: String,
        authHeaderValue: String,
        body: String,
        operation: String,
    ): ApiResult {
        val requestLog = buildString {
            append("POST ").append(url).append('\n')
            if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
                append("$authHeaderName: ***\n")
            } else {
                append("без заголовка авторизации\n")
            }
            append(body)
        }

        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")

        if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
            builder.header(authHeaderName, authHeaderValue)
        }

        log.info(LogCategory.HTTP, "$operation: запрос отправлен", requestLog)

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    log.warn(LogCategory.HTTP, "$operation: не удалось прочитать тело ответа", e.toString())
                    ""
                }

                log.log(
                    level = if (response.isSuccessful) LogLevel.INFO else LogLevel.WARN,
                    category = LogCategory.HTTP,
                    message = "$operation: ответ ${response.code}",
                    details = responseBody.ifEmpty { "(пустое тело)" },
                )

                if (response.isSuccessful) {
                    ApiResult.Ok(response.code, responseBody)
                } else {
                    ApiResult.HttpError(response.code, responseBody)
                }
            }
        } catch (e: Exception) {
            log.error(LogCategory.HTTP, "$operation: отправка не удалась", e.toString())
            ApiResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 60L
    }
}
