package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: prima l'indirizzo dei download (oggi il pannello), GitHub solo come riserva se il pannello fallisce */
class UpdFeedTest {
    private val gh = "https://github.com/mariopiccolo8562-lang/kairos-tv/releases/latest/download/kairos-tv.json"
    private val panel = "https://panel-kairos.com/get/tv.json"

    @Test
    fun oggiPannelloPoiGitHub() {
        // l'indirizzo dei download oggi è il pannello: il secondo tentativo, uguale al primo, si salta
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com", "tv", gh))
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com/", "tv", gh))
    }

    @Test
    fun dominioDedicatoPrimaDelPannello() {
        assertEquals(listOf("https://dl.example.com/get/tv.json", panel, gh), UpdFeed.urls("https://dl.example.com/", "tv", gh))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf(panel), UpdFeed.urls("", "tv", ""))
        assertEquals(listOf(panel, gh), UpdFeed.urls("http://dl.example.com", "tv", gh))
    }

    @Test
    fun pannelloCheRispondeGitHubNonSiChiede() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "tv", gh)) { asked.add(it); "pannello" }
        assertEquals("pannello", r)
        assertEquals(listOf(panel), asked) // nessun controllo in più del solito
    }

    @Test
    fun pannelloConErroreRispostaNonValidaOMutoSiUsaGitHub() {
        // null = errore HTTP (es. 502) o file non valido; eccezione = il pannello non risponde (rete, tempo scaduto)
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("tempo scaduto") })
        for (fail in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "tv", gh)) { u -> asked.add(u); if (u == gh) "github" else fail(u) }
            assertEquals("github", r)
            assertEquals(listOf(panel, gh), asked)
        }
    }

    @Test
    fun dominioDedicatoGiuRispondeIlPannello() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://dl.example.com", "tv", gh)) { u -> asked.add(u); if (u == panel) "pannello" else null }
        assertEquals("pannello", r)
        assertEquals(listOf("https://dl.example.com/get/tv.json", panel), asked)
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        // pannello e GitHub non rispondono (o repository privato): come una rete giù, si riprova più tardi
        assertNull(UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "tv", gh)) { null })
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
        assertTrue(UpdFeed.valid(20402, "https://panel-kairos.com/get/tv.apk"))
        assertFalse(UpdFeed.valid(0, "https://panel-kairos.com/get/tv.apk"))
        assertFalse(UpdFeed.valid(20402, "http://panel-kairos.com/get/tv.apk"))
        assertFalse(UpdFeed.valid(20402, ""))
    }
}
