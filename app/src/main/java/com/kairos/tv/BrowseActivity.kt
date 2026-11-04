package com.kairos.tv

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * Home, Film, Serie TV e La mia lista in stile Netflix: riquadro grande in alto e righe di locandine.
 * Pensata per le chiavette datate:
 * - il riquadro grande è FISSO (non cambia immagine a ogni locandina: niente lavoro mentre ci si muove);
 * - ogni riga legge dal database solo i suoi primi 20 titoli, e solo quando sta per comparire;
 * - le immagini sono caricate alla misura mostrata e si fermano mentre si scorre col tasto tenuto premuto;
 * - nessuna animazione, nessuna sfocatura, nessun video di anteprima.
 */
open class BrowseActivity : BaseActivity() {
    /** "home" | "vod" | "series" | "list" */
    protected open val kind: String get() = intent.getStringExtra("kind") ?: "vod"

    private lateinit var rows: RecyclerView
    private lateinit var sync: TextView
    private val adapter = RowsAdapter()
    private val pool = RecyclerView.RecycledViewPool()
    private var hero: Db.Card? = null
    private var posterW = 0
    private var posterH = 0
    private var recentW = 0
    private var recentH = 0
    private var seq = 0
    private var loadedSig = ""
    private var built = false
    private var focusDone = false
    private val vers = HashMap<String, Int>()
    private val syncL: () -> Unit = { onSync() }

    class Row(val key: String, val title: String, val type: Int, val table: String = "", val cat: String = "") {
        @Volatile var cards: List<Db.Card>? = null
        var recent: List<JSONObject> = emptyList()
        var loading = false
        var more = false
        var count = 0
    }

    companion object {
        const val T_HERO = 0
        const val T_POSTERS = 1
        const val T_RECENT = 2
        const val LIMIT = 20
        private const val V_CARD = 10
        private const val V_MORE = 11
        private const val V_RECENT = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        clock(findViewById(R.id.clock))
        sync = findViewById(R.id.sync)
        posterW = resources.getDimensionPixelSize(R.dimen.poster_w)
        posterH = resources.getDimensionPixelSize(R.dimen.poster_h)
        recentW = resources.getDimensionPixelSize(R.dimen.recent_w)
        recentH = resources.getDimensionPixelSize(R.dimen.recent_h)
        pool.setMaxRecycledViews(V_CARD, 40)

        rows = findViewById(R.id.rows)
        rows.fast()
        // la riga selezionata non finisce mai sotto la barra delle schede (che resta sopra, semitrasparente)
        rows.setPadding(rows.paddingLeft, Ui.dp(this, 64), rows.paddingRight, rows.paddingBottom)
        rows.clipChildren = false
        rows.setItemViewCacheSize(if (App.lite) 2 else 4)
        adapter.setHasStableIds(true)
        rows.adapter = adapter

        tabs()
        findViewById<View>(R.id.search).setOnClickListener { open(SearchActivity::class.java) }
        val av = findViewById<TextView>(R.id.avatar)
        av.setOnClickListener { open(SettingsActivity::class.java) }
        av.setOnFocusChangeListener { v, has -> (v.background as? android.graphics.drawable.GradientDrawable)?.setStroke(if (has) Ui.dp(this, 3) else 0, Color.WHITE) }
        for (t in listOf("vod", "series")) vers[t] = Sync.ver(t)
        Sync.listen(syncL)
        build()
    }

    /* ---------------- schede in alto ---------------- */
    private fun tabs() {
        val map = linkedMapOf(R.id.tabHome to "home", R.id.tabFilm to "vod", R.id.tabSeries to "series", R.id.tabLive to "live", R.id.tabList to "list")
        for ((id, k) in map) {
            val t = findViewById<TextView>(id)
            t.isActivated = k == kind
            t.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusFirstVisible(); true } else false
            }
            t.setOnClickListener {
                if (k == kind) { focusFirst(); return@setOnClickListener }
                when (k) {
                    "home" -> open(HomeActivity::class.java)
                    "live" -> open(LiveActivity::class.java)
                    else -> open(BrowseActivity::class.java) { putExtra("kind", k) }
                }
                // le schede non si accumulano: da Film a Serie si torna comunque alla Home con INDIETRO
                if (kind != "home") finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        fillAccount()
        Sync.start()
        showSync()
        if (built) refreshDynamic()
    }

