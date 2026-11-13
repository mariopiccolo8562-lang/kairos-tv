package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsBadgeTest {
    @Test
    fun senzaLaVoceDelPannelloNonCENiente() {
        assertEquals("", DnsBadge.state(false, false, "on"))
        assertEquals("", DnsBadge.state(false, true, "off"))
        assertEquals("", DnsBadge.text(""))
    }

    @Test
    fun conLaVpnDelFornitoreCiPensaLei() {
        assertEquals("vpn", DnsBadge.state(true, true, "off"))
        assertTrue(DnsBadge.on("vpn"))
        assertFalse(DnsBadge.warn("vpn"))
    }

    @Test
    fun statoDelServizio() {
        assertEquals("on", DnsBadge.state(true, false, "on"))
        assertEquals("denied", DnsBadge.state(true, false, "denied"))
        assertEquals("off", DnsBadge.state(true, false, ""))
        assertTrue(DnsBadge.on("on"))
        for (s in listOf("asking", "denied", "error", "other")) {
            assertTrue(s, DnsBadge.warn(s))
            assertFalse(s, DnsBadge.on(s))
        }
        assertFalse(DnsBadge.warn("off"))
        assertFalse(DnsBadge.on("off"))
    }

    @Test
    fun coloriDiversiPerAttivoAttenzioneEAvvio() {
        assertEquals(3, setOf(DnsBadge.color("on"), DnsBadge.color("denied"), DnsBadge.color("off")).size)
        assertEquals(DnsBadge.color("on"), DnsBadge.color("vpn"))
    }

    @Test
    fun siRiprovaSoloDopoUnRifiutoOUnErrore() {
        assertTrue(DnsBadge.canRetry("denied"))
        assertTrue(DnsBadge.canRetry("error"))
        for (s in listOf("on", "vpn", "asking", "other", "off", "")) assertFalse(s, DnsBadge.canRetry(s))
    }

    @Test
    fun ogniStatoHaLaSuaFrase() {
        for (s in listOf("on", "vpn", "asking", "denied", "error", "other", "off")) assertTrue(s, DnsBadge.text(s).startsWith("DNS"))
    }
}
