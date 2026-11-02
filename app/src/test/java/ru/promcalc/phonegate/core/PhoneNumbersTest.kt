package ru.promcalc.phonegate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun `одинаковые номера в разном формате считаются равными`() {
        assertTrue(PhoneNumbers.same("+7 900 123-45-67", "79001234567"))
        assertTrue(PhoneNumbers.same("+79001234567", "89001234567"))
        assertTrue(PhoneNumbers.same("9001234567", "+7 (900) 123-45-67"))
    }

    @Test
    fun `разные номера не совпадают`() {
        assertFalse(PhoneNumbers.same("+79001234567", "+79007654321"))
    }

    @Test
    fun `пустые строки не совпадают`() {
        assertFalse(PhoneNumbers.same("", "+79001234567"))
        assertFalse(PhoneNumbers.same("+79001234567", ""))
    }

    @Test
    fun `короткие номера сравниваются только точно`() {
        assertTrue(PhoneNumbers.same("112", "112"))
        assertFalse(PhoneNumbers.same("12", "112"))
    }

    @Test
    fun `нормализация оставляет только цифры`() {
        assertEquals("79001234567", PhoneNumbers.normalize("+7 (900) 123-45-67"))
        assertEquals("Beeline", PhoneNumbers.normalize("Beeline"))
    }

    @Test
    fun `E164 принимает российские форматы`() {
        assertEquals("+79001234567", PhoneNumbers.e164OrNull("+7 (900) 123-45-67"))
        assertEquals("+79001234567", PhoneNumbers.e164OrNull("79001234567"))
        assertEquals("+79001234567", PhoneNumbers.e164OrNull("89001234567"))
        assertEquals("+79001234567", PhoneNumbers.e164OrNull("9001234567"))
    }

    @Test
    fun `E164 сохраняет международный номер`() {
        assertEquals("+442071838750", PhoneNumbers.e164OrNull("+44 20 7183 8750"))
        assertEquals("+442071838750", PhoneNumbers.e164OrNull("00 44 20 7183 8750"))
    }

    @Test
    fun `E164 отклоняет буквы и некорректные номера`() {
        assertEquals(null, PhoneNumbers.e164OrNull("Beeline"))
        assertEquals(null, PhoneNumbers.e164OrNull("SBER"))
        assertEquals(null, PhoneNumbers.e164OrNull(""))
        assertEquals(null, PhoneNumbers.e164OrNull("+0123456789"))
        assertEquals(null, PhoneNumbers.e164OrNull("+123"))
        assertEquals(null, PhoneNumbers.e164OrNull("1234567890123456"))
    }
}
