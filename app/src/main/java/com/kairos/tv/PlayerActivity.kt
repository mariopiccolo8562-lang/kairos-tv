package com.kairos.tv

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.util.Locale

/**
 * Player nativo a schermo intero (ExoPlayer o VLC).
 * Riceve dal player web: lista canali/titolo, indice, posizione di partenza e preferenze.
 */
class PlayerActivity : ComponentActivity(), EngineListener {

    companion object {
        /** payload passato in memoria (le liste canali possono essere troppo grandi per un Intent) */
        @Volatile var pending: String? = null
        /** lista già analizzata (dal thread del ponte JS, non da quello dell'interfaccia) */
        @Volatile var pendingJson: JSONObject? = null
        /** player aperto in questo momento (per «Disconnetti» dal pannello) */
        @Volatile var current: PlayerActivity? = null
        const val RESULT = "kx_result"
        private val ACCENT = Color.parseColor("#E5202B")
    }

    /** e = guida: [titolo ora, inizio, fine (secondi), titolo dopo, inizio dopo]; logo = indirizzo del logo (può mancare) */
    private data class Item(val id: String, val title: String, val url: String, val alt: String, val num: Int, val group: String, val epg: org.json.JSONArray?, val logo: String = "", val lite: List<Pair<String, String>> = emptyList())

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var spinner: ProgressBar
    private lateinit var osd: LinearLayout
    private lateinit var brandView: TextView
    private lateinit var tBadge: TextView
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var tTime: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bar: LinearLayout
    private lateinit var bPause: TextView
    private lateinit var bEngine: TextView
    private lateinit var zapView: TextView
    private lateinit var msgView: TextView
    private lateinit var veil: View
    private lateinit var tEpg: TextView

    private var engine: Engine? = null
    private var items: List<Item> = emptyList()
    private var index = 0
    private var kind = "live"
    private var engineName = "exo"
    private var userChangedEngine = false
    private var aspect = "fit"
    private var brand = "KAIROS"
    private lateinit var prefs: PlayPrefs
    private var currentUrl = ""
    private var triedAlt = false
    private var triedFallback = false
    private var retries = 0

    /* ---------------- salta intro (serie) e immagine nell'immagine ---------------- */
    private var introKey = ""
    private var intro: LongArray? = null
    private var introAuto = false
    private var introChanged = false
    private var introDoneFor = ""
    private var introStartMark = -1L
    private lateinit var skipView: TextView

    /** ogni secondo: mostra «Salta intro» (o la salta da solo) mentre si è dentro l'intro segnata */
    private fun introTick() {
        if (!::skipView.isInitialized) return
        val iv = intro; val e = engine
        if (iv == null || isLive || e == null || !e.isPlaying() || zapPending) { if (skipView.visibility == View.VISIBLE) skipView.visibility = View.GONE; return }
        val pos = e.position()
        val inside = pos >= iv[0] && pos < iv[1] - 3000
        if (inside && introAuto && introDoneFor != item.id) { introDoneFor = item.id; skipIntro(); toast("Intro saltata"); return }
        if (inside && introDoneFor != item.id) { if (skipView.visibility != View.VISIBLE) { skipView.visibility = View.VISIBLE; skipView.alpha = 0f; skipView.animate().alpha(1f).setDuration(200).start() } }
        else if (skipView.visibility == View.VISIBLE) skipView.visibility = View.GONE
    }
    private fun skipIntro() {
        val iv = intro ?: return
        introDoneFor = item.id
        skipView.visibility = View.GONE
        engine?.seekTo(iv[1])
    }
    private fun fmtT(ms: Long): String { val t = ms / 1000; return if (t >= 3600) String.format(Locale.ITALIAN, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) else String.format(Locale.ITALIAN, "%d:%02d", t / 60, t % 60) }
    private fun menuIntro() {
        val pos = engine?.position() ?: 0L
        val iv = intro
        val opts = mutableListOf(
            Track("start", "Segna INIZIO intro qui (${fmtT(pos)})", false),
            Track("end", "Segna FINE intro qui (${fmtT(pos)})" + (if (introStartMark < 0 && iv == null) " · dall'inizio" else ""), false),
            Track("auto", "Salta automaticamente: " + if (introAuto) "Sì" else "No", false),
        )
        if (iv != null) opts.add(0, Track("info", "Intro: ${fmtT(iv[0])} – ${fmtT(iv[1])} (vale per tutti gli episodi)", false))
        if (iv != null) opts.add(Track("del", "Cancella l'intro segnata", false))
        choose("Salta intro", opts, "") {
            when (it.id) {
                "start" -> { introStartMark = pos; toast("Inizio intro segnato: ora vai alla fine e scegli «Segna FINE»") }
                "end" -> {
                    val st = if (introStartMark in 0 until pos) introStartMark else if (iv != null && iv[0] < pos) iv[0] else 0L
                    if (pos - st < 5000) { toast("L'intro deve durare almeno 5 secondi"); return@choose }
                    intro = longArrayOf(st, pos); introChanged = true; introStartMark = -1L; introDoneFor = item.id
                    toast("Intro segnata: ${fmtT(st)} – ${fmtT(pos)}. Negli altri episodi comparirà «Salta intro»")
                }
                "auto" -> { introAuto = !introAuto; introChanged = true; toast(if (introAuto) "L'intro verrà saltata da sola" else "Comparirà il pulsante «Salta intro»") }
                "del" -> { intro = null; introChanged = true; toast("Intro cancellata") }
            }
        }
    }
    /* ---------------- recupero automatico ---------------- */
    /** qualità adattiva: passa da solo alla versione più leggera del canale se la linea non regge */
    private var adapt = true
    /** decodifica software forzata dal recupero (problemi del decoder hardware): vale per tutta la visione */
    private var hwOff = false
    /** passi già provati sul contenuto in onda */
    private val recDone = HashSet<String>()
    private var recCooldownUntil = 0L
    private var lastActionAt = 0L
    /** variante in uso: -1 = originale, 0.. = item.lite[i] */
    private var varIdx = -1
    private var stayDown = false
    private var upAt = 0L
    /** dopo un calo, i canali successivi partono già nella versione più leggera per 10 minuti */
    private var weakUntil = 0L
    private var startAt = 0L
    private var readyOnce = false
    private var isBuffering = false
    private var bufferingSince = 0L
    private val stallTimes = java.util.ArrayDeque<Long>()
    private var lastFrames = -1L
    private var lastQueued = -1L
    private var lastPos = -1L
    private var freezeSec = 0
    /** primo fotogramma arrivato dall'ultimo avvio / cambio di superficie (prima non si controlla l'immagine ferma) */
    private var frameSeen = false
    private var stallCounted = false
    /** player fermo (app in secondo piano): nessun controllo */
    private var stopped = false
    /** nuovo tentativo programmato (va annullato se si cambia canale o si esce) */
    private var retryRun: Runnable? = null
    /** motore e decodifica scelti all'apertura: il recupero li cambia solo per il contenuto in onda */
    private var baseEngine = ""
    private var recEngineChanged = false
    /** eventi dei motori: quelli di un motore sostituito o di un caricamento precedente vengono ignorati */
    private var engineGen = 0
    @Volatile private var playTok = 0
    private inner class GenL(val g: Int) : EngineListener {
        override fun onBuffering(buffering: Boolean) = evBuffering(g, playTok, buffering)
        override fun onReady() = evReady(g, playTok)
        override fun onError(message: String) = evError(g, playTok, message)
        override fun onEnded() = evEnded(g, playTok)
        override fun onFirstFrame() = evFirstFrame(g, playTok)
    }
    private fun live(g: Int, t: Int) = g == engineGen && t == playTok && !closing
    private fun cancelRetry() { retryRun?.let { ui.removeCallbacks(it) }; retryRun = null }
    private var okSince = 0L
    /** problema in corso, mandato al pannello quando si sa com'è finita */
    private var pendingEvt: JSONObject? = null

    private fun liteOf(a: org.json.JSONArray?): List<Pair<String, String>> {
        if (a == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) { val x = a.optJSONArray(i) ?: continue; val u = x.optString(0); if (u.isNotEmpty()) out.add(u to x.optString(1)) }
        return out
    }

