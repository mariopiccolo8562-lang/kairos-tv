package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** aggiornamento senza conferma: quando lo si usa, VPN e riga per il «Blocco automatico» dei Samsung (Quiet.kt) */
class QuietTest {
    @Test fun soloDaAndroid12ConPermesso() {
        assertTrue(Quiet.handles(31, true, 0, 120, ready = true, willFetch = false))
        assertTrue(Quiet.handles(35, true, 119, 120, ready = false, willFetch = true))
        assertFalse("Android 11", Quiet.handles(30, true, 0, 120, ready = true, willFetch = true))
        assertFalse("senza «Installa app sconosciute»", Quiet.handles(34, false, 0, 120, ready = true, willFetch = true))
    }

    @Test fun unTentativoPerVersione() {
        assertFalse(Quiet.handles(34, true, 120, 120, ready = true, willFetch = true))
        assertTrue("versione dopo", Quiet.handles(34, true, 120, 121, ready = true, willFetch = false))
    }

    @Test fun senzaApkNeScaricamentoNo() {
        assertFalse(Quiet.handles(34, true, 0, 120, ready = false, willFetch = false))
    }

    @Test fun vpn() {
        assertTrue(Quiet.vpnOk(active = false, appOnly = false))
        assertTrue("VPN solo dell'app", Quiet.vpnOk(active = true, appOnly = true))
        assertFalse("VPN anche per altre app", Quiet.vpnOk(active = true, appOnly = false))
    }

    @Test fun rigaSamsung() {
        assertTrue(Quiet.hint(120, 2, 120, "samsung").contains("Blocco automatico"))
        assertTrue(Quiet.hint(120, 2, 120, "Samsung").contains("Sicurezza e privacy"))
        assertEquals("", Quiet.hint(120, 2, 120, "Xiaomi"))
        assertEquals("altro esito", "", Quiet.hint(120, 1, 120, "samsung"))
        assertEquals("altra versione", "", Quiet.hint(119, 2, 120, "samsung"))
        assertEquals("nessuna versione", "", Quiet.hint(0, 2, 0, "samsung"))
    }
}
