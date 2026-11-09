package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Riquadro della qualità nel lettore dal pannello (voce rc["qbox"]): logica pura di [QBox] */
class QBoxTest {
    /** tutto quello che il pannello può mandare (o non mandare) e che deve valere come oggi */
    private val likeToday = listOf("full", "", null, "null", "OFF", " off", "noq ", "qualsiasi")

    @Test
    fun soloOffENoqCambianoQualcosa() {
        assertEquals(QBox.OFF, QBox.mode("off"))
        assertEquals(QBox.NOQ, QBox.mode("noq"))
        for (v in likeToday) assertEquals("«$v»", QBox.FULL, QBox.mode(v))
    }

    @Test
    fun conOffIlRiquadroNonCompareMai() {
        assertFalse(QBox.shown(true, "off"))
        assertFalse(QBox.shown(false, "off"))
    }

    @Test
    fun ilPermessoResIndBloccatoLoNascondeSempre() {
        for (v in likeToday + listOf("noq", "off")) assertFalse("«$v»", QBox.shown(false, v))
    }

    @Test
    fun conNoqEGliAltriValoriIlRiquadroCompare() {
        for (v in likeToday + "noq") assertTrue("«$v»", QBox.shown(true, v))
    }

    @Test
    fun sigleDallAltezza() {
        assertEquals("4K", QBox.tag(2160))
        assertEquals("4K", QBox.tag(2000))
        assertEquals("FHD", QBox.tag(1999))
        assertEquals("FHD", QBox.tag(1080))
        assertEquals("FHD", QBox.tag(1000))
        assertEquals("HD", QBox.tag(999))
        assertEquals("HD", QBox.tag(720))
        assertEquals("HD", QBox.tag(700))
        assertEquals("SD", QBox.tag(699))
        assertEquals("SD", QBox.tag(576))
    }

    @Test
    fun testoComeOggi() {
        for (v in likeToday) {
            assertEquals("FHD · 1080p · 50 fps", QBox.text("FHD", 1080, "50", v))
            assertEquals("4K · 2160p · 25 fps", QBox.text(QBox.tag(2160), 2160, "25", v))
            assertEquals("HD · 720p · 29,97 fps", QBox.text("HD", 720, "29,97", v))
            assertEquals("FHD · 1080p", QBox.text("FHD", 1080, "", v)) // FPS non ancora noti
        }
    }

    @Test
    fun conNoqSenzaLaSigla() {
        assertEquals("1080p · 50 fps", QBox.text("FHD", 1080, "50", "noq"))
        assertEquals("2160p · 25 fps", QBox.text("4K", 2160, "25", "noq"))
        assertEquals("2160p · 50 fps", QBox.text("UHD", 2160, "50", "noq"))
        assertEquals("720p · 29,97 fps", QBox.text("HD", 720, "29,97", "noq"))
        assertEquals("576p", QBox.text("SD", 576, "", "noq"))
    }

    @Test
    fun maiSeparatoriDoppiAllInizioOAllaFine() {
        for (q in listOf("FHD", "4K", "", " ")) for (fps in listOf("50", "", " ")) for (v in listOf("full", "noq", null)) {
            val t = QBox.text(q, 1080, fps, v)
            // ogni parte fra due « · » c'è davvero: niente parti vuote, quindi niente « · » doppi né ai bordi
            assertTrue("«$t»", t.split(" · ").all { it.isNotBlank() && it == it.trim() })
            assertFalse("«$t»", t.startsWith("·") || t.endsWith("·") || t != t.trim())
        }
    }

    @Test
    fun senzaAltezzaNessunRiquadro() {
        for (v in listOf("full", "noq", null)) {
            assertEquals("", QBox.text("FHD", 0, "50", v))
            assertEquals("", QBox.text("SD", -1, "", v))
        }
    }
}