    override fun onDestroy() {
        super.onDestroy()
        Sync.unlisten(syncL)
    }

    override fun onStateChanged() {
        fillAccount()
        if (sig() != loadedSig) build()
    }

    private fun sig() = K.activeIds().joinToString(",") + "|" + K.kids + "|" + Store.profileId

    private fun fillAccount() {
        val s = Panel.state ?: return
        val pr = K.profile()
        Ui.avatar(findViewById(R.id.avatar), pr?.name ?: s.name.ifEmpty { "Io" }, pr?.avatar ?: "a1", 22)
    }

    /* ---------------- aggiornamento delle liste in sottofondo ---------------- */
    private fun onSync() {
        showSync()
        var changed = false
        for (t in listOf("vod", "series")) { val v = Sync.ver(t); if (vers[t] != v) { vers[t] = v; changed = true } }
        if (changed && !isFinishing) build()
    }

    private fun showSync() {
        when {
            Sync.running -> { sync.visibility = View.VISIBLE; sync.text = "Aggiornamento delle liste… ${Sync.pct}%" }
            Sync.error.isNotEmpty() && !built -> { sync.visibility = View.VISIBLE; sync.text = "Liste non aggiornate: ${Sync.error}" }
            else -> sync.visibility = View.GONE
        }
    }

    /* ---------------- righe ---------------- */
    private fun build() {
        loadedSig = sig()
        val pls = K.activeIds()
        val kids = K.kids
        val k = kind
        val my = ++seq
        bg {
            val list = ArrayList<Row>()
            var h: Db.Card? = null
            if (k != "list") {
                h = Db.featured(if (k == "series") "series" else "vod", pls, kids)
                if (h == null && k == "home") h = Db.featured("series", pls, kids)
            }
            list.add(Row("hero", "", T_HERO))
            if (k == "home") {
                val rec = Store.recent()
                if (rec.isNotEmpty()) list.add(Row("recent", "Continua a guardare", T_RECENT).also { it.recent = rec })
            }
            val tables = when (k) { "vod" -> listOf("vod"); "series" -> listOf("series"); else -> listOf("vod", "series") }
            for (t in tables) {
                val name = if (t == "vod") "Film" else "Serie TV"
                if (Store.favs(t).isNotEmpty() || k == "list") list.add(Row("fav$t", if (tables.size > 1) "La mia lista · $name" else "La mia lista", T_POSTERS, t, Db.FAV))
            }
            if (k != "list") {
                for (t in tables) list.add(Row("new$t", if (t == "vod") "Film aggiunti di recente" else "Serie TV aggiunte di recente", T_POSTERS, t, Db.NEW))
                for (t in tables) {
                    val cats = Db.cats(t, pls, kids)
                    for (c in if (k == "home") cats.take(5) else cats) {
                        list.add(Row("c$t:" + c.id, c.name, T_POSTERS, t, c.id).also { it.more = c.count > LIMIT; it.count = c.count })
                    }
                }
            }
            // le prime righe arrivano già piene: la schermata compare completa
            list.filter { it.type == T_POSTERS }.take(3).forEach { r -> r.cards = Db.cards(r.table, r.cat, pls, kids, Store.favs(r.table), LIMIT) }
            ui {
                if (isFinishing || my != seq) return@ui
                hero = h
                adapter.items = list
                adapter.notifyDataSetChanged()
                built = true
                showSync()
                if (!focusDone) { focusDone = true; rows.postDelayed({ if (!isFinishing) focusFirst() }, 150) }
            }
        }
    }

    /** di ritorno da un film: «Continua a guardare» e La mia lista aggiornati senza ricostruire tutto */
    private fun refreshDynamic() {
        val list = adapter.items
        if (kind == "home") {
            val rec = Store.recent()
            val i = list.indexOfFirst { it.type == T_RECENT }
            if (i >= 0 && rec.isNotEmpty()) { list[i].recent = rec; adapter.notifyItemChanged(i) }
            else if ((i >= 0) != rec.isNotEmpty()) { build(); return }
        }
        list.forEachIndexed { i, r -> if (r.cat == Db.FAV) { r.cards = null; r.loading = false; adapter.notifyItemChanged(i) } }
        val hasFav = list.any { it.cat == Db.FAV }
        if (!hasFav && (Store.favs("vod").isNotEmpty() || Store.favs("series").isNotEmpty())) build()
    }

