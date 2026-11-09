package com.kairos.tv

import java.text.Normalizer

/**
 * Contenuti per adulti: riconoscimento uguale in tutte le app Kairos (contratto comune del 9 novembre 2026; i 94 esempi
 * obbligatori sono in src/test/resources/adult-vectors.json e si provano in AdultFilterTest).
 * - [cat]: categoria per adulti («XXX», «+18», «Per adulti», «Film erotici», «VM18»…), ma non «Adult Swim».
 * - [title]: titolo o canale per adulti, solo con le parole forti: «Sex Education» o «Sexy Beast» restano.
 * Oggetto puro, senza Android. Costa una normalizzazione e un'espressione regolare: si chiama una volta per categoria
 * (cache in [HomeCfg]) e, per i titoli, solo per gli elementi delle righe della Home quando si caricano.
 */
object AdultFilter {
    /** categoria per adulti: espressione del contratto, identica nelle altre app */
    private val CAT = Regex("""(\bxxx\b|adult|\bporn|erotic|erotik|erotis|erotiq|hentai|\b18\s*\+|\+\s*18\b|\bv\.?\s*m\.?\s*18\b|\bsexy?\b|\bhot\s*club\b|playboy|brazzers|hustler|dorcel|only\s*fans|red\s*light|\bx[\s-]?rated\b|\bnsfw\b|\bmilf\b|private\s*tv|vietato ai minori)""")

    /** titolo o canale per adulti: solo parole forti (espressione del contratto) */
    private val TITLE = Regex("""(\bxxx\b|\bporn|hentai|brazzers|playboy\s*tv|hustler\s*tv|dorcel|only\s*fans|\b18\s*\+|\+\s*18\b)""")

    /** eccezione del contratto: «Adult Swim» (cartoni animati) non è una categoria per adulti */
    private val SWIM = Regex("""adult\s*swim""")

    private val MARKS = Regex("""[\u0300-\u036F]+""")

    /** gli spazi di \s in JavaScript e Dart, compresi quelli che NFKD lascia com'erano */
    private val SPACES = Regex("""[\t\n\x0B\f\r\x20\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+""")

    /** NFKD («ＸＸＸ» a larghezza piena diventa «XXX»), senza segni diacritici, minuscolo, spazi multipli ridotti a uno e tolti ai bordi */
    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var t: String = s
        // nomi solo ASCII (quasi tutti): NFKD e accenti non cambiano niente
        if (t.any { it.code >= 0x80 }) t = MARKS.replace(Normalizer.normalize(t, Normalizer.Form.NFKD), "")
        return SPACES.replace(t.lowercase(), " ").trim(' ')
    }

    /** il nome di una categoria è per adulti */
    fun cat(name: String?): Boolean {
        val n = norm(name)
        return n.isNotEmpty() && CAT.containsMatchIn(SWIM.replace(ascii(n), ""))
    }

    /** il nome di un titolo o di un canale è per adulti (solo parole forti) */
    fun title(name: String?): Boolean {
        val n = norm(name)
        return n.isNotEmpty() && TITLE.containsMatchIn(ascii(n))
    }

    /**
     * Dopo la normalizzazione restano fuori dall'ASCII solo lettere e segni di altri alfabeti (cirillico, arabo, emoji…):
     * diventano «#», così \b e \s funzionano come in JavaScript e Dart (lì sono solo ASCII) su tutte le versioni di Android
     * e di Java, dove invece \b conta come lettere anche quelle degli altri alfabeti. Esempio: «XXXфильмы» è per adulti ovunque.
     */
    private fun ascii(n: String): String {
        var i = 0
        while (i < n.length && n[i].code < 0x80) i++
        if (i == n.length) return n
        val b = StringBuilder(n)
        for (j in i until b.length) if (b[j].code >= 0x80) b.setCharAt(j, '#')
        return b.toString()
    }
}
