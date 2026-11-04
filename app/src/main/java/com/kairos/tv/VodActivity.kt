package com.kairos.tv

import android.database.Cursor
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Film o Serie TV: generi a sinistra, locandine a griglia, ricerca in alto */
class VodActivity : BaseActivity() {
    private lateinit var table: String
    private lateinit var cats: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var query: EditText
    private lateinit var catAdapter: CatAdapter
    private val posters = PosterAdapter()
    private lateinit var glm: GridLayoutManager
    private var curCat = Db.ALL
    private var pendingCat: Db.Cat? = null
    private var favSet: Set<String> = emptySet()
    private var seq = 0
    private var loadedSig = ""
    private var verWas = -1
    /** appena film (o serie) sono ricaricati si rilegge, senza aspettare il resto */
    private val syncL: () -> Unit = {
        val v = Sync.ver(table)
        if (verWas >= 0 && v != verWas && !isFinishing) loadCats(refresh = true)
        verWas = v
    }
    private var posterW = 0
    private var posterH = 0

    private val loadR = Runnable { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        table = if (intent.getStringExtra("kind") == "series") "series" else "vod"
        curCat = intent.getStringExtra("cat") ?: Db.ALL // «Vedi tutti» da una riga della Home
        clock(findViewById(R.id.clock))
        findViewById<TextView>(R.id.title).text = if (table == "series") "Serie TV" else "Film"
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        cats = findViewById(R.id.cats)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        query = findViewById(R.id.query)
        posterW = resources.getDimensionPixelSize(R.dimen.poster_w)
        posterH = resources.getDimensionPixelSize(R.dimen.poster_h)

        catAdapter = CatAdapter(
            onFocus = { c, _ -> if (c.id != curCat) { pendingCat = c; App.main.removeCallbacks(loadR); App.main.postDelayed(loadR, 280) } },
            onClick = { c, _ -> pendingCat = c; App.main.removeCallbacks(loadR); load(focus = true) },
            onRight = { focusGrid() },
        )
        cats.fast()
        cats.adapter = catAdapter
        glm = GridLayoutManager(this, 6)
        grid.layoutManager = glm
        grid.fast()
        grid.adapter = posters
        grid.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or2, _ ->
            if (r - l != or2 - ol) {
                val item = posterW + Ui.dp(this, 14)
                val span = ((v.width - v.paddingLeft - v.paddingRight) / item).coerceAtLeast(2)
                if (span != glm.spanCount) glm.spanCount = span
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pendingCat = catAdapter.items.getOrNull(catAdapter.selected)
                App.main.removeCallbacks(loadR)
                App.main.postDelayed(loadR, 450)
            }
        })
        query.setOnEditorActionListener { _, _, _ -> grid.requestFocus(); false }
        favSet = Store.favs(table)
        verWas = Sync.ver(table)
        Sync.listen(syncL)
        loadCats()
    }

    private fun sig() = K.activeIds().joinToString(",") + "|" + K.kids

    override fun onResume() {
        super.onResume()
        favSet = Store.favs(table)
    }

    override fun onDestroy() {
        super.onDestroy()
        Sync.unlisten(syncL)
        App.main.removeCallbacks(loadR)
        posters.swap(null)
    }

    override fun onStateChanged() { if (sig() != loadedSig) loadCats(refresh = true) }

    private fun loadCats(refresh: Boolean = false) {
        loadedSig = sig()
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs(table)
        bg {
            val list = Db.cats(table, pls, kids)
            val total = Db.count(table, pls, kids)
            ui {
                if (isFinishing) return@ui
                val all = fixedCats(this, table, total, favs.size) + list
                var idx = all.indexOfFirst { it.id == curCat }
                if (idx < 0) idx = 0
                catAdapter.set(all, idx)
                pendingCat = all[idx]
                load(focus = !refresh, keep = refresh)
            }
        }
    }

    /** [focus]: porta il cursore sulle locandine; [keep]: aggiornamento, la lista resta dov'è */
    private fun load(focus: Boolean = false, keep: Boolean = false) {
        val c = pendingCat ?: return
        pendingCat = null
        curCat = c.id
        val i = catAdapter.items.indexOfFirst { it.id == c.id }
        if (i >= 0) catAdapter.select(i)
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs(table)
        val text = query.text.toString()
        val my = ++seq
        bg {
            val cur = Db.vod(table, c.id, pls, kids, text, favs)
            ui {
                if (isFinishing || my != seq) { cur.close(); return@ui }
                val hadFocus = grid.hasFocus()
                posters.swap(cur)
                if (!keep) grid.scrollToPosition(0)
                val n = cur.count
                empty.visibility = if (n == 0) View.VISIBLE else View.GONE
                if (n == 0) empty.text = when {
                    text.isNotBlank() -> "Nessun risultato per «$text»"
                    c.id == Db.FAV -> "Nessun preferito: tieni premuto OK su una locandina per aggiungerla."
                    Sync.running -> "Sto caricando i contenuti…"
                    else -> "Nessun contenuto."
                }
                if (n > 0 && !keep && (focus || hadFocus) && !query.hasFocus()) grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private fun focusCats() = cats.focusAt(catAdapter.selected)
    private fun focusGrid() = grid.focusVisible()

    private fun openAt(pos: Int) {
        val c = posters.cursor ?: return
        if (pos < 0 || c.isClosed || !c.moveToPosition(pos)) return
        val id = c.getLong(0)
        open(DetailActivity::class.java) { putExtra("table", table); putExtra("id", id) }
    }

    private fun toggleFav(pos: Int) {
        val c = posters.cursor ?: return
        if (pos < 0 || c.isClosed || !c.moveToPosition(pos)) return
        val k = c.getString(1) ?: return
        val on = Store.toggleFav(table, k)
        favSet = Store.favs(table)
        Ui.toast(if (on) "Aggiunto ai preferiti" else "Tolto dai preferiti")
        catAdapter.items.find { it.id == Db.FAV }?.let { it.count = favSet.size; catAdapter.notifyItemChanged(1) }
        if (curCat == Db.FAV) { pendingCat = catAdapter.items.find { it.id == Db.FAV }; load() }
    }

    private inner class PosterAdapter : CursorAdapter<PosterAdapter.H>() {
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val ph: TextView = v.findViewById(R.id.ph)
            val title: TextView = v.findViewById(R.id.title)
            val meta: TextView = v.findViewById(R.id.meta)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_poster, p, false))
            h.itemView.findViewById<View>(R.id.box).clipToOutline = true
            h.itemView.setOnClickListener { openAt(h.bindingAdapterPosition) }
            h.itemView.setOnLongClickListener { toggleFav(h.bindingAdapterPosition); true }
            h.itemView.setOnKeyListener { _, code, e ->
                val pos = h.bindingAdapterPosition
                when {
                    code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP -> { toggleFav(pos); true }
                    // prima colonna: SINISTRA va sempre alle categorie (la griglia altrimenti salta al film della riga sopra)
                    code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN && pos >= 0 && pos % glm.spanCount == 0 -> { focusCats(); true }
                    // prima riga: SU va alla ricerca
                    code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN && pos in 0 until glm.spanCount -> { query.requestFocus(); true }
                    else -> false
                }
            }
            Ui.zoom(h.itemView, 1.06f)
            return h
        }

        // colonne: _id, k, pl, cat, name, img, sid, ext, url, rating, year
        override fun bind(h: H, c: Cursor, position: Int) {
            val name = c.getString(4) ?: ""
            h.title.text = name
            h.ph.text = name
            val year = c.getString(10) ?: ""
            val rating = (c.getString(9) ?: "").toDoubleOrNull()?.takeIf { it > 0 }
            h.meta.text = listOfNotNull(year.ifEmpty { null }, rating?.let { "★ " + String.format(java.util.Locale.ITALY, "%.1f", if (it > 10) it / 10 else it) }).joinToString(" · ")
            // «Leggerissima»: locandine decodificate più piccole (un terzo della memoria in meno)
            if (App.max) Ui.img(h.img, c.getString(5), posterW * 2 / 3, posterH * 2 / 3) else Ui.img(h.img, c.getString(5), posterW, posterH)
        }
    }
}
