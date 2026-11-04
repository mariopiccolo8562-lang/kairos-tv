package com.kairos.tv

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Caricamento delle playlist nell'archivio locale.
 * Xtream: le risposte JSON vengono lette «a flusso» (JsonReader) e scritte subito su disco;
 * M3U: riga per riga. In memoria non c'è mai la lista intera, anche con 100.000 titoli.
 * Durante l'aggiornamento l'app continua a mostrare i dati di prima (SQLite in modalità WAL).
 */
object Sync {
    @Volatile var running = false
        private set
    @Volatile var pct = 0
        private set
    @Volatile var error = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private var lastNotify = 0L
    private fun emit(force: Boolean) {
        val t = System.currentTimeMillis()
        if (!force && t - lastNotify < 400) return
        lastNotify = t
        ui { listeners.forEach { try { it() } catch (_: Throwable) {} } }
    }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kl-sync").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }

    private fun sig(pls: List<Playlist>): String = pls.joinToString("§") { it.sig() }

    @Volatile private var failAt = 0L

    fun needed(): Boolean {
        val pls = K.active()
        if (pls.isEmpty()) return false
        // dopo un errore si riprova al massimo ogni 30 minuti (o subito con «Aggiorna liste»)
        if (System.currentTimeMillis() - failAt < 30 * 60_000L) return false
        return sig(pls) != Store.syncSig || System.currentTimeMillis() - Store.syncAt > 12 * 3600_000L || Db.isEmpty()
    }

    fun start(force: Boolean = false) {
        if (running) return
        val pls = K.active()
        if (pls.isEmpty()) return
        if (!force && !needed()) return
        running = true
        pct = 0
        error = ""
        emit(true)
        exec.execute { run(pls) }
    }

    /** sale ogni volta che una parte delle liste è pronta (prima i canali, poi film e serie) */
    @Volatile var phase = 0
        private set
    private val vers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** sale quando [kind] (live / vod / series) è stato ricaricato: quella schermata si rilegge subito */
    fun ver(kind: String): Int = vers[kind] ?: 0
    private fun bump(kind: String) { vers[kind] = ver(kind) + 1 }

