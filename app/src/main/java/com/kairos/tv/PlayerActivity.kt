package com.kairos.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Indirizzi dei contenuti, sempre con il server attuale della playlist (cambio DNS dal pannello) */
object Play {
    fun liveUrls(r: Db.Row): List<String> {
        val p = K.pl(r.s("pl")) ?: return emptyList()
        val sid = r.s("sid")
        if (p.isXtream && sid.isNotEmpty()) return listOfNotNull(p.liveUrl(sid, "m3u8"), p.liveUrl(sid, "ts"))
        val u = r.s("url")
        return if (u.isEmpty()) emptyList() else listOf(p.fix(u))
    }

    fun movieUrl(r: Db.Row): String? {
        val p = K.pl(r.s("pl")) ?: return null
        if (p.isXtream && r.s("sid").isNotEmpty()) return p.movieUrl(r.s("sid"), r.s("ext"))
        return r.s("url").ifEmpty { null }?.let { p.fix(it) }
    }

    fun vod(a: Activity, kind: String, id: String, title: String, sub: String, url: String, pos: Long, recent: JSONObject, queue: JSONArray?) {
        val i = Intent(a, PlayerActivity::class.java)
            .putExtra("kind", kind).putExtra("id", id).putExtra("title", title).putExtra("sub", sub)
            .putExtra("url", url).putExtra("pos", pos).putExtra("recent", recent.toString())
        if (queue != null && queue.length() > 0) i.putExtra("queue", queue.toString())
        a.startActivity(i)
    }

    /** da «Continua a guardare» */
    fun resume(a: Activity, o: JSONObject) {
        val p = K.pl(o.optString("pl"))
        if (p == null) { Ui.toast("Questo contenuto non è più nella tua playlist"); return }
        val kind = o.optString("kind", "movie")
        val sid = o.optString("sid")
        val stored = o.optString("url")
        val url = when {
            stored.isNotEmpty() && !p.isXtream -> p.fix(stored)
            kind == "series" -> p.episodeUrl(sid, o.optString("ext"))
            else -> p.movieUrl(sid, o.optString("ext"))
        } ?: run { Ui.toast("Indirizzo non valido"); return }
        val id = o.optString("id")
        vod(a, kind, id, o.optString("title"), "", url, Store.position(id), o, null)
    }
}

