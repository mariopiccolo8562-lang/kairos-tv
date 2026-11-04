package com.kairos.tv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Ricerca unica su canali, film e serie (archivio locale, istantanea) */
class SearchActivity : BaseActivity() {
    private lateinit var query: EditText
    private lateinit var empty: TextView
    private val adapter = HitAdapter()
    private var seq = 0
    private val searchR = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val rv = findViewById<RecyclerView>(R.id.results)
        rv.fast()
        rv.adapter = adapter
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                App.main.removeCallbacks(searchR)
                App.main.postDelayed(searchR, 350)
            }
        })
        query.setOnEditorActionListener { _, _, _ -> if (adapter.items.isNotEmpty()) rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }; false }
        query.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(searchR)
    }

    private fun search() {
        val t = query.text.toString()
        val my = ++seq
        val pls = K.activeIds()
        val kids = K.kids
        bg {
            val hits = Db.search(t, pls, kids)
            ui {
                if (my != seq || isFinishing) return@ui
                adapter.items = hits
                adapter.notifyDataSetChanged()
                empty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (t.trim().length < 2) "Scrivi almeno 2 lettere" else "Nessun risultato per «${t.trim()}»"
            }
        }
    }

    private fun openHit(h: Db.Hit) {
        when (h.table) {
            "live" -> {
                Store.lastLiveId = h.id
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("kind", "live").putExtra("cat", h.cat).putExtra("rowId", h.id))
            }
            else -> open(DetailActivity::class.java) { putExtra("table", h.table); putExtra("id", h.id) }
        }
    }

    private inner class HitAdapter : RecyclerView.Adapter<HitAdapter.H>() {
        var items: List<Db.Hit> = emptyList()
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val kind: TextView = v.findViewById(R.id.kind)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_hit, p, false))
            h.itemView.setOnClickListener { val i = h.bindingAdapterPosition; if (i in items.indices) openHit(items[i]) }
            return h
        }
        override fun onBindViewHolder(h: H, i: Int) {
            val x = items[i]
            h.name.text = x.name
            h.kind.text = when (x.table) { "live" -> "CANALE"; "vod" -> "FILM"; else -> "SERIE" }
            val s = Ui.dp(h.itemView.context, 36)
            Ui.img(h.img, x.img, s, s)
        }
    }
}
