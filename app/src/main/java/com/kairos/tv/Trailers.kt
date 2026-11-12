package com.kairos.tv

import android.app.Activity
import android.content.Intent
import org.json.JSONObject

/** un trailer: [yt] codice del video su YouTube oppure [url] file video diretto (anteprima di Apple) */
class Trailer(val yt: String, val url: String, val title: String)

/**
 * Trailer di film e serie (da questa versione, come in KAIROS Ultimate).
 *  1. il video che la linea mette nella scheda (youtube_trailer), quando c'è: nessuna richiesta in più;
 *  2. altrimenti lo cerca il pannello: TMDB, poi YouTube, poi le anteprime di Apple (/api/player/trailer; il pannello
 *     ricorda la risposta, quindi lo stesso titolo si cerca una volta sola per tutti).
 * YouTube si vede dentro l'app (TrailerActivity); il file di Apple parte nel lettore dell'app.
 */
object Trailers {
    private const val KEEP = 50
    private val cache = object : LinkedHashMap<String, Trailer?>(KEEP + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Trailer?>?) = size > KEEP
    }

    /** risposta del pannello → trailer (null se non ha trovato niente o il dato non è un video valido) */
    fun parse(body: String, fallbackTitle: String): Trailer? {
        val o = try { JSONObject(body) } catch (_: Exception) { return null }
        val yt = Yt.id(o.optString("yt")).orEmpty()
        val url = o.optString("url").trim().takeIf { it.startsWith("https://") }.orEmpty()
        if (yt.isEmpty() && url.isEmpty()) return null
        return Trailer(yt, url, o.optString("title").ifBlank { fallbackTitle })
    }

    /** da un thread di lavoro. [lineValue]: il campo youtube_trailer della linea (codice o link), anche vuoto */
    fun find(title: String, year: String, kind: String, lineValue: String): Trailer? {
        Yt.id(lineValue)?.let { return Trailer(it, "", title) }
        if (title.isBlank() || Store.code.isEmpty() || Panel.base.isEmpty()) return null
        val key = "$kind|$title|$year"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val found = try {
            val q = "?t=${Playlist.enc(title.take(160))}&y=${Playlist.enc(year.take(4))}&kind=${if (kind == "series") "series" else "movie"}&c=${Playlist.enc(Store.code)}"
            val r = Http.text(Panel.base + "/api/player/trailer" + q, connectMs = 4_000, readMs = 12_000)
            if (r.code !in 200..299) return null // pannello non raggiungibile: si riprova alla prossima apertura della scheda
            parse(r.text, title)
        } catch (_: Exception) { return null }
        synchronized(cache) { cache[key] = found }
        return found
    }

    fun open(a: Activity, t: Trailer, fallbackTitle: String) {
        val title = t.title.ifBlank { fallbackTitle }
        val video = Yt.id(t.yt)
        if (video != null) TrailerActivity.start(a, video, title)
        else if (t.url.startsWith("https://")) a.startActivity(
            Intent(a, PlayerActivity::class.java)
                .putExtra("kind", "movie").putExtra("id", "").putExtra("title", "Trailer · $title").putExtra("sub", "")
                .putExtra("url", t.url).putExtra("pos", 0L),
        )
    }
}
