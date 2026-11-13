package com.kairos.tv

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Archivio locale (SQLite): canali, film e serie stanno su disco, non in memoria.
 * Le liste leggono solo le righe visibili con un Cursor: 50.000 titoli non pesano sulla RAM.
 */
object Db {
    private const val NAME = "lib.db"
    // 2: versioni dei canali (qualità adattiva) · 3: guida XMLTV (programmi letti in sottofondo; le liste scaricate restano)
    private const val VERSION = 3
    private val V3 = listOf(
        "CREATE TABLE IF NOT EXISTS prog (ch TEXT, start INTEGER, stop INTEGER, title TEXT, descr TEXT)",
        "CREATE INDEX IF NOT EXISTS prog_ch_stop ON prog(ch, stop)",
        "CREATE TABLE IF NOT EXISTS prog_stage (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT NOT NULL, PRIMARY KEY(ch, start))",
    )

    private val SCHEMA = listOf(
        "CREATE TABLE cat (kind TEXT, id TEXT, name TEXT, ord INTEGER, pl TEXT, adult INTEGER)",
        "CREATE INDEX cat_k ON cat(kind, pl)",
        "CREATE TABLE live (_id INTEGER PRIMARY KEY, k TEXT, pl TEXT, cat TEXT, num INTEGER, name TEXT, logo TEXT, sid TEXT, url TEXT, epg TEXT, adult INTEGER, qkey TEXT, q INTEGER, hevc INTEGER)",
        "CREATE INDEX live_cat ON live(cat)",
        "CREATE INDEX live_q ON live(qkey)",
        "CREATE INDEX live_k ON live(k)",
        "CREATE TABLE vod (_id INTEGER PRIMARY KEY, k TEXT, pl TEXT, cat TEXT, name TEXT, img TEXT, sid TEXT, ext TEXT, url TEXT, rating TEXT, year TEXT, added INTEGER, adult INTEGER)",
        "CREATE INDEX vod_cat ON vod(cat)",
        "CREATE INDEX vod_k ON vod(k)",
        "CREATE INDEX vod_added ON vod(added)",
        "CREATE TABLE series (_id INTEGER PRIMARY KEY, k TEXT, pl TEXT, cat TEXT, name TEXT, img TEXT, sid TEXT, ext TEXT, url TEXT, rating TEXT, year TEXT, added INTEGER, adult INTEGER, plot TEXT)",
        "CREATE INDEX series_cat ON series(cat)",
        "CREATE INDEX series_k ON series(k)",
        "CREATE INDEX series_added ON series(added)",
        "CREATE TABLE ep (_id INTEGER PRIMARY KEY, series INTEGER, season INTEGER, num INTEGER, title TEXT, url TEXT)",
        "CREATE INDEX ep_s ON ep(series)",
    ) + V3

