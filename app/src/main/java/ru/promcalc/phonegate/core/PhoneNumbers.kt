package ru.promcalc.phonegate.core

/**
 * Сравнение телефонных номеров.
 *
 * Номера приходят из разных источников (настройки, журнал вызовов, SMS-провайдер, ответ
 * сервера) и записаны по-разному: `+7 900 123-45-67`, `79001234567`, `89001234567`.
 * Поэтому сравниваем по цифрам, приводя номер к каноническому виду: для 11-значных номеров
 * ведущая восьмёрка заменяется на семёрку, а 10-значный номер считается номером без кода
 * страны (приложение рассчитано на российские SIM).
 */
object PhoneNumbers {

    private const val MIN_COMPARABLE_DIGITS = 10
    private const val COUNTRY_CODE = "7"

    fun digits(raw: String): String = raw.filter { it.isDigit() }

    /** Канонический вид номера: только цифры, `8…` → `7…`, 10 цифр → `7` + номер. */
    fun canonical(raw: String): String {
        val digits = digits(raw)
        return when {
            digits.length == 11 && digits.startsWith("8") -> COUNTRY_CODE + digits.substring(1)
            digits.length == 10 -> COUNTRY_CODE + digits
            else -> digits
        }
    }

    /** Похожи ли номера на один и тот же. */
    fun same(left: String, right: String): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length < MIN_COMPARABLE_DIGITS || b.length < MIN_COMPARABLE_DIGITS) return false
        // Последние 10 цифр совпадают у одного и того же абонента даже при разных кодах.
        return a.takeLast(MIN_COMPARABLE_DIGITS) == b.takeLast(MIN_COMPARABLE_DIGITS)
    }

    /** Нормализованный вид для журналов и логов. */
    fun normalize(raw: String): String = digits(raw).ifEmpty { raw.trim() }
}
