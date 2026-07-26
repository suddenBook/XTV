package com.xtv.app.core.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrdinalsTest {

    @Test
    fun `english ordinals follow the last digit`() {
        assertEquals("1st", Ordinals.dayOfMonth(1, "en"))
        assertEquals("2nd", Ordinals.dayOfMonth(2, "en"))
        assertEquals("3rd", Ordinals.dayOfMonth(3, "en"))
        assertEquals("4th", Ordinals.dayOfMonth(4, "en"))
        assertEquals("26th", Ordinals.dayOfMonth(26, "en"))
    }

    @Test
    fun `the teens are the exception to the last-digit rule`() {
        assertEquals("11th", Ordinals.dayOfMonth(11, "en"))
        assertEquals("12th", Ordinals.dayOfMonth(12, "en"))
        assertEquals("13th", Ordinals.dayOfMonth(13, "en"))
    }

    @Test
    fun `the twenties and thirty-first resume the last-digit rule`() {
        assertEquals("21st", Ordinals.dayOfMonth(21, "en"))
        assertEquals("22nd", Ordinals.dayOfMonth(22, "en"))
        assertEquals("23rd", Ordinals.dayOfMonth(23, "en"))
        assertEquals("31st", Ordinals.dayOfMonth(31, "en"))
    }

    @Test
    fun `other string sets get a bare number and supply their own suffix`() {
        assertEquals("26", Ordinals.dayOfMonth(26, "zh"))
        assertEquals("1", Ordinals.dayOfMonth(1, "zh"))
    }

    @Test
    fun `a day outside the month is refused rather than rendered`() {
        // X returns cap_reset_day as a bare number; a 0 would otherwise print as "the 0th".
        assertNull(Ordinals.dayOfMonth(0, "en"))
        assertNull(Ordinals.dayOfMonth(32, "en"))
        assertNull(Ordinals.dayOfMonth(-1, "zh"))
    }
}