    /**
     * Prima i canali di tutte le playlist, poi i film, poi le serie: la TV in diretta si può usare
     * dopo pochi secondi anche sulle chiavette lente. Una transazione per parte e per playlist:
     * se una non risponde restano i suoi dati di prima e le altre si aggiornano lo stesso.
     */
    private fun run(pls: List<Playlist>) {
        val errors = ArrayList<String>()
        try {
            Vpns.awaitForced() // VPN «sempre attiva»: le liste passano dalla VPN
            val db = Db.w
            // playlist tolte dal pannello (o di un altro profilo): via dall'archivio
            val keep = pls.map { it.id }
            db.beginTransactionNonExclusive()
            try {
                Db.dropOthers(db, keep)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val parts = listOf("live", "vod", "series")
            // le M3U si leggono in una volta sola (canali, film e serie sono nello stesso file)
            val jobs = ArrayList<Pair<Int, String>>()
            for (part in parts) pls.forEachIndexed { i, p -> if (p.isXtream || part == "live") jobs.add(i to part) }
            val failed = HashSet<String>()
            jobs.forEachIndexed { n, (i, part) ->
                val p = pls[i]
                if (failed.contains(p.id)) return@forEachIndexed // server che non risponde: non si insiste
                val from = n * 100 / jobs.size
                val span = 100 / jobs.size
                val step = { v: Int -> setPct(from + v * span / 100) }
                db.beginTransactionNonExclusive()
                try {
                    if (p.isXtream) Db.dropKind(db, p.id, part) else Db.dropPlaylist(db, p.id)
                    val st = Stmts(db, i * 1_000_000L + parts.indexOf(part) * 300_000L)
                    if (p.isXtream) xtream(p, st, part, step) else m3u(p, st, step)
                    db.setTransactionSuccessful()
                    if (!p.isXtream) Store.setPlOrigin(p.id, p.origin())
                } catch (t: Throwable) {
                    failed.add(p.id)
                    errors.add((if (pls.size > 1) p.name + ": " else "") + (t.message ?: t.javaClass.simpleName))
                } finally {
                    db.endTransaction()
                }
                if (p.isXtream) bump(part) else parts.forEach { bump(it) }
                phase++
                emit(true)
            }
            if (errors.isEmpty()) {
                Store.syncSig = sig(pls)
                Store.syncAt = System.currentTimeMillis()
                failAt = 0L
            } else failAt = System.currentTimeMillis()
            error = errors.joinToString(" · ")
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            failAt = System.currentTimeMillis()
        } finally {
            running = false
            pct = 100
            phase++
            emit(true)
        }
    }

    /** «Aggiorna liste» dalle Impostazioni: si riprova subito anche dopo un errore */
    fun force() {
        failAt = 0L
        start(force = true)
    }

    private fun setPct(v: Int) {
        if (v != pct) { pct = v.coerceIn(0, 99); emit(false) }
    }

    /* ---------------- scrittura ---------------- */
    private class Stmts(db: SQLiteDatabase, base: Long) {
        val cat: SQLiteStatement = db.compileStatement("INSERT INTO cat(kind,id,name,ord,pl,adult) VALUES(?,?,?,?,?,?)")
        val live: SQLiteStatement = db.compileStatement("INSERT INTO live(k,pl,cat,num,name,logo,sid,url,epg,adult,qkey,q,hevc) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")

        /** versione del canale (chiave comune, qualità, HEVC) dopo gli altri campi */
        fun quality(name: String) {
            live.bindString(11, Quality.key(name))
            live.bindLong(12, Quality.rank(name).toLong())
            live.bindLong(13, if (Quality.hevc(name)) 1 else 0)
        }
        val vod: SQLiteStatement = db.compileStatement("INSERT INTO vod(k,pl,cat,name,img,sid,ext,url,rating,year,added,adult) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
        val series: SQLiteStatement = db.compileStatement("INSERT INTO series(k,pl,cat,name,img,sid,ext,url,rating,year,added,adult,plot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
        val ep: SQLiteStatement = db.compileStatement("INSERT INTO ep(series,season,num,title,url) VALUES(?,?,?,?,?)")
        var ord = base // categorie nell'ordine delle playlist, anche quando sono unite

        fun addCat(kind: String, id: String, name: String, pl: String, adult: Boolean) {
            cat.clearBindings()
            cat.bindString(1, kind); cat.bindString(2, id); cat.bindString(3, name); cat.bindLong(4, ord++); cat.bindString(5, pl); cat.bindLong(6, if (adult) 1 else 0)
            cat.executeInsert()
        }
    }

    private fun SQLiteStatement.s(i: Int, v: String?) { if (v == null) bindNull(i) else bindString(i, v) }

    private val RX_ADULT = Regex("(?i)(\\badult|adulti|\\bxxx|18\\+|\\+18|\\bporn|erotic|hentai|for adults|\\bsexy?\\b)")
    private fun adult(s: String) = RX_ADULT.containsMatchIn(s)

    /* ---------------- Xtream Codes ---------------- */
    /** una parte ([kind]: live / vod / series) di una playlist Xtream: le sue categorie e i suoi contenuti */
    private fun xtream(p: Playlist, st: Stmts, kind: String, step: (Int) -> Unit) {
        val known = HashMap<String, Boolean>() // categoria → per adulti
        val catAction = when (kind) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        eachObject(p.api(catAction) ?: return) { o ->
            val id = o["category_id"] ?: return@eachObject
            val name = (o["category_name"] ?: "").trim().ifEmpty { "Altro" }
            val key = p.id + ":" + kind + ":" + id
            if (known.containsKey(key)) return@eachObject
            val ad = adult(name)
            known[key] = ad
            st.addCat(kind, key, name, p.id, ad)
        }
        step(10)
        fun catOf(id: String?): Pair<String, Boolean> {
            val key = p.id + ":" + kind + ":" + (id ?: "")
            val ad = known[key]
            if (ad != null) return key to ad
            known[key] = false
            st.addCat(kind, key, "Altro", p.id, false)
            return key to false
        }
        when (kind) {
            "live" -> {
                var n = 0L
                eachObject(p.api("get_live_streams") ?: return) { o ->
                    val sid = o["stream_id"] ?: return@eachObject
                    val name = (o["name"] ?: "").trim()
                    val (cat, ad) = catOf(o["category_id"])
                    n++
                    val s = st.live
                    s.clearBindings()
                    s.bindString(1, p.id + ":" + sid); s.bindString(2, p.id); s.bindString(3, cat)
                    s.bindLong(4, o["num"]?.toDoubleOrNull()?.toLong() ?: n); s.bindString(5, name); s.s(6, o["stream_icon"])
                    s.bindString(7, sid); s.bindNull(8); s.s(9, o["epg_channel_id"]); s.bindLong(10, if (ad || adult(name)) 1 else 0)
                    st.quality(name)
                    s.executeInsert()
                }
            }
            "vod" -> eachObject(p.api("get_vod_streams") ?: return) { o ->
                val sid = o["stream_id"] ?: return@eachObject
                val name = (o["name"] ?: "").trim()
                val (cat, ad) = catOf(o["category_id"])
                val s = st.vod
                s.clearBindings()
                s.bindString(1, p.id + ":" + sid); s.bindString(2, p.id); s.bindString(3, cat); s.bindString(4, name)
                s.s(5, o["stream_icon"]); s.bindString(6, sid); s.s(7, o["container_extension"]); s.bindNull(8)
                s.s(9, o["rating"]); s.s(10, yearOf(o["year"] ?: o["releasedate"] ?: o["release_date"], name))
                s.bindLong(11, o["added"]?.toDoubleOrNull()?.toLong() ?: 0); s.bindLong(12, if (ad || adult(name)) 1 else 0)
                s.executeInsert()
            }
            else -> eachObject(p.api("get_series") ?: return) { o ->
                val sid = o["series_id"] ?: return@eachObject
                val name = (o["name"] ?: "").trim()
                val (cat, ad) = catOf(o["category_id"])
                val s = st.series
                s.clearBindings()
                s.bindString(1, p.id + ":s" + sid); s.bindString(2, p.id); s.bindString(3, cat); s.bindString(4, name)
                s.s(5, o["cover"]); s.bindString(6, sid); s.bindNull(7); s.bindNull(8)
                s.s(9, o["rating"]); s.s(10, yearOf(o["releaseDate"] ?: o["release_date"] ?: o["year"], name))
                s.bindLong(11, o["last_modified"]?.toDoubleOrNull()?.toLong() ?: 0); s.bindLong(12, if (ad || adult(name)) 1 else 0)
                // la trama arriva comunque aprendo la scheda: in modalità leggera non occupa spazio
                if (App.lite) s.bindNull(13) else s.s(13, o["plot"]?.take(600))
                s.executeInsert()
            }
        }
        step(100)
    }

    private val RX_YEAR = Regex("(19|20)\\d{2}")
    private fun yearOf(v: String?, name: String): String? {
        val t = v?.trim().orEmpty()
        RX_YEAR.find(t)?.let { return it.value }
        return Regex("\\((19|20)\\d{2}\\)").find(name)?.value?.trim('(', ')')
    }

    /** ogni oggetto di un array JSON (o dei valori di un oggetto), letto a flusso */
    private fun eachObject(url: String, fn: (HashMap<String, String>) -> Unit) {
        val c = Http.open(url, connectMs = 20_000, readMs = 90_000)
        try {
            if (c.responseCode >= 400) throw Exception("il server delle playlist ha risposto HTTP ${c.responseCode}")
            val r = JsonReader(InputStreamReader(Http.stream(c), Charsets.UTF_8))
            r.isLenient = true
            // risposta vuota (alcuni server per le liste senza contenuti) = lista vuota
            val first = try { r.peek() } catch (_: java.io.EOFException) { return }
            when (first) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) { if (r.peek() == JsonToken.BEGIN_OBJECT) fn(flat(r)) else r.skipValue() }
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) { r.nextName(); if (r.peek() == JsonToken.BEGIN_OBJECT) fn(flat(r)) else r.skipValue() }
                    r.endObject()
                }
                else -> {}
            }
            r.close()
        } finally {
            c.disconnect()
        }
    }

    private fun flat(r: JsonReader): HashMap<String, String> {
        val m = HashMap<String, String>(32)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    var first: String? = null
                    while (r.hasNext()) { if (first == null && r.peek() == JsonToken.STRING) first = r.nextString() else r.skipValue() }
                    r.endArray()
                    if (first != null) m[k] = first
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    /* ---------------- M3U ---------------- */
    private val RX_SE = Regex("^(.*?)[\\s._-]*[sS](\\d{1,2})\\s?[eE](\\d{1,3})")
    private val RX_SE2 = Regex("[sS]\\d{1,2}\\s?[eE]\\d{1,3}")
    private val RX_VOD = Regex("\\.(mp4|mkv|avi|mov|m4v)(\\?|$)")

    private class Inf(val name: String, val logo: String, val group: String, val num: Long)

    private fun attr(l: String, key: String): String {
        val i = l.indexOf("$key=\"")
        if (i < 0) return ""
        val s = i + key.length + 2
        val e = l.indexOf('"', s)
        return if (e < 0) "" else l.substring(s, e)
    }

    private fun m3u(p: Playlist, st: Stmts, step: (Int) -> Unit) {
        val c = Http.open(Playlist.fixUrl(p.url), connectMs = 20_000, readMs = 120_000)
        try {
            if (c.responseCode >= 400) throw Exception("la playlist ha risposto HTTP ${c.responseCode}")
            val total = c.contentLength.toLong()
            var read = 0L
            val cats = HashMap<String, String>()
            val series = HashMap<String, Long>()
            fun cat(kind: String, group: String): String {
                val k = kind + "|" + group
                cats[k]?.let { return it }
                val id = p.id + ":" + kind + ":" + group
                cats[k] = id
                st.addCat(kind, id, group, p.id, adult(group))
                return id
            }
            BufferedReader(InputStreamReader(Http.stream(c), Charsets.UTF_8), 64 * 1024).use { br ->
                var meta: Inf? = null
                var n = 0L
                while (true) {
                    val raw = br.readLine() ?: break
                    read += raw.length + 1
                    if (total > 0 && (n and 511L) == 0L) step((read * 100 / total).toInt().coerceIn(0, 99))
                    val l = raw.trim()
                    if (l.isEmpty()) continue
                    if (l[0] == '#') {
                        if (l.startsWith("#EXTINF")) {
                            val q = l.lastIndexOf('"')
                            val ci = l.indexOf(',', if (q > 0) q else 0)
                            val name = (if (ci >= 0) l.substring(ci + 1).trim() else "").ifEmpty { attr(l, "tvg-name") }
                            meta = Inf(name, attr(l, "tvg-logo"), attr(l, "group-title").ifEmpty { "Senza categoria" }, attr(l, "tvg-chno").toLongOrNull() ?: 0)
                        }
                        continue
                    }
                    val m = meta ?: continue
                    meta = null
                    n++
                    val low = l.lowercase()
                    val key = p.id + ":" + Integer.toHexString(Playlist.pathOf(l).hashCode())
                    val ad = adult(m.group) || adult(m.name)
                    if (low.contains("/movie/") || (RX_VOD.containsMatchIn(low) && !low.contains("/series/"))) {
                        val s = st.vod
                        s.clearBindings()
                        s.bindString(1, key); s.bindString(2, p.id); s.bindString(3, cat("vod", m.group)); s.bindString(4, m.name)
                        s.bindString(5, m.logo); s.bindNull(6); s.bindNull(7); s.bindString(8, l)
                        s.bindNull(9); s.s(10, yearOf(null, m.name)); s.bindLong(11, n); s.bindLong(12, if (ad) 1 else 0)
                        s.executeInsert()
                    } else if (low.contains("/series/") || RX_SE2.containsMatchIn(m.name)) {
                        val sm = RX_SE.find(m.name)
                        val title = sm?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: m.name
                        val sk = m.group + "|" + title
                        val row = series[sk] ?: run {
                            val s = st.series
                            s.clearBindings()
                            s.bindString(1, p.id + ":s" + Integer.toHexString(sk.hashCode())); s.bindString(2, p.id); s.bindString(3, cat("series", m.group)); s.bindString(4, title)
                            s.bindString(5, m.logo); s.bindNull(6); s.bindNull(7); s.bindNull(8)
                            s.bindNull(9); s.s(10, yearOf(null, title)); s.bindLong(11, n); s.bindLong(12, if (ad) 1 else 0); s.bindNull(13)
                            s.executeInsert().also { series[sk] = it }
                        }
                        val e = st.ep
                        e.clearBindings()
                        e.bindLong(1, row); e.bindLong(2, sm?.groupValues?.get(2)?.toLongOrNull() ?: 1); e.bindLong(3, sm?.groupValues?.get(3)?.toLongOrNull() ?: n)
                        e.bindString(4, m.name); e.bindString(5, l)
                        e.executeInsert()
                    } else {
                        val s = st.live
                        s.clearBindings()
                        s.bindString(1, key); s.bindString(2, p.id); s.bindString(3, cat("live", m.group))
                        s.bindLong(4, if (m.num > 0) m.num else n); s.bindString(5, m.name); s.bindString(6, m.logo)
                        s.bindNull(7); s.bindString(8, l); s.bindNull(9); s.bindLong(10, if (ad) 1 else 0)
                        st.quality(m.name)
                        s.executeInsert()
                    }
                }
            }
            step(100)
        } finally {
            c.disconnect()
        }
    }
}
