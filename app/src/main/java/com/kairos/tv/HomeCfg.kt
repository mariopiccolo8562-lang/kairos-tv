package com.kairos.tv

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Home configurabile dal pannello (controllo remoto, voci rc["home.top"], rc["home.hide"] e rc["adult"]).
 * home.top e home.hide: nomi o parti di nome di categorie separati da virgola, punto e virgola o a capo, in ordine di
 * priorità. Una categoria corrisponde se il suo nome, minuscolo e senza accenti, contiene lo schema (scritto allo stesso modo).
 * - In primo piano (home.top): nella Home righe subito dopo «Continua a guardare», nell'ordine degli schemi (al massimo 10);
 *   negli elenchi delle categorie (Film, Serie TV, TV in diretta) vengono per prime.
 * - Nascoste (home.hide): mai righe della Home, e i loro titoli non vanno nel riquadro grande né tra gli «aggiunti di
 *   recente»; negli elenchi restano raggiungibili, in fondo (il PIN del controllo genitori resta com'è).
 *   Una categoria che corrisponde a tutte e due le voci conta come nascosta.
 * - Adulti (adult, contratto comune di tutte le app Kairos): se è diverso da "on", anche assente o vuoto, le categorie per
 *   adulti ([AdultFilter.cat]) contano come nascoste, e nella Home non entra nessun titolo per adulti ([blocked]).
 *   Con "on" vale solo home.hide, come prima.
 */
object HomeCfg {
    /** righe «in primo piano» al massimo nella Home */
    const val MAX_TOP = 10

    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /** minuscolo, senza accenti (NFD senza segni diacritici), spazi multipli ridotti a uno */
    fun norm(s: String): String =
        SPACES.replace(MARKS.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""), " ").trim()

    private fun patterns(s: String): List<String> = s.split(',', ';', '\n', '\r').map { norm(it) }.filter { it.isNotEmpty() }

    /**
     * Regole di una configurazione: le tre voci del pannello come arrivano. Pure (niente Android), provate in HomeCfgTest.
     * Ogni categoria si riconosce una volta sola per nome (cache), finché la configurazione non cambia.
     */
    class Rules(val top: String, val hide: String, val adult: String) {
        /** impronta della configurazione: cambia con una qualunque delle tre voci */
        val sig: String = top + "\n\u0000\n" + hide + "\n\u0000\n" + adult
        /** adulti fuori dalla Home: rc["adult"] diverso da "on" (anche assente o vuoto) */
        val noAdult: Boolean = adult != "on"
        private val topP = patterns(top)
        private val hideP = patterns(hide)
        private val memo = ConcurrentHashMap<String, Boolean>()

        private fun rankOf(n: String): Int = topP.indexOfFirst { n.contains(it) }

        /** la categoria è nascosta dalla Home: da home.hide oppure, con [noAdult], perché è per adulti */
        fun hidden(name: String): Boolean {
            if (hideP.isEmpty() && !noAdult) return false
            memo[name]?.let { return it }
            val v = (hideP.isNotEmpty() && norm(name).let { n -> hideP.any { n.contains(it) } }) || (noAdult && AdultFilter.cat(name))
            if (memo.size >= 5000) memo.clear()
            memo[name] = v
            return v
        }

        /** Home: le categorie in primo piano fra [items], per primo schema che le prende (a parità nell'ordine di partenza); al massimo [MAX_TOP], mai le nascoste */
        fun <T> top(items: List<T>, name: (T) -> String): List<T> {
            if (topP.isEmpty()) return emptyList()
            val out = ArrayList<Pair<Int, T>>()
            for (p in items) {
                val nm = name(p)
                val r = rankOf(norm(nm))
                if (r >= 0 && !hidden(nm)) out.add(r to p)
            }
            return out.sortedBy { it.first }.take(MAX_TOP).map { it.second }
        }

        /** elenchi delle categorie: prima quelle in primo piano (ordine degli schemi), poi le altre come sempre, le nascoste in fondo */
        fun <T> order(items: List<T>, name: (T) -> String): List<T> {
            if (topP.isEmpty() && hideP.isEmpty() && !noAdult) return items
            val first = ArrayList<Pair<Int, T>>()
            val mid = ArrayList<T>()
            val last = ArrayList<T>()
            for (c in items) {
                val nm = name(c)
                if (hidden(nm)) { last.add(c); continue }
                val r = if (topP.isEmpty()) -1 else rankOf(norm(nm))
                if (r >= 0) first.add(r to c) else mid.add(c)
            }
            return first.sortedBy { it.first }.map { it.second } + mid + last
        }

        /**
         * Un titolo (film, serie, episodio) non entra in nessuna riga della Home: con [noAdult], se la sua categoria
         * ([catName], null = sconosciuta) è nascosta o per adulti, oppure se il suo nome è per adulti ([AdultFilter.title]).
         * Con adult "on" la Home resta com'era: sempre false.
         */
        fun blocked(title: String?, catName: String?): Boolean =
            noAdult && ((catName != null && hidden(catName)) || AdultFilter.title(title))
    }

    @Volatile private var cur = Rules("", "", "")

    /** regole della configurazione attuale: si ricalcolano (e la cache riparte) solo quando cambia */
    fun rules(): Rules {
        val rc = Remote.rc()
        val t = rc.optString("home.top", "")
        val h = rc.optString("home.hide", "")
        val a = rc.optString("adult", "")
        val c = cur
        if (c.top == t && c.hide == h && c.adult == a) return c
        return Rules(t, h, a).also { cur = it }
    }

    /** impronta della configurazione: se cambia, Home ed elenchi si ricostruiscono */
    fun sig(): String = rules().sig

    /** adulti fuori dalla Home (rc["adult"] diverso da "on") */
    fun noAdult(): Boolean = rules().noAdult

    /** la categoria è tra quelle nascoste dalla Home (da home.hide o, senza adulti, per adulti) */
    fun hidden(name: String): Boolean = rules().hidden(name)

    /** il titolo non entra nella Home (vedi [Rules.blocked]) */
    fun blocked(title: String?, catName: String?): Boolean = rules().blocked(title, catName)

    /** Home: le categorie in primo piano fra [cats] (coppie tabella–categoria nell'ordine solito), mai le nascoste */
    fun top(cats: List<Pair<String, Db.Cat>>): List<Pair<String, Db.Cat>> = rules().top(cats) { it.second.name }

    /** elenchi delle categorie: prima quelle in primo piano, poi le altre come sempre, le nascoste (e quelle per adulti) in fondo */
    fun order(cats: List<Db.Cat>): List<Db.Cat> = rules().order(cats) { it.name }
}