    private fun closeEvt(outcome: String) {
        val e = pendingEvt ?: return
        pendingEvt = null
        try { e.put("outcome", outcome); PlayStats.event(e) } catch (_: Exception) {}
    }

    private fun newEvt(problem: String, action: String, err: String) {
        closeEvt("non risolto")
        pendingEvt = try {
            JSONObject().put("t", System.currentTimeMillis()).put("problem", problem).put("action", action).put("err", err.take(120))
                .put("title", item.title.take(120)).put("id", item.id.take(60)).put("k", kind).put("engine", engineName)
                .put("codec", PlayStats.codec).put("dec", if (PlayStats.decoder.isEmpty()) "" else if (isSoftware(PlayStats.decoder)) "sw" else "hw")
                .put("res", if (PlayStats.height > 0) "${PlayStats.height}p" else "").put("var", if (varIdx >= 0) item.lite.getOrNull(varIdx)?.second ?: "" else "")
        } catch (_: Exception) { null }
    }

    private fun resetRecovery() {
        closeEvt("chiuso")
        recDone.clear(); recCooldownUntil = 0L; varIdx = -1; stayDown = false; upAt = 0L
        readyOnce = false; isBuffering = false; bufferingSince = 0L; stallTimes.clear(); stallCounted = false
        lastFrames = -1L; lastQueued = -1L; lastPos = -1L; freezeSec = 0; okSince = 0L; frameSeen = false
        cancelRetry()
        startAt = System.currentTimeMillis()
        PlayStats.recovery = ""; PlayStats.reduced = ""
    }

    private fun curPos(): Long = if (isLive) 0L else if (seekTarget >= 0) seekTarget else engine?.position() ?: 0L

    /**
     * Prova da sola una soluzione quando la visione ha un problema, senza far uscire l'utente.
     * freeze = immagine ferma con l'audio che va · stall = caricamento che si ripete · error = errore del lettore
     * Ritorna false se non c'è più niente da provare.
     */
    private fun recover(problem: String, err: String = ""): Boolean {
        if (closing || zapPending) return true
        val now = System.currentTimeMillis()
        if (now < recCooldownUntil) return true
        val canAlt = item.alt.isNotEmpty() && currentUrl != item.alt && !triedAlt
        val canSw = engine is ExoEngine && !hwOff && prefs.hw != "off"
        val canLite = adapt && varIdx + 1 < item.lite.size
        val ladder = when (problem) {
            "freeze" -> listOf("reload", "sw", "engine", "lite")
            "stall" -> listOf("alt", "lite", "reload", "engine")
            else -> listOf("alt", if (err.contains("DECOD", true) || err.contains("CODEC", true)) "sw" else "", "engine", "retry", "retry2", "retry3", "lite")
        }
        val step = ladder.firstOrNull { st ->
            st.isNotEmpty() && !recDone.contains(st) && when (st) {
                "alt" -> canAlt; "sw" -> canSw; "lite" -> canLite
                "engine" -> false // niente secondo lettore in Kairos TV
                "retry", "retry2", "retry3" -> isLive
                else -> true
            }
        } ?: return false
        recDone.add(step)
        recCooldownUntil = now + 8000L; lastActionAt = now
        freezeSec = 0; stallTimes.clear(); okSince = 0L
        val pos = curPos()
        val what = when (problem) { "freeze" -> "Immagine ferma"; "stall" -> "Caricamento continuo"; else -> "Errore" }
        when (step) {
            "reload", "retry", "retry2", "retry3" -> {
                newEvt(problem, "ricaricato", err)
                PlayStats.recovery = "$what → ricaricato"
                if (step == "reload") toast("Problema di riproduzione: riparto…")
                val delay = when (step) { "retry" -> 1500L; "retry2" -> 3000L; "retry3" -> 4500L; else -> 0L }
                val u = currentUrl; val idx = index
                cancelRetry()
                if (delay == 0L) startUrl(u, pos)
                else { val r = Runnable { retryRun = null; if (!closing && !stopped && !zapPending && index == idx && currentUrl == u) startUrl(u, pos) }; retryRun = r; ui.postDelayed(r, delay) }
            }
            "sw" -> {
                newEvt(problem, "decodifica software", err)
                PlayStats.recovery = "$what → decodifica software"
                toast("Problema di riproduzione: provo la decodifica software…")
                hwOff = true; recEngineChanged = true
                startEngine("exo")
                if (engine != null) startUrl(currentUrl, pos)
                updateInfo()
            }
            "engine" -> {
                triedFallback = true; recEngineChanged = true
                val other = if (engineName == "vlc") "exo" else "vlc"
                newEvt(problem, "passato a ${engineLabel(other)}", err)
                PlayStats.recovery = "$what → ${engineLabel(other)}"
                toast("Problema di riproduzione: provo con ${engineLabel(other)}…")
                startEngine(other)
                if (engine != null) startUrl(currentUrl, pos)
                updateInfo()
            }
            "alt" -> {
                triedAlt = true
                newEvt(problem, "formato alternativo", err)
                PlayStats.recovery = "$what → formato alternativo"
                startUrl(item.alt, pos)
            }
            "lite" -> {
                val v = item.lite[varIdx + 1]
                newEvt(problem, "qualità ridotta" + (if (v.second.isNotEmpty()) " (${v.second})" else ""), err)
                varIdx++
                PlayStats.recovery = "$what → qualità ridotta"
                PlayStats.reduced = v.second.ifEmpty { "ridotta" }
                weakUntil = now + 10 * 60_000L
                toast("Qualità ridotta per la tua connessione" + (if (v.second.isNotEmpty()) " (${v.second})" else ""))
                startUrl(v.first, pos)
            }
        }
        return true
    }

    /** controllo ogni secondo: immagine ferma, blocchi lunghi, ritorno alla qualità piena */
    private val watchRunnable = object : Runnable {
        override fun run() {
            if (closing) return
            try { watchdog() } catch (_: Throwable) {}
            ui.postDelayed(this, 1000)
        }
    }

    private fun watchdog() {
        try { introTick() } catch (_: Throwable) {}
        if (zapPending || stopped) return
        val now = System.currentTimeMillis()
        val e = engine
        while (stallTimes.isNotEmpty() && now - stallTimes.first > 75_000L) stallTimes.removeFirst()
        // immagine ferma: il tempo avanza ma non arriva nessun fotogramma nuovo (solo ExoPlayer espone i contatori).
        // Solo dopo il primo fotogramma e 8 s dall'avvio; per i video quasi fermi (radio con immagine, slide) serve che il
        // decoder stia ricevendo dati, altrimenti un fotogramma ogni tanto è normale.
        if (e is ExoEngine && frameSeen && now - startAt > 8000L && e.playingVideo()) {
            val fr = e.renderedFrames(); val q = e.queuedFrames(); val pos = e.rawPosition()
            val fed = q > lastQueued && lastQueued >= 0 || e.frameRate() >= 5f
            if (fr >= 0 && lastFrames >= 0 && fr == lastFrames && pos != lastPos && lastPos >= 0 && fed) freezeSec++ else freezeSec = 0
            lastFrames = fr; lastQueued = q; lastPos = pos
            if (freezeSec >= 4) { freezeSec = 0; if (!recover("freeze", PlayStats.lastCodecError)) closeEvt("non risolto") }
        } else { freezeSec = 0; lastFrames = -1L; lastQueued = -1L; lastPos = -1L }
        // blocco vero: caricamento di almeno 1 s durante la visione (non all'avvio né dopo un salto)
        if (isBuffering && !stallCounted && readyOnce && bufferingSince > 0 && now - bufferingSince >= 1000L && bufferingSince - startAt >= 4000L && seekTarget < 0) {
            stallCounted = true
            PlayStats.buffers++
            PlayStats.stall()
            stallTimes.addLast(now)
            // tornati alla qualità piena da poco e di nuovo blocchi: si resta su quella ridotta
            if (upAt > 0 && now - upAt < 120_000L) stayDown = true
            if (stallTimes.size >= 3 && !recover("stall")) closeEvt("non risolto")
        }
        // caricamento che non finisce
        val longMs = if (isLive) 12_000L else 20_000L
        if (readyOnce && isBuffering && bufferingSince > 0 && now - bufferingSince > longMs && now - startAt > longMs) { bufferingSince = now; if (!recover("stall")) closeEvt("non risolto") }
        // tutto regolare da un po': il problema è risolto
        val fine = readyOnce && !isBuffering && freezeSec == 0
        if (fine) { if (okSince == 0L) okSince = now } else okSince = 0L
        if (fine && pendingEvt != null && now - okSince > 25_000L) closeEvt("risolto")
        if (fine && recDone.isNotEmpty() && now - lastActionAt > 120_000L && now - okSince > 60_000L && varIdx < 0) recDone.clear()
        // qualità ridotta: dopo 6 minuti senza blocchi si riprova quella piena (una volta; se torna a bloccarsi resta ridotta)
        if (fine && varIdx >= 0 && !stayDown && now - lastActionAt > 6 * 60_000L && stallTimes.isEmpty() && now - okSince > 6 * 60_000L) {
            varIdx = -1; upAt = now; lastActionAt = now
            PlayStats.reduced = ""; PlayStats.recovery = "qualità piena ripristinata"
            recDone.remove("lite")
            toast("Connessione migliorata: qualità piena")
            startUrl(item.url, curPos())
        }
    }
    private var resumeMs = 0L
    private var numBuf = ""
    private var subScale = 1f
    private var showRes = true
    private var autoNext = false
    private var engineKey = ""
    private lateinit var resView: TextView
    private lateinit var tRec: TextView
    private var bRec: TextView? = null
    private var dialog: AlertDialog? = null
    /** canale in onda (per «canale precedente») e quello visto prima */
    private var playingIndex = -1
    private var prevIndex = -1