/**
 * Riproduzione a schermo intero.
 * Diretta: ▲▼ (o CH+/CH−) cambia canale, i numeri vanno al canale, OK mostra i comandi.
 * Film/serie: ◀▶ 10 secondi, OK pausa, la posizione resta salvata, l'episodio dopo parte da solo.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {
    companion object {
        /** dopo un calo della linea, per 10 minuti i canali partono già nella versione più leggera */
        @Volatile var lighterUntil = 0L
    }

    private lateinit var pv: PlayerView
    private lateinit var spin: ProgressBar
    private lateinit var msg: TextView
    private lateinit var zap: TextView
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var nameV: TextView
    private lateinit var subV: TextView
    private lateinit var logo: ImageView
    private lateinit var badge: TextView
    private lateinit var tStart: TextView
    private lateinit var tEnd: TextView
    private lateinit var time: ProgressBar
    private lateinit var info: TextView
    private lateinit var playPause: ImageButton

    private var player: ExoPlayer? = null
    private var live = false

    // diretta
    private var cat = Db.ALL
    private var ids = LongArray(0)
    private var idx = 0
    private var curId = 0L
    private var row: Db.Row? = null
    private var urls: List<String> = emptyList()
    private var urlIdx = 0
    private var tuneSeq = 0
    private var progs: List<Prog> = emptyList()
    private var zapBuf = ""

    // film / episodi
    private var kind = "movie"
    private var vodId = ""
    private var vodTitle = ""
    private var vodSub = ""
    private var vodUrl = ""
    private var startPos = 0L
    private var recent: JSONObject? = null
    private var queue = JSONArray()
    private var lastSave = 0L

    private var blocked = false
    private var reconnects = 0
    private var bufferingSince = 0L
    private val events = JSONArray()

    // recupero automatico (come KAIROS TV 2.0.3): immagine ferma, caricamento che si ripete, errore del lettore
    private var soft = false
    private var problem = ""
    private var problemErr = ""
    private var step = 0
    private var lastAction = ""
    private var fixAt = 0L
    private var firstReady = false
    private val stalls = ArrayList<Long>()
    private val stallsHour = ArrayList<Long>()
    private var lastRendered = -1
    private var lastPos = -1L
    private var frozen = 0
    private var lastStallAt = 0L
    private var lastFrameAt = 0L
    private var lastKind = ""
    private var lastStep = 0
    private var resolvedAt = 0L
    private var decoderProblem = false
    private var gaveUp = false
    private var seeking = false

    // qualità adattiva: versioni dello stesso canale (4K / FHD / HD / SD)
    private var baseRow: Db.Row? = null
    private var variants: List<Db.Variant> = emptyList()
    private var reduced = false
    private var reducedAt = 0L
    private var stayReduced = false
    private var tryingFull = false
    private var tryFullAt = 0L

    // diagnostica per il pannello
    private var decoderName = ""
    private var dropped = 0L
    private lateinit var notice: TextView
    private val noticeR = Runnable { notice.visibility = View.GONE }

    private val hideR = Runnable { hideOverlay() }
    private val tuneR = Runnable { if (ids.isNotEmpty()) tune(ids[idx]) }
    private val zapR = Runnable { commitZap() }
    private val retryR = Runnable { startUrl() }
    private val tickR = object : Runnable {
        override fun run() {
            updateTime()
            checkStall()
            checkFreeze()
            checkResolved()
            checkReduced()
            if (!live) savePos(false)
            App.main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        pv = findViewById(R.id.pv)
        spin = findViewById(R.id.spin)
        msg = findViewById(R.id.msg)
        zap = findViewById(R.id.zap)
        top = findViewById(R.id.top)
        bottom = findViewById(R.id.bottom)
        nameV = findViewById(R.id.name)
        subV = findViewById(R.id.sub)
        logo = findViewById(R.id.logo)
        badge = findViewById(R.id.badge)
        tStart = findViewById(R.id.tStart)
        tEnd = findViewById(R.id.tEnd)
        time = findViewById(R.id.time)
        info = findViewById(R.id.info)
        playPause = findViewById(R.id.playPause)
        notice = findViewById(R.id.notice)
        findViewById<View>(R.id.logoBox).clipToOutline = true
        clock(findViewById(R.id.clock))

        live = intent.getStringExtra("kind") == "live"
        val prev = findViewById<ImageButton>(R.id.prev)
        val next = findViewById<ImageButton>(R.id.next)
        findViewById<View>(R.id.back).setOnClickListener { leave() }
        playPause.setOnClickListener { togglePlay(); bump() }
        findViewById<View>(R.id.subs).setOnClickListener { tracks(C.TRACK_TYPE_TEXT) }
        findViewById<View>(R.id.audio).setOnClickListener { tracks(C.TRACK_TYPE_AUDIO) }
        val list = findViewById<View>(R.id.list)
        list.setOnClickListener { leave() }
        val hint = findViewById<TextView>(R.id.hint)
        if (live) {
            prev.setOnClickListener { zapBy(-1); bump() }
            next.setOnClickListener { zapBy(1); bump() }
            hint.text = "▲ ▼ cambia canale   ·   numeri: vai al canale   ·   INDIETRO: lista canali"
            cat = intent.getStringExtra("cat") ?: Db.ALL
            curId = intent.getLongExtra("rowId", 0)
            badge.text = "LIVE"
        } else {
            prev.setImageResource(R.drawable.ic_rew)
            next.setImageResource(R.drawable.ic_fwd)
            prev.setOnClickListener { seekBy(-10_000); bump() }
            next.setOnClickListener { seekBy(10_000); bump() }
            list.visibility = View.GONE
            hint.text = "◀ ▶ 10 secondi   ·   OK pausa   ·   ▼ audio e sottotitoli   ·   INDIETRO: esci"
            playPause.nextFocusDownId = R.id.subs
            playPause.nextFocusUpId = R.id.subs
            kind = intent.getStringExtra("kind") ?: "movie"
            vodId = intent.getStringExtra("id") ?: ""
            vodTitle = intent.getStringExtra("title") ?: ""
            vodSub = intent.getStringExtra("sub") ?: ""
            vodUrl = intent.getStringExtra("url") ?: ""
            startPos = intent.getLongExtra("pos", 0)
            recent = try { JSONObject(intent.getStringExtra("recent") ?: "") } catch (_: Exception) { null }
            queue = try { JSONArray(intent.getStringExtra("queue") ?: "[]") } catch (_: Exception) { JSONArray() }
            findViewById<View>(R.id.logoBox).visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            newPlayer()
            Pulse.stats = { stats() }
            if (live) {
                tune(curId)
                loadIds()
            } else startVod()
        }
        App.main.removeCallbacks(tickR)
        App.main.post(tickR)
    }

    override fun onStop() {
        super.onStop()
        App.main.removeCallbacks(tickR)
        App.main.removeCallbacks(retryR)
        App.main.removeCallbacks(tuneR)
        if (!live) {
            savePos(true)
            // al ritorno nell'app si riparte da qui, non dal punto di partenza iniziale
            player?.let { p -> if (p.currentPosition > 0) startPos = p.currentPosition }
        }
        // problema in corso e il cliente è uscito
        closeProblem()
        sendEvents()
        releasePlayer()
        Pulse.stats = null
        Pulse.watch(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(hideR)
        App.main.removeCallbacks(tuneR)
        App.main.removeCallbacks(zapR)
        App.main.removeCallbacks(retryR)
        sendEvents()
    }

    private fun newPlayer() {
        val p = Players.build(this, live, soft)
        player = p
        pv.player = p
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        pv.player = null
        try { p.removeListener(listener); p.removeAnalyticsListener(analytics); p.release() } catch (_: Exception) {}
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            this@PlayerActivity.decoderName = decoderName
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped += droppedFrames
        }
    }

    override fun onKicked() {
        blocked = true
        App.main.removeCallbacks(retryR)
        App.main.removeCallbacks(tuneR)
        player?.stop()
        Pulse.watch(null)
        Ui.dialog(this, "Visione interrotta", "Il tuo fornitore ha chiuso la visione su questo dispositivo.") { finish() }
    }

    /* ---------------- eventi del lettore ---------------- */
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> { spin.visibility = View.VISIBLE; if (bufferingSince == 0L) bufferingSince = mono() }
                Player.STATE_READY -> {
                    spin.visibility = View.GONE
                    msg.visibility = View.GONE
                    if (firstReady && bufferingSince > 0L && !seeking) stalled()
                    seeking = false
                    firstReady = true
                    reconnects = 0
                    bufferingSince = 0L
                }
                Player.STATE_ENDED -> {
                    bufferingSince = 0L
                    if (live) {
                        // il flusso si è chiuso: si ricollega, ma con pause sempre più lunghe e al massimo 5 volte
                        if (reconnects < 5 && !blocked) { reconnects++; App.main.postDelayed(retryR, 1500L * reconnects) }
                        else { spin.visibility = View.GONE; showMsg("Il canale si interrompe continuamente.\nProva più tardi o scegli un altro canale.") }
                    } else onEnded()
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) bufferingSince = 0L
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val h = videoSize.height
            val q = when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; h > 0 -> "SD"; else -> "" }
            badge.text = if (live) (if (q.isEmpty()) "LIVE" else "$q · LIVE") else q
            badge.visibility = if (badge.text.isEmpty()) View.GONE else View.VISIBLE
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                seeking = true
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // 4000-4999 = errori del decoder (lì serve la decodifica software), il resto è rete o formato
            recover("error", error.errorCodeName.removePrefix("ERROR_CODE_").lowercase(Locale.ROOT), decoderError = error.errorCode in 4000..4999)
        }
    }

    /* ---------------- recupero automatico ---------------- */
    /** orologio che non salta (le chiavette senza batteria tampone correggono l'ora all'avvio) */
    private fun mono(): Long = android.os.SystemClock.elapsedRealtime()

    /** un blocco (caricamento a metà visione): 3 in 75 secondi = la linea non regge */
    private fun stalled() {
        val now = mono()
        stalls.add(now); stallsHour.add(now); lastStallAt = now
        stalls.removeAll { now - it > 75_000 }
        if (stalls.size >= 3) { stalls.clear(); recover("stall", "3 blocchi in 75 s") }
    }

    /** un blocco di oltre 12 secondi (o un avvio di oltre 20), solo mentre il lettore sta davvero caricando */
    private fun checkStall() {
        val p = player ?: return
        if (bufferingSince == 0L || blocked) return
        if (p.playbackState != Player.STATE_BUFFERING) return
        val d = mono() - bufferingSince
        if ((firstReady && d > 12_000) || (!firstReady && d > 20_000)) {
            bufferingSince = 0L
            stallsHour.add(mono()); lastStallAt = mono()
            recover("stall", if (firstReady) "blocco di oltre 12 s" else "avvio lento")
        }
    }

    /** immagine ferma con l'audio che va avanti: nessun fotogramma nuovo per 4 secondi mentre il tempo avanza */
    private fun checkFreeze() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady || p.videoFormat == null) { frozen = 0; lastRendered = -1; return }
        // fine di un film: la traccia video spesso finisce qualche secondo prima dell'audio
        if (!live && p.duration > 0 && p.duration - p.currentPosition < 10_000) { frozen = 0; return }
        val c = p.videoDecoderCounters ?: return
        c.ensureUpdated()
        val n = c.renderedOutputBufferCount
        val pos = p.currentPosition
        frozen = if (n == lastRendered && pos != lastPos) frozen + 1 else 0
        if (n != lastRendered) lastFrameAt = mono()
        lastRendered = n
        lastPos = pos
        if (frozen >= 4) {
            frozen = 0
            SurfaceFix.learn(this)
            recover("freeze", "immagine ferma")
        }
    }

    /**
     * Problema risolto quando la visione è regolare abbastanza a lungo:
     * blocchi = 75 s senza blocchi; immagine ferma = fotogrammi che avanzano; errore = 10 s di visione.
     */
    private fun checkResolved() {
        if (problem.isEmpty() || fixAt == 0L) return
        val p = player ?: return
        val now = mono()
        if (!p.isPlaying || bufferingSince != 0L) return
        val ok = when (problem) {
            "stall" -> now - fixAt > 75_000 && now - lastStallAt > 75_000
            "freeze" -> now - fixAt > 10_000 && frozen == 0 && now - lastFrameAt < 2_000
            else -> now - fixAt > 10_000
        }
        if (!ok) return
        event(problem, lastAction, "risolto", problemErr)
        // se lo stesso problema torna entro 3 minuti si riparte dal passo successivo, non da capo
        lastKind = problem; lastStep = step; resolvedAt = now
        problem = ""
        fixAt = 0L
    }

    /** chiude il problema in corso (il cliente ha cambiato canale, episodio o è uscito) */
    private fun closeProblem() {
        if (problem.isNotEmpty()) event(problem, lastAction.ifEmpty { "nessuna" }, "chiuso", problemErr)
        problem = ""
        fixAt = 0L
        lastKind = ""
    }

    /**
     * Prova le soluzioni in ordine senza far uscire il cliente. L'ordine dipende dal problema:
     * immagine ferma o errore del decoder = dispositivo (decodifica software), blocchi = linea (versione più leggera).
     */
    private fun recover(kind: String, err: String, decoderError: Boolean = false) {
        val p = player ?: return
        if (blocked) return
        val now = mono()
        val fresh = problem.isEmpty()
        if (fresh) {
            problem = kind; problemErr = err; lastAction = ""
            step = if (kind == lastKind && now - resolvedAt < 180_000) lastStep else 0
            decoderProblem = decoderError
        } else {
            // una soluzione è appena partita: i blocchi e l'immagine ferma si ricontrollano dopo; gli errori no
            if (kind != "error" && now - fixAt < 4_000) return
            if (decoderError) decoderProblem = true
        }
        // tornati alla qualità piena e la linea non regge ancora: si resta su quella ridotta
        if (tryingFull && kind == "stall") {
            tryingFull = false
            stayReduced = true
            if (switchLighter(byLine = true)) { lastAction = "versione più leggera"; fixAt = now; return }
        }
        val ladder = if (live) when (problem) {
            "freeze" -> listOf("reload", "soft", "alt", "lighter")
            "stall" -> listOf("reload", "alt", "lighter")
            else -> listOf("reload", "alt", "soft", "lighter")
        } else when (problem) {
            "stall" -> listOf("reload", "reload")
            else -> listOf("reload", "soft")
        }
        // stesso problema tornato dopo l'ultima soluzione possibile: si ricomincia (una volta) dal primo passo
        if (fresh && step >= ladder.size) step = 0
        while (step < ladder.size) {
            val a = ladder[step++]
            if (applyFix(a)) { fixAt = mono(); return }
        }
        // provato tutto: ci si ferma (nessun nuovo tentativo finché il cliente non sceglie)
        event(problem, lastAction.ifEmpty { "nessuna" }, "non risolto", problemErr)
        problem = ""
        fixAt = 0L
        lastKind = ""
        bufferingSince = 0L
        gaveUp = true
        spin.visibility = View.GONE
        App.main.removeCallbacks(retryR)
        try { p.stop() } catch (_: Exception) {}
        showMsg(if (live) "Il canale non risponde in questo momento.\nProva più tardi o scegli un altro canale." else "Il contenuto non si apre ($err).\nRiprova più tardi.")
    }

    private fun applyFix(a: String): Boolean = when (a) {
        "reload" -> {
            lastAction = "ricaricato"
            notice("Ricarico…")
            App.main.removeCallbacks(retryR)
            App.main.postDelayed(retryR, 1_200)
            true
        }
        // decodifica software solo per immagine ferma o errori del decoder, mai per problemi di rete
        "soft" -> if (soft || (problem == "error" && !decoderProblem)) false else {
            lastAction = "decodifica software"
            notice("Provo la decodifica software…")
            setSoft(true, if (live) null else player?.currentPosition, restart = true)
            true
        }
        "alt" -> if (!live || urls.size < 2) false else {
            lastAction = "formato alternativo"
            notice("Provo il formato alternativo…")
            urlIdx = (urlIdx + 1) % urls.size
            startUrl()
            true
        }
        "lighter" -> if (switchLighter(byLine = problem == "stall")) { lastAction = "versione più leggera"; true } else false
        else -> false
    }

    /** decodifica software accesa/spenta: il lettore va ricreato. [restart] = riparte subito lo stesso contenuto */
    private fun setSoft(on: Boolean, pos: Long?, restart: Boolean = false) {
        if (soft == on) return
        soft = on
        if (player == null) return
        releasePlayer()
        newPlayer()
        if (restart) startUrl(pos)
    }

    private fun sendEvents() {
        if (events.length() == 0) return
        val e = JSONArray(events.toString())
        while (events.length() > 0) events.remove(0)
        bg { Panel.errors(e) }
    }

    /** versione più leggera dello stesso canale (mai una HEVC come ripiego di un canale che non lo è) */
    private fun lighterThan(r: Db.Row): Db.Variant? {
        val q = r.l("q").toInt()
        val hv = r.l("hevc") == 1L
        return variants.filter { it.q < q && (!it.hevc || hv) }.maxByOrNull { it.q }
    }

    /** [byLine]: la causa è la connessione (solo allora i canali successivi partono ridotti) */
    private fun switchLighter(byLine: Boolean): Boolean {
        val r = row ?: return false
        if (baseRow?.l("_id") != curId) return false // nel frattempo il cliente ha cambiato canale
        val v = lighterThan(r) ?: return false
        val now = mono()
        reduced = true
        reducedAt = now
        if (byLine) lighterUntil = now + 10 * 60_000L else stayReduced = true // versione piena guasta: non si riprova
        notice(if (byLine) "Qualità ridotta per la tua connessione" else "Passo a un'altra versione del canale", 5_000)
        tune(v.id, variant = true)
        return true
    }

    /** dopo 6 minuti senza blocchi si riprova la qualità piena; se torna a bloccarsi resta ridotta */
    private fun checkReduced() {
        val now = mono()
        if (tryingFull && now - tryFullAt > 120_000) tryingFull = false
        if (!live || !reduced || stayReduced || blocked || tryingFull || problem.isNotEmpty()) return
        if (now - reducedAt < 6 * 60_000L || now - lastStallAt < 6 * 60_000L) return
        val b = baseRow ?: return
        if (b.l("_id") != curId) return
        tryingFull = true
        tryFullAt = now
        reduced = false
        notice("Riprovo la qualità piena…", 3_000)
        tune(b.l("_id"), variant = true)
    }

    private fun notice(t: String, ms: Long = 3_500) {
        notice.text = t
        notice.visibility = View.VISIBLE
        App.main.removeCallbacks(noticeR)
        App.main.postDelayed(noticeR, ms)
    }

    /* ---------------- diagnostica in «Online ora» ---------------- */
    private fun stats(): JSONObject? {
        val p = player ?: return null
        val o = JSONObject().put("engine", "exo")
        val f = p.videoFormat
        if (f != null) {
            if (f.width > 0) o.put("res", "${f.width}x${f.height}")
            if (f.frameRate > 0) o.put("fps", f.frameRate.toDouble())
            if (f.bitrate > 0) o.put("bitrate", f.bitrate)
            o.put("codec", codecName(f.sampleMimeType ?: ""))
        }
        if (decoderName.isNotEmpty()) o.put("dec", if (decoderName.startsWith("OMX.google") || decoderName.startsWith("c2.android")) "sw" else "hw")
        val now = mono()
        stallsHour.removeAll { now - it > 3_600_000L }
        o.put("stallsH", stallsHour.size).put("buffers", stallsHour.size).put("dropped", dropped)
        o.put("reduced", if (reduced) Quality.label(row?.l("q")?.toInt() ?: 2) else "")
        return o
    }

    private fun codecName(mime: String): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/mpeg2" -> "MPEG-2"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        else -> mime.substringAfter('/').take(16)
    }

    private fun event(problem: String, action: String, outcome: String, err: String) {
        if (events.length() >= 20) return
        val base = baseRow ?: row
        val title = if (live) base?.s("name") ?: "" else vodTitle
        events.put(JSONObject().put("t", System.currentTimeMillis()).put("problem", problem).put("action", action).put("outcome", outcome)
            .put("err", err).put("title", title).put("id", if (live) base?.s("k") ?: "" else vodId).put("k", if (live) "live" else kind).put("engine", "exo"))
    }

    /* ---------------- diretta ---------------- */
    private fun loadIds() {
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs("live")
        val c = cat
        bg {
            val a = Db.liveIds(c, pls, kids, favs)
            ui {
                ids = a
                val i = a.indexOf(curId)
                idx = if (i >= 0) i else 0
            }
        }
    }

    /** [variant] = un'altra versione dello stesso canale (qualità adattiva), non un cambio canale */
    private fun tune(id: Long, variant: Boolean = false) {
        if (id <= 0) return
        if (!variant) {
            closeProblem() // problema del canale di prima: il cliente è passato oltre
            setSoft(false, null)
            dropped = 0
        }
        val my = ++tuneSeq
        if (!variant) curId = id
        App.main.removeCallbacks(retryR)
        msg.visibility = View.GONE
        spin.visibility = View.VISIBLE
        val lighter = !variant && mono() < lighterUntil
        val capped = !variant && App.max
        val keepVariants = variants
        bg {
            val r0 = Db.one("live", id) ?: return@bg
            val vars = if (variant) keepVariants else try { Db.variants(r0) } catch (_: Exception) { emptyList() }
            var r = r0
            var startReduced = false
            var startCapped = false
            if (lighter) {
                val q = r0.l("q").toInt()
                val hv = r0.l("hevc") == 1L
                vars.filter { it.q < q && (!it.hevc || hv) }.maxByOrNull { it.q }?.let { v -> Db.one("live", v.id)?.let { r = it; startReduced = true } }
            }
            // «Leggerissima»: se il canale ha anche la versione HD (non HEVC) si parte da quella invece di FHD / 4K
            if (!startReduced && capped && r0.l("q") >= 3L) {
                vars.firstOrNull { it.q == 2 && !it.hevc }?.let { v -> Db.one("live", v.id)?.let { r = it; startCapped = true } }
            }
            val list = Play.liveUrls(r)
            Vpns.awaitForced()
            val block = if (variant) null else Panel.stream("live", r0.s("name"))
            ui {
                if (isFinishing || my != tuneSeq || player == null) return@ui
                if (!variant) {
                    baseRow = r0
                    variants = vars
                    reduced = startReduced || startCapped
                    reducedAt = if (reduced) mono() else 0L
                    stayReduced = startCapped // scelta dell'utente: non si torna da soli alla versione piena
                    tryingFull = false
                    stalls.clear()
                    progs = emptyList()
                    if (startReduced) notice("Qualità ridotta per la tua connessione", 4_000)
                }
                row = r
                urls = list
                urlIdx = 0
                Store.lastLiveId = curId
                showLiveInfo(baseRow ?: r)
                blocked = block != null
                if (block != null) { player?.stop(); spin.visibility = View.GONE; showMsg(block); return@ui }
                if (list.isEmpty()) { spin.visibility = View.GONE; showMsg("Indirizzo del canale non valido."); return@ui }
                startUrl()
                if (!variant) {
                    Pulse.watch(Pulse.Watch("live", "", r0.s("name"), r0.s("k"), "", r0.s("pl")))
                    loadEpg(r0, my)
                }
            }
        }
    }

    private fun startUrl(pos: Long? = null) {
        val p = player ?: return
        if (blocked) return
        val u = if (live) urls.getOrNull(urlIdx) else vodUrl
        if (u.isNullOrEmpty()) return
        bufferingSince = mono() // il tempo di avvio si conta da qui (il lettore può restare in caricamento)
        firstReady = false
        gaveUp = false
        // film: si riparte dal punto in cui si era (un errore di rete non fa ricominciare da capo)
        if (live) p.setMediaItem(Players.item(u)) else p.setMediaItem(Players.item(u), maxOf(pos ?: p.currentPosition, 0L))
        p.prepare()
        p.playWhenReady = true
    }

    private fun showLiveInfo(r: Db.Row) {
        val num = r.l("num")
        nameV.text = (if (num > 0) "$num · " else "") + r.s("name")
        subV.text = ""
        info.text = ""
        Ui.img(logo, r.s("logo"), Ui.dp(this, 38), Ui.dp(this, 38))
        showTopOnly()
    }

    private fun loadEpg(r: Db.Row, my: Int) {
        val p = K.pl(r.s("pl"))
        val sid = r.s("sid")
        if (p?.xt == null || sid.isEmpty()) return
        bg {
            val list = Epg.get(p, sid, 3)
            ui {
                if (r.l("_id") != curId) return@ui // stesso canale anche se è cambiata la versione
                progs = list
                val now = list.firstOrNull()
                subV.text = now?.title ?: ""
                info.text = list.getOrNull(1)?.let { "Dopo · " + Ui.hm(it.start) + "  " + it.title } ?: ""
                updateTime()
                if (now != null) Pulse.watch(Pulse.Watch("live", now.title, r.s("name"), r.s("k"), "", r.s("pl")))
            }
        }
    }

    private fun zapBy(d: Int) {
        if (ids.isEmpty()) return
        idx = (idx + d).mod(ids.size)
        val id = ids[idx]
        bg {
            val r = Db.one("live", id) ?: return@bg
            ui { if (!isFinishing) { val num = r.l("num"); nameV.text = (if (num > 0) "$num · " else "") + r.s("name"); subV.text = ""; Ui.img(logo, r.s("logo"), Ui.dp(this, 38), Ui.dp(this, 38)); showTopOnly() } }
        }
        App.main.removeCallbacks(tuneR)
        App.main.postDelayed(tuneR, 350)
    }

    private fun digit(d: Int) {
        if (zapBuf.length >= 5) zapBuf = ""
        zapBuf += d.toString()
        zap.text = zapBuf
        zap.visibility = View.VISIBLE
        App.main.removeCallbacks(zapR)
        App.main.postDelayed(zapR, 1500)
    }

    private fun commitZap() {
        val n = zapBuf.toLongOrNull()
        zapBuf = ""
        zap.visibility = View.GONE
        if (n == null) return
        val pls = K.activeIds()
        val kids = K.kids
        bg {
            val ph = pls.joinToString(",") { "?" }
            val args = ArrayList(pls)
            args.add(n.toString())
            val id = try {
                Db.r.rawQuery("SELECT _id FROM live WHERE pl IN ($ph)" + (if (kids) " AND adult = 0" else "") + " AND num = ? ORDER BY _id LIMIT 1", args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            } catch (_: Exception) { 0L }
            ui {
                if (id <= 0) { Ui.toast("Canale $n non trovato"); return@ui }
                val i = ids.indexOf(id)
                if (i >= 0) idx = i
                tune(id)
            }
        }
    }

    /* ---------------- film ed episodi ---------------- */
    private fun startVod() {
        nameV.text = vodTitle
        subV.text = vodSub
        val st = kind
        val title = vodTitle
        spin.visibility = View.VISIBLE
        bg {
            Vpns.awaitForced()
            val block = Panel.stream(st, title)
            ui {
                val p = player ?: return@ui
                if (isFinishing) return@ui
                blocked = block != null
                if (block != null) { spin.visibility = View.GONE; showMsg(block); return@ui }
                if (vodUrl.isEmpty()) { spin.visibility = View.GONE; showMsg("Indirizzo non valido."); return@ui }
                firstReady = false
                bufferingSince = mono()
                stalls.clear()
                p.setMediaItem(Players.item(vodUrl), startPos.coerceAtLeast(0))
                p.prepare()
                p.playWhenReady = true
                Pulse.watch(Pulse.Watch(st, title, "", vodId, "", recent?.optString("pl") ?: ""))
                showOverlay()
            }
        }
    }

    private fun savePos(force: Boolean) {
        val p = player ?: return
        val t = System.currentTimeMillis()
        if (!force && t - lastSave < 10_000) return
        lastSave = t
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET || vodId.isEmpty()) return
        if (pos > dur * 95 / 100) {
            Store.setPosition(vodId, 0)
            Store.removeRecent(vodId)
            return
        }
        if (pos < 15_000) return
        Store.setPosition(vodId, pos, dur)
        recent?.let { Store.saveRecent(JSONObject(it.toString()).put("pos", pos).put("dur", dur)) }
    }

    private fun onEnded() {
        closeProblem()
        dropped = 0
        Store.setPosition(vodId, 0)
        Store.removeRecent(vodId)
        if (queue.length() == 0) { finish(); return }
        val n = queue.optJSONObject(0) ?: run { finish(); return }
        val rest = JSONArray()
        for (i in 1 until queue.length()) rest.put(queue.opt(i))
        queue = rest
        vodId = n.optString("id")
        vodTitle = n.optString("title")
        vodSub = n.optString("sub")
        vodUrl = n.optString("url")
        recent = n.optJSONObject("recent")
        startPos = Store.position(vodId)
        setSoft(false, null)
        startVod()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val d = p.duration
        var t = p.currentPosition + ms
        if (d > 0 && d != C.TIME_UNSET) t = t.coerceAtMost(d - 1000)
        seeking = true // il caricamento dopo uno spostamento non è un blocco della linea
        p.seekTo(t.coerceAtLeast(0))
        updateTime()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (live && !p.isPlaying && p.playbackState == Player.STATE_IDLE) { startUrl(); return }
        p.playWhenReady = !p.playWhenReady
    }

    /* ---------------- comandi a schermo ---------------- */
    private fun updateTime() {
        if (bottom.visibility != View.VISIBLE) return
        if (live) {
            val now = progs.firstOrNull { it.end > System.currentTimeMillis() }
            if (now != null) {
                tStart.text = Ui.hm(now.start)
                tEnd.text = Ui.hm(now.end)
                time.progress = now.progress() * 10
            } else { tStart.text = ""; tEnd.text = ""; time.progress = 0 }
            return
        }
        val p = player ?: return
        val d = p.duration
        val pos = p.currentPosition
        if (d > 0 && d != C.TIME_UNSET) {
            tStart.text = Ui.clock(pos)
            tEnd.text = "-" + Ui.clock(d - pos)
            time.progress = (pos * 1000 / d).toInt()
            info.text = vodSub.ifEmpty { "Durata " + Ui.minutes(d) }
        }
    }

    private fun showTopOnly() {
        if (bottom.visibility == View.VISIBLE) { bump(); return }
        (top as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        top.visibility = View.VISIBLE
        App.main.removeCallbacks(hideR)
        App.main.postDelayed(hideR, 3500)
    }

    private fun showOverlay() {
        (top as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        top.visibility = View.VISIBLE
        val wasHidden = bottom.visibility != View.VISIBLE
        bottom.visibility = View.VISIBLE
        updateTime()
        if (wasHidden) playPause.requestFocus()
        bump()
    }

    private fun bump() {
        App.main.removeCallbacks(hideR)
        App.main.postDelayed(hideR, if (live) 5000 else 4000)
    }

    private fun hideOverlay() {
        App.main.removeCallbacks(hideR)
        top.visibility = View.GONE
        bottom.visibility = View.GONE
    }

    private fun showMsg(t: String) {
        msg.text = t
        msg.visibility = View.VISIBLE
    }

    private fun tracks(type: Int) {
        val p = player ?: return
        val names = ArrayList<String>()
        val picks = ArrayList<Pair<androidx.media3.common.TrackGroup, Int>?>()
        var checked = -1
        if (type == C.TRACK_TYPE_TEXT) {
            names.add("Nessuno")
            picks.add(null)
            if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) checked = 0
        }
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                names.add(label(g.getTrackFormat(i), names.size))
                picks.add(g.mediaTrackGroup to i)
                if (g.isTrackSelected(i)) checked = names.size - 1
            }
        }
        if (type == C.TRACK_TYPE_TEXT && checked < 0) checked = 0
        if (names.isEmpty() || (type == C.TRACK_TYPE_TEXT && names.size == 1)) { Ui.toast(if (type == C.TRACK_TYPE_TEXT) "Nessun sottotitolo disponibile" else "Nessuna traccia audio alternativa"); return }
        App.main.removeCallbacks(hideR)
        Ui.choose(this, if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Sottotitoli", names.toTypedArray(), checked) { i ->
            val pick = picks[i]
            val b = p.trackSelectionParameters.buildUpon()
            if (pick == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else { b.setTrackTypeDisabled(type, false); b.setOverrideForType(TrackSelectionOverride(pick.first, pick.second)) }
            p.trackSelectionParameters = b.build()
            bump()
        }
    }

    private fun label(f: Format, n: Int): String {
        val lang = f.language?.let { l -> try { Locale(l).getDisplayLanguage(Locale.ITALY).replaceFirstChar { it.titlecase(Locale.ITALY) } } catch (_: Exception) { l } }
        val base = f.label ?: lang ?: "Traccia $n"
        val ch = if (f.channelCount >= 6) " · 5.1" else if (f.channelCount == 2) " · stereo" else ""
        return base + ch
    }

    /* ---------------- telecomando ---------------- */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shown = bottom.visibility == View.VISIBLE
        if (event.action == KeyEvent.ACTION_DOWN && shown) bump()
        // comandi nascosti: frecce e OK li gestisce il lettore, mai un pulsante rimasto selezionato
        if (!shown && isNavKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            return true
        }
        // film: con il cursore su Play/Pausa ◀ ▶ spostano di 10 secondi
        if (shown && !live && currentFocus === playPause && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isNavKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT ||
        k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shown = bottom.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (live) { zapBy(1); return true } }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (live) { zapBy(-1); return true } }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); showOverlay(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!live) { seekBy(30_000); showOverlay(); return true } }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (!live) { seekBy(-30_000); showOverlay(); return true } }
            KeyEvent.KEYCODE_MENU -> { showOverlay(); return true }
        }
        if (live && keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) { digit(keyCode - KeyEvent.KEYCODE_0); return true }
        if (shown) return super.onKeyDown(keyCode, event) // i tasti muovono il cursore tra i comandi
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (live) zapBy(1) else showOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (live) zapBy(-1) else showOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (live) showOverlay() else { seekBy(-10_000); showOverlay() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (live) showOverlay() else { seekBy(10_000); showOverlay() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!live) togglePlay()
                showOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            zap.visibility == View.VISIBLE -> { App.main.removeCallbacks(zapR); zapBuf = ""; zap.visibility = View.GONE }
            bottom.visibility == View.VISIBLE -> hideOverlay()
            else -> leave()
        }
    }

    /** si torna alla lista canali posizionati sul canale guardato */
    private fun leave() {
        if (live) setResult(RESULT_OK, Intent().putExtra("ch", curId))
        finish()
    }

    // tocco sullo schermo (telefoni): mostra/nasconde i comandi
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            if (bottom.visibility == View.VISIBLE) hideOverlay() else showOverlay()
            return true
        }
        return super.onTouchEvent(event)
    }
}
