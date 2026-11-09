package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Riconoscimento degli adulti: i 94 esempi obbligatori del contratto comune di tutte le app Kairos, letti da
 * src/test/resources/adult-vectors.json (copia identica di vectors.json), con lo stesso esito del controllo in JavaScript.
 */
class AdultFilterTest {
    private val v: Map<String, List<String>> = Esempi.leggi("/adult-vectors.json")

    @Test
    fun ciSonoTuttiGliEsempiDelContratto() {
        assertEquals(43, v.getValue("cat_true").size)
        assertEquals(26, v.getValue("cat_false").size)
        assertEquals(11, v.getValue("title_true").size)
        assertEquals(14, v.getValue("title_false").size)
        assertEquals(94, v.values.sumOf { it.size })
    }

    @Test
    fun categoriePerAdulti() {
        for (s in v.getValue("cat_true")) assertTrue("categoria non riconosciuta: «$s»", AdultFilter.cat(s))
    }

    @Test
    fun categorieNormali() {
        for (s in v.getValue("cat_false")) assertFalse("categoria presa per adulti per sbaglio: «$s»", AdultFilter.cat(s))
    }

    @Test
    fun titoliPerAdulti() {
        for (s in v.getValue("title_true")) assertTrue("titolo non riconosciuto: «$s»", AdultFilter.title(s))
    }

    @Test
    fun titoliNormali() {
        for (s in v.getValue("title_false")) assertFalse("titolo preso per adulti per sbaglio: «$s»", AdultFilter.title(s))
    }

    @Test
    fun normalizzazioneDelContratto() {
        assertEquals("xxx", AdultFilter.norm("ＸＸＸ")) // NFKD: lettere a larghezza piena
        assertEquals("erotique", AdultFilter.norm("  Érotique "))
        assertEquals("film +18", AdultFilter.norm("Film    +18"))
        assertEquals("", AdultFilter.norm(null))
        assertEquals("", AdultFilter.norm("   "))
        assertFalse(AdultFilter.cat(null))
        assertFalse(AdultFilter.title(null))
    }

    @Test
    fun comeInJavaScriptAncheConGliAltriAlfabeti() {
        // \b e \s come in JavaScript e Dart (le altre app): lettere e segni fuori dall'ASCII non attaccano le parole
        assertTrue(AdultFilter.cat("XXXфильмы"))
        assertTrue(AdultFilter.title("ФИЛЬМ XXX"))
        assertTrue(AdultFilter.cat("XXX҃"))
        assertTrue(AdultFilter.cat("18 +"))
        assertFalse(AdultFilter.cat("Essex ★"))
        assertFalse(AdultFilter.title("Sexy Beast ★"))
        // l'eccezione toglie solo «adult swim»
        assertTrue(AdultFilter.cat("Adult Swim · Adult"))
        assertFalse(AdultFilter.cat("ADULT  SWIM"))
    }
}

/** lettore minimo del file degli esempi (un oggetto con liste di stringhe): nessuna libreria in più per i test */
private object Esempi {
    fun leggi(path: String): Map<String, List<String>> {
        val s = (Esempi::class.java.getResourceAsStream(path) ?: error("manca $path nelle risorse dei test")).use { String(it.readBytes(), Charsets.UTF_8) }
        val out = LinkedHashMap<String, List<String>>()
        var i = 0
        fun spazi() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun testo(): String {
            check(s[i] == '"') { "atteso \" alla posizione $i" }
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }
        spazi(); check(s[i++] == '{')
        while (true) {
            spazi()
            if (s[i] == '}') break
            val k = testo()
            spazi(); check(s[i++] == ':'); spazi(); check(s[i++] == '[')
            val list = ArrayList<String>()
            while (true) {
                spazi()
                if (s[i] == ']') { i++; break }
                list.add(testo())
                spazi()
                if (s[i] == ',') i++
            }
            out[k] = list
            spazi()
            if (s[i] == ',') i++
        }
        return out
    }
}
