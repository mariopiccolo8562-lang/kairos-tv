package com.kairos.tv

import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

/**
 * TV in diretta, tre colonne: categorie · canali · anteprima con guida (adesso / dopo).
 * Scorrendo si vedono logo e guida; OK una volta fa partire il canale nel riquadro,
 * OK di nuovo apre lo schermo intero (il riquadro si chiude prima: un solo video alla volta).
 */
class LiveActivity : BaseActivity() {
    private lateinit var cats: RecyclerView
    private lateinit var channels: RecyclerView
    private lateinit var empty: TextView
    private lateinit var catName: TextView
    private lateinit var pvideo: FrameLayout
    private lateinit var pspin: ProgressBar
    private lateinit var phint: TextView
    private lateinit var plogo: ImageView
    private lateinit var pch: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowRow: View
    private lateinit var nowTime: TextView
    private lateinit var nowBar: ProgressBar
    private lateinit var nowLeft: TextView
    private lateinit var nowDesc: TextView
    private lateinit var next: TextView
    private lateinit var favBtn: ImageButton

    private lateinit var catAdapter: CatAdapter
    private val chAdapter = ChannelAdapter()
    private var curCat = Db.ALL
    private var pendingCat: Db.Cat? = null
    private var curRow: Db.Row? = null
    private var curPos = -1
    private var restoreId = 0L
    private var favSet: Set<String> = emptySet()
    private var seq = 0
    private var loadedSig = ""
    private var verWas = Sync.ver("live")
    /** appena i canali sono ricaricati (film e serie arrivano dopo) si rilegge, restando sul canale selezionato */
    private val syncL: () -> Unit = {
        val v = Sync.ver("live")
        if (v != verWas && !isFinishing) { curRow?.let { restoreId = it.l("_id") }; loadCats(refresh = true) }
        verWas = v
    }

    private var player: ExoPlayer? = null
    private var pview: PlayerView? = null