    private fun loadRow(r: Row, pos: Int) {
        if (r.loading || r.cards != null) return
        r.loading = true
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs(r.table)
        val my = seq
        bg {
            val c = Db.cards(r.table, r.cat, pls, kids, favs, LIMIT)
            ui {
                if (isFinishing || my != seq) return@ui
                r.cards = c
                r.loading = false
                val i = adapter.items.indexOf(r)
                if (i >= 0) adapter.notifyItemChanged(i)
            }
        }
    }

    /** dalle schede in alto GIÙ: al riquadro grande se si vede, altrimenti alla prima riga visibile */
    private fun focusFirstVisible() {
        val lm = rows.layoutManager as? LinearLayoutManager
        if (lm == null || lm.findFirstVisibleItemPosition() <= 0) { focusFirst(); return }
        val p = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition()
        val rh = rows.findViewHolderForAdapterPosition(p) as? RowH
        val v = rh?.list?.let { l -> (l.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.let { l.findViewHolderForAdapterPosition(it)?.itemView } ?: l.getChildAt(0) }
        if (v != null) v.requestFocus() else focusFirst()
    }

    private fun focusFirst() {
        val h = rows.findViewHolderForAdapterPosition(0)?.itemView
        val play = h?.findViewById<View>(R.id.play)
        if (play != null && play.visibility == View.VISIBLE && play.isShown) { play.requestFocus(); return }
        for (i in 1 until adapter.itemCount) {
            val v = (rows.findViewHolderForAdapterPosition(i) as? RowH)?.list?.getChildAt(0) ?: continue
            v.requestFocus(); return
        }
        findViewById<View>(if (kind == "list") R.id.tabList else R.id.tabHome).requestFocus()
    }

    /** la scheda attiva in alto (Home, Film…) prende il cursore */
    private fun focusTab() {
        val id = when (kind) { "vod" -> R.id.tabFilm; "series" -> R.id.tabSeries; "list" -> R.id.tabList; else -> R.id.tabHome }
        findViewById<View>(id).requestFocus()
    }

    /** SU dalla prima riga quando sopra non c'è il riquadro grande */
    private fun upFromRow(card: View): Boolean {
        val rowView = (card.parent as? View)?.parent as? View ?: return false
        val pos = rows.getChildAdapterPosition(rowView)
        val firstRow = adapter.items.indexOfFirst { it.type != T_HERO }
        if (pos == firstRow && hero == null) { focusTab(); return true }
        return false
    }

    /** in fondo alle righe INDIETRO riporta in cima; in cima chiude (la Home chiede conferma) */
    protected fun backToTop(): Boolean {
        val lm = rows.layoutManager as? LinearLayoutManager ?: return false
        if (lm.findFirstVisibleItemPosition() <= 0 && !rows.canScrollVertically(-1)) return false
        rows.scrollToPosition(0)
        rows.postDelayed({ if (!isFinishing) focusFirst() }, 120)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!backToTop()) super.onBackPressed()
    }

    /* ---------------- azioni ---------------- */
    private fun openCard(c: Db.Card, autoplay: Boolean = false) {
        open(DetailActivity::class.java) { putExtra("table", c.table); putExtra("id", c.id); if (autoplay) putExtra("autoplay", true) }
    }

    private fun toggleFav(c: Db.Card) {
        if (c.k.isEmpty()) return
        val on = Store.toggleFav(c.table, c.k)
        Ui.toast(if (on) "Aggiunto a La mia lista" else "Tolto da La mia lista")
        refreshDynamic()
    }

