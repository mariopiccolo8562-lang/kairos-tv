package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: store-k.download, poi il pannello, poi GitHub; si passa alla base dopo solo se quella prima fallisce */
class UpdFeedTest {
    private val dl = "https://store-k.download"
    private val gh = "https://github.com/mariopiccolo8562-lang/kairos-tv/releases/latest/download/kairos-tv.json"
    private val ghApk = "https://github.com/mariopiccolo8562-lang/kairos-tv/releases/latest/download/kairos-tv.apk"
    private val dlFeed = "https://store-k.download/get/tv.json"
    private val panel = "https://panel-kairos.com/get/tv.json"
    private val dlApk = "https://store-k.download/get/tv.apk"
    private val panelApk = "https://panel-kairos.com/get/tv.apk"

    @Test
    fun ordineDominioDeiDownloadPannelloGitHub() {
        assertEquals(listOf(dlFeed, panel, gh), UpdFeed.urls(dl, "tv", gh))
        assertEquals(listOf(dlFeed, panel, gh), UpdFeed.urls("$dl/", "tv", gh))
    }

    @Test
    fun conIlPannelloComeIndirizzoDeiDownloadIlDoppioneSiSalta() {
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com", "tv", gh))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf(panel), UpdFeed.urls("", "tv", ""))
        assertEquals(listOf(panel, gh), UpdFeed.urls("http://store-k.download", "tv", gh))
    }

    @Test
    fun conLaPrimaBaseCheRispondeLeAltreNonSiChiedono() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls(dl, "tv", gh)) { asked.add(it); "file" }
        assertEquals("file", r)
        assertEquals(listOf(dlFeed), asked)
    }

    @Test
    fun dominioNonAttivoOParcheggiatoSiPassaAlPannello() {
        // null = 200 con HTML (dominio parcheggiato), JSON rotto o senza versione/link, errore HTTP;
        // eccezione = nessuna risposta (nome sconosciuto, connessione scaduta dopo 5 s)
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("store-k.download: nome sconosciuto") })
        for (fail in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls(dl, "tv", gh)) { u -> asked.add(u); if (u == panel) "pannello" else fail(u) }
            assertEquals("pannello", r)
            assertEquals(listOf(dlFeed, panel), asked) // GitHub non si chiede
        }
    }

    @Test
    fun pannelloGiuSiUsaGitHub() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls(dl, "tv", gh)) { u -> asked.add(u); if (u == gh) "github" else null }
        assertEquals("github", r)
        assertEquals(listOf(dlFeed, panel, gh), asked)
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        // nessuno risponde (o repository privato): come una rete giù, si riprova più tardi
        assertNull(UpdFeed.first(UpdFeed.urls(dl, "tv", gh)) { null })
    }

    @Test
    fun apkDalLinkDelFilePoiDalleBasiSuccessivePoiGitHub() {
        // file dal dominio dei download: poi il pannello, poi GitHub
        assertEquals(listOf(dlApk, panelApk, ghApk), UpdFeed.apkUrls(dlApk, dl, "tv", ghApk))
        // file dal pannello (dominio dei download giù): il dominio dei download non si riprova
        assertEquals(listOf(panelApk, ghApk), UpdFeed.apkUrls(panelApk, dl, "tv", ghApk))
        // file di riserva di GitHub: il suo link, poi l'ultima release
        val rel = "https://github.com/mariopiccolo8562-lang/kairos-tv/releases/download/v2.4.3/kairos-tv.apk"
        assertEquals(listOf(rel, ghApk), UpdFeed.apkUrls(rel, dl, "tv", ghApk))
        assertEquals(listOf(ghApk), UpdFeed.apkUrls(ghApk, dl, "tv", ghApk))
        // link del file con un nome diverso sulla stessa base: lo stesso APK con il nome solito sulle basi dopo
        assertEquals(listOf("$dl/get/tv.apk?v=20403", panelApk, ghApk), UpdFeed.apkUrls("$dl/get/tv.apk?v=20403", dl, "tv", ghApk))
    }

    @Test
    fun downloadRiuscitoAlPrimoColpo() {
        val tried = ArrayList<String>()
        val r = UpdFeed.firstOk(listOf(dlApk, panelApk, ghApk)) { tried.add(it); "ok:$it" }
        assertEquals("ok:$dlApk", r)
        assertEquals(listOf(dlApk), tried)
    }

    @Test
    fun downloadFallitoSiProvaLaBaseDopoELErroreRestaQuelloDelPrimo() {
        val tried = ArrayList<String>()
        val r = UpdFeed.firstOk(listOf(dlApk, panelApk, ghApk)) { u -> tried.add(u); if (u == ghApk) "ok" else throw IOException("HTTP 502 da $u") }
        assertEquals("ok", r)
        assertEquals(listOf(dlApk, panelApk, ghApk), tried)
        val first = IOException("HTTP 502")
        try {
            UpdFeed.firstOk<String>(listOf(dlApk, panelApk)) { u -> throw if (u == dlApk) first else IOException("file non trovato (HTTP 404)") }
            fail("doveva fallire")
        } catch (e: IOException) {
            assertSame(first, e)
        }
    }

    private class Visione : Exception()

    @Test
    fun visioneIniziataFermaTuttoSenzaProvareLeAltreBasi() {
        val tried = ArrayList<String>()
        try {
            UpdFeed.firstOk<String>(listOf(dlApk, panelApk, ghApk), { it is Visione }) { u -> tried.add(u); throw Visione() }
            fail("doveva fermarsi")
        } catch (_: Visione) {
        }
        assertEquals(listOf(dlApk), tried)
    }

    @Test
    fun numeroDellaVersione() {
        assertEquals(20402, UpdFeed.code("2.4.2", 0))
        assertEquals(20402, UpdFeed.code("2.4.2", 99))
        assertEquals(20402, UpdFeed.code("", 20402))
        assertEquals(7, UpdFeed.code("2.4", 7))
    }

    @Test
    fun fileValido() {
        assertTrue(UpdFeed.valid(20402, panelApk))
        assertFalse(UpdFeed.valid(0, panelApk))
        assertFalse(UpdFeed.valid(20402, "http://panel-kairos.com/get/tv.apk"))
        assertFalse(UpdFeed.valid(20402, ""))
    }
}