    private val item: Item get() = items[index]
    private val hmFmt by lazy { java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN) }
    /** numeri dei canali come testo (per la digitazione diretta), calcolati una volta */
    private val numStrs by lazy { items.map { if (it.num > 0) it.num.toString() else "" } }
    private val isLive: Boolean get() = kind == "live"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handed = Handoff.engine
        Handoff.engine = null
        val payload = pending; val pre = pendingJson
        pending = null; pendingJson = null
        // chiusura immediata: nessun tasto o evento deve toccare l'interfaccia mai costruita
        if (payload == null && pre == null) { closing = true; handed?.release(); finish(); return }
        try {
            val j = pre ?: JSONObject(payload!!)
            kind = j.optString("kind", "live")
            index = j.optInt("index", 0)
            engineName = "exo" // Kairos TV: solo ExoPlayer
            aspect = j.optString("aspect", "fit")
            brand = j.optString("brand", "KAIROS")
            prefs = PlayPrefs(j.optString("quality", "auto"), j.optString("buffer", "balanced"), aspect, live = kind == "live", hw = j.optString("hw", "auto"))
            subScale = when (j.optString("sub", "medium")) { "small" -> 0.8f; "large" -> 1.3f; "xl" -> 1.6f; else -> 1f }
            showRes = j.optBoolean("res", true)
            autoNext = j.optBoolean("autoNext", false)
            adapt = j.optBoolean("adapt", true)
            introKey = j.optString("introKey", "")
            introAuto = j.optBoolean("introAuto", false)
            j.optJSONArray("intro")?.let { a -> if (a.length() == 2 && a.optLong(1) > a.optLong(0)) intro = longArrayOf(a.optLong(0) * 1000, a.optLong(1) * 1000) }
            engineKey = j.optString("engineKey", "")
            baseEngine = engineName
            resumeMs = j.optLong("start", 0L)
            val arr = j.getJSONArray("items")
            items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.optString("id"), o.optString("t"), o.optString("u"), o.optString("a"), o.optInt("n"), o.optString("g"), o.optJSONArray("e"), o.optString("l"), liteOf(o.optJSONArray("v")))
            }
        } catch (e: Exception) { closing = true; handed?.release(); finish(); return }
        if (items.isEmpty()) { closing = true; handed?.release(); finish(); return }
        index = index.coerceIn(0, items.size - 1)
        playingIndex = index

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        buildUi()
        hideSystemUi()
        // stesso video dell'anteprima: nessun nuovo caricamento, continua senza interruzioni
        val h = handed
        if (h != null && isLive && engineName != "vlc" && Handoff.id == item.id) {
            h.l = GenL(++engineGen)
            engine = h
            adopted = h
            if (prefs.quality != "auto") h.selectQuality(prefs.quality) // rispetta la qualità scelta nelle impostazioni
            engineName = h.name
            currentUrl = h.playingUrl.ifEmpty { item.url }
            statsStart()
            markPlaying()
            resetRecovery(); readyOnce = h.isActive()
            ui.postDelayed(watchRunnable, 1000)
            // Questa finestra è trasparente: sotto resta l'anteprima ingrandita a tutto schermo con il video.
            // La nuova vista parte minuscola (la sua superficie, finché è vuota, sarebbe nera: tendina del player
            // e sfondo nero delle SurfaceView da Android 10) e si allarga solo quando ha disegnato il primo
            // fotogramma; allora l'anteprima sotto viene tolta. Niente sparizione/ricomparsa del video.
            root.setBackgroundColor(Color.TRANSPARENT)
            handoffPending = true
            val v = h.moveTo(this, false, if (SurfaceFix.needed(this)) 3500L else 1500L) { completeHandoff(false) }
            h.setAspect(aspect)
            h.setSubtitleScale(subScale)
            root.addView(v, 0, FrameLayout.LayoutParams(4, 4, Gravity.TOP or Gravity.START))
        } else {
            if (h != null) {
                h.release()
                // l'anteprima ingrandita resta sotto questa finestra (nera): la si toglie quando questa è sullo schermo
                ui.postDelayed({ Handoff.host?.fullShown() }, 400)
            }
            startEngine(engineName)
            playCurrent(resumeMs)
            setOpaque(true)
        }
        showOsd(false)
        current = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    closing -> {}
                    listOpen -> closeList()
                    osd.visibility == View.VISIBLE && bar.hasFocus() -> hideOsd()
                    else -> finishWithResult()
                }
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Passaggio dall'anteprima                                            */
    /* ------------------------------------------------------------------ */
    private var handoffPending = false
    private var dropPending: Runnable? = null

    /** il video è visibile qui (o è scaduta l'attesa): vista a tutto schermo, poi via l'anteprima che sta sotto.
     *  [now] = subito (chiusura, cambio motore, uscita dall'app), senza attendere il disegno del fotogramma. */
    /** finestra opaca durante la visione: la WebView sotto non viene più composta a ogni frame (meno lavoro per la GPU della Fire TV);
     *  traslucida solo per le animazioni di entrata e uscita (Android 8+; prima non c'è l'API) */
    private fun setOpaque(opaque: Boolean) { if (Build.VERSION.SDK_INT >= 26) try { setTranslucent(!opaque) } catch (_: Throwable) {} }
    private fun completeHandoff(now: Boolean) {
        if (handoffPending) {
            handoffPending = false
            val e = adopted
            val v = e?.view
            if (v != null && v.parent === root) v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            root.setBackgroundColor(Color.BLACK)
            setOpaque(true)
            val r = Runnable { dropPending = null; e?.dropOldView(); Handoff.host?.fullShown() }
            dropPending = r
            // un attimo per far arrivare sullo schermo questa finestra a tutto schermo, poi l'anteprima (coperta) sparisce
            if (!now) { ui.postDelayed(r, 150); return }
        }
        if (now) dropPending?.let { ui.removeCallbacks(it); it.run() }
    }

    /* ------------------------------------------------------------------ */
    /* Interfaccia                                                         */
    /* ------------------------------------------------------------------ */
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        val normal = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(70, 255, 255, 255)) }
        val focused = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE)))
        setOnClickListener { onClick(); touchOsd() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.setOnClickListener { if (listOpen) closeList() else if (osd.visibility == View.VISIBLE) hideOsd() else showOsd(true) }

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            visibility = View.GONE
        }
        msgView = text(18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(200, 20, 20, 24)) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            visibility = View.GONE
        }
        zapView = text(64f, Color.WHITE, true).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(64), dp(40), 0) }
            visibility = View.GONE
        }
        // niente "overlapping rendering": l'alpha del velo non crea un layer a schermo intero a ogni frame (pesante su Fire TV)
        veil = object : View(this) { override fun hasOverlappingRendering(): Boolean = false }.apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        resView = text(13f, Color.WHITE, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.argb(150, 0, 0, 0)); setStroke(dp(1), Color.argb(90, 255, 255, 255)) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(40), 0) }
            visibility = View.GONE
        }
        brandView = text(26f, ACCENT, true).apply {
            text = brand.uppercase(Locale.ROOT)
            letterSpacing = 0.18f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(26), 0, 0) }
        }

        // pannello in basso
        osd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(80), dp(40), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.TRANSPARENT))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        tBadge = text(12f, Color.WHITE, true).apply {
            letterSpacing = 0.2f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(ACCENT) }
        }
        // registrazione in corso del canale in onda
        tRec = text(12f, Color.WHITE, true).apply {
            text = "● REC"
            letterSpacing = 0.2f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(Color.argb(200, 0, 0, 0)); setStroke(dp(1), Color.parseColor("#FF3B3B")) }
            setTextColor(Color.parseColor("#FF3B3B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }
            visibility = View.GONE
        }
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tBadge); addView(tRec) }
        tTitle = text(30f, Color.WHITE, true).apply { maxLines = 1; setPadding(0, dp(8), 0, 0) }
        tSub = text(16f, Color.argb(200, 255, 255, 255))
        tEpg = text(17f, Color.WHITE).apply { setPadding(0, dp(8), 0, 0); maxLines = 3 }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(16) }
        }
        tTime = text(13f, Color.argb(190, 255, 255, 255)).apply { setPadding(0, dp(6), 0, dp(14)) }

        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bPause = button("Pausa") { commitSeek(); engine?.togglePause(); ui.postDelayed({ updatePauseLabel() }, 150) }
        if (!isLive) bar.addView(bPause)
        if (isLive) {
            bar.addView(button("Lista canali") { openList() })
        }
        bar.addView(button("Audio") { menuAudio() })
        bar.addView(button("Qualità") { menuQuality() })
        bar.addView(button("Sottotitoli") { menuSubtitles() })
        bar.addView(button("Formato") { menuAspect() })
        bEngine = button("") { menuEngine() } // non mostrato: Kairos TV ha un solo lettore
        if (!isLive) {
            bar.addView(button("−10 s") { seekAdd(-10_000) })
            bar.addView(button("+30 s") { seekAdd(30_000) })
            if (introKey.isNotEmpty()) bar.addView(button("Intro") { menuIntro() })
        }
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }

        osd.addView(badgeRow)
        osd.addView(tTitle)
        osd.addView(tSub)
        osd.addView(tEpg)
        osd.addView(progress)
        osd.addView(tTime)
        osd.addView(scroller)

        root.addView(veil)
        root.addView(osd)
        root.addView(brandView)
        root.addView(resView)
        root.addView(zapView)
        root.addView(spinner)
        root.addView(msgView)
        skipView = button("Salta intro  ›") { skipIntro() }.apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(64)) }
        }
        root.addView(skipView)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private val hideRunnable = Runnable { hideOsd() }
    // dati della visione per il pannello ("Online ora"): letti ogni 5 secondi sul thread principale
    private val statsRunnable = object : Runnable {
        override fun run() {
            val e = engine
            if (e != null) {
                PlayStats.engine = e.name
                if (e is ExoEngine) e.fillStats() else { PlayStats.width = 0; PlayStats.height = e.videoHeight() }
            }
            ui.postDelayed(this, 5000)
        }
    }
    private fun statsStart() { PlayStats.start(engineName); ui.removeCallbacks(statsRunnable); ui.postDelayed(statsRunnable, 1500) }
    private val tickRunnable = object : Runnable {
        override fun run() { updateTime(); updateRes(); updateRec(); ui.postDelayed(this, 1000) }
    }

    private fun showOsd(focusBar: Boolean) {
        if (closing) return
        if (listOpen) closeList()
        updateInfo()
        ui.postDelayed({ updateRes() }, 30)
        // annulla una chiusura in corso (altrimenti al termine lo nasconderebbe)
        osd.animate().cancel(); brandView.animate().cancel()
        if (osd.visibility == View.VISIBLE && osd.alpha < 1f) { osd.alpha = 1f; osd.translationY = 0f; brandView.alpha = 1f }
        if (osd.visibility != View.VISIBLE) {
            osd.visibility = View.VISIBLE
            osd.alpha = 0f; osd.translationY = dp(40).toFloat()
            osd.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            brandView.alpha = 0f
            brandView.animate().alpha(1f).setDuration(220).start()
        }
        brandView.visibility = View.VISIBLE
        if (focusBar && bar.childCount > 0 && !bar.hasFocus()) bar.getChildAt(0).requestFocus()
        ui.removeCallbacks(tickRunnable); ui.post(tickRunnable)
        touchOsd()
    }

    private fun touchOsd() {
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, if (bar.hasFocus()) 8000L else 4500L)
    }

    private fun hideOsd() {
        ui.removeCallbacks(tickRunnable)
        root.requestFocus()
        if (osd.visibility != View.VISIBLE) return
        osd.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(180).withEndAction { osd.visibility = View.GONE; osd.alpha = 1f; osd.translationY = 0f }.start()
        brandView.animate().alpha(0f).setDuration(180).withEndAction { brandView.visibility = View.GONE; brandView.alpha = 1f }.start()
        resView.visibility = View.GONE
    }

    private fun engineLabel(n: String) = if (n == "vlc") "VLC" else "ExoPlayer"

    private fun updateRes() {
        if (!showRes) { resView.visibility = View.GONE; return }
        val e = engine
        val info = when (e) { is ExoEngine -> e.videoInfo(); null -> ""; else -> e.videoHeight().let { if (it > 0) "${it}p" else "" } }
        val h = e?.videoHeight() ?: 0
        val tag = when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; h > 0 -> "SD"; else -> "" }
        if (info.isBlank()) { resView.visibility = View.GONE; return }
        resView.text = if (tag.isNotEmpty()) "$tag  ·  $info" else info
        resView.visibility = if (osd.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun updateInfo() {
        tBadge.text = if (isLive) "● IN DIRETTA" else "FILM · SERIE"
        tTitle.text = if (isLive && item.num > 0) "${item.num}  ${item.title}" else item.title
        tSub.text = listOf(item.group, engineLabel(engineName)).filter { it.isNotBlank() }.joinToString("  ·  ")
        val e = item.epg
        if (isLive && e != null && e.length() >= 3 && e.optString(0).isNotBlank()) {
            val f = hmFmt
            val s0 = e.optLong(1) * 1000; val e0 = e.optLong(2) * 1000
            val now = System.currentTimeMillis()
            var t = "Ora  ${f.format(java.util.Date(s0))}–${f.format(java.util.Date(e0))}   ${e.optString(0)}"
            if (e.length() >= 5 && e.optString(3).isNotBlank()) t += "\nDopo  ${f.format(java.util.Date(e.optLong(4) * 1000))}   ${e.optString(3)}"
            tEpg.text = t; tEpg.visibility = View.VISIBLE
            if (e0 > s0) { progress.visibility = View.VISIBLE; progress.progress = (((now - s0) * 1000) / (e0 - s0)).toInt().coerceIn(0, 1000) }
            else progress.visibility = View.GONE
        } else tEpg.visibility = View.GONE
        bEngine.text = "Motore: ${engineLabel(engineName)}"
        updateRec()
        if (!isLive) progress.visibility = View.VISIBLE else if (tEpg.visibility != View.VISIBLE) progress.visibility = View.GONE
        updatePauseLabel()
        updateTime()
    }

    private fun updatePauseLabel() { bPause.text = if (engine?.isPlaying() == false) "Riproduci" else "Pausa" }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    private fun updateTime() {
        val e = engine ?: return
        if (isLive) {
            tTime.text = "▲▼ cambia canale   ·   OK menu   ·   OK a schermo pulito: lista canali   ·   Indietro per uscire"
        } else {
            val d = e.duration()
            val cur = e.position()
            val pending = seekTarget >= 0
            var p = if (pending) seekTarget else cur
            // subito dopo il salto alcuni motori riportano ancora la posizione vecchia: si mostra quella scelta
            if (!pending && seekHold >= 0) { if (SystemClock.uptimeMillis() < seekHoldUntil && kotlin.math.abs(cur - seekHold) > 3000) p = seekHold else seekHold = -1L }
            progress.progress = if (d > 0) ((p * 1000) / d).toInt().coerceIn(0, 1000) else 0
            val jump = if (pending) "   (${if (seekTarget >= cur) "▶▶ +" else "◀◀ −"}${fmt(kotlin.math.abs(seekTarget - cur))})" else ""
            tTime.text = "${fmt(p)} / ${if (d > 0) fmt(d) else "--:--"}$jump   ·   ◀ ▶ cerca (tieni premuto: più veloce)   ·   OK menu"
        }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun showMsg(msg: String?) {
        if (msg == null) { msgView.visibility = View.GONE; return }
        msgView.text = msg; msgView.visibility = View.VISIBLE
    }

    /* ------------------------------------------------------------------ */
    /* Riproduzione                                                        */
    /* ------------------------------------------------------------------ */
    private fun userAgent(): String = try {
        WebSettings.getDefaultUserAgent(this) + " KairosPlayer/" + BuildConfig.VERSION_NAME
    } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) KairosPlayer/" + BuildConfig.VERSION_NAME }

    private fun startEngine(name: String) {
        completeHandoff(true) // il video dell'anteprima viene sostituito: l'anteprima sotto va tolta subito
        ui.removeCallbacks(seekCommit); seekTarget = -1L; seekHold = -1L
        engine?.let { root.removeView(it.view); it.release() }
        engine = null
        cancelRetry()
        val gl = GenL(++engineGen)
        // Kairos TV: solo ExoPlayer
        val e: Engine? = try { ExoEngine(this, gl, userAgent(), prefs.copy(aspect = aspect, hw = if (hwOff) "off" else prefs.hw)) } catch (t: Throwable) { null }
        if (e == null) { spinner.visibility = View.GONE; showMsg("Impossibile avviare il player su questo dispositivo"); return }
        engineName = e.name
        engine = e
        (e as? ExoEngine)?.setSubtitleScale(subScale)
        root.addView(e.view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        statsStart()
    }

    /** segna il canale [index] come quello in onda (il precedente diventa «canale precedente») */
    private fun markPlaying() {
        if (playingIndex != index) {
            if (playingIndex in items.indices) prevIndex = playingIndex
            playingIndex = index
        }
        listChanged()
    }

    /** il numero di righe della lista dipende da canale scelto/precedente (riga «↩»): l'adattatore va avvisato
     *  a ogni cambio, altrimenti ListView lancia IllegalStateException al primo ridisegno (es. mentre si chiude) */
    private fun listChanged() { if (::listPanel.isInitialized) listAdapter.notifyDataSetChanged() }

    private fun playCurrent(startMs: Long) {
        markPlaying()
        triedAlt = false; triedFallback = false; retries = 0
        resetRecovery()
        // il recupero aveva cambiato lettore o decodifica per il contenuto precedente: il nuovo riparte con quelli scelti
        if (recEngineChanged && !userChangedEngine && baseEngine.isNotEmpty()) { recEngineChanged = false; hwOff = false; startEngine(baseEngine) }
        ui.removeCallbacks(watchRunnable); ui.postDelayed(watchRunnable, 1000)
        // linea debole negli ultimi minuti: si parte già dalla versione più leggera (nessun blocco iniziale)
        if (adapt && item.lite.isNotEmpty() && System.currentTimeMillis() < weakUntil) {
            varIdx = 0
            PlayStats.reduced = item.lite[0].second.ifEmpty { "ridotta" }
            startUrl(item.lite[0].first, startMs)
            return
        }
        startUrl(item.url, startMs)
    }

    private fun startUrl(url: String, startMs: Long) {
        currentUrl = url
        playTok++
        startAt = System.currentTimeMillis() // i primi secondi di caricamento non sono «blocchi»
        frameSeen = false; isBuffering = false; bufferingSince = 0L; stallCounted = false
        PlayStats.lastCodecError = ""
        showMsg(null)
        spinner.visibility = View.VISIBLE
        engine?.play(url, startMs)
    }

    /** cambio canale: numero e nome cambiano subito, il video parte quando smetti di premere (più veloce) */
    private var zapPending = false
    private val zapPlay = Runnable { zapPending = false; playCurrent(0) }
    private fun zap(delta: Int) {
        if (items.size < 2) return
        index = (index + delta + items.size) % items.size
        listChanged()
        veil.animate().cancel()
        veil.animate().alpha(0.55f).setDuration(120).start()
        ui.removeCallbacks(zapPlay)
        zapPending = true
        ui.postDelayed(zapPlay, 150)
        showOsd(false)
        osd.translationY = (if (delta > 0) dp(18) else -dp(18)).toFloat()
        osd.animate().translationY(0f).setDuration(200).start()
        flashNumber(if (item.num > 0) item.num.toString() else "")
    }

    private fun flashNumber(t: String) {
        if (t.isEmpty()) return
        zapView.text = t; zapView.visibility = View.VISIBLE
        zapView.scaleX = 1.35f; zapView.scaleY = 1.35f; zapView.alpha = 0.4f
        zapView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        ui.removeCallbacks(hideZap); ui.postDelayed(hideZap, 1500)
    }
    private val hideZap = Runnable { zapView.visibility = View.GONE }

    private val numRunnable = Runnable {
        val n = numBuf.toIntOrNull(); numBuf = ""
        zapView.visibility = View.GONE
        val i = items.indexOfFirst { it.num == n }
        if (i >= 0 && i != index) jumpTo(i) else if (n != null && i < 0) toast("Canale $n non trovato")
    }

    /** salto diretto a un canale (numero, lista canali, canale precedente): parte subito, stessa strada dello zapping */
    private fun jumpTo(i: Int) {
        if (i !in items.indices || closing) return
        ui.removeCallbacks(zapPlay); zapPending = false
        index = i
        listChanged()
        veil.animate().cancel()
        veil.animate().alpha(0.55f).setDuration(120).start()
        playCurrent(0)
        showOsd(false)
        flashNumber(if (item.num > 0) item.num.toString() else "")
    }

    private fun hasPrev(): Boolean = prevIndex in items.indices && prevIndex != index

    /** tasto «canale precedente» (LAST): alterna tra gli ultimi due canali visti */
    private fun previousChannel() {
        if (!isLive) return
        // durante uno zapping non ancora partito, «precedente» è il canale ancora in onda
        val t = if (zapPending && playingIndex in items.indices && playingIndex != index) playingIndex else prevIndex
        if (t in items.indices && t != index) jumpTo(t) else toast("Nessun canale precedente")
    }

    private fun switchEngine(name: String, byUser: Boolean) {
        val pos = if (isLive) 0L else if (seekTarget >= 0) seekTarget else engine?.position() ?: 0L
        if (byUser) userChangedEngine = true
        startEngine(name)
        if (engine != null) startUrl(currentUrl, pos)
        updateInfo()
    }

    /* EngineListener */
    // l'activity resta l'ascoltatore «di riserva» (motori creati altrove): eventi del caricamento attuale
    override fun onBuffering(buffering: Boolean) = evBuffering(engineGen, playTok, buffering)
    override fun onReady() = evReady(engineGen, playTok)
    override fun onFirstFrame() = evFirstFrame(engineGen, playTok)
    override fun onEnded() = evEnded(engineGen, playTok)
    override fun onError(message: String) = evError(engineGen, playTok, message)

    private fun evBuffering(g: Int, t: Int, buffering: Boolean) {
        ui.post {
            if (!live(g, t)) return@post
            spinner.visibility = if (buffering) View.VISIBLE else View.GONE
            if (buffering == isBuffering) return@post
            isBuffering = buffering
            if (!buffering) { bufferingSince = 0L; return@post }
            bufferingSince = System.currentTimeMillis(); stallCounted = false
        }
    }

    private fun evReady(g: Int, t: Int) { ui.post { if (!live(g, t)) return@post; spinner.visibility = View.GONE; showMsg(null); retries = 0; readyOnce = true; isBuffering = false; bufferingSince = 0L; veil.animate().alpha(0f).setDuration(260).start() } }
    private fun evFirstFrame(g: Int, t: Int) { ui.post { if (g != engineGen || closing) return@post; frameSeen = true; veil.animate().alpha(0f).setDuration(260).start() } }

    private fun evEnded(g: Int, t: Int) {
        ui.post {
            if (!live(g, t)) return@post
            if (isLive) { if (retries < 3) { retries++; startUrl(currentUrl, 0) } }
            else if (autoNext && index < items.size - 1) {
                index++
                toast("Episodio successivo: ${item.title}")
                veil.animate().alpha(0.7f).setDuration(150).start()
                playCurrent(0)
                showOsd(false)
            } else finishWithResult()
        }
    }

    private fun evError(g: Int, t: Int, message: String) {
        ui.post {
            if (!live(g, t)) return@post
            // un errore va gestito subito, ma non due volte di fila per lo stesso tentativo
            if (System.currentTimeMillis() - lastActionAt > 1500L) recCooldownUntil = 0L
            if (!recover("error", message)) {
                if (pendingEvt != null) closeEvt("non risolto") else PlayStats.event(try { JSONObject().put("t", System.currentTimeMillis()).put("problem", "error").put("action", "nessuna soluzione").put("err", message.take(120)).put("title", item.title.take(120)).put("id", item.id.take(60)).put("k", kind).put("engine", engineName).put("codec", PlayStats.codec).put("outcome", "non risolto") } catch (_: Exception) { JSONObject() })
                spinner.visibility = View.GONE; showMsg("Impossibile riprodurre questo contenuto\n($message)")
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Menu                                                                */
    /* ------------------------------------------------------------------ */
    private fun choose(title: String, list: List<Track>, empty: String, onPick: (Track) -> Unit) {
        if (list.isEmpty()) { toast(empty); return }
        if (closing || isFinishing) return
        val labels = list.map { it.label }.toTypedArray()
        val checked = list.indexOfFirst { it.selected }
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which -> d.dismiss(); if (!closing && which in list.indices) { onPick(list[which]); touchOsd() } }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun menuAudio() {
        val list = engine?.audioTracks().orEmpty()
        if (list.size == 1) { toast("Questo contenuto ha una sola traccia audio: ${list[0].label}"); return }
        choose("Traccia audio", list, "Nessuna traccia audio disponibile (attendi l'avvio del video)") { engine?.selectAudio(it.id); toast("Audio: ${it.label}") }
    }

    private fun menuQuality() = choose("Qualità video", engine?.qualities().orEmpty(), "Qualità non disponibile") {
        engine?.selectQuality(it.id); toast("Qualità: ${it.label}")
    }

    private fun menuSubtitles() = choose("Sottotitoli", engine?.subtitles().orEmpty(), "Nessun sottotitolo disponibile") {
        engine?.selectSubtitle(it.id)
    }

    private fun menuAspect() {
        val opts = listOf(Track("fit", "Adatta allo schermo", aspect == "fit"), Track("fill", "Riempi (deforma)", aspect == "fill"), Track("zoom", "Zoom (taglia i bordi)", aspect == "zoom"))
        choose("Formato immagine", opts, "") { aspect = it.id; engine?.setAspect(it.id) }
    }

    private fun menuEngine() {
        val opts = listOf(Track("exo", "ExoPlayer", true))
        choose("Motore di riproduzione", opts, "") { if (it.id != engineName) { switchEngine(it.id, true); toast("Motore: ${engineLabel(it.id)}") } }
    }

    /* ------------------------------------------------------------------ */
    /* Telecomando                                                         */
    /* ------------------------------------------------------------------ */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (closing) return true
        if (listOpen && listKey(keyCode, event)) return true
        if (::skipView.isInitialized && skipView.visibility == View.VISIBLE && osd.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) { skipIntro(); return true }
        val barFocused = osd.visibility == View.VISIBLE && bar.hasFocus()
        if (barFocused) {
            touchOsd()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { hideOsd(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (isLive) zap(-1) else showOsd(false)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (isLive) zap(1) else showOsd(true)
            // film: le pressioni si sommano e il salto avviene quando smetti di premere (un solo caricamento)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (isLive) showOsd(false) else seekStep(-1, event)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (isLive) showOsd(false) else seekStep(1, event)
            // diretta a schermo pulito: OK apre la lista canali (come nelle app TV); con le info visibili apre il menu
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (isLive && osd.visibility != View.VISIBLE) openList() else showOsd(true)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> showOsd(true)
            KeyEvent.KEYCODE_GUIDE -> if (isLive) openList() else showOsd(true)
            KeyEvent.KEYCODE_LAST_CHANNEL -> previousChannel()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> { commitSeek(); engine?.togglePause(); showOsd(false) }
            KeyEvent.KEYCODE_CAPTIONS -> menuSubtitles()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> menuAudio()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!isLive) return true
                if (numBuf.length >= 5) numBuf = ""
                numBuf += (keyCode - KeyEvent.KEYCODE_0).toString()
                zapView.text = numBuf; zapView.visibility = View.VISIBLE
                ui.removeCallbacks(numRunnable)
                // se nessun canale ha un numero più lungo che inizia così, si parte quasi subito
                val b = numBuf
                val more = numStrs.any { s -> s.length > b.length && s.startsWith(b) }
                ui.postDelayed(numRunnable, if (more) 1500L else 400L)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /* ------------------------------------------------------------------ */
    /* Ricerca nei film: pressioni accumulate, un solo salto               */
    /* ------------------------------------------------------------------ */
    private var seekTarget = -1L
    private var seekDir = 0
    private var seekStreak = 0
    private var seekLastPress = 0L
    private var seekHold = -1L
    private var seekHoldUntil = 0L
    private val seekCommit = Runnable { commitSeek() }

    /** Sinistra/Destra (anche tenuti premuti): il passo cresce 10 s → 30 s → 1 min → 3 min */
    private fun seekStep(dir: Int, event: KeyEvent?) {
        val e = engine ?: return
        val now = SystemClock.uptimeMillis()
        val repeat = event != null && event.repeatCount > 0
        if (repeat && now - seekLastPress < 140) return // tasto tenuto: un passo ogni 140 ms circa
        if (dir != seekDir || now - seekLastPress > 1500) seekStreak = 0
        seekDir = dir
        seekLastPress = now
        seekStreak++
        val step = when {
            seekStreak <= 3 -> 10_000L
            seekStreak <= 6 -> 30_000L
            seekStreak <= 10 -> 60_000L
            else -> 180_000L
        }
        seekAdd(dir * step, e)
    }

    /** aggiunge [delta] alla posizione scelta (mostrata subito); il salto vero parte 450 ms dopo l'ultima pressione */
    private fun seekAdd(delta: Long, eng: Engine? = engine) {
        val e = eng ?: return
        if (closing) return
        val d = e.duration()
        val base = if (seekTarget >= 0) seekTarget else e.position()
        var t = maxOf(0L, base + delta)
        if (d > 0) t = minOf(t, maxOf(0L, d - 2000))
        seekTarget = t
        ui.removeCallbacks(seekCommit)
        ui.postDelayed(seekCommit, 450)
        showOsd(false)
    }

    private fun commitSeek() {
        ui.removeCallbacks(seekCommit)
        val t = seekTarget
        seekTarget = -1L
        if (t < 0) return
        val e = engine ?: return
        startAt = System.currentTimeMillis() // il caricamento dopo un salto non è un «blocco»
        e.seekTo(t)
        seekHold = t
        seekHoldUntil = SystemClock.uptimeMillis() + 1500
        if (::osd.isInitialized && osd.visibility == View.VISIBLE) updateTime()
    }

    private fun updateRec() { if (::tRec.isInitialized) tRec.visibility = View.GONE } // niente registrazioni in Kairos TV

    /* ------------------------------------------------------------------ */
    /* Lista canali (OK durante la diretta)                                */
    /* ------------------------------------------------------------------ */
    private var listOpen = false
    private var listSel = 0
    private lateinit var listPanel: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var listView: ListView
    private val listAdapter = ChannelAdapter()
    private val listHide = Runnable { closeList() }
    private val rowH: Int get() = dp(66)

    /** riga dell'elenco ↔ canale: con un canale precedente, la prima riga è «↩ Canale precedente» */
    private fun listOffset(): Int = if (hasPrev()) 1 else 0
    private fun idxOf(pos: Int): Int = if (hasPrev() && pos == 0) prevIndex else pos - listOffset()

    private fun buildList() {
        if (::listPanel.isInitialized) return
        val w = (resources.displayMetrics.widthPixels * 0.40f).toInt().coerceIn(dp(300), dp(560))
        listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(12), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(246, 8, 8, 12), Color.argb(228, 10, 10, 14)))
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
            visibility = View.GONE
            isClickable = true // i tocchi sul pannello non arrivano allo schermo sotto
        }
        listTitle = text(14f, Color.argb(220, 255, 255, 255), true).apply {
            letterSpacing = 0.14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(6), dp(12))
        }
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            setSelector(ColorDrawable(Color.TRANSPARENT))
            cacheColorHint = Color.TRANSPARENT
            isFocusable = false
            isFocusableInTouchMode = false
            itemsCanFocus = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adapter = listAdapter
            setOnItemClickListener { _, _, pos, _ -> pickList(pos) }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) { if (listOpen) touchList() }
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
            })
        }
        listPanel.addView(listTitle)
        listPanel.addView(listView)
        root.addView(listPanel, root.indexOfChild(spinner).coerceAtLeast(0))
    }

    private fun openList() {
        if (!isLive || items.isEmpty() || closing) return
        buildList()
        hideOsd()
        currentFocus?.clearFocus()
        listOpen = true
        listSel = index + listOffset()
        listTitle.text = (if (item.group.isNotBlank()) item.group.uppercase(Locale.ITALIAN) + "  ·  " else "") + "${items.size} CANALI"
        listAdapter.notifyDataSetChanged()
        listPanel.animate().cancel()
        listPanel.visibility = View.VISIBLE
        listPanel.alpha = 0f
        listPanel.translationX = -dp(36).toFloat()
        listPanel.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(170).start()
        centerList()
        listView.post { if (listOpen) centerList() } // dopo la prima misura la posizione è esatta
        touchList()
    }

    private fun closeList() {
        ui.removeCallbacks(listHide)
        if (!listOpen) return
        listOpen = false
        listPanel.animate().cancel()
        listPanel.animate().alpha(0f).translationX(-dp(36).toFloat()).setStartDelay(0).setDuration(140)
            .withEndAction { if (!listOpen) { listPanel.visibility = View.GONE; listPanel.alpha = 1f; listPanel.translationX = 0f } }.start()
    }

    private fun touchList() { ui.removeCallbacks(listHide); ui.postDelayed(listHide, 8000) }

    /** la riga scelta resta al centro: scorrimento immediato anche con migliaia di canali */
    private fun centerList() {
        val h = listView.height.takeIf { it > 0 } ?: (root.height - dp(70)).coerceAtLeast(rowH)
        listView.setSelectionFromTop(listSel.coerceIn(0, maxOf(0, listAdapter.count - 1)), ((h - rowH) / 2).coerceAtLeast(0))
    }

    private fun moveSel(delta: Int) {
        val n = listAdapter.count
        if (n == 0) return
        listSel = ((listSel + delta) % n + n) % n
        listAdapter.notifyDataSetChanged()
        centerList()
        touchList()
    }

    private fun pickList(pos: Int) {
        val i = idxOf(pos)
        closeList()
        if (i !in items.indices) return
        if (i == index && !zapPending) { showOsd(false); return }
        jumpTo(i)
    }

    /** tasti con la lista aperta; false = il tasto prosegue normalmente */
    private fun listKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveSel(if (event.repeatCount > 15) -3 else -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveSel(if (event.repeatCount > 15) 3 else 1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveSel(-8)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveSel(8)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (event.repeatCount == 0) pickList(listSel) }
            KeyEvent.KEYCODE_DPAD_LEFT -> closeList()
            KeyEvent.KEYCODE_DPAD_RIGHT -> touchList()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { closeList(); showOsd(true) }
            KeyEvent.KEYCODE_BACK -> return false // chiude la lista tramite il gestore di Indietro
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> return false
            else -> { closeList(); return false }
        }
        return true
    }

    /** programma in onda: titolo e avanzamento 0..1000 (-1 = sconosciuto) */
    private fun nowOf(it: Item): Pair<String, Int> {
        val e = it.epg ?: return "" to -1
        val now = System.currentTimeMillis() / 1000
        val t0 = e.optString(0)
        val s0 = e.optLong(1); val e0 = e.optLong(2)
        if (t0.isNotBlank() && (e0 <= 0 || now < e0)) {
            val f = if (e0 > s0 && s0 > 0) (((now - s0) * 1000) / (e0 - s0)).toInt().coerceIn(0, 1000) else -1
            return t0 to f
        }
        // il programma indicato è finito: ora va in onda il successivo
        val t1 = if (e.length() >= 4) e.optString(3) else ""
        return t1 to -1
    }

    private class Row(val root: LinearLayout, val num: TextView, val logo: ImageView, val letter: TextView, val name: TextView, val prog: TextView, val bar: ProgressBar, val selBg: GradientDrawable)

    private fun makeRow(): Row {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(12), 0)
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
        }
        val num = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
        }
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(34, 255, 255, 255)) }
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(44)).apply { marginEnd = dp(14) }
        }
        val letter = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(210, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        box.addView(letter)
        box.addView(logo)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val prog = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.argb(175, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(5) }
        }
        col.addView(name)
        col.addView(prog)
        col.addView(bar)
        r.addView(num)
        r.addView(box)
        r.addView(col)
        val sel = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(52, 255, 255, 255)); setStroke(dp(2), ACCENT) }
        return Row(r, num, logo, letter, name, prog, bar, sel).also { r.tag = it }
    }

    private fun bindRow(row: Row, pos: Int) {
        val prevRow = hasPrev() && pos == 0
        val i = idxOf(pos)
        val it = items.getOrNull(i) ?: return
        val playing = !prevRow && i == index
        row.num.text = if (prevRow) "↩" else if (it.num > 0) it.num.toString() else ""
        row.num.setTextColor(if (playing) ACCENT else Color.argb(170, 255, 255, 255))
        row.name.text = if (prevRow) "Canale precedente: ${it.title}" else it.title
        row.name.setTextColor(if (playing) ACCENT else Color.WHITE)
        val (title, frac) = nowOf(it)
        row.prog.text = title.ifBlank { it.group }
        row.prog.visibility = if (row.prog.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (frac >= 0) { row.bar.visibility = View.VISIBLE; row.bar.progress = frac } else row.bar.visibility = View.INVISIBLE
        row.root.background = if (pos == listSel) row.selBg else null
        // logo (scaricato in sottofondo e tenuto in memoria) oppure l'iniziale del nome
        val clean = it.title.substringAfter('|', it.title).trim()
        row.letter.text = (clean.firstOrNull { c -> c.isLetterOrDigit() } ?: '?').uppercaseChar().toString()
        val bmp = if (it.logo.isNotEmpty()) logos.get(it.logo) else null
        if (bmp != null) {
            row.logo.setImageBitmap(bmp); row.logo.visibility = View.VISIBLE; row.letter.visibility = View.INVISIBLE
        } else {
            row.logo.setImageDrawable(null); row.logo.visibility = View.GONE; row.letter.visibility = View.VISIBLE
        }
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount(): Int = if (items.isEmpty()) 0 else items.size + listOffset()
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: makeRow()
            bindRow(row, position)
            return row.root
        }
    }

    /* loghi: piccolo scaricatore con cache in memoria (2 thread, prima i più recenti = quelli visibili) */
    private val logosLazy = lazy { LogoCache(userAgent()) }
    private val logos: LogoCache get() = logosLazy.value

    private inner class LogoCache(private val ua: String) {
        // condivisa tra le aperture del player: i loghi non si riscaricano e non si ridecodificano ogni volta
        private val mem = LogoMem.mem
        private val failed = LogoMem.failed
        private val loading = HashSet<String>()
        private var refreshQueued = false
        private val pool = java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
            object : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                override fun offer(e: Runnable): Boolean = offerFirst(e)
            },
        )

        /** sul thread principale: il logo se è già pronto, altrimenti lo chiede e restituisce null */
        fun get(url: String): Bitmap? {
            mem.get(url)?.let { return it }
            if (url in failed || url in loading || loading.size >= 40) return null
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) { failed.add(url); return null }
            loading.add(url)
            try {
                pool.execute {
                    val b = fetch(url)
                    ui.post {
                        loading.remove(url)
                        if (b != null) mem.put(url, b) else failed.add(url)
                        refresh()
                    }
                }
            } catch (_: Exception) { loading.remove(url) }
            return null
        }

        private fun refresh() {
            if (refreshQueued || !listOpen) return
            refreshQueued = true
            ui.postDelayed({ refreshQueued = false; if (listOpen) listAdapter.notifyDataSetChanged() }, 150)
        }

        private fun fetch(url: String): Bitmap? {
            var conn: java.net.HttpURLConnection? = null
            try {
                var target = java.net.URL(url)
                var hops = 0
                while (true) {
                    val c = target.openConnection() as java.net.HttpURLConnection
                    conn = c
                    c.connectTimeout = 6000
                    c.readTimeout = 8000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", ua)
                    val code = c.responseCode
                    val loc = c.getHeaderField("Location")
                    if (code in 300..399 && loc != null && hops < 4) { c.disconnect(); target = java.net.URL(target, loc); hops++; continue }
                    if (code != 200) return null
                    break
                }
                val bytes = conn!!.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 2_000_000) return null // non è un logo
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) return null
                var s = 1
                while (o.outWidth / (s * 2) >= 140 && o.outHeight / (s * 2) >= 70) s *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) {
                return null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        fun shutdown() { try { pool.shutdownNow() } catch (_: Exception) {} }
    }

    /* ------------------------------------------------------------------ */
    /* Ciclo di vita                                                       */
    /* ------------------------------------------------------------------ */
    private var stoppedAt = -1L

    override fun onStop() {
        super.onStop()
        if (!::osd.isInitialized) return // chiusa prima di costruire l'interfaccia
        completeHandoff(true)
        stopped = true
        ui.removeCallbacks(watchRunnable); cancelRetry()
        // in secondo piano niente lavoro inutile ogni secondo (statistiche, orologio dell'OSD)
        ui.removeCallbacks(statsRunnable); ui.removeCallbacks(tickRunnable)
        PlayStats.stop()
        isBuffering = false; bufferingSince = 0L; freezeSec = 0; lastFrames = -1L; okSince = 0L
        if (zapPending) { ui.removeCallbacks(zapPlay); zapPending = false; markPlaying(); resetRecovery(); currentUrl = item.url }
        val pendingSeek = seekTarget
        ui.removeCallbacks(seekCommit); seekTarget = -1L
        val e = engine ?: return
        stoppedAt = if (isLive) 0L else if (pendingSeek >= 0) pendingSeek else e.position()
        e.stop()
    }

    override fun onRestart() {
        super.onRestart()
        stopped = false
        if (stoppedAt >= 0 && currentUrl.isNotEmpty()) startUrl(currentUrl, stoppedAt)
        stoppedAt = -1L
        ui.removeCallbacks(watchRunnable); ui.postDelayed(watchRunnable, 1000)
        if (engine != null) statsStart()
        if (::osd.isInitialized && osd.visibility == View.VISIBLE) { ui.removeCallbacks(tickRunnable); ui.post(tickRunnable) }
    }

    private var closing = false
    /** il player arrivato dall'anteprima: solo questo può tornare nel riquadro (gli altri appartengono a questa schermata) */
    private var adopted: ExoEngine? = null
    private fun finishWithResult() {
        if (closing) return
        closing = true
        cancelRetry()
        ui.removeCallbacks(zapPlay)
        ui.removeCallbacks(listHide)
        val pendingSeek = seekTarget
        ui.removeCallbacks(seekCommit); seekTarget = -1L
        try { dialog?.dismiss() } catch (_: Exception) {}
        completeHandoff(true)
        val e = engine
        val r = JSONObject()
            .put("kind", kind)
            .put("id", item.id)
            .put("pos", (if (pendingSeek >= 0) pendingSeek else e?.position() ?: 0L) / 1000)
            .put("dur", (e?.duration() ?: 0L) / 1000)
            .put("height", e?.videoHeight() ?: 0)
        if (userChangedEngine) { r.put("engine", engineName); r.put("engineKey", engineKey) }
        if (introChanged && introKey.isNotEmpty()) { r.put("introKey", introKey); r.put("introAuto", introAuto); intro?.let { r.put("intro", org.json.JSONArray().put(it[0] / 1000).put(it[1] / 1000)) } ?: r.put("intro", org.json.JSONArray()) }
        setResult(android.app.Activity.RESULT_OK, Intent().putExtra(RESULT, r.toString()))
        val box = Handoff.box
        val v = e?.view
        if (isLive && !zapPending && e is ExoEngine && e === adopted && e.isActive() && box != null && v != null && v.parent === root && root.width > 0) {
            // il video torna nel riquadro dell'anteprima e continua lì
            osd.visibility = View.GONE; brandView.visibility = View.GONE; zapView.visibility = View.GONE; spinner.visibility = View.GONE; veil.alpha = 0f; resView.visibility = View.GONE
            if (::listPanel.isInitialized) listPanel.visibility = View.GONE
            msgView.visibility = View.GONE
            setOpaque(false)
            root.setBackgroundColor(Color.TRANSPARENT)
            val W = root.width; val H = root.height
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    v.layoutParams = FrameLayout.LayoutParams((W + (box[2] - W) * f).toInt(), (H + (box[3] - H) * f).toInt()).apply {
                        gravity = Gravity.TOP or Gravity.START; leftMargin = (box[0] * f).toInt(); topMargin = (box[1] * f).toInt()
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        try {
                            if (engine === e && !isDestroyed) {
                                e.l = NoopListener
                                Handoff.id = item.id
                                Handoff.back = e
                                engine = null
                                // La schermata principale crea subito la sua vista nel riquadro, sotto questa finestra:
                                // il video passa lì, e solo quando vi è visibile la nostra (ferma sull'ultimo
                                // fotogramma, nello stesso punto) viene tolta e questa schermata si chiude.
                                val host = Handoff.host
                                if (host != null && host.adoptNow(Runnable { finishNow() })) {
                                    ui.postDelayed({ finishNow() }, 1800) // sicurezza
                                    return
                                }
                                // altrimenti la riprende al ritorno (risultato dell'activity)
                            }
                        } catch (_: Exception) {}
                        finishNow()
                    }
                })
                // un frame perché la finestra sotto torni visibile (è stata resa traslucida ora), poi l'animazione
                root.post { start() }
            }
            return
        }
        // nessun ritorno nel riquadro: l'interfaccia sotto viene coperta di nero e poi sfuma, niente lampi
        try { Handoff.host?.coverUi() } catch (_: Exception) {}
        finishNow()
    }

    private var done = false
    private fun finishNow() {
        if (done) return
        done = true
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /** «Disconnetti» dal pannello: chiusura immediata, il video non torna nell'anteprima */
    fun killByPanel() {
        if (closing || done) return
        closing = true
        ui.removeCallbacks(zapPlay)
        ui.removeCallbacks(listHide)
        try { dialog?.dismiss() } catch (_: Exception) {}
        completeHandoff(true)
        setResult(android.app.Activity.RESULT_OK, Intent().putExtra(RESULT, JSONObject().put("kind", kind).put("id", item.id).put("kicked", true).toString()))
        finishNow()
    }

    override fun onDestroy() {
        if (current === this) current = null
        closeEvt("chiuso")
        closing = true // un errore tardivo del motore non deve crearne un altro su un'activity distrutta
        ui.removeCallbacksAndMessages(null)
        PlayStats.stop()
        if (::osd.isInitialized) completeHandoff(true)
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
        if (logosLazy.isInitialized()) logos.shutdown()
        engine?.release()
        engine = null
        super.onDestroy()
    }
}

/** cache dei loghi dei canali per tutto il processo (solo bitmap, nessun riferimento alle activity) */
object LogoMem {
    val mem = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
}