    private class H(c: Context) : SQLiteOpenHelper(c, NAME, null, VERSION) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) { SCHEMA.forEach { db.execSQL(it) } }
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            // 2 → 3: solo le tabelle della guida XMLTV, le liste scaricate restano
            if (oldV == 2) { V3.forEach { db.execSQL(it) }; return }
            rebuild(db)
        }
        // app tornata a una versione più vecchia (installazione manuale): archivio rifatto invece di un errore all'apertura
        override fun onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int) = rebuild(db)

        private fun rebuild(db: SQLiteDatabase) {
            listOf("cat", "live", "vod", "series", "ep", "prog", "prog_stage").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
            Store.syncSig = "" // archivio nuovo: le liste si ricaricano
            Store.clearEpgBuild()
        }
    }

    private lateinit var h: H
    fun init(c: Context) { h = H(c.applicationContext) }
    val r: SQLiteDatabase get() = h.readableDatabase
    val w: SQLiteDatabase get() = h.writableDatabase

    fun clear(db: SQLiteDatabase) {
        listOf("cat", "live", "vod", "series", "ep", "prog", "prog_stage").forEach { db.execSQL("DELETE FROM $it") }
        Store.clearEpgBuild()
        forget()
    }

    // ---------- guida XMLTV (tabella prog): canali da cercare nel file e programmi salvati ----------
    class EpgChannel(val epg: String, val name: String)
    class StoredProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)

    /** id della guida e nome di ogni canale dei portali in uso: con questi Epg riconosce i canali dentro il file XMLTV */
    fun epgChannels(pls: List<String>): List<EpgChannel> {
        if (pls.isEmpty()) return emptyList()
        val q = base(pls, kids = false)
        val out = ArrayList<EpgChannel>()
        r.rawQuery("SELECT DISTINCT epg, name FROM live WHERE ${q.where} ORDER BY epg, name", q.args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(EpgChannel(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        return out
    }

    /** programmi salvati dei canali [channels] che toccano l'intervallo (secondi), in ordine di inizio; [visit] false = basta */
    fun scanProgrammes(channels: List<String>, fromSeconds: Long, toSeconds: Long, maxRows: Int, visit: (StoredProgramme) -> Boolean) {
        if (channels.isEmpty() || maxRows <= 0) return
        var scanned = 0
        var keepGoing = true
        for (group in channels.distinct().chunked(300)) {
            if (!keepGoing || scanned >= maxRows) break
            val placeholders = group.joinToString(",") { "?" }
            val args = group.toTypedArray() + arrayOf(fromSeconds.toString(), toSeconds.toString(), (maxRows - scanned).toString())
            r.rawQuery("SELECT ch, start, stop, title, descr FROM prog WHERE ch IN ($placeholders) AND stop > ? AND start < ? ORDER BY start LIMIT ?", args).use { c ->
                while (c.moveToNext()) {
                    scanned++
                    keepGoing = visit(StoredProgramme(c.getString(0) ?: "", c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: ""))
                    if (!keepGoing || scanned >= maxRows) break
                }
            }
        }
    }

    /** archivio svuotato: categorie e totali in memoria da ricontare */
    fun forget() {
        synchronized(catMemo) { catMemo.clear() }
        synchronized(countMemo) { countMemo.clear() }
    }

    /** toglie i contenuti di una playlist (prima di ricaricarla) */
    fun dropPlaylist(db: SQLiteDatabase, pl: String) {
        val a = arrayOf(pl)
        db.execSQL("DELETE FROM ep WHERE series IN (SELECT _id FROM series WHERE pl = ?)", a)
        listOf("cat", "live", "vod", "series").forEach { db.execSQL("DELETE FROM $it WHERE pl = ?", a) }
    }

    /** toglie una parte (live / vod / series) di una playlist prima di ricaricarla */
    fun dropKind(db: SQLiteDatabase, pl: String, kind: String) {
        val a = arrayOf(pl)
        db.execSQL("DELETE FROM cat WHERE pl = ? AND kind = ?", arrayOf(pl, kind))
        when (kind) {
            "live" -> db.execSQL("DELETE FROM live WHERE pl = ?", a)
            "vod" -> db.execSQL("DELETE FROM vod WHERE pl = ?", a)
            else -> {
                db.execSQL("DELETE FROM ep WHERE series IN (SELECT _id FROM series WHERE pl = ?)", a)
                db.execSQL("DELETE FROM series WHERE pl = ?", a)
            }
        }
    }

    /** toglie le playlist che non sono più in uso */
    fun dropOthers(db: SQLiteDatabase, keep: List<String>) {
        if (keep.isEmpty()) { clear(db); return }
        val ph = keep.joinToString(",") { "?" }
        val a = keep.toTypedArray()
        db.execSQL("DELETE FROM ep WHERE series IN (SELECT _id FROM series WHERE pl NOT IN ($ph))", a)
        listOf("cat", "live", "vod", "series").forEach { db.execSQL("DELETE FROM $it WHERE pl NOT IN ($ph)", a) }
    }

    /* ---------------- filtri ---------------- */
    const val ALL = "*all"
    const val FAV = "*fav"
    const val NEW = "*new"
    /** riga «Di tendenza»: i titoli della classifica della settimana (vedi [Trend]) che la linea ha davvero */
    const val TREND = "*trend"

    private class Q(var where: String, val args: ArrayList<String>)

    private fun base(pls: List<String>, kids: Boolean): Q {
        val q = Q("pl IN (" + pls.joinToString(",") { "?" } + ")", ArrayList(pls))
        if (kids) q.where += " AND adult = 0"
        return q
    }

    private fun favIn(q: Q, favs: Set<String>) {
        val list = favs.take(900)
        if (list.isEmpty()) { q.where += " AND 0"; return }
        q.where += " AND k IN (" + list.joinToString(",") { "?" } + ")"
        q.args.addAll(list)
    }

    /**
     * senza i contenuti delle categorie [skip] (nascoste dalla Home dal pannello, vedi [HomeCfg]). SQLite accetta al massimo
     * 999 valori per lettura: con i preferiti (La mia lista nella Home senza adulti) si usa solo lo spazio che resta.
     */
    private fun skipCats(q: Q, skip: List<String>) {
        if (skip.isEmpty()) return
        val list = skip.take(minOf(900, 990 - q.args.size).coerceAtLeast(0))
        if (list.isEmpty()) return
        q.where += " AND cat NOT IN (" + list.joinToString(",") { "?" } + ")"
        q.args.addAll(list)
    }

    /* ---------------- categorie ---------------- */
    class Cat(val id: String, val name: String, var count: Int = -1, val adult: Boolean = false)

    /**
     * Categorie e totali già contati, in memoria: contarli legge tutta la tabella (decine di migliaia di righe su disco),
     * e lo si rifà solo quando le liste cambiano davvero ([Sync.ver]) o cambiano playlist e profilo.
     */
    private val catMemo = HashMap<String, List<Cat>>()
    private val countMemo = HashMap<String, Int>()

    private fun memoKey(kind: String, pls: List<String>, kids: Boolean): String = kind + "|" + Sync.ver(kind) + "|" + kids + "|" + pls.joinToString(",")

    private fun copyOf(list: List<Cat>): List<Cat> = list.map { Cat(it.id, it.name, it.count, it.adult) }

    /** lo stesso conteggio chiesto due volte insieme (es. Home e menu delle categorie): il secondo aspetta il primo */
    private val catLocks = HashMap<String, Any>()
    private fun lockOf(key: String): Any = synchronized(catLocks) { if (catLocks.size >= 24) catLocks.clear(); catLocks.getOrPut(key) { Any() } }

    fun cats(kind: String, pls: List<String>, kids: Boolean): List<Cat> {
        if (pls.isEmpty()) return emptyList()
        val key = memoKey(kind, pls, kids)
        synchronized(catMemo) { catMemo[key] }?.let { return copyOf(it) }
        synchronized(lockOf(key)) {
            synchronized(catMemo) { catMemo[key] }?.let { return copyOf(it) }
            val out = readCats(kind, pls, kids)
            synchronized(catMemo) {
                if (catMemo.size >= 12) catMemo.clear()
                catMemo[key] = copyOf(out)
            }
            return out
        }
    }

    private fun readCats(kind: String, pls: List<String>, kids: Boolean): List<Cat> {
        val q = base(pls, kids)
        val out = ArrayList<Cat>()
        val table = if (kind == "live") "live" else kind
        val counts = HashMap<String, Int>()
        r.rawQuery("SELECT cat, COUNT(*) FROM $table WHERE ${q.where} GROUP BY cat", q.args.toTypedArray()).use { c ->
            while (c.moveToNext()) counts[c.getString(0) ?: ""] = c.getInt(1)
        }
        val q2 = base(pls, kids)
        r.rawQuery("SELECT id, name, adult FROM cat WHERE kind = ? AND ${q2.where} ORDER BY ord", arrayOf(kind) + q2.args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = counts[id] ?: 0
                if (n > 0) out.add(Cat(id, c.getString(1) ?: "", n, c.getInt(2) == 1))
            }
        }
        return out
    }

    fun count(table: String, pls: List<String>, kids: Boolean): Int {
        if (pls.isEmpty()) return 0
        val key = memoKey(table, pls, kids)
        synchronized(countMemo) { countMemo[key] }?.let { return it }
        val q = base(pls, kids)
        val n = r.rawQuery("SELECT COUNT(*) FROM $table WHERE ${q.where}", q.args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        synchronized(countMemo) {
            if (countMemo.size >= 12) countMemo.clear()
            countMemo[key] = n
        }
        return n
    }

    fun isEmpty(): Boolean = try {
        r.rawQuery("SELECT EXISTS(SELECT 1 FROM live) OR EXISTS(SELECT 1 FROM vod) OR EXISTS(SELECT 1 FROM series)", null).use { c -> !c.moveToFirst() || c.getLong(0) == 0L }
    } catch (_: Exception) { true }

    /* ---------------- canali ---------------- */
    private const val LIVE_COLS = "_id, k, pl, cat, num, name, logo, sid, url, epg, qkey, q, hevc"

    fun live(cat: String, pls: List<String>, kids: Boolean, favs: Set<String>): Cursor {
        val q = base(pls, kids)
        when (cat) {
            ALL -> {}
            FAV -> favIn(q, favs)
            else -> { q.where += " AND cat = ?"; q.args.add(cat) }
        }
        val c = r.rawQuery("SELECT $LIVE_COLS FROM live WHERE ${q.where} ORDER BY num, _id", q.args.toTypedArray())
        c.count // riempie la prima finestra qui, fuori dal thread dell'interfaccia
        return c
    }

    fun liveIds(cat: String, pls: List<String>, kids: Boolean, favs: Set<String>): LongArray {
        live(cat, pls, kids, favs).use { c ->
            val out = LongArray(c.count)
            var i = 0
            while (c.moveToNext()) out[i++] = c.getLong(0)
            return out
        }
    }

    /* ---------------- film e serie ---------------- */
    fun vod(table: String, cat: String, pls: List<String>, kids: Boolean, text: String, favs: Set<String>): Cursor {
        val q = base(pls, kids)
        var order = "_id"
        when (cat) {
            ALL -> order = "added DESC, _id"
            FAV -> favIn(q, favs)
            NEW -> order = "added DESC, _id"
            else -> { q.where += " AND cat = ?"; q.args.add(cat) }
        }
        val t = text.trim()
        if (t.isNotEmpty()) { q.where += " AND name LIKE ?"; q.args.add("%" + t.replace("%", "").replace("_", "") + "%") }
        val limit = if (cat == NEW) " LIMIT 200" else ""
        val c = r.rawQuery("SELECT _id, k, pl, cat, name, img, sid, ext, url, rating, year FROM $table WHERE ${q.where} ORDER BY $order$limit", q.args.toTypedArray())
        c.count
        return c
    }

    /* ---------------- righe stile Netflix (Home, Film, Serie) ---------------- */
    class Card(val table: String, val id: Long, val k: String, val name: String, val img: String, val year: String, val rating: String, val plot: String)

    /** i primi [limit] titoli di una riga: categoria, preferiti o aggiunti di recente ([skip]: categorie da escludere) */
    fun cards(table: String, cat: String, pls: List<String>, kids: Boolean, favs: Set<String>, limit: Int, skip: List<String> = emptyList()): List<Card> {
        if (pls.isEmpty()) return emptyList()
        if (cat == TREND) return trend(table, pls, kids, limit, skip)
        val q = base(pls, kids)
        var order = "_id"
        when (cat) {
            ALL, NEW -> order = "added DESC, _id"
            FAV -> favIn(q, favs)
            else -> { q.where += " AND cat = ?"; q.args.add(cat) }
        }
        skipCats(q, skip)
        val plot = if (table == "series") "plot" else "''"
        val out = ArrayList<Card>(limit)
        r.rawQuery("SELECT _id, k, name, img, year, rating, $plot FROM $table WHERE ${q.where} ORDER BY $order LIMIT $limit", q.args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(Card(table, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: ""))
        }
        return out
    }

    /** titoli di tendenza della settimana che la linea ha, nell'ordine della classifica; mai sul thread principale */
    private fun trend(table: String, pls: List<String>, kids: Boolean, limit: Int, skip: List<String>): List<Card> {
        val titles = Trend.get()?.let { if (table == "series") it.series else it.movies }.orEmpty()
        if (titles.isEmpty() || (table != "vod" && table != "series")) return emptyList()
        val plot = if (table == "series") "plot" else "''"
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for ((title, year) in titles) {
            if (out.size >= limit.coerceAtMost(20) || title.isBlank()) break
            val q = base(pls, kids)
            skipCats(q, skip)
            q.where += " AND name LIKE ? ESCAPE '\\'"
            q.args.add("%" + title.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            r.rawQuery("SELECT _id, k, name, img, year, rating, $plot FROM $table WHERE ${q.where} ORDER BY added DESC, _id LIMIT 8", q.args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val card = Card(table, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "")
                    if (TitleMatch.matches(card.name, card.year.toIntOrNull(), title, year) && seen.add(card.k)) {
                        out.add(card)
                        break
                    }
                }
            }
        }
        return out
    }

    /**
     * titolo in evidenza: il più recente con un'immagine, fuori dalle categorie [skip];
     * [clean] (Home senza adulti): il primo fra i più recenti che non ha un nome per adulti, così il riquadro non resta vuoto
     */
    fun featured(table: String, pls: List<String>, kids: Boolean, skip: List<String> = emptyList(), clean: Boolean = false): Card? {
        if (pls.isEmpty()) return null
        val q = base(pls, kids)
        skipCats(q, skip)
        val plot = if (table == "series") "plot" else "''"
        val limit = if (clean) 50 else 1
        r.rawQuery("SELECT _id, k, name, img, year, rating, $plot FROM $table WHERE ${q.where} AND img LIKE 'http%' ORDER BY added DESC, _id LIMIT $limit", q.args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2) ?: ""
                if (clean && AdultFilter.title(name)) continue
                return Card(table, c.getLong(0), c.getString(1) ?: "", name, c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "")
            }
            return null
        }
    }

    /**
     * nome della categoria di alcuni titoli di [table] (vod / series), per chiave k: serve a «Continua a guardare» nella Home
     * senza adulti. Una sola lettura con gli indici; se non riesce restano i soli nomi dei titoli.
     */
    fun catNames(table: String, keys: Collection<String>): Map<String, String> {
        val list = keys.filter { it.isNotEmpty() }.distinct().take(200)
        if (list.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        try {
            val ph = list.joinToString(",") { "?" }
            r.rawQuery("SELECT t.k, c.name FROM $table t JOIN cat c ON c.kind = ? AND c.pl = t.pl AND c.id = t.cat WHERE t.k IN ($ph)", arrayOf(table) + list.toTypedArray()).use { c ->
                while (c.moveToNext()) { val k = c.getString(0) ?: continue; out[k] = c.getString(1) ?: "" }
            }
        } catch (_: Exception) {}
        return out
    }

    /* ---------------- una riga ---------------- */
    class Row(private val m: Map<String, String?>) {
        fun s(k: String): String = m[k] ?: ""
        fun l(k: String): Long = m[k]?.toLongOrNull() ?: 0L
    }

    private fun row(c: Cursor): Row {
        val m = HashMap<String, String?>()
        for (i in 0 until c.columnCount) m[c.getColumnName(i)] = if (c.isNull(i)) null else c.getString(i)
        return Row(m)
    }

    fun one(table: String, id: Long): Row? =
        r.rawQuery("SELECT * FROM $table WHERE _id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) row(c) else null }

    fun current(c: Cursor): Row = row(c)

    /** altre versioni dello stesso canale nella stessa playlist (4K/FHD/HD/SD) */
    class Variant(val id: Long, val name: String, val q: Int, val hevc: Boolean)

    fun variants(r: Row): List<Variant> {
        val key = r.s("qkey")
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Variant>()
        this.r.rawQuery("SELECT _id, name, q, hevc FROM live WHERE qkey = ? AND pl = ? AND _id != ? ORDER BY q DESC, _id LIMIT 8", arrayOf(key, r.s("pl"), r.s("_id"))).use { c ->
            while (c.moveToNext()) out.add(Variant(c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3) == 1))
        }
        return out
    }

    class Ep(val season: Int, val num: Int, val title: String, val url: String)

    fun episodes(seriesRow: Long): List<Ep> {
        val out = ArrayList<Ep>()
        r.rawQuery("SELECT season, num, title, url FROM ep WHERE series = ? ORDER BY season, num, _id", arrayOf(seriesRow.toString())).use { c ->
            while (c.moveToNext()) out.add(Ep(c.getInt(0), c.getInt(1), c.getString(2) ?: "", c.getString(3) ?: ""))
        }
        return out
    }

    /* ---------------- ricerca ---------------- */
    class Hit(val table: String, val id: Long, val name: String, val img: String, val cat: String)

    fun search(text: String, pls: List<String>, kids: Boolean): List<Hit> {
        val t = text.trim().replace("%", "").replace("_", "")
        if (t.length < 2 || pls.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        for ((table, img) in listOf("live" to "logo", "vod" to "img", "series" to "img")) {
            val q = base(pls, kids)
            q.where += " AND name LIKE ?"
            q.args.add("%$t%")
            r.rawQuery("SELECT _id, name, $img, cat FROM $table WHERE ${q.where} ORDER BY name LIMIT 60", q.args.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(Hit(table, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            }
        }
        return out
    }
}
