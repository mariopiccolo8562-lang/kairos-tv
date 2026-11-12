package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgShiftTest {
    @Test
    fun appliesOnlySupportedHourOffsets() {
        assertEquals(0L, EpgShift.seconds("0"))
        assertEquals(3_600L, EpgShift.seconds("1"))
        assertEquals(7_200L, EpgShift.seconds("2"))
        assertEquals(-3_600L, EpgShift.seconds("-1"))
        assertEquals(-7_200L, EpgShift.seconds("-2"))
        assertEquals(0L, EpgShift.seconds("12"))
    }
}
