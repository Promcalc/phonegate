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
    private const val MIN_E164_DIGITS = 8
    private const val MAX_E164_DIGITS = 15

    fun digits(raw: String): String = raw.filter { it.isDigit() }

    /**
     * Приводит номер к E.164 или возвращает `null`, если номер содержит буквы,
     * другие недопустимые символы или не проходит проверку длины.
     *
     * Правила совпадают с бэкендом (`backend/internal/phone`): допускаются цифры,
     * ведущий `+`, пробелы, дефисы, скобки и точки; `8XXXXXXXXXX` и `XXXXXXXXXX`
     * считаются российскими номерами; префикс `00` заменяет `+`.
     */
    fun e164OrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val builder = StringBuilder(trimmed.length)
        trimmed.forEachIndexed { index, ch ->
            when {
                ch in '0'..'9' -> builder.append(ch)
                ch == '+' -> {
                    if (index != 0 || builder.isNotEmpty()) return null
                    builder.append(ch)
                }
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.' || ch == '\t' -> Unit
                else -> return null
            }
        }

        var digits = builder.toString().removePrefix("+")
        if (digits.startsWith("00")) digits = digits.removePrefix("00")
        if (digits.isEmpty() || digits[0] == '0') return null
        if (digits.any { it !in '0'..'9' }) return null
        digits = when {
            digits.length == 11 && digits[0] == '8' -> COUNTRY_CODE + digits.substring(1)
            digits.length == 10 -> COUNTRY_CODE + digits
            else -> digits
        }
        if (digits.length !in MIN_E164_DIGITS..MAX_E164_DIGITS) return null
        return "+$digits"
    }

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
