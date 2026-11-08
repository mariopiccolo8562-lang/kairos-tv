package com.kairos.tv

import com.kairos.tv.QualityPolicy.Heavy
import com.kairos.tv.QualityPolicy.V
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qualità dei canali: con la linea buona la qualità non si tocca, nemmeno sulle chiavette da 1 GB */
class QualityPolicyTest {
    private val k4 = V(1, 4, false)
    private val fhd = V(2, 3, false)
    private val hd = V(3, 2, false)
    private val sd = V(4, 1, false)
    private val fhdHevc = V(5, 3, true)

    private fun start(
        ch: V, sisters: List<V>, adaptOn: Boolean = true, quality: String = "auto", liteMax: Boolean = false, dev: String = "N",
        screen4k: Boolean = false, heavyCap: Int = 4, lineQ: Int = 4, carry: Boolean = false,
    ) = QualityPolicy.start(ch, sisters, adaptOn, quality, liteMax, dev, screen4k, heavyCap, lineQ, carry)

    @Test
    fun chiavettaDa1GbConLaLineaBuonaVedeIlFhd() {
        // prima: la chiavetta «debole» faceva partire tutto al massimo in HD (o in SD), anche con internet che va bene
        val s = start(fhd, listOf(hd, sd), dev = "D")
        assertEquals(fhd, s.full)
        assertEquals(fhd, s.start)
        assertFalse(s.byLine)
    }

    @Test
    fun chiavettaDa1GbNiente4k() {
        assertEquals(fhd, start(k4, listOf(fhd, hd), dev = "D", screen4k = true).start)
        // anche con «Massima»: la chiavetta il 4K non lo decodifica
        assertEquals(fhd, start(k4, listOf(fhd, hd), dev = "D", quality = "max").start)
        // senza sorelle si resta sul canale scelto
        assertEquals(k4, start(k4, emptyList(), dev = "D").start)
    }

    @Test
    fun schermoNon4kParteDalFhdSenzaPerditaVisibile() {
        assertEquals(fhd, start(k4, listOf(fhd, hd)).start)
        assertEquals(k4, start(k4, listOf(fhd, hd), screen4k = true).start)
        // «Massima»: il 4K resta anche sullo schermo 1080p
        assertEquals(k4, start(k4, listOf(fhd, hd), quality = "max").start)
        // l'unica sorella è SD: sullo schermo 1080p di un box capace si resta sul 4K
        assertEquals(k4, start(k4, listOf(sd)).start)
    }

    @Test
    fun lineaDeboleAdessoSiPartePiuLeggeri() {
        val s = start(fhd, listOf(hd, sd), lineQ = 2)
        assertEquals(fhd, s.full)
        assertEquals(hd, s.start)
        assertTrue(s.byLine)
        assertEquals(sd, start(fhd, listOf(hd, sd), lineQ = 1).start)
    }

    @Test
    fun conMassimaLaLineaNonAbbassaLaQualita() {
        assertEquals(fhd, start(fhd, listOf(hd, sd), quality = "max", lineQ = 1, carry = true).start)
        assertFalse(QualityPolicy.lineDowngrades(true, "max"))
        assertTrue(QualityPolicy.lineDowngrades(true, "auto"))
        assertFalse(QualityPolicy.lineDowngrades(false, "auto"))
    }

    @Test
    fun qualitaAdattivaSpentaSoloLaSceltaDelCliente() {
        assertEquals(k4, start(k4, listOf(fhd, hd), adaptOn = false, dev = "D", lineQ = 1).start)
        assertEquals(hd, start(k4, listOf(fhd, hd), adaptOn = false, quality = "720").start)
    }

    @Test
    fun qualitaVideoSceltaAMano() {
        assertEquals(fhd, start(k4, listOf(fhd, hd), quality = "1080", screen4k = true).start)
        assertEquals(hd, start(fhd, listOf(hd, sd), quality = "720").start)
        assertEquals(sd, start(fhd, listOf(hd, sd), quality = "480").start)
    }

    @Test
    fun dopoUnCaloRecenteUnGradinoSotto() {
        val s = start(fhd, listOf(hd, sd), carry = true)
        assertEquals(hd, s.start)
        assertTrue(s.byLine)
        // la versione «piena» è già il FHD del canale 4K: un gradino sotto è HD
        assertEquals(hd, start(k4, listOf(fhd, hd, sd), dev = "D", carry = true).start)
    }

    @Test
    fun leggerissimaHdNonHevc() {
        assertEquals(hd, start(fhd, listOf(hd, sd), liteMax = true).full)
        // niente HD: si resta sul FHD (non si scende in SD)
        assertEquals(fhd, start(fhd, listOf(sd), liteMax = true).full)
    }

    @Test
    fun maiHevcComeRipiegoDiUnCanaleCheNonLoE() {
        assertEquals(hd, start(k4, listOf(fhdHevc, hd), dev = "D").start)
        // canale HEVC: la sorella HEVC va bene, ma a parità di qualità si preferisce quella non HEVC
        val k4Hevc = V(6, 4, true)
        assertEquals(fhd, start(k4Hevc, listOf(fhdHevc, fhd), dev = "D").start)
        assertEquals(fhdHevc, start(k4Hevc, listOf(fhdHevc), dev = "D").start)
    }