    private val loadCatR = Runnable { loadChannels() }
    private val previewR = Runnable { updatePreview() }
    /** canale partito nel riquadro: il prossimo OK su di lui apre lo schermo intero */
    private var previewId = 0L
    private var videoSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        clock(findViewById(R.id.clock))
        cats = findViewById(R.id.cats)
        channels = findViewById(R.id.channels)
        empty = findViewById(R.id.empty)
        catName = findViewById(R.id.catName)
        pvideo = findViewById(R.id.pvideo)
        pspin = findViewById(R.id.pspin)
        phint = findViewById(R.id.phint)
        plogo = findViewById(R.id.plogo)
        pch = findViewById(R.id.pch)
        nowTitle = findViewById(R.id.nowTitle)
        nowRow = findViewById(R.id.nowRow)
        nowTime = findViewById(R.id.nowTime)
        nowBar = findViewById(R.id.nowBar)
        nowLeft = findViewById(R.id.nowLeft)
        nowDesc = findViewById(R.id.nowDesc)
        next = findViewById(R.id.next)
        favBtn = findViewById(R.id.fav)
        findViewById<View>(R.id.pbox).clipToOutline = true
        findViewById<View>(R.id.pbox).setOnClickListener { onChannelClick(curPos) } // telefoni: tocco sul riquadro

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.search).setOnClickListener { open(SearchActivity::class.java) }
        findViewById<View>(R.id.watch).setOnClickListener { play(curPos) }
        findViewById<View>(R.id.guide).setOnClickListener { guide() }
        favBtn.setOnClickListener { toggleFav(curPos) }

        catAdapter = CatAdapter(
            onFocus = { c, _ -> if (c.id != curCat) { pendingCat = c; App.main.removeCallbacks(loadCatR); App.main.postDelayed(loadCatR, 260) } },
            onClick = { c, _ -> pendingCat = c; App.main.removeCallbacks(loadCatR); loadChannels(focus = true) },
            onRight = { channels.focusVisible() },
        )
        cats.fast()
        cats.adapter = catAdapter
        channels.fast()
        channels.adapter = chAdapter

        curCat = Store.lastLiveCat.ifEmpty { Db.ALL }
        restoreId = Store.lastLiveId
        favSet = Store.favs("live")
        Sync.listen(syncL)
        loadCats()
    }

    override fun onResume() {
        super.onResume()
        favSet = Store.favs("live")
        chAdapter.notifyDataSetChanged()
    }

    private fun sig() = K.activeIds().joinToString(",") + "|" + K.kids

    override fun onPause() {
        super.onPause()
        stopVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        Sync.unlisten(syncL)
        App.main.removeCallbacks(loadCatR)
        App.main.removeCallbacks(previewR)
        releaseVideo()
        chAdapter.swap(null)
    }

    // il pannello ha cambiato qualcosa: si ricarica solo se cambiano playlist o profilo, senza spostare la selezione
    override fun onStateChanged() {
        if (sig() != loadedSig) { curRow?.let { restoreId = it.l("_id") }; loadCats(refresh = true) }
    }

    /* ---------------- categorie ---------------- */
    private fun loadCats(refresh: Boolean = false) {
        loadedSig = sig()
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs("live")
        bg {
            val list = Db.cats("live", pls, kids)
            val total = Db.count("live", pls, kids)
            ui {
                if (isFinishing) return@ui
                val all = fixedCats(this, "live", total, favs.size) + list
                var idx = all.indexOfFirst { it.id == curCat }
                if (idx < 0) { idx = 0; curCat = Db.ALL }
                catAdapter.set(all, idx)
                pendingCat = all[idx]
                loadChannels(focus = !refresh)
            }
        }
    }

    /** [focus]: porta il cursore sulla lista (avvio o OK su una categoria) */
    private fun loadChannels(focus: Boolean = false) {
        val c = pendingCat ?: return
        val changed = c.id != curCat
        pendingCat = null
        curCat = c.id
        Store.lastLiveCat = c.id
        val i = catAdapter.items.indexOfFirst { it.id == c.id }
        if (i >= 0) catAdapter.select(i)
        catName.text = "· " + c.name
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs("live")
        val want = restoreId
        val my = ++seq
        bg {
            val cur = Db.live(c.id, pls, kids, favs)
            var at = -1
            if (want > 0) {
                var n = 0
                while (cur.moveToNext()) { if (cur.getLong(0) == want) { at = n; break }; n++ }
            }
            ui {
                if (isFinishing || my != seq) { cur.close(); return@ui }
                val hadFocus = channels.hasFocus()
                if (at < 0 && (changed || focus)) { curPos = -1; curRow = null }
                chAdapter.swap(cur)
                val count = cur.count
                empty.visibility = if (count == 0) View.VISIBLE else View.GONE
                if (count == 0) empty.text = when {
                    c.id == Db.FAV -> "Nessun preferito: tieni premuto OK su un canale per aggiungerlo."
                    Sync.running -> "Sto caricando i canali…"
                    else -> "Nessun canale."
                }
                when {
                    at >= 0 -> { restoreId = 0; if (focus || hadFocus || !cats.hasFocus()) focusChannel(at) else channels.scrollToPosition(at) }
                    count > 0 && (focus || hadFocus || (!cats.hasFocus() && currentFocus == null)) -> { channels.scrollToPosition(0); focusChannel(0) }
                    else -> channels.scrollToPosition(0)
                }
            }
        }
    }

    private fun focusChannel(pos: Int) {
        channels.scrollToPosition(pos)
        channels.post {
            val v = channels.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus() else channels.post { channels.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    /* ---------------- canale selezionato ---------------- */
    private fun onChannelFocus(pos: Int) {
        if (pos == curPos && curRow != null) return
        curPos = pos
        val c = chAdapter.cursor ?: return
        if (c.isClosed || !c.moveToPosition(pos)) return
        curRow = Db.current(c)
        stopVideo()
        App.main.removeCallbacks(previewR)
        App.main.postDelayed(previewR, 180)
    }

    /** OK sul canale: la prima volta parte l'anteprima nel riquadro, la seconda si apre lo schermo intero */
    private fun onChannelClick(pos: Int) {
        val c = chAdapter.cursor ?: return
        if (pos < 0 || c.isClosed || !c.moveToPosition(pos)) return
        val id = c.getLong(0)
        if (!previewOn() || (previewId > 0 && id == previewId)) { play(pos); return }
        if (pos != curPos || curRow == null) onChannelFocus(pos) // al tocco (telefoni) il canale non prende il focus
        startVideo()
    }

    private fun key() = if (channels.isInTouchMode) "Tocca" else "OK"

    private fun hint(t: String?) {
        if (t == null) { phint.visibility = View.GONE; return }
        phint.text = t
        phint.visibility = View.VISIBLE
    }

    /** suggerimento nel riquadro quando il video non è partito */
    private fun idleHint() = hint(if (previewOn() && curRow != null) key() + " · anteprima" else null)

    private fun updatePreview() {
        val r = curRow ?: return
        val name = r.s("name")
        if (previewId == 0L) idleHint()
        pch.text = (if (r.l("num") > 0) r.l("num").toString() + " · " else "") + name
        Ui.img(plogo, r.s("logo"), Ui.dp(this, 72), Ui.dp(this, 72))
        favBtn.setImageResource(if (favSet.contains(r.s("k"))) R.drawable.ic_heart_fill else R.drawable.ic_heart)
        nowTitle.text = name
        nowRow.visibility = View.GONE
        nowDesc.text = ""
        next.visibility = View.GONE
        val p = K.pl(r.s("pl"))
        val sid = r.s("sid")
        if (p?.xt == null || sid.isEmpty()) { nowDesc.text = "Guida TV non disponibile per questo canale."; return }
        val pos = curPos
        net {
            if (curRow !== r) return@net // già passati a un altro canale
            val list = Epg.get(p, sid, 4)
            ui {
                if (curRow !== r || isFinishing) return@ui
                showEpg(list)
                chAdapter.notifyItemChanged(pos)
            }
        }
    }

    private fun showEpg(list: List<Prog>) {
        val now = list.firstOrNull()
        if (now == null) { nowDesc.text = "Nessuna informazione sul programma."; return }
        nowTitle.text = now.title
        nowRow.visibility = View.VISIBLE
        nowTime.text = Ui.hm(now.start) + " – " + Ui.hm(now.end)
        nowBar.progress = now.progress()
        nowLeft.text = "mancano " + Ui.minutes(now.end - System.currentTimeMillis())
        nowDesc.text = now.desc
        val n = list.getOrNull(1)
        if (n != null) { next.visibility = View.VISIBLE; next.text = "DOPO   " + Ui.hm(n.start) + "   " + n.title }
    }

    /* ---------------- anteprima leggerissima nel riquadro ---------------- */
    /**
     * Parte solo con OK (il secondo OK apre lo schermo intero): mentre si scorre la lista non si decodifica niente.
     * Usa la versione più leggera del canale (es. SD al posto di FHD) e al massimo 480p.
     */
    private fun previewOn(): Boolean = Store.preview != "off"

    private fun startVideo() {
        if (!previewOn() || isFinishing) return
        val r = curRow ?: return
        val id = r.l("_id")
        previewId = id
        val my = ++videoSeq
        pspin.visibility = View.VISIBLE
        hint(key() + " · schermo intero")
        bg {
            // versione più leggera dello stesso canale, se la playlist ne ha (niente HEVC: pesa di più da decodificare)
            val light = try { Db.variants(r).filter { !it.hevc && it.q < Quality.rank(r.s("name")) }.minByOrNull { it.q } } catch (_: Exception) { null }
            val src = light?.let { Db.one("live", it.id) } ?: r
            val url = Play.liveUrls(src).firstOrNull() ?: Play.liveUrls(r).firstOrNull()
            if (url == null) { ui { if (my == videoSeq) { previewId = 0; pspin.visibility = View.GONE; hint(null) } }; return@bg }
            Vpns.awaitForced() // VPN «sempre attiva»: anche l'anteprima passa dalla VPN
            // limite di visioni contemporanee del pannello: l'anteprima è una visione come le altre
            val block = Panel.stream("live", r.s("name"))
            ui {
                if (isFinishing || my != videoSeq || previewId != id) return@ui
                if (block != null) { previewId = 0; pspin.visibility = View.GONE; idleHint(); Ui.toast(block); return@ui }
                val p = player ?: Players.build(this, true, preview = true).also { pl ->
                    player = pl
                    val v = PlayerView(this)
                    v.useController = false
                    v.setShutterBackgroundColor(0)
                    pvideo.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    v.player = pl
                    pview = v
                    pl.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (previewId == 0L) return
                            pspin.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        }
                        // il riquadro non insiste: lo schermo intero ha il recupero automatico completo
                        override fun onPlayerError(error: PlaybackException) {
                            try { pl.stop() } catch (_: Exception) {}
                            pview?.visibility = View.INVISIBLE
                            pspin.visibility = View.GONE
                            hint("Anteprima non disponibile · " + key() + ": schermo intero")
                        }
                    })
                }
                pview?.visibility = View.VISIBLE
                p.setMediaItem(Players.item(url))
                p.prepare()
                p.playWhenReady = true
            }
        }
    }

    private fun stopVideo() {
        videoSeq++
        previewId = 0
        player?.let { try { it.stop(); it.clearMediaItems() } catch (_: Exception) {} }
        pview?.visibility = View.INVISIBLE
        pspin.visibility = View.GONE
        idleHint()
    }

    private fun releaseVideo() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        pview?.player = null
        pvideo.removeAllViews()
        pview = null
    }

    /* ---------------- azioni ---------------- */
    private fun play(pos: Int) {
        val c = chAdapter.cursor ?: return
        if (pos < 0 || c.isClosed || !c.moveToPosition(pos)) return
        val id = c.getLong(0)
        stopVideo()
        releaseVideo() // un solo decoder alla volta: l'anteprima si chiude prima dello schermo intero
        Store.lastLiveId = id
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, PlayerActivity::class.java).putExtra("kind", "live").putExtra("cat", curCat).putExtra("rowId", id), 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && data != null) {
            val id = data.getLongExtra("ch", 0)
            if (id > 0) { restoreId = id; pendingCat = catAdapter.items.find { it.id == curCat }; loadChannels() }
        }
    }

    private fun toggleFav(pos: Int) {
        val c = chAdapter.cursor ?: return
        if (pos < 0 || c.isClosed || !c.moveToPosition(pos)) return
        val k = c.getString(1) ?: return
        val on = Store.toggleFav("live", k)
        favSet = Store.favs("live")
        chAdapter.notifyItemChanged(pos)
        if (pos == curPos) favBtn.setImageResource(if (on) R.drawable.ic_heart_fill else R.drawable.ic_heart)
        Ui.toast(if (on) "Aggiunto ai preferiti" else "Tolto dai preferiti")
        catAdapter.items.find { it.id == Db.FAV }?.let { it.count = favSet.size; catAdapter.notifyItemChanged(1) }
        if (curCat == Db.FAV) { pendingCat = catAdapter.items.find { it.id == Db.FAV }; loadChannels() }
    }

    private fun guide() {
        val r = curRow ?: return
        val p = K.pl(r.s("pl"))
        val sid = r.s("sid")
        net {
            val list = Epg.get(p, sid, 20)
            ui {
                if (list.isEmpty()) { Ui.toast("Guida TV non disponibile per questo canale"); return@ui }
                val text = list.joinToString("\n") { Ui.hm(it.start) + "   " + it.title }
                Ui.dialog(this, "Guida TV · " + r.s("name"), text)
            }
        }
    }

    /* ---------------- lista canali ---------------- */
    private inner class ChannelAdapter : CursorAdapter<ChannelAdapter.H>() {
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val logo: ImageView = v.findViewById(R.id.logo)
            val initials: TextView = v.findViewById(R.id.initials)
            val name: TextView = v.findViewById(R.id.name)
            val now: TextView = v.findViewById(R.id.now)
            val bar: ProgressBar = v.findViewById(R.id.bar)
            val fav: ImageView = v.findViewById(R.id.fav)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))
            h.itemView.setOnFocusChangeListener { _, has -> if (has) onChannelFocus(h.bindingAdapterPosition) }
            h.itemView.setOnClickListener { onChannelClick(h.bindingAdapterPosition) }
            h.itemView.setOnLongClickListener { toggleFav(h.bindingAdapterPosition); true }
            h.itemView.setOnKeyListener { _, code, e ->
                when {
                    code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP -> { toggleFav(h.bindingAdapterPosition); true }
                    // SINISTRA: torna sempre alla categoria in uso, anche dopo aver scorso tanti canali
                    code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN -> { cats.focusAt(catAdapter.selected); true }
                    else -> false
                }
            }
            return h
        }

        override fun bind(h: H, c: Cursor, position: Int) {
            val num = c.getLong(4)
            val name = c.getString(5) ?: ""
            val logo = c.getString(6)
            h.num.text = if (num > 0) num.toString() else ""
            h.name.text = name
            h.initials.text = Ui.initials(name)
            val size = Ui.dp(h.itemView.context, 38)
            Ui.img(h.logo, if (App.max) null else logo, size, size) // «Leggerissima»: solo iniziali, niente immagini da scaricare
            val k = c.getString(1) ?: ""
            h.fav.visibility = if (favSet.contains(k)) View.VISIBLE else View.GONE
            val p = K.pl(c.getString(2) ?: "")
            val now = Epg.cached(p, c.getString(7) ?: "", 4)?.firstOrNull()
            if (now != null) {
                h.now.visibility = View.VISIBLE
                h.now.text = now.title
                h.bar.visibility = View.VISIBLE
                h.bar.progress = now.progress()
            } else {
                h.now.visibility = View.GONE
                h.bar.visibility = View.GONE
            }
        }
    }
}
