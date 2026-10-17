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
}