    private fun meta(c: Db.Card): String {
        val r = c.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.ITALY, "%.1f", if (it > 10) it / 10 else it) }
        return listOfNotNull(c.year.ifEmpty { null }, r).joinToString("  •  ")
    }

    /* ---------------- adapter delle righe ---------------- */
    private inner class HeroH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val kindT: TextView = v.findViewById(R.id.kind)
        val title: TextView = v.findViewById(R.id.title)
        val metaT: TextView = v.findViewById(R.id.meta)
        val plot: TextView = v.findViewById(R.id.plot)
        val play: TextView = v.findViewById(R.id.play)
        val info: TextView = v.findViewById(R.id.info)
    }

    private inner class RowH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val list: RecyclerView = v.findViewById(R.id.list)
        val cards = CardAdapter()
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: MutableList<Row> = ArrayList()
            set(v) { field = ArrayList(v) }

        override fun getItemCount() = items.size
        override fun getItemViewType(i: Int) = if (items[i].type == T_HERO) 0 else 1
        override fun getItemId(i: Int): Long = items[i].key.hashCode().toLong()

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            if (t == 0) {
                val h = HeroH(inf.inflate(R.layout.item_hero, p, false))
                val sh = resources.displayMetrics.heightPixels
                val sw = resources.displayMetrics.widthPixels
                h.itemView.layoutParams.height = (sh * 0.56f).toInt()
                (h.img.layoutParams as ViewGroup.MarginLayoutParams).width = (sw * 0.66f).toInt()
                for (b in listOf(h.play, h.info)) {
                    b.compoundDrawables[0]?.mutate()?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    b.setOnFocusChangeListener { x, has -> (x as TextView).compoundDrawables[0]?.setColorFilter(if (has) Color.BLACK else Color.WHITE, PorterDuff.Mode.SRC_IN) }
                }
                for (b in listOf(h.play, h.info)) b.setOnKeyListener { _, code, e ->
                    if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) { focusTab(); true } else false
                }
                h.play.setOnClickListener { hero?.let { openCard(it, autoplay = it.table == "vod") } }
                h.info.setOnClickListener { hero?.let { openCard(it) } }
                return h
            }
            val h = RowH(inf.inflate(R.layout.item_row, p, false))
            h.list.layoutManager = LinearLayoutManager(p.context, RecyclerView.HORIZONTAL, false).also { it.initialPrefetchItemCount = 6 }
            h.list.fast(horizontal = true)
            h.list.setRecycledViewPool(pool)
            h.list.adapter = h.cards
            // la locandina selezionata si ingrandisce un poco: non deve essere tagliata sopra e sotto
            h.list.clipChildren = false
            (h.itemView as ViewGroup).clipChildren = false
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val r = items[i]
            if (h is HeroH) { bindHero(h); return }
            h as RowH
            h.title.text = r.title
            val empty = (r.type == T_POSTERS && r.cards?.isEmpty() == true && r.cat != Db.FAV) ||
                (r.type == T_RECENT && r.recent.isEmpty())
            h.itemView.layoutParams.height = if (empty) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            if (r.type == T_POSTERS && r.cat == Db.FAV && r.cards?.isEmpty() == true) h.title.text = r.title + "  ·  vuota: tieni premuto OK su una locandina per aggiungerla"
            if (h.cards.row !== r) { h.cards.row = r; h.list.scrollToPosition(0) }
            h.cards.notifyDataSetChanged()
            if (r.type == T_POSTERS && r.cards == null) loadRow(r, i)
        }
    }

    private fun bindHero(h: HeroH) {
        val c = hero
        if (c == null) {
            // niente titolo in evidenza (La mia lista, o liste non ancora caricate): solo lo spazio per le schede in alto
            h.itemView.layoutParams.height = Ui.dp(this, 6)
            for (v in listOf(h.img, h.kindT, h.title, h.metaT, h.plot, h.play, h.info)) v.visibility = View.GONE
            return
        }
        h.itemView.layoutParams.height = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        for (v in listOf(h.img, h.kindT, h.title, h.metaT, h.play, h.info)) v.visibility = View.VISIBLE
        h.kindT.text = if (c.table == "series") "SERIE TV" else "FILM"
        h.title.text = c.name
        h.metaT.text = meta(c)
        h.plot.text = c.plot
        h.plot.visibility = if (c.plot.isBlank()) View.GONE else View.VISIBLE
        h.play.text = if (c.table == "series") "Guarda" else "Riproduci"
        val w = h.img.layoutParams.width.coerceAtLeast(1)
        val ht = h.itemView.layoutParams.height.coerceAtLeast(1)
        // una sola immagine grande, caricata una volta; più piccola sulle chiavette lente
        if (App.lite) Ui.img(h.img, c.img, w / 2, ht / 2) else Ui.img(h.img, c.img, w, ht)
    }

    /* ---------------- locandine di una riga ---------------- */
    private inner class CardH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
    }

    private inner class RecentH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val title: TextView = v.findViewById(R.id.title)
        val left: TextView = v.findViewById(R.id.left)
    }

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var row: Row? = null

        override fun getItemCount(): Int {
            val r = row ?: return 0
            if (r.type == T_RECENT) return r.recent.size
            val n = r.cards?.size ?: 0
            return n + if (r.more && n > 0) 1 else 0
        }

        override fun getItemViewType(i: Int): Int {
            val r = row ?: return V_CARD
            return if (r.type == T_RECENT) V_RECENT else if (i >= (r.cards?.size ?: 0)) V_MORE else V_CARD
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            if (t == V_RECENT) {
                val h = RecentH(inf.inflate(R.layout.item_recent, p, false))
                h.itemView.findViewById<View>(R.id.box).clipToOutline = true
                h.itemView.setOnClickListener { val o = h.recentItem() ?: return@setOnClickListener; Play.resume(this@BrowseActivity, o) }
                h.itemView.setOnLongClickListener { val o = h.recentItem(); if (o != null) { Store.removeRecent(o.optString("id")); refreshDynamic() }; true }
                h.itemView.setOnKeyListener { _, code, e ->
                    e.action == KeyEvent.ACTION_DOWN && ((code == KeyEvent.KEYCODE_DPAD_RIGHT && h.bindingAdapterPosition >= itemCount - 1) || (code == KeyEvent.KEYCODE_DPAD_LEFT && h.bindingAdapterPosition == 0))
                }
                Ui.zoom(h.itemView, 1.06f)
                return h
            }
            val h = CardH(inf.inflate(R.layout.item_card, p, false))
            h.itemView.findViewById<View>(R.id.box).clipToOutline = true
            h.itemView.setOnClickListener {
                val r = row ?: return@setOnClickListener
                val i = h.bindingAdapterPosition
                val cards = r.cards ?: return@setOnClickListener
                if (i in cards.indices) openCard(cards[i])
                else if (i == cards.size) open(VodActivity::class.java) { putExtra("kind", r.table); putExtra("cat", r.cat) }
            }
            h.itemView.setOnLongClickListener { h.card()?.let { toggleFav(it) }; true }
            h.itemView.setOnKeyListener { _, code, e ->
                when {
                    code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP -> { h.card()?.let { toggleFav(it) }; true }
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT -> h.bindingAdapterPosition >= itemCount - 1
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT -> h.bindingAdapterPosition == 0
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP -> upFromRow(h.itemView)
                    else -> false
                }
            }
            Ui.zoom(h.itemView, 1.07f)
            return h
        }

        private fun RecentH.recentItem(): JSONObject? { val r = row ?: return null; val i = bindingAdapterPosition; return r.recent.getOrNull(i) }
        private fun CardH.card(): Db.Card? { val r = row ?: return null; return r.cards?.getOrNull(bindingAdapterPosition) }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val r = row ?: return
            when (h) {
                is RecentH -> {
                    val o = r.recent.getOrNull(i) ?: return
                    val title = o.optString("title")
                    h.title.text = title
                    h.ph.text = title
                    val dur = o.optLong("dur")
                    val pos = o.optLong("pos")
                    h.bar.progress = if (dur > 0) (pos * 100 / dur).toInt() else 0
                    h.left.text = if (dur > 0) Ui.minutes(dur - pos) else ""
                    Ui.img(h.img, o.optString("img"), recentW, recentH)
                }
                is CardH -> {
                    val cards = r.cards ?: return
                    if (i >= cards.size) {
                        h.ph.text = "Vedi tutti\n" + Ui.num(r.count)
                        Ui.img(h.img, null, 0, 0)
                        return
                    }
                    val c = cards[i]
                    h.ph.text = c.name
                    if (App.max) Ui.img(h.img, c.img, posterW * 2 / 3, posterH * 2 / 3) else Ui.img(h.img, c.img, posterW, posterH)
                }
            }
        }
    }
}
