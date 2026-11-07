package com.kairos.tv

import java.text.Normalizer
import java.util.Locale

/**
 * Home configurabile dal pannello (controllo remoto, voci rc["home.top"] e rc["home.hide"]): nomi o parti di nome di
 * categorie separati da virgola, punto e virgola o a capo, in ordine di priorità. Una categoria corrisponde se il suo
 * nome, minuscolo e senza accenti, contiene lo schema (scritto allo stesso modo).
 * - In primo piano (home.top): nella Home righe subito dopo «Continua a guardare», nell'ordine degli schemi (al massimo 10);
 *   negli elenchi delle categorie (Film, Serie TV, TV in diretta) vengono per prime.
 * - Nascoste (home.hide): mai righe della Home, e i loro titoli non vanno nel riquadro grande né tra gli «aggiunti di
 *   recente»; negli elenchi restano raggiungibili, in fondo (il PIN del controllo genitori resta com'è).
 *   Una categoria che corrisponde a tutte e due le voci conta come nascosta.
 * Voci assenti o vuote: tutto come prima.
 */
object HomeCfg {
    /** righe «in primo piano» al massimo nella Home */
    const val MAX_TOP = 10

    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    @Volatile private var raw: String? = null
    @Volatile private var topP: List<String> = emptyList()
    @Volatile private var hideP: List<String> = emptyList()

    /** schemi della configurazione attuale: si ricalcolano solo quando cambia */
    @Synchronized private fun load() {
        val rc = Remote.rc()
        val t = rc.optString("home.top", "")
        val h = rc.optString("home.hide", "")
        val r = t + "\n\u0000\n" + h
        if (r == raw) return
        topP = patterns(t)
        hideP = patterns(h)
        raw = r
    }

    /** impronta della configurazione: se cambia, Home ed elenchi si ricostruiscono */
    fun sig(): String { load(); return raw ?: "" }

    /** minuscolo, senza accenti (NFD senza segni diacritici), spazi multipli ridotti a uno */
    fun norm(s: String): String =
        SPACES.replace(MARKS.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""), " ").trim()

    private fun patterns(s: String): List<String> = s.split(',', ';', '\n', '\r').map { norm(it) }.filter { it.isNotEmpty() }

    private fun rankOf(n: String): Int = topP.indexOfFirst { n.contains(it) }
    private fun hiddenN(n: String): Boolean = hideP.any { n.contains(it) }

    /** la categoria è tra quelle nascoste dalla Home */
    fun hidden(name: String): Boolean {
        load()
        return hideP.isNotEmpty() && hiddenN(norm(name))
    }

    /**
     * Home: le categorie in primo piano fra [cats] (coppie tabella–categoria nell'ordine solito), ordinate per il primo
     * schema che le prende e a parità nell'ordine di partenza; al massimo [MAX_TOP], mai quelle nascoste.
     */
    fun top(cats: List<Pair<String, Db.Cat>>): List<Pair<String, Db.Cat>> {
        load()
        if (topP.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Pair<String, Db.Cat>>>()
        for (p in cats) {
            val n = norm(p.second.name)
            val r = rankOf(n)
            if (r >= 0 && !hiddenN(n)) out.add(r to p)
        }
        return out.sortedBy { it.first }.take(MAX_TOP).map { it.second }
    }

    /** elenchi delle categorie: prima quelle in primo piano (ordine degli schemi), poi le altre come sempre, le nascoste in fondo */
    fun order(cats: List<Db.Cat>): List<Db.Cat> {
        load()
        if (topP.isEmpty() && hideP.isEmpty()) return cats
        val first = ArrayList<Pair<Int, Db.Cat>>()
        val mid = ArrayList<Db.Cat>()
        val last = ArrayList<Db.Cat>()
        for (c in cats) {
            val n = norm(c.name)
            if (hiddenN(n)) { last.add(c); continue }
            val r = rankOf(n)
            if (r >= 0) first.add(r to c) else mid.add(c)
        }
        return first.sortedBy { it.first }.map { it.second } + mid + last
    }
}