    @Test
    fun versioneAScattiImparataSuDueCanaliDiversi() {
        val t = 1_000_000_000_000L
        var h: Heavy? = QualityPolicy.heavyNote(null, 3, "sky1", t)
        assertEquals(4, QualityPolicy.heavyCap(h, t))
        // stesso canale: non conta due volte
        h = QualityPolicy.heavyNote(h, 3, "sky1", t + 60_000)
        assertEquals(1, h!!.n)
        assertEquals(4, QualityPolicy.heavyCap(h, t + 60_000))
        // secondo canale: si parte un gradino sotto (HD) per 7 giorni
        h = QualityPolicy.heavyNote(h, 3, "dazn1", t + 120_000)
        assertEquals(2, QualityPolicy.heavyCap(h, t + 120_000))
        assertEquals(2, QualityPolicy.deviceQ("D", QualityPolicy.heavyCap(h, t + 120_000)))
        assertEquals(4, QualityPolicy.heavyCap(h, t + 120_000 + QualityPolicy.HEAVY_MS + 1))
        // sotto il FHD non si impara nulla
        assertNull(QualityPolicy.heavyNote(null, 2, "x", t))
        // salvataggio
        assertEquals(h, Heavy.load(h!!.save()))
        assertNull(Heavy.load(""))
        assertNull(Heavy.load("a|b|c|d"))
    }

    @Test
    fun versioneAScattiScaduta() {
        val t = 1_000_000_000_000L
        val old = Heavy(3, 5, t, "a")
        val h = QualityPolicy.heavyNote(old, 3, "b", t + QualityPolicy.HEAVY_MS + 1)
        assertEquals(1, h!!.n)
        // orologio tornato indietro (chiavette senza ora all'avvio): nessun tetto
        assertEquals(4, QualityPolicy.heavyCap(Heavy(3, 2, t, "b"), t - 1))
    }

    @Test
    fun tettoDellaLinea() {
        assertEquals(4, QualityPolicy.lineQ(false, 1_000_000L, true))
        assertEquals(2, QualityPolicy.lineQ(true, 3_000_000L, true))
        assertEquals(1, QualityPolicy.lineQ(true, 2_000_000L, true))
        // banda bassa ma vecchia: al massimo HD, mai SD «per sempre»
        assertEquals(2, QualityPolicy.lineQ(true, 2_000_000L, false))
        assertEquals(2, QualityPolicy.lineQ(true, 0L, true))
    }

    @Test
    fun discesaAnticipataSoloConUnCaloVero() {
        val now = 100_000L
        // calo costante da 7 s a 3 s in 16 s: verso lo stallo
        val steady = (0..16).map { (now - 16_000L + it * 1_000L) to (7_000L - it * 250L) }
        assertTrue(QualityPolicy.draining(steady, now, 3_000L, 5_000L))
        // un solo vuoto del Wi-Fi: prima piatto, poi giù di colpo negli ultimi secondi
        val dip = (0..16).map { (now - 16_000L + it * 1_000L) to (if (it < 14) 6_000L else 3_000L) }
        assertFalse(QualityPolicy.draining(dip, now, 3_000L, 5_000L))
        // buffer ancora alto: niente
        val high = (0..16).map { (now - 16_000L + it * 1_000L) to (12_000L - it * 250L) }
        assertFalse(QualityPolicy.draining(high, now, 8_000L, 5_000L))
        // finestra troppo corta
        val short = (0..8).map { (now - 8_000L + it * 1_000L) to (7_000L - it * 500L) }
        assertFalse(QualityPolicy.draining(short, now, 3_000L, 5_000L))
    }

    @Test
    fun sogliaDellaDiscesaAnticipata() {
        assertEquals(5_000L, QualityPolicy.drainThreshold(6_000)) // chiavette: prima era 8 s
        assertEquals(5_000L, QualityPolicy.drainThreshold(3_000))
        assertEquals(4_500L, QualityPolicy.drainThreshold(2_500))
    }

    @Test
    fun anteprimaInHd() {
        assertEquals(2, QualityPolicy.previewQ(3, listOf(2 to false, 1 to false)))
        assertEquals(1, QualityPolicy.previewQ(3, listOf(1 to false)))
        assertEquals(3, QualityPolicy.previewQ(4, listOf(3 to false)))
        assertEquals(2, QualityPolicy.previewQ(4, listOf(3 to false, 2 to false, 1 to false)))
        // HD e SD restano come sono
        assertNull(QualityPolicy.previewQ(2, listOf(1 to false)))
        // niente HEVC nell'anteprima
        assertNull(QualityPolicy.previewQ(3, listOf(2 to true)))
    }

    @Test
    fun tettoVideoScelto() {
        assertEquals(4, QualityPolicy.settingQ("auto"))
        assertEquals(4, QualityPolicy.settingQ("max"))
        assertEquals(3, QualityPolicy.settingQ("1080"))
        assertEquals(2, QualityPolicy.settingQ("720"))
        assertEquals(1, QualityPolicy.settingQ("480"))
    }
}
