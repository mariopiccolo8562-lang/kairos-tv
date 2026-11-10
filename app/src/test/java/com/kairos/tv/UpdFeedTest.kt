package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: store-k.download, poi le riserve (compilata e annunciate dal file); niente pannello, niente GitHub */
class UpdFeedTest {
    private val dl = "https://store-k.download"
    private val r2 = "https://riserva-k.com"
    private val dlFeed = "https://store-k.download/get/tv.json"
    private val r2Feed = "https://riserva-k.com/get/tv.json"
    private val dlApk = "https://store-k.download/get/tv.apk"
    private val r2Apk = "https://riserva-k.com/get/tv.apk"

    @Test
    fun soloIlDominioDeiDownloadSenzaPannelloNeGitHub() {
        assertEquals(listOf(dlFeed), UpdFeed.urls(dl, "tv"))
        assertEquals(listOf(dlFeed), UpdFeed.urls("$dl/", "tv", listOf("")))
        assertFalse(UpdFeed.urls(dl, "tv").any { it.contains("panel-kairos") || it.contains("github") })
    }

    @Test
    fun poiLeRiserveInOrdineSenzaDoppioni() {
        assertEquals(listOf(dlFeed, r2Feed), UpdFeed.urls(dl, "tv", listOf(r2, "$dl/", r2)))
        assertEquals(listOf(r2Feed), UpdFeed.urls("", "tv", listOf(r2)))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf<String>(), UpdFeed.urls("", "tv"))
        assertEquals(listOf(r2Feed), UpdFeed.urls("http://store-k.download", "tv", listOf("ftp://x.com", "https://", "https://riserva-k.com/percorso", r2)))
    }

    @Test
    fun riserveAnnunciateDalFile() {
        assertEquals(listOf(dl, r2), UpdFeed.mirrors(listOf("$dl/", " $r2 ", r2, "http://chiaro.com", "https://x", "https://a.it", "https://b.it")).take(2))
        assertEquals(3, UpdFeed.mirrors(listOf("https://a.it", "https://b.it", "https://c.it", "https://d.it")).size)
        assertEquals(listOf<String>(), UpdFeed.mirrors(listOf("javascript:alert(1)", "https://evil.com/../x")))
    }

    @Test
    fun alPiuQuattroBasi() {
        assertEquals(4, UpdFeed.bases(dl, listOf("https://a.it", "https://b.it", "https://c.it", "https://d.it")).size)
    }

    @Test
    fun conLaPrimaBaseCheRispondeLeAltreNonSiChiedono() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls(dl, "tv", listOf(r2))) { asked.add(it); "file" }
        assertEquals("file", r)
        assertEquals(listOf(dlFeed), asked)
    }

    @Test
    fun dominioBloccatoOParcheggiatoSiPassaAllaRiserva() {
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("store-k.download: nome sconosciuto") })
        for (failing in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls(dl, "tv", listOf(r2))) { u -> asked.add(u); if (u == r2Feed) "riserva" else failing(u) }
            assertEquals("riserva", r)
            assertEquals(listOf(dlFeed, r2Feed), asked)
        }
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        assertNull(UpdFeed.first(UpdFeed.urls(dl, "tv", listOf(r2))) { null })
    }

    @Test
    fun apkDalLinkDelFilePoiDalleAltreBasi() {
        assertEquals(listOf(dlApk, r2Apk), UpdFeed.apkUrls(dlApk, dl, "tv", listOf(r2)))
        // file dalla riserva (dominio dei download giù): il dominio dei download non si riprova
        assertEquals(listOf(r2Apk), UpdFeed.apkUrls(r2Apk, dl, "tv", listOf(r2)))
        // link fuori dalle basi (es. un vecchio link di GitHub): il suo link, poi tutte le basi
        val rel = "https://github.com/x/y/releases/download/v2.4.3/tv.apk"
        assertEquals(listOf(rel, dlApk, r2Apk), UpdFeed.apkUrls(rel, dl, "tv", listOf(r2)))
        assertEquals(listOf("$dl/get/tv.apk?v=20403", r2Apk), UpdFeed.apkUrls("$dl/get/tv.apk?v=20403", dl, "tv", listOf(r2)))
        assertEquals(listOf(dlApk), UpdFeed.apkUrls(dlApk, dl, "tv"))
    }

    @Test
    fun downloadFallitoSiProvaLaBaseDopoELErroreRestaQuelloDelPrimo() {
        val tried = ArrayList<String>()
        val r = UpdFeed.firstOk(listOf(dlApk, r2Apk)) { u -> tried.add(u); if (u == r2Apk) "ok" else throw IOException("HTTP 502 da $u") }
        assertEquals("ok", r)
        assertEquals(listOf(dlApk, r2Apk), tried)
        val first = IOException("HTTP 502")
        try {
            UpdFeed.firstOk<String>(listOf(dlApk, r2Apk)) { u -> throw if (u == dlApk) first else IOException("file non trovato (HTTP 404)") }
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
            UpdFeed.firstOk<String>(listOf(dlApk, r2Apk), { it is Visione }) { u -> tried.add(u); throw Visione() }
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
        assertTrue(UpdFeed.valid(20402, dlApk))
        assertFalse(UpdFeed.valid(0, dlApk))
        assertFalse(UpdFeed.valid(20402, "http://store-k.download/get/tv.apk"))
        assertFalse(UpdFeed.valid(20402, ""))
    }
}
