package com.kairos.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/** Scheda di un film o di una serie (stagioni ed episodi) */
class DetailActivity : BaseActivity() {
    class Ep(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val url: String, val img: String, val dur: String)

    private lateinit var table: String
    private var row: Db.Row? = null
    private var pl: Playlist? = null
    private var key = ""
    private var title = ""
    private var poster = ""
    private var backdrop = ""

    private lateinit var playBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var favBtn: ImageButton
    private lateinit var loading: TextView

    private var episodes: List<Ep> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var season = 0
    private val seasonAdapter = SeasonAdapter()
    private val epAdapter = EpAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        table = if (intent.getStringExtra("table") == "series") "series" else "vod"
        playBtn = findViewById(R.id.play)
        restartBtn = findViewById(R.id.restart)
        favBtn = findViewById(R.id.fav)
        loading = findViewById(R.id.loading)
        findViewById<View>(R.id.posterBox).clipToOutline = true
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.kind).text = if (table == "series") "Serie TV" else "Film"
        favBtn.setOnClickListener {
            if (key.isEmpty()) return@setOnClickListener
            val on = Store.toggleFav(table, key)
            favBtn.setImageResource(if (on) R.drawable.ic_heart_fill else R.drawable.ic_heart)
        }
        playBtn.setOnClickListener { play(false) }
        restartBtn.setOnClickListener { play(true) }
        val sv = findViewById<RecyclerView>(R.id.seasons)
        sv.fast(horizontal = true)
        sv.adapter = seasonAdapter
        val ev = findViewById<RecyclerView>(R.id.episodes)
        ev.fast(horizontal = true)
        ev.adapter = epAdapter
        Ui.zoom(playBtn, 1.04f)

        val id = intent.getLongExtra("id", 0)
        bg {
            val r = Db.one(table, id)
            ui {
                if (r == null) { Ui.toast("Contenuto non trovato"); finish(); return@ui }
                row = r
                pl = K.pl(r.s("pl"))
                key = r.s("k")
                title = r.s("name")
                poster = r.s("img")
                fillBasic(r)
                loadInfo(r)
                // «Riproduci» dal riquadro grande della Home: il film parte subito
                if (table == "vod" && intent.getBooleanExtra("autoplay", false)) { intent.removeExtra("autoplay"); play(false) }
            }
        }
        playBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (row != null) refreshButtons()
        epAdapter.notifyDataSetChanged()
    }

    private fun fillBasic(r: Db.Row) {
        findViewById<TextView>(R.id.title).text = title
        findViewById<TextView>(R.id.plot).text = r.s("plot")
        meta(r.s("year"), r.s("rating"), "", "")
        Ui.img(findViewById(R.id.poster), poster, resources.getDimensionPixelSize(R.dimen.poster_w), resources.getDimensionPixelSize(R.dimen.poster_h))
        favBtn.setImageResource(if (Store.isFav(table, key)) R.drawable.ic_heart_fill else R.drawable.ic_heart)
        refreshButtons()
    }

    private fun meta(year: String, rating: String, genre: String, dur: String) {
        val r = rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.ITALY, "%.1f", if (it > 10) it / 10 else it) }
        findViewById<TextView>(R.id.meta).text = listOfNotNull(year.ifEmpty { null }, genre.ifEmpty { null }, dur.ifEmpty { null }, r).joinToString("  •  ")
    }

    private fun setBackdrop(url: String) {
        if (url.isEmpty() || url == backdrop || App.max) return // «Leggerissima»: basta la locandina
        backdrop = url
        val iv = findViewById<ImageView>(R.id.backdrop)
        val w = if (App.lite) 640 else 1280
        Ui.img(iv, url, w, w * 9 / 16)
    }

    /* ---------------- dati dal server ---------------- */
    private fun loadInfo(r: Db.Row) {
        val p = pl
        if (table == "vod") {
            val url = p?.api("get_vod_info", "&vod_id=" + r.s("sid")) ?: return
            net {
                val info = try { JSONObject(Http.text(url, connectMs = 12_000, readMs = 20_000).text).optJSONObject("info") } catch (_: Exception) { null }
                ui {
                    if (info == null || isFinishing) return@ui
                    val plot = info.optString("plot").ifEmpty { info.optString("description") }
                    if (plot.isNotEmpty()) findViewById<TextView>(R.id.plot).text = plot
                    meta(r.s("year").ifEmpty { info.optString("releasedate").take(4) }, info.optString("rating").ifEmpty { r.s("rating") }, info.optString("genre"), durText(info))
                    setBackdrop(first(info.opt("backdrop_path")).ifEmpty { info.optString("cover_big") })
                }
            }
            return
        }
        findViewById<View>(R.id.epBox).visibility = View.VISIBLE
        if (p != null && p.isXtream && r.s("sid").isNotEmpty()) {
            loading.visibility = View.VISIBLE
            loading.text = "Caricamento episodi…"
            val url = p.api("get_series_info", "&series_id=" + r.s("sid")) ?: return
            net {
                val j = try { JSONObject(Http.text(url, connectMs = 12_000, readMs = 30_000).text) } catch (_: Exception) { null }
                val list = ArrayList<Ep>()
                val eps = j?.optJSONObject("episodes")
                if (eps != null) {
                    val it = eps.keys()
                    while (it.hasNext()) {
                        val sk = it.next()
                        val arr = eps.optJSONArray(sk) ?: continue
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val inf = o.optJSONObject("info")
                            list.add(Ep(o.optString("id"), o.optString("season", sk).toIntOrNull() ?: sk.toIntOrNull() ?: 1, o.optString("episode_num").toIntOrNull() ?: (i + 1),
                                o.optString("title"), o.optString("container_extension"), "", inf?.optString("movie_image") ?: "", if (inf != null) durText(inf) else ""))
                        }
                    }
                }
                val info = j?.optJSONObject("info")
                ui {
                    if (isFinishing) return@ui
                    loading.visibility = View.GONE
                    if (info != null) {
                        val plot = info.optString("plot")
                        if (plot.isNotEmpty()) findViewById<TextView>(R.id.plot).text = plot
                        meta(r.s("year").ifEmpty { info.optString("releaseDate").take(4) }, info.optString("rating").ifEmpty { r.s("rating") }, info.optString("genre"), "")
                        setBackdrop(first(info.opt("backdrop_path")))
                    }
                    setEpisodes(list.sortedWith(compareBy({ it.season }, { it.num })))
                }
            }
        } else {
            bg {
                val list = Db.episodes(r.l("_id")).mapIndexed { i, e -> Ep(Integer.toHexString(Playlist.pathOf(e.url).hashCode()), e.season, e.num, e.title, "", e.url, "", "") }
                ui { setEpisodes(list) }
            }
        }
    }

    private fun durText(o: JSONObject): String {
        val secs = o.optString("duration_secs").toLongOrNull() ?: 0
        if (secs > 0) return Ui.minutes(secs * 1000)
        val d = o.optString("duration")
        return if (d.length >= 5 && d[2] == ':') { val h = d.substring(0, 2).toIntOrNull() ?: 0; val m = d.substring(3, 5).toIntOrNull() ?: 0; Ui.minutes((h * 60L + m) * 60_000) } else ""
    }

    private fun first(v: Any?): String = when (v) {
        is JSONArray -> if (v.length() > 0) v.optString(0) else ""
        is String -> v
        else -> ""
    }

    private fun setEpisodes(list: List<Ep>) {
        episodes = list
        seasons = list.map { it.season }.distinct().sorted()
        if (list.isEmpty()) { loading.visibility = View.VISIBLE; loading.text = "Nessun episodio disponibile."; return }
        val last = lastEpisode()
        season = last?.season ?: seasons.first()
        seasonAdapter.notifyDataSetChanged()
        epAdapter.items = list.filter { it.season == season }
        epAdapter.notifyDataSetChanged()
        refreshButtons()
    }

    /* ---------------- riproduzione ---------------- */
    private fun epKey(e: Ep) = "ep:$key:${e.id}"

    private fun lastEpisode(): Ep? {
        val rec = Store.recent().firstOrNull { it.optString("group") == key } ?: return null
        val id = rec.optString("id")
        return episodes.firstOrNull { epKey(it) == id }
    }

    private fun refreshButtons() {
        if (table == "vod") {
            val pos = Store.position(key)
            playBtn.text = if (pos > 60_000) "Riprendi da " + Ui.clock(pos) else "Guarda"
            restartBtn.visibility = if (pos > 60_000) View.VISIBLE else View.GONE
        } else {
            val e = lastEpisode()
            playBtn.text = if (e != null) "Riprendi S${e.season} E${e.num}" else if (episodes.isNotEmpty()) "Guarda S${episodes[0].season} E${episodes[0].num}" else "Guarda"
            restartBtn.visibility = View.GONE
        }
    }

    private fun play(fromStart: Boolean) {
        val r = row ?: return
        if (table == "vod") {
            val url = Play.movieUrl(r) ?: run { Ui.toast("Indirizzo del film non valido"); return }
            val start = if (fromStart) 0L else Store.position(key)
            val rec = JSONObject().put("id", key).put("kind", "movie").put("title", title).put("img", backdrop.ifEmpty { poster })
                .put("pl", r.s("pl")).put("sid", r.s("sid")).put("ext", r.s("ext")).put("url", r.s("url"))
            Play.vod(this, "movie", key, title, "", url, start, rec, null)
            return
        }
        val e = lastEpisode() ?: episodes.firstOrNull() ?: return
        playEpisode(e)
    }

    private fun playEpisode(e: Ep) {
        val r = row ?: return
        val url = epUrl(e) ?: run { Ui.toast("Indirizzo dell'episodio non valido"); return }
        val all = episodes
        val i = all.indexOf(e)
        val queue = JSONArray()
        for (n in all.drop(i + 1).take(30)) {
            val u = epUrl(n) ?: continue
            queue.put(JSONObject().put("id", epKey(n)).put("title", title).put("sub", "S${n.season} E${n.num} · " + n.title).put("url", u).put("recent", recentOf(r, n)))
        }
        Play.vod(this, "series", epKey(e), title, "S${e.season} E${e.num} · " + e.title, url, Store.position(epKey(e)), recentOf(r, e), queue)
    }

    private fun recentOf(r: Db.Row, e: Ep): JSONObject = JSONObject().put("id", epKey(e)).put("group", key).put("kind", "series")
        .put("title", "$title · S${e.season} E${e.num}").put("img", e.img.ifEmpty { backdrop.ifEmpty { poster } })
        .put("pl", r.s("pl")).put("sid", e.id).put("ext", e.ext).put("url", e.url)

    private fun epUrl(e: Ep): String? {
        val p = pl ?: return null
        return if (e.url.isNotEmpty()) p.fix(e.url) else p.episodeUrl(e.id, e.ext)
    }

    /* ---------------- stagioni ed episodi ---------------- */
    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonAdapter.H>() {
        inner class H(val t: TextView) : RecyclerView.ViewHolder(t)
        override fun getItemCount() = seasons.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_pill, p, false) as TextView)
            h.t.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i !in seasons.indices) return@setOnClickListener
                season = seasons[i]
                notifyDataSetChanged()
                epAdapter.items = episodes.filter { it.season == season }
                epAdapter.notifyDataSetChanged()
            }
            return h
        }
        override fun onBindViewHolder(h: H, i: Int) {
            h.t.text = "Stagione " + seasons[i]
            h.t.isActivated = seasons[i] == season
        }
    }

    private inner class EpAdapter : RecyclerView.Adapter<EpAdapter.H>() {
        var items: List<Ep> = emptyList()
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val tag: TextView = v.findViewById(R.id.tag)
            val bar: ProgressBar = v.findViewById(R.id.bar)
            val title: TextView = v.findViewById(R.id.title)
            val meta: TextView = v.findViewById(R.id.meta)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
            h.itemView.findViewById<View>(R.id.box).clipToOutline = true
            h.itemView.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i in items.indices) playEpisode(items[i])
            }
            Ui.zoom(h.itemView, 1.05f)
            return h
        }
        override fun onBindViewHolder(h: H, i: Int) {
            val e = items[i]
            h.tag.text = "E" + e.num
            h.title.text = e.num.toString() + ". " + e.title.ifEmpty { "Episodio " + e.num }
            h.meta.text = e.dur
            val pos = Store.position(epKey(e))
            val dur = Store.duration(epKey(e))
            h.bar.visibility = if (pos > 0 && dur > 0) View.VISIBLE else View.GONE
            h.bar.progress = if (dur > 0) (pos * 100 / dur).toInt() else 0
            val w = resources.getDimensionPixelSize(R.dimen.ep_w)
            Ui.img(h.img, e.img.ifEmpty { backdrop.ifEmpty { poster } }, w, w * 9 / 16)
        }
    }
}
