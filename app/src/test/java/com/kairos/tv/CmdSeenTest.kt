package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comandi del pannello rimandati finché la conferma non arriva: eseguiti una volta sola */
class CmdSeenTest {
    @Test
    fun ilComandoNuovoSiEsegue() {
        val s = CmdSeen()
        assertNull(s.arrive(7))
    }

    @Test
    fun ilComandoInCorsoNonRiparte() {
        // es. «Aggiorna l'app»: il controllo è in corso quando il battito dopo rimanda lo stesso comando
        val s = CmdSeen()
        assertNull(s.arrive(7))
        assertSame(CmdSeen.BUSY, s.arrive(7))
    }

    @Test
    fun ilComandoGiaEseguitoSiConfermaSoltanto() {
        // conferma persa: il pannello rimanda «Reimposta PIN» e l'app non deve rifarlo, solo confermarlo di nuovo
        val s = CmdSeen()
        assertNull(s.arrive(9))
        s.finish(9, true, "PIN riportato a 0000")
        val d = s.arrive(9)!!
        assertTrue(d.ok)
        assertEquals("PIN riportato a 0000", d.info)
        assertEquals("PIN riportato a 0000", s.arrive(9)!!.info)
    }

    @Test
    fun ancheLEsitoNegativoSiRipete() {
        val s = CmdSeen()
        assertNull(s.arrive(3))
        s.finish(3, false, "visione in corso: rimandato")
        val d = s.arrive(3)!!
        assertEquals(false, d.ok)
        assertEquals("visione in corso: rimandato", d.info)
    }

    @Test
    fun restanoGliUltimiEsiti() {
        val s = CmdSeen(keep = 3)
        for (id in 1L..5L) { assertNull(s.arrive(id)); s.finish(id, true, "ok $id") }
        assertNull(s.arrive(1)) // il più vecchio è stato dimenticato
        assertEquals("ok 5", s.arrive(5)!!.info)
        assertEquals("ok 3", s.arrive(3)!!.info)
    }
}
