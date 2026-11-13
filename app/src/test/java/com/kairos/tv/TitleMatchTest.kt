package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    @Test fun normalizesPrefixesAccentsYearsAndQuality() {
        assertEquals("la citta di vetro", TitleMatch.norm("IT - La città di vetro (2025) 4K UHD ITA"))
        assertEquals("odissea", TitleMatch.norm("|IT| Odissea"))
        assertEquals("odissea", TitleMatch.norm("4K | Odissea"))
    }

    @Test fun matchesNamesAndAllowsOneYearDifference() {
        assertTrue(TitleMatch.matches("|IT| Odissea (2025) HD", 2025, "Odissea", 2026))
        assertTrue(TitleMatch.matches("Amélie", 2001, "Amelie", 2002))
        assertFalse(TitleMatch.matches("Amélie", 2001, "Amelie", 2003))
        assertFalse(TitleMatch.matches("Odissea", 2025, "Odissea 2", 2025))
    }
}
