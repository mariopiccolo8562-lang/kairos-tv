package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home dal pannello (home.top, home.hide e adult): logica pura di [HomeCfg.Rules], con e senza la voce «adult» */
class HomeCfgTest {
    private val cats = listOf("Prima Visione", "Sport", "XXX | 4K", "Kids", "Film +18", "Adult Swim", "Documentari", "Per adulti")

    private fun rules(top: String = "", hide: String = "", adult: String = "") = HomeCfg.Rules(top, hide, adult)

    @Test
    fun senzaLaVoceAdultiGliAdultiSonoNascosti() {
        for (r in listOf(rules(), rules(adult = "off"), rules(adult = "qualsiasi"))) {
            assertTrue(r.noAdult)
            assertTrue(r.hidden("XXX | 4K"))
            assertTrue(r.hidden("Film +18"))
            assertTrue(r.hidden("Per adulti"))
            assertFalse(r.hidden("Adult Swim"))
            assertFalse(r.hidden("Sport"))
        }
    }

    @Test
    fun conOnTuttoComePrima() {
        val r = rules(adult = "on")
        assertFalse(r.noAdult)
        assertFalse(r.hidden("XXX | 4K"))
        // senza home.top e home.hide l'elenco resta quello di prima, lo stesso oggetto
        assertSame(cats, r.order(cats) { it })
        assertFalse(r.blocked("XXX Private", "XXX | 4K"))
        assertEquals(listOf("XXX | 4K", "Sport"), rules(top = "xxx, sport", adult = "on").top(cats) { it })
    }

    @Test
    fun laListaHomeHideValeSempre() {
        for (a in listOf("", "on", "off")) {
            val r = rules(hide = "sport; documentari", adult = a)
            assertTrue(r.hidden("Sport"))
            assertTrue(r.hidden("DOCUMENTARI HD"))
            assertFalse(r.hidden("Kids"))
        }
    }

    @Test
    fun negliElenchiGliAdultiVannoInFondo() {
        assertEquals(
            listOf("Prima Visione", "Sport", "Kids", "Adult Swim", "Documentari", "XXX | 4K", "Film +18", "Per adulti"),
            rules().order(cats) { it },
        )
        // primo piano prima, poi le altre, in fondo le nascoste da home.hide e quelle per adulti (ognuna nel suo ordine)
        assertEquals(
            listOf("Documentari", "Prima Visione", "Kids", "Adult Swim", "Sport", "XXX | 4K", "Film +18", "Per adulti"),
            rules(top = "documentari, prima", hide = "sport").order(cats) { it },
        )
        // con «on» gli adulti restano al loro posto, come prima
        assertEquals(
            listOf("Documentari", "Prima Visione", "XXX | 4K", "Kids", "Film +18", "Adult Swim", "Per adulti", "Sport"),
            rules(top = "documentari, prima", hide = "sport", adult = "on").order(cats) { it },
        )
        // una categoria per adulti in primo piano va comunque in fondo
        assertEquals("XXX | 4K", rules(top = "xxx").order(cats) { it }[5])
    }

    @Test
    fun maiCategorieInPrimoPianoPerAdulti() {
        assertEquals(listOf("Sport"), rules(top = "xxx, sport").top(cats) { it })
        assertEquals(emptyList<String>(), rules().top(cats) { it })
    }

    @Test
    fun titoliDellaHome() {
        val r = rules(hide = "sport")
        assertTrue(r.blocked("XXX Private", "Film"))          // nome per adulti
        assertTrue(r.blocked("Un film qualsiasi", "Film +18")) // categoria per adulti
        assertTrue(r.blocked("Partita", "Sport"))             // categoria nascosta da home.hide
        assertTrue(r.blocked("Film 18+", null))               // categoria sconosciuta: conta il nome
        assertFalse(r.blocked("Sex Education", "Serie TV"))
        assertFalse(r.blocked("Sex Education", null))
        assertFalse(r.blocked("Adult Swim", "Adult Swim"))
    }

    @Test
    fun limprontaCambiaConLaVoceAdulti() {
        assertNotEquals(rules().sig, rules(adult = "on").sig)
        assertNotEquals(rules(adult = "on").sig, rules(adult = "off").sig)
        assertNotEquals(rules(top = "a").sig, rules(hide = "a").sig)
        assertEquals(rules(top = "a", adult = "off").sig, rules(top = "a", adult = "off").sig)
    }

    @Test
    fun laCacheDaSempreLoStessoEsito() {
        val r = rules(hide = "kids")
        repeat(3) {
            assertTrue(r.hidden("XXX | 4K"))
            assertTrue(r.hidden("Kids"))
            assertFalse(r.hidden("Sport"))
        }
    }
}
