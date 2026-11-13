package com.kairos.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** separatori del fornitore («----NOME----») nascosti, titoli veri no: stessa regola di Kairos Pro */
class SepNameTest {
    @Test fun separatori() {
        for (n in listOf("----NOME----", "##### SPORT #####", "==== FILM 2024 ====", "★★★ CINEMA ★★★", "—— PRIME VIDEO ——", "*** NOVITÀ ***", "  ")) assertTrue(n, sepName(n))
    }
    @Test fun titoliVeri() {
        for (n in listOf("...E giustizia per tutti", "Rocky ★★★", "X-Men: Giorni di un futuro passato", "|IT| RAI 1", "M*A*S*H", "Spider-Man – No Way Home", "Fast & Furious 7")) assertFalse(n, sepName(n))
    }
}
