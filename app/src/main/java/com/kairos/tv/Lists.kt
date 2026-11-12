package com.kairos.tv

import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import androidx.recyclerview.widget.RecyclerView

/** Adapter sopra un Cursor SQLite: legge solo le righe che si vedono */
abstract class CursorAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    var cursor: Cursor? = null
        private set

    init { setHasStableIds(true) }

    fun swap(c: Cursor?) {
        val old = cursor
        cursor = c
        notifyDataSetChanged()
        if (old != null && old !== c) try { old.close() } catch (_: Exception) {}
    }

    override fun getItemCount(): Int = try { cursor?.let { if (it.isClosed) 0 else it.count } ?: 0 } catch (_: Exception) { 0 }

    override fun getItemId(position: Int): Long {
        val c = cursor ?: return RecyclerView.NO_ID
        return try { if (!c.isClosed && c.moveToPosition(position)) c.getLong(0) else RecyclerView.NO_ID } catch (_: Exception) { RecyclerView.NO_ID }
    }

    // l'aggiornamento delle liste in background può cambiare le righe sotto un Cursor aperto: mai un crash
    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cursor ?: return
        try { if (!c.isClosed && c.moveToPosition(position)) bind(h, c, position) } catch (_: Exception) {}
    }

    abstract fun bind(h: VH, c: Cursor, position: Int)
}

/** liste veloci: niente animazioni, cache di viste, dimensione fissa */
fun RecyclerView.fast(horizontal: Boolean = false) {
    itemAnimator = null
    setHasFixedSize(true)
    setItemViewCacheSize(if (App.lite) 6 else 14)
    if (layoutManager == null) layoutManager = LinearLayoutManager(context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
    // modalità leggera: si scorre senza caricare le immagini, che arrivano appena ci si ferma (freccia tenuta premuta: vedi FastScroll)
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            try {
                if (state == RecyclerView.SCROLL_STATE_IDLE) { if (!FastScroll.held) Glide.with(rv).resumeRequests() } else if (App.lite) Glide.with(rv).pauseRequests()
            } catch (_: Exception) {}
        }
    })
}

/**
 * Scorrimento veloce col telecomando (freccia tenuta premuta), su tutti i dispositivi: i caricamenti delle immagini restano
 * in pausa finché non la si lascia, poi parte solo quello che è rimasto sullo schermo (niente download e decodifiche di
 * locandine passate in un attimo). Chiamato da [BaseActivity.dispatchKeyEvent].
 */
object FastScroll {
    var held = false
        private set
    private var view: View? = null
    private val resumeR = Runnable { resume() }

    fun onKey(a: Activity, e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            else -> return
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
            if (!held) {
                held = true
                val v = a.window.decorView
                view = v
                try { Glide.with(v).pauseRequests() } catch (_: Exception) {}
            }
            // anche se il rilascio del tasto non arriva (finestra di dialogo, telecomandi strani) si riparte da soli
            App.main.removeCallbacks(resumeR)
            App.main.postDelayed(resumeR, 400)
        } else if (e.action == KeyEvent.ACTION_UP && held) {
            App.main.removeCallbacks(resumeR)
            App.main.post(resumeR)
        }
    }

    /** si riparte: le immagini delle viste rimaste sullo schermo arrivano ora */
    fun resume() {
        App.main.removeCallbacks(resumeR)
        if (!held) return
        held = false
        val v = view
        view = null
        try { if (v != null) Glide.with(v).resumeRequests() } catch (_: Exception) {}
    }
}

/** colonna delle categorie (Diretta, Film, Serie) */
class CatAdapter(private val onFocus: (Db.Cat, Int) -> Unit, private val onClick: (Db.Cat, Int) -> Unit, private val onRight: (() -> Unit)? = null) : RecyclerView.Adapter<CatAdapter.H>() {
    var items: List<Db.Cat> = emptyList()
    var selected = 0

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    fun set(list: List<Db.Cat>, sel: Int) {
        items = list
        selected = sel.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun select(i: Int) {
        if (i == selected) return
        val old = selected
        selected = i
        notifyItemChanged(old)
        notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_cat, p, false))
        h.itemView.setOnFocusChangeListener { _, has ->
            // nome lungo: sulla categoria dove sei scorre per intero (prima restava tagliato con «…»)
            h.name.isSelected = has
            val i = h.bindingAdapterPosition
            if (has && i >= 0 && i < items.size) onFocus(items[i], i)
        }
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i >= 0 && i < items.size) onClick(items[i], i)
        }
        // DESTRA dalle categorie: alla lista accanto, nel punto visibile (non a un elemento a caso)
        h.itemView.setOnKeyListener { _, code, e ->
            val r = onRight
            if (r != null && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && e.action == android.view.KeyEvent.ACTION_DOWN) { r(); true } else false
        }
        return h
    }

    override fun onBindViewHolder(h: H, i: Int) {
        val c = items[i]
        h.name.text = c.name
        h.name.isSelected = h.itemView.isFocused
        h.count.text = if (c.count >= 0) Ui.num(c.count) else ""
        h.itemView.isActivated = i == selected
    }
}

/** porta il cursore sull'elemento [i] della lista (scorrendola se serve) */
fun RecyclerView.focusAt(i: Int) {
    val n = adapter?.itemCount ?: 0
    if (n == 0) return
    val p = i.coerceIn(0, n - 1)
    val v = findViewHolderForAdapterPosition(p)?.itemView
    if (v != null) { v.requestFocus(); return }
    scrollToPosition(p)
    post { (findViewHolderForAdapterPosition(p)?.itemView ?: getChildAt(0))?.requestFocus() }
}

/** il primo elemento visibile della lista prende il cursore */
fun RecyclerView.focusVisible() {
    val lm = layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
    val first = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 } ?: lm?.findFirstVisibleItemPosition() ?: -1
    ((if (first >= 0) findViewHolderForAdapterPosition(first)?.itemView else null) ?: getChildAt(0))?.requestFocus()
}

/** categorie fisse in testa alle liste */
fun fixedCats(ctx: Context, kind: String, total: Int, favs: Int): List<Db.Cat> {
    val out = ArrayList<Db.Cat>()
    out.add(Db.Cat(Db.ALL, if (kind == "live") "Tutti i canali" else "Tutti", total))
    out.add(Db.Cat(Db.FAV, "Preferiti", favs))
    if (kind != "live") out.add(Db.Cat(Db.NEW, "Aggiunti di recente", -1))
    return out
}
