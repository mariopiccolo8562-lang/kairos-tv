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
import androidx.media3.exoplayer.ExoPlaybackException
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
        /** per ogni portale l'ultimo formato che ha funzionato (0 = m3u8, 1 = ts) */
        val fmtPref = java.util.concurrent.ConcurrentHashMap<String, Int>()
        /** fotogrammi al secondo «standard»: la misura vicina (meno di 1,5) si arrotonda a uno di questi */
        private val STD_FPS = intArrayOf(24, 25, 30, 48, 50, 60, 100, 120)
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
    /** posizione buona dei film (aggiornata ogni secondo): dopo un errore si riparte da qui */
    private var lastGoodPos = 0L

    private var blocked = false
    private var reconnects = 0
    private var bufferingSince = 0L
    private val events = JSONArray()

    // recupero automatico (Kairos Adaptive §6): blocchi, immagine ferma, errore del lettore
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
    /** il problema in cura è un blocco lungo (scala con la ricarica per prima) e non una serie di blocchi brevi */
    private var longStall = false
    /** rimasti fuori dalla finestra della diretta: dopo 3 volte si passa all'altro formato */
    private var behindLive = 0
    /** traccia audio tolta perché il decoder non parte (AC-3, MP2…): il video continua */
    private var audioOff = false
    /** tentativi con l'altro formato dopo un «non trovato» del server, per questo canale */
    private var altTried = 0
    /** HLS senza estensione nell'indirizzo: si riprova dicendo a ExoPlayer che è HLS */
    private var forceHls = false
    /** il pannello ha chiuso la visione: niente più riproduzione in questa schermata */
    private var kicked = false

    // «cuscinetto crescente»: dopo un blocco breve si riparte solo con un po' più di buffer (nessun riavvio)
    private var cushioning = false
    private var cushionTarget = 0L
    private var cushionUntil = 0L
    /** gradini in più decisi dalla scala dei rimedi (+2 s ciascuno, fino a +6 s) */
    private var cushionExtra = 0

    // qualità adattiva: versioni dello stesso canale (4K / FHD / HD / SD)
    private var baseRow: Db.Row? = null
    private var variants: List<Db.Variant> = emptyList()
    private var reduced = false
    private var reducedAt = 0L
    private var stayReduced = false
    private var tryingFull = false
    private var tryFullAt = 0L
    /** ridotto per la connessione (non per guasto della versione piena o scelta dell'utente) */
    private var reducedByLine = false
    /** ritorni alla qualità piena falliti su questo canale: al secondo si resta giù */
    private var fullFails = 0
    /** tetto ABR messo a mano su un HLS a più qualità senza versioni sorelle */
    private var hlsCap = 0

    // Kairos Adaptive: classe effettiva con cui è stato costruito il lettore (se cambia al prossimo avvio si ricrea)
    private var playerCls = Line.NORMAL
    /** formato in uso: hls / ts / vod */
    private var fmt = ""
    /** trasferito dal misuratore con l'indirizzo in corso: i campioni valgono dopo 512 KB e 2 s */
    private var bwMs = 0L
    private var bwBytes = 0L

    // diagnostica per il pannello
    private var decoderName = ""
    private var dropped = 0L
    private var startAt = 0L
    private var playStartAt = 0L
    private var ttff = -1L
    private var fillT0 = 0L
    private var fillP0 = 0L
    private var fill = -1.0
    private var stallMs = 0L
    private lateinit var notice: TextView
    private val noticeR = Runnable { notice.visibility = View.GONE }

    // riquadro risoluzione e FPS dei canali in diretta (permesso «resInd» del cliente)
    private lateinit var resInd: TextView
    /** fine della comparsa a tempo: ~6 s dal primo fotogramma, ~4 s da un cambio di risoluzione */
    private var resUntil = 0L
    /** altezza del video vista in questo avvio: se cambia (qualità adattiva) il riquadro ricompare */
    private var resH = 0
    private val resR = Runnable { resShow() }
    /** FPS misurati dal decoder quando il flusso non li dichiara (canali TS): media smorzata, ultimo conteggio e istante */
    private var fpsAvg = 0.0
    private var fpsN = -1
    private var fpsT = 0L

    private val hideR = Runnable { hideOverlay() }
    private val tuneR = Runnable { if (ids.isNotEmpty()) tune(ids[idx]) }
    private val zapR = Runnable { commitZap() }
    private val retryR = Runnable { startUrl() }
    /** rete assente: si aspetta che torni e si riparte da soli */
    private val netWaitR = object : Runnable {
        override fun run() {
            if (isFinishing || blocked || player == null) return
            if (Net.type() == "none") { App.main.postDelayed(this, 3_000); return }
            msg.visibility = View.GONE
            notice("Connessione tornata, riprendo…")
            gaveUp = false
            startUrl()
        }
    }
    /** dopo «non risponde»: nuovo tentativo automatico ogni 30 s (al massimo 10 volte) */
    private var giveUps = 0
    private val giveUpR = Runnable {
        if (!isFinishing && !blocked && gaveUp && player != null && giveUps < 10) {
            giveUps++
            step = 0; lastKind = ""
            msg.visibility = View.GONE
            spin.visibility = View.VISIBLE
            startUrl()
        }
    }
    private val cushionR = object : Runnable {
        override fun run() {
            val p = player
            if (!cushioning || p == null) { cushioning = false; return }
            val st = p.playbackState
            if (p.totalBufferedDuration >= cushionTarget || mono() >= cushionUntil || (st != Player.STATE_READY && st != Player.STATE_BUFFERING)) {
                cushioning = false
                p.playWhenReady = true
                return
            }
            App.main.postDelayed(this, 250)
        }
    }
    private val tickR = object : Runnable {
        override fun run() {
            updateTime()
            checkStall()
            checkFreeze()
            checkResolved()
            checkReduced()
            checkFill()
            if (live) measureFps()
            if (!live) { savePos(false); goodPos() }
            App.main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // aggiornamento obbligatorio scaduto: niente visione, si torna al menu dove compare la finestra che blocca l'app
        if (Updater.must()) { finish(); return }
        // il buffer video viene prima delle immagini: cache svuotata, sfondi e riquadri grandi delle schermate sotto liberati
        Ui.freeForPlayer(this)
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
        resInd = findViewById(R.id.resInd)
        // quando la barra in alto si dispone il riquadro risoluzione scende sotto di lei
        top.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (resInd.visibility == View.VISIBLE) resPlace() }
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
        App.main.removeCallbacks(netWaitR)
        App.main.removeCallbacks(giveUpR)
        App.main.removeCallbacks(cushionR)
        App.main.removeCallbacks(tuneR)
        resHide()
        if (!live) {
            savePos(true)
            // al ritorno nell'app si riparte da qui, non dal punto di partenza iniziale
            player?.let { p -> if (p.currentPosition > 0) startPos = p.currentPosition }
        }
        // problema in corso e il cliente è uscito
        closeProblem()
        sendEvents()
        releasePlayer()
        Line.save(true)
        Pulse.stats = null
        Pulse.watch(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(hideR)
        App.main.removeCallbacks(tuneR)
        App.main.removeCallbacks(zapR)
        App.main.removeCallbacks(retryR)
        App.main.removeCallbacks(netWaitR)
        App.main.removeCallbacks(giveUpR)
        App.main.removeCallbacks(cushionR)
        App.main.removeCallbacks(noticeR)
        App.main.removeCallbacks(resR)
        sendEvents()
    }

    private fun newPlayer() {
        playerCls = Line.cls()
        hlsCap = 0
        val p = Players.build(this, live, soft, cls = playerCls)
        player = p
        pv.player = p
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        pv.player = null
        cushioning = false
        try { p.removeListener(listener); p.removeAnalyticsListener(analytics); p.release() } catch (_: Exception) {}
    }

    /** la classe effettiva si ricalcola a ogni avvio: se è cambiata da quando il lettore è stato costruito, lo si ricrea */
    private fun ensureClass() {
        if (player == null || Line.cls() == playerCls) return
        releasePlayer()
        newPlayer()
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            this@PlayerActivity.decoderName = decoderName
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped += droppedFrames
        }
        /** campioni del misuratore condiviso: contano solo con i segmenti HLS, dopo 512 KB e 2 s, e se non sono la stima iniziale */
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            if (fmt != "hls") return
            bwMs += totalLoadTimeMs
            bwBytes += totalBytesLoaded
            if (bwMs < 2_000 || bwBytes < 512 * 1024 || bitrateEstimate <= 0 || bitrateEstimate == Players.meterInitial) return
            Line.sample(bitrateEstimate)
        }
    }

    override fun onKicked() {
        kicked = true
        blocked = true
        App.main.removeCallbacks(giveUpR)
        App.main.removeCallbacks(netWaitR)
        App.main.removeCallbacks(retryR)
        App.main.removeCallbacks(tuneR)
        stopCushion()
        player?.stop()
        Pulse.watch(null)
        finish() // «Disconnetti» dal pannello: il lettore si chiude, senza avvisi al cliente
    }

    /* ---------------- eventi del lettore ---------------- */
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> { spin.visibility = View.VISIBLE; if (bufferingSince == 0L) bufferingSince = mono() }
                Player.STATE_READY -> {
                    spin.visibility = View.GONE
                    msg.visibility = View.GONE
                    val now = mono()
                    // stallo = caricamento di almeno 1 s a video partito da almeno 4 s (non all'avvio, dopo un salto o un cambio traccia)
                    if (firstReady && bufferingSince > 0L && !seeking && now - bufferingSince >= 1_000 && bufferingSince - playStartAt >= 4_000) {
                        stallMs += now - bufferingSince
                        stalled()
                    }
                    if (!firstReady) { playStartAt = now; fillT0 = now; fillP0 = player?.bufferedPosition ?: 0L }
                    seeking = false
                    firstReady = true
                    reconnects = 0
                    giveUps = 0
                    bufferingSince = 0L
                    if (live) row?.s("pl")?.let { fmtPref[it] = urlIdx }
                }
                Player.STATE_ENDED -> {
                    bufferingSince = 0L
                    if (live) {
                        // il flusso si è chiuso: si ricollega, ma con pause sempre più lunghe e al massimo 5 volte
                        if (reconnects < 5 && !blocked) { reconnects++; App.main.postDelayed(retryR, 1500L * reconnects) }
                        else { spin.visibility = View.GONE; showMsg("Il canale si interrompe continuamente.\nProva più tardi o scegli un altro canale.") }
                    } else {
                        val p = player
                        val d = p?.duration ?: 0L
                        // fine vera solo oltre il 90 %: prima è il server che ha chiuso la transcodifica, si riprende da dove si era
                        if (p == null || (d > 0 && d != C.TIME_UNSET && p.currentPosition >= d * 9 / 10)) onEnded()
                        else recover("error", "flusso chiuso dal server")
                    }
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) bufferingSince = 0L
        }

        override fun onRenderedFirstFrame() {
            if (ttff < 0 && startAt > 0) ttff = mono() - startAt
            if (live) resFlash(6_000) // canale partito: risoluzione e FPS per qualche secondo
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val h = videoSize.height
            val q = when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; h > 0 -> "SD"; else -> "" }
            badge.text = if (live) (if (q.isEmpty()) "LIVE" else "$q · LIVE") else q
            badge.visibility = if (badge.text.isEmpty()) View.GONE else View.VISIBLE
            // qualità adattiva: a ogni cambio di risoluzione il riquadro ricompare per qualche secondo
            if (live) {
                val again = h > 0 && resH > 0 && h != resH
                if (h > 0) resH = h
                if (again) resFlash(4_000) else resShow()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val code = error.errorCode
            val name = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase(Locale.ROOT)
            if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // rimasti fuori dalla finestra della diretta: si torna al bordo, al massimo 3 volte, poi l'altro formato
                if (++behindLive <= 3) { seeking = true; p.seekToDefaultPosition(); p.prepare(); return }
                behindLive = 0
                if (live && urls.size > 1) { urlIdx = (urlIdx + 1) % urls.size; startUrl(); return }
            }
            // rete assente (Wi-Fi caduto): non si consumano i rimedi, si aspetta che torni
            if (code in 2000..2999 && Net.type() == "none") {
                spin.visibility = View.GONE
                showMsg("Connessione assente.\nLa visione riprende da sola appena torna la rete.")
                App.main.removeCallbacks(netWaitR)
                App.main.postDelayed(netWaitR, 3_000)
                return
            }
            // il server non ha questo formato (es. m3u8 non abilitato): subito l'altro, senza attese
            if (live && (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) && urls.size > 1 && altTried < urls.size - 1) {
                altTried++
                urlIdx = (urlIdx + 1) % urls.size
                startUrl()
                return
            }
            // indirizzo senza estensione che in realtà è HLS: si riprova una volta come HLS
            if (!forceHls && (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                forceHls = true
                startUrl(if (live) null else p.currentPosition)
                return
            }
            // decoder audio che non parte (AC-3, MP2…): via la traccia audio, il video continua; niente decodifica software
            val mime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType ?: ""
            if (code in 4000..4999 && mime.startsWith("audio/") && !audioOff) {
                audioOff = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                Ui.toast("Audio non supportato dal dispositivo: video senza audio")
                event("error", "audio disattivato", "applicato", name)
                startUrl(if (live) null else p.currentPosition)
                return
            }
            // 4000-4999 = errori del decoder (lì serve la decodifica software), il resto è rete o formato
            recover("error", name, decoderError = code in 4000..4999)
        }
    }

    /* ---------------- recupero automatico ---------------- */
    /** orologio che non salta (le chiavette senza batteria tampone correggono l'ora all'avvio) */
    private fun mono(): Long = android.os.SystemClock.elapsedRealtime()

    /** canale (o titolo) per il conteggio degli stalli della linea: uguale per tutte le versioni del canale */
    private fun stallKey(): String = if (live) (baseRow ?: row)?.s("k") ?: "" else vodId

    /** un blocco breve a metà visione: ai primi due si allarga il cuscinetto, al terzo in 90 s si scende di versione */
    private fun stalled() {
        val now = mono()
        stalls.add(now); stallsHour.add(now); lastStallAt = now
        stalls.removeAll { now - it > 90_000 }
        Line.stall(stallKey())
        if (stalls.size >= 3) { stalls.clear(); recover("stall", "3 blocchi in 90 s") }
        else cushion(stalls.size)
    }

    /**
     * «Cuscinetto crescente»: dopo il 1º e il 2º blocco nessun riavvio; al ritorno in READY si resta fermi finché il
     * buffer non copre rebuffer + 2 s × blocchi (al massimo +6 s, attesa massima 15 s, controllo ogni 250 ms).
     */
    private fun cushion(n: Int) {
        val p = player ?: return
        cushionTarget = Players.buf(live, playerCls).rebuffer + (2_000L * (n + cushionExtra)).coerceAtMost(6_000L)
        if (p.totalBufferedDuration >= cushionTarget) return
        cushioning = true
        cushionUntil = mono() + 15_000
        p.playWhenReady = false
        App.main.removeCallbacks(cushionR)
        App.main.postDelayed(cushionR, 250)
    }

    private fun stopCushion() {
        App.main.removeCallbacks(cushionR)
        if (cushioning) { cushioning = false; player?.playWhenReady = true }
    }

    /** un blocco di oltre 12 s in diretta (20 nei film) o un avvio di oltre 20 s, solo mentre il lettore sta davvero caricando */
    private fun checkStall() {
        val p = player ?: return
        if (bufferingSince == 0L || blocked) return
        if (p.playbackState != Player.STATE_BUFFERING) return
        val d = mono() - bufferingSince
        val limit = if (!firstReady) 20_000 else if (live) 12_000 else 20_000
        if (d > limit) {
            bufferingSince = 0L
            stallsHour.add(mono()); lastStallAt = mono()
            stallMs += d
            if (firstReady) Line.stall(stallKey())
            // una serie di blocchi brevi era già in cura: il blocco lungo ha la sua scala (ricarica per prima)
            if (problem == "stall" && !longStall) { event(problem, lastAction.ifEmpty { "nessuna" }, "chiuso", problemErr); problem = ""; fixAt = 0L }
            recover("stall", if (firstReady) "blocco di oltre ${limit / 1000} s" else "avvio lento", long = true)
        }
    }

    /** immagine ferma con l'audio che va avanti: nessun fotogramma nuovo per 8 secondi mentre il tempo avanza (non sotto i 5 fps: radio, immagini fisse) */
    private fun checkFreeze() {
        val p = player ?: return
        val f = p.videoFormat
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady || f == null) { frozen = 0; lastRendered = -1; return }
        if (f.frameRate > 0f && f.frameRate <= 5f) { frozen = 0; return }
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
        if (frozen >= 8) {
            frozen = 0
            recover("freeze", "immagine ferma")
        }
    }

    /** fill-ratio dei primi 10 s: secondi di video che arrivano per ogni secondo reale (sul TS: > 1 la linea regge il canale) */
    private fun checkFill() {
        val p = player ?: return
        if (fillT0 == 0L || fill >= 0) return
        val dt = mono() - fillT0
        if (dt < 10_000) return
        fill = ((p.bufferedPosition - fillP0).toDouble() / dt).coerceAtLeast(0.0)
        fillT0 = 0L
    }

    private fun goodPos() {
        val p = player ?: return
        if (p.isPlaying && p.currentPosition > 0) lastGoodPos = p.currentPosition
    }

    private fun swDecoder(): Boolean = decoderName.startsWith("OMX.google") || decoderName.startsWith("c2.android")

    /**
     * Problema risolto quando la visione è regolare abbastanza a lungo:
     * blocchi = 90 s senza blocchi; immagine ferma = fotogrammi che avanzano; errore = 10 s di visione.
     */
    private fun checkResolved() {
        if (problem.isEmpty() || fixAt == 0L) return
        val p = player ?: return
        val now = mono()
        if (!p.isPlaying || bufferingSince != 0L) return
        val ok = when (problem) {
            "stall" -> now - fixAt > 90_000 && now - lastStallAt > 90_000
            "freeze" -> now - fixAt > 10_000 && frozen == 0 && now - lastFrameAt < 2_000
            else -> now - fixAt > 10_000
        }
        if (!ok) return
        // il rimedio permanente del dispositivo (SurfaceFix) si impara solo se la ricarica ha sbloccato il decoder hardware
        if (problem == "freeze" && lastAction == "ricaricato" && decoderName.isNotEmpty() && !swDecoder()) SurfaceFix.learn(this)
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
     * blocchi brevi ripetuti = linea (prima la versione più leggera, mai la ricarica per prima); blocco lungo = ricarica;
     * immagine ferma o errore del decoder = dispositivo (decodifica software).
     */
    private fun recover(kind: String, err: String, decoderError: Boolean = false, long: Boolean = false) {
        val p = player ?: return
        if (blocked) return
        val now = mono()
        val fresh = problem.isEmpty()
        if (fresh) {
            problem = kind; problemErr = err; lastAction = ""
            longStall = long
            step = if (kind == lastKind && now - resolvedAt < 180_000) lastStep else 0
            decoderProblem = decoderError
        } else {
            // una soluzione è appena partita: i blocchi e l'immagine ferma si ricontrollano dopo; gli errori no
            if (kind != "error" && now - fixAt < 4_000) return
            if (decoderError) decoderProblem = true
        }
        // tornati alla qualità piena e la linea non regge ancora: giù di nuovo (al secondo ritorno fallito si resta giù)
        if (tryingFull && kind == "stall") {
            tryingFull = false
            if (++fullFails >= 2) stayReduced = true
            if (switchLighter(byLine = true)) { lastAction = "versione più leggera"; fixAt = now; return }
        }
        // canale partito già ridotto per la connessione e che si blocca ancora: dritto alla versione ancora più leggera
        if (fresh && kind == "stall" && reduced && reducedByLine && switchLighter(byLine = true)) { lastAction = "versione più leggera"; fixAt = now; return }
        val ladder = if (live) when (problem) {
            "freeze" -> listOf("reload", "soft", "alt", "lighter")
            "stall" -> if (longStall) listOf("reload", "alt", "lighter") else listOf("lighter", "alt", "cushion")
            else -> listOf("reload", "alt", "soft", "lighter")
        } else when (problem) {
            "stall" -> if (longStall) listOf("reload", "reload") else listOf("cushion", "cushion")
            else -> listOf("reload", "soft")
        }
        // stesso problema tornato dopo l'ultima soluzione possibile: si ricomincia (una volta) dal primo passo
        if (fresh && step >= ladder.size) step = 0
        while (step < ladder.size) {
            val a = ladder[step++]
            if (applyFix(a)) { fixAt = mono(); return }
        }
        // blocchi brevi ripetuti: il video va, anche se a scatti: niente stop, alla prossima serie si riparte dal primo passo
        if (problem == "stall" && !longStall) {
            event(problem, lastAction.ifEmpty { "nessuna" }, "non risolto", problemErr)
            problem = ""
            fixAt = 0L
            lastKind = ""
            return
        }
        // provato tutto: ci si ferma, con nuovi tentativi automatici ogni 30 s (al massimo 10)
        event(problem, lastAction.ifEmpty { "nessuna" }, "non risolto", problemErr)
        problem = ""
        fixAt = 0L
        lastKind = ""
        bufferingSince = 0L
        gaveUp = true
        spin.visibility = View.GONE
        App.main.removeCallbacks(retryR)
        try { p.stop() } catch (_: Exception) {}
        showMsg(if (live) "Il canale non risponde in questo momento.\nRiprovo da solo tra poco, oppure scegli un altro canale." else "Il contenuto non si apre ($err).\nRiprovo da solo tra poco, oppure premi OK.")
        App.main.removeCallbacks(giveUpR)
        if (giveUps < 10) App.main.postDelayed(giveUpR, 30_000)
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
        "lighter" -> if (switchLighter(byLine = problem == "stall")) { lastAction = "versione più leggera"; true } else capHls()
        // cuscinetto un gradino più largo per il resto del canale (+2 s per gradino, fino a +6 s)
        "cushion" -> if (cushionExtra >= 3) false else { cushionExtra++; lastAction = "cuscinetto più largo"; true }
        else -> false
    }

    /** HLS a più qualità senza versioni sorelle: tetto ABR al 70 % del bitrate in uso (sotto il tetto l'ABR resta attivo) */
    private fun capHls(): Boolean {
        val p = player ?: return false
        if (fmt != "hls") return false
        val br = p.videoFormat?.bitrate ?: return false
        if (br <= 0) return false
        // serve almeno una variante più leggera
        val lower = p.currentTracks.groups.any { g -> g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { g.getTrackFormat(it).bitrate in 1 until br } }
        if (!lower) return false
        hlsCap = (br * 0.7).toInt()
        lastAction = "qualità più bassa"
        notice("Qualità ridotta per la tua connessione", 5_000)
        seeking = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoBitrate(hlsCap).build()
        return true
    }

    /** decodifica software accesa/spenta: il lettore va ricreato (audio e sottotitoli scelti restano). [restart] = riparte subito lo stesso contenuto */
    private fun setSoft(on: Boolean, pos: Long?, restart: Boolean = false) {
        if (soft == on) return
        soft = on
        if (player == null) return
        val keep = try { player?.trackSelectionParameters } catch (_: Exception) { null }
        releasePlayer()
        newPlayer()
        if (keep != null) try { player?.trackSelectionParameters = keep } catch (_: Exception) {}
        if (restart) startUrl(pos)
    }

    /** riaccende la traccia audio tolta per un decoder mancante (valeva per il contenuto di prima) */
    private fun audioOn() {
        if (!audioOff) return
        audioOff = false
        val p = player ?: return
        try { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build() } catch (_: Exception) {}
    }

    /** toglie il tetto ABR messo a mano sul canale di prima */
    private fun uncapHls() {
        if (hlsCap <= 0) return
        hlsCap = 0
        val p = player ?: return
        try { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoBitrate(Players.maxBitrate(playerCls)).build() } catch (_: Exception) {}
    }

    private fun sendEvents() {
        if (events.length() == 0) return
        val e = JSONArray(events.toString())
        while (events.length() > 0) events.remove(0)
        net { Panel.errors(e) }
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
        reducedByLine = byLine
        if (byLine) lighterUntil = now + 10 * 60_000L else stayReduced = true // versione piena guasta: non si riprova
        notice(if (byLine) "Qualità ridotta per la tua connessione" else "Passo a un'altra versione del canale", 5_000)
        tune(v.id, variant = true)
        return true
    }

    /** dopo 6 minuti senza blocchi si riprova la qualità piena (prova di 5 minuti); se torna a bloccarsi si scende di nuovo */
    private fun checkReduced() {
        val now = mono()
        if (tryingFull && now - tryFullAt > 5 * 60_000L) tryingFull = false
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
    private fun variantLabel(): String = if (reduced) Quality.label(row?.l("q")?.toInt() ?: 2) else "full"

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
        if (decoderName.isNotEmpty()) o.put("dec", if (swDecoder()) "sw" else "hw")
        val now = mono()
        stallsHour.removeAll { now - it > 3_600_000L }
        o.put("stallsH", stallsHour.size).put("stalls10", stallsHour.count { now - it < 600_000L }).put("buffers", stallsHour.size).put("dropped", dropped)
        o.put("reduced", if (reduced) Quality.label(row?.l("q")?.toInt() ?: 2) else "")
        // Kairos Adaptive: banda (Mbps, solo campioni validi), classi, riempimento all'avvio, primo fotogramma, stalli, versione e formato
        if (Line.bw > 0) o.put("bw", Math.round(Line.bw / 100_000.0) / 10.0)
        o.put("line", Line.line()).put("dev", Line.device()).put("cls", Line.name(playerCls))
        if (fill >= 0) o.put("fill", Math.round(fill * 100) / 100.0)
        if (ttff >= 0) o.put("ttff", ttff)
        o.put("stallMs", stallMs)
        if (fmt.isNotEmpty()) o.put("fmt", fmt)
        if (live) o.put("var", variantLabel())
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
        val o = JSONObject().put("t", System.currentTimeMillis()).put("problem", problem).put("action", action).put("outcome", outcome)
            .put("err", err).put("title", title).put("id", if (live) base?.s("k") ?: "" else vodId).put("k", if (live) "live" else kind).put("engine", "exo")
            .put("line", Line.line()).put("dev", Line.device()).put("fmt", fmt).put("stallMs", stallMs)
        if (Line.bw > 0) o.put("bw", Math.round(Line.bw / 100_000.0) / 10.0)
        if (live) o.put("var", variantLabel())
        events.put(o)
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
        if (id <= 0 || kicked) return
        if (!variant) {
            closeProblem() // problema del canale di prima: il cliente è passato oltre
            setSoft(false, null)
            audioOn()
            uncapHls()
            dropped = 0
            reconnects = 0
            cushionExtra = 0
            fullFails = 0
        }
        val my = ++tuneSeq
        if (!variant) curId = id
        App.main.removeCallbacks(retryR)
        stopCushion()
        msg.visibility = View.GONE
        spin.visibility = View.VISIBLE
        val lighter = !variant && mono() < lighterUntil
        val capped = !variant && App.max
        val cls = Line.cls()
        val keepVariants = variants
        bg {
            val r0 = Db.one("live", id) ?: return@bg
            val vars = if (variant) keepVariants else try { Db.variants(r0) } catch (_: Exception) { emptyList() }
            var r = r0
            var startReduced = false
            var startCapped = false
            if (!variant) {
                val q0 = r0.l("q").toInt()
                val hv = r0.l("hevc") == 1L
                // versione di partenza (§5): classe debole ⇒ al massimo HD (SD con banda sotto 2,5 Mbps), normale ⇒ al massimo FHD,
                // ottima ⇒ piena; dopo un calo recente della linea un gradino sotto la versione scelta
                var maxQ = when (cls) {
                    Line.WEAK -> if (Line.bw in 1L until 2_500_000L) 1 else 2
                    Line.NORMAL -> 3
                    else -> 4
                }
                if (lighter) maxQ = minOf(maxQ, q0 - 1)
                if (q0 > maxQ) vars.filter { it.q <= maxQ && (!it.hevc || hv) }.maxByOrNull { it.q }?.let { v -> Db.one("live", v.id)?.let { r = it; startReduced = true } }
                // «Leggerissima»: se il canale ha anche la versione HD (non HEVC) si parte da quella invece di FHD / 4K
                if (!startReduced && capped && q0 >= 3) {
                    vars.firstOrNull { it.q == 2 && !it.hevc }?.let { v -> Db.one("live", v.id)?.let { r = it; startCapped = true } }
                }
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
                    reducedByLine = startReduced
                    reducedAt = if (reduced) mono() else 0L
                    stayReduced = startCapped // scelta dell'utente: non si torna da soli alla versione piena
                    tryingFull = false
                    stalls.clear()
                    progs = emptyList()
                    if (startReduced) notice("Qualità ridotta per la tua connessione", 4_000)
                }
                row = r
                urls = list
                // formato che ha funzionato l'ultima volta su questo portale (m3u8 o ts): si parte da quello
                urlIdx = (fmtPref[r.s("pl")] ?: 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
                altTried = 0
                forceHls = false
                behindLive = 0
                giveUps = 0
                App.main.removeCallbacks(giveUpR)
                Store.lastLiveId = curId
                showLiveInfo(baseRow ?: r)
                blocked = block != null
                if (block != null) { player?.stop(); spin.visibility = View.GONE; showMsg(block); return@ui }
                if (list.isEmpty()) { spin.visibility = View.GONE; showMsg("Indirizzo del canale non valido."); return@ui }
                ensureClass() // classe effettiva ricalcolata a ogni avvio: se è cambiata il lettore si ricrea
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
        if (blocked || kicked) return
        val u = if (live) urls.getOrNull(urlIdx) else vodUrl
        if (u.isNullOrEmpty()) return
        stopCushion()
        fmt = if (Players.isHls(u, forceHls)) "hls" else if (live) "ts" else "vod"
        bwMs = 0L; bwBytes = 0L
        startAt = mono(); playStartAt = 0L; ttff = -1L; fill = -1.0; fillT0 = 0L
        bufferingSince = startAt // il tempo di avvio si conta da qui (il lettore può restare in caricamento)
        firstReady = false
        gaveUp = false
        resH = 0 // nuovo avvio: il riquadro risoluzione ricompare al primo fotogramma
        fpsAvg = 0.0; fpsN = -1 // flusso nuovo: gli FPS si misurano da capo
        // film: si riparte dal punto in cui si era (un errore di rete non fa ricominciare da capo)
        if (live) p.setMediaItem(Players.item(u, forceHls))
        else p.setMediaItem(Players.item(u, forceHls), maxOf(pos ?: (if (p.currentPosition > 0) p.currentPosition else lastGoodPos), 0L))
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
        net {
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
        resShow()
        App.main.removeCallbacks(zapR)
        App.main.postDelayed(zapR, 1500)
    }

    private fun commitZap() {
        val n = zapBuf.toLongOrNull()
        zapBuf = ""
        zap.visibility = View.GONE
        resShow()
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
        if (kicked) return
        nameV.text = vodTitle
        subV.text = vodSub
        val st = kind
        val title = vodTitle
        spin.visibility = View.VISIBLE
        bg {
            Vpns.awaitForced()
            val block = Panel.stream(st, title)
            ui {
                if (player == null || isFinishing) return@ui
                blocked = block != null
                if (block != null) { spin.visibility = View.GONE; showMsg(block); return@ui }
                if (vodUrl.isEmpty()) { spin.visibility = View.GONE; showMsg("Indirizzo non valido."); return@ui }
                audioOn()
                reconnects = 0
                cushionExtra = 0
                forceHls = false
                lastGoodPos = startPos.coerceAtLeast(0)
                stalls.clear()
                ensureClass()
                startUrl(startPos.coerceAtLeast(0))
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
        if (cushioning) { stopCushion(); return } // il cliente vuole ripartire subito
        if (!p.isPlaying && p.playbackState == Player.STATE_IDLE) { msg.visibility = View.GONE; App.main.removeCallbacks(giveUpR); giveUps = 0; startUrl(); return }
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
        resShow()
    }

    private fun showOverlay() {
        (top as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        top.visibility = View.VISIBLE
        val wasHidden = bottom.visibility != View.VISIBLE
        bottom.visibility = View.VISIBLE
        updateTime()
        if (wasHidden) playPause.requestFocus()
        bump()
        resShow()
    }

    private fun bump() {
        App.main.removeCallbacks(hideR)
        App.main.postDelayed(hideR, if (live) 5000 else 4000)
    }

    private fun hideOverlay() {
        App.main.removeCallbacks(hideR)
        top.visibility = View.GONE
        bottom.visibility = View.GONE
        resShow()
    }

    /* ---------------- riquadro risoluzione e FPS (solo canali in diretta) ---------------- */
    /** permesso del cliente «resInd» (assente = consentito); film e serie restano come sono */
    private fun resOn(): Boolean = live && Panel.state?.perm("resInd") != false

    /** «FHD · 1080p · 50 fps»: qualità, righe e fotogrammi al secondo (dichiarati dal flusso, altrimenti misurati dal decoder) */
    private fun resText(): String {
        val p = player ?: return ""
        val f = p.videoFormat
        val h = p.videoSize.height.takeIf { it > 0 } ?: f?.height?.takeIf { it > 0 } ?: return ""
        val q = when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" }
        val fr = f?.frameRate ?: 0f
        val m = fpsShown()
        val fps = when {
            fr > 0f && Math.abs(fr - Math.round(fr)) < 0.05f -> " · ${Math.round(fr)} fps"
            fr > 0f -> " · " + String.format(Locale.ITALY, "%.2f", fr) + " fps"
            m > 0 -> " · $m fps" // il flusso (es. TS) non li dichiara: misurati
            else -> ""
        }
        return "$q · ${h}p$fps"
    }

    /** ogni secondo, mentre il video va davvero: fotogrammi mostrati dal decoder / tempo trascorso, smorzati (0,6 vecchio + 0,4 nuovo) */
    private fun measureFps() {
        val p = player ?: return
        val c = if (p.isPlaying) p.videoDecoderCounters else null
        if (c == null) { fpsN = -1; return }
        c.ensureUpdated()
        val n = c.renderedOutputBufferCount
        val now = mono()
        val pn = fpsN
        val pt = fpsT
        fpsN = n
        fpsT = now
        if (pn < 0 || n < pn || now - pt < 500) return
        val v = (n - pn) * 1000.0 / (now - pt)
        if (v < 5.0 || v > 130.0) return // fuori misura (avvio, salto, decoder appena ricreato)
        val before = fpsShown()
        fpsAvg = if (fpsAvg <= 0.0) v else fpsAvg * 0.6 + v * 0.4
        // «· NN fps» compare nel riquadro appena c'è la misura, e si aggiorna se cambia
        if (fpsShown() != before && resInd.visibility == View.VISIBLE) resShow()
    }

    /** FPS misurati: al valore standard più vicino (24, 25, 30, 48, 50, 60, 100, 120) se dista meno di 1,5, altrimenti all'intero */
    private fun fpsShown(): Int {
        val a = fpsAvg
        if (a <= 0.0) return 0
        val s = STD_FPS.minByOrNull { Math.abs(it - a) } ?: return Math.round(a).toInt()
        return if (Math.abs(s - a) < 1.5) s else Math.round(a).toInt()
    }

    /** il riquadro resta per [ms] (oltre che sempre insieme alla barra informazioni) */
    private fun resFlash(ms: Long) {
        resUntil = maxOf(resUntil, mono() + ms)
        resShow()
    }

    /** visibile durante la comparsa a tempo e sempre con la barra informazioni; non copre le cifre del cambio canale */
    private fun resShow() {
        App.main.removeCallbacks(resR)
        val osd = top.visibility == View.VISIBLE || bottom.visibility == View.VISIBLE
        val now = mono()
        val t = if (resOn() && (osd || zap.visibility != View.VISIBLE)) resText() else ""
        if (t.isEmpty() || (!osd && now >= resUntil)) { resInd.visibility = View.GONE; return }
        if (resInd.text.toString() != t) resInd.text = t
        resInd.visibility = View.VISIBLE
        resPlace()
        if (!osd) App.main.postDelayed(resR, resUntil - now)
    }

    /** con la barra in alto visibile il riquadro scende sotto di lei (non copre qualità e orologio) */
    private fun resPlace() {
        val mt = (resInd.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        resInd.translationY = if (top.visibility == View.VISIBLE && top.height > 0) (top.bottom - top.paddingBottom - mt + Ui.dp(this, 8)).coerceAtLeast(0).toFloat() else 0f
    }

    private fun resHide() {
        App.main.removeCallbacks(resR)
        resUntil = 0L
        resInd.visibility = View.GONE
    }

    /** permessi cambiati dal pannello durante la visione (es. «resInd»): il riquadro si riallinea */
    override fun onStateChanged() { resShow() }

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
            seeking = true // il caricamento dopo un cambio traccia non è un blocco della linea
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
            zap.visibility == View.VISIBLE -> { App.main.removeCallbacks(zapR); zapBuf = ""; zap.visibility = View.GONE; resShow() }
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
