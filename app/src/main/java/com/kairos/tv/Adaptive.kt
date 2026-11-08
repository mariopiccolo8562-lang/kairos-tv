package com.kairos.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock

/**
 * Kairos Adaptive v1 (KAIROS_ADAPTIVE.md): classe del dispositivo (D / N / O), classe della linea
 * (weak / normal / good) e banda misurata (media mobile esponenziale, peso 0,2 al campione nuovo, salvata al più ogni
 * 30 s e allo stop).
 * - La classe della LINEA ([net]) decide la qualità (versione di partenza del canale e ABR): solo prove recenti, cioè
 *   blocchi su 2 canali nell'ultima mezz'ora (solo in memoria) o banda misurata da meno di 2 ore. Il dispositivo non
 *   abbassa mai la qualità da solo: una chiavetta da 1 GB con la linea buona vede i canali in FHD.
 * - La classe effettiva ([cls], la peggiore fra dispositivo e linea) decide solo memoria e buffer del lettore.
 * Entrambe si ricalcolano a ogni avvio di canale o titolo e, se sono cambiate, il lettore si ricrea.
 */
object Line {
    /** classe effettiva */
    const val WEAK = 0
    const val NORMAL = 1
    const val GOOD = 2

    fun name(cls: Int): String = when (cls) { WEAK -> "debole"; GOOD -> "ottima"; else -> "normale" }

    /* ---------------- dispositivo (calcolato una volta, salvato) ---------------- */
    /** modelli low-end noti: Fire TV Stick 1ª/2ª gen e Basic (AFTM, AFTT), Stick Lite (AFTSSS), Stick 3ª gen MT8695 con 1 GB (AFTSS) */
    private val LOW_MODELS = setOf("AFTM", "AFTT", "AFTSSS", "AFTSS")
    @Volatile private var dev = ""

    /** D = debole, N = normale, O = ottimo */
    fun device(): String {
        dev.ifEmpty { null }?.let { return it }
        var d = Store.devCls
        if (d.isEmpty()) {
            d = try { detectDevice() } catch (_: Exception) { "N" }
            Store.devCls = d
        }
        dev = d
        return d
    }

    private fun detectDevice(): String {
        val am = App.I.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val mem = mi.totalMem
        return when {
            am.isLowRamDevice || mem in 1L..1_200_000_000L || LOW_MODELS.contains(Build.MODEL) || Build.VERSION.SDK_INT < 24 -> "D"
            // i dispositivi «da 2 GB» ne dichiarano circa 1,8-1,9 miliardi di byte
            mem >= 1_800_000_000L && Build.VERSION.SDK_INT >= 28 -> "O"
            else -> "N"
        }
    }

    /* ---------------- linea ---------------- */
    private const val STALL_WINDOW = 30 * 60_000L
    @Volatile private var loaded = false
    @Volatile private var cls = "normal"
    @Volatile private var clsAt = 0L
    /** banda misurata in bit/s (solo campioni validi, vedi PlayerActivity); 0 = mai misurata */
    @Volatile var bw = 0L
        private set
    private var dirty = false
    private var savedAt = 0L
    /** stalli recenti: orologio monotono e canale (chiave stabile, uguale per tutte le versioni del canale) */
    private val stalls = ArrayList<Pair<Long, String>>()

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        cls = Store.lineCls.ifEmpty { "normal" }
        clsAt = Store.lineAt
        bw = Store.bw
    }

    /**
     * weak / normal / good in questo momento: blocchi nell'ultima mezz'ora su almeno 2 canali ⇒ weak (finché durano);
     * altrimenti il giudizio della banda misurata, che vale 2 ore (poi «normal»: niente giudizi vecchi)
     */
    fun line(): String {
        load()
        if (stallWeak()) return "weak"
        return if (cls != "normal" && System.currentTimeMillis() - clsAt !in 0..QualityPolicy.BW_FRESH_MS) "normal" else cls
    }

    /** classe della linea per la QUALITÀ (versione di partenza del canale, ABR): solo la connessione, mai il dispositivo */
    fun net(): Int = when (line()) { "weak" -> WEAK; "good" -> GOOD; else -> NORMAL }

    /** banda misurata da poco: solo allora conta per scendere fino all'SD e come stima iniziale del lettore */
    fun bwFresh(): Boolean {
        load()
        return bw > 0 && System.currentTimeMillis() - clsAt in 0..QualityPolicy.BW_FRESH_MS
    }

    /** classe effettiva per memoria e buffer del lettore: la peggiore fra dispositivo e linea */
    fun cls(): Int {
        val d = device()
        val l = line()
        return when {
            d == "D" || l == "weak" -> WEAK
            d == "O" && l == "good" -> GOOD
            else -> NORMAL
        }
    }

    /** stima iniziale del misuratore di banda: quella misurata da poco (×0,8) oppure 2 / 6 / 12 Mbps per classe della linea */
    fun initialBps(net: Int): Long {
        load()
        if (bwFresh()) return bw * 8 / 10
        return when (net) { WEAK -> 2_000_000L; GOOD -> 12_000_000L; else -> 6_000_000L }
    }

    /** campione di banda valido (bit/s): arriva solo dai segmenti HLS, mai dal TS o dall'anteprima */
    @Synchronized fun sample(bps: Long) {
        if (bps <= 0) return
        load()
        bw = if (bw <= 0) bps else (bw * 0.8 + bps * 0.2).toLong()
        dirty = true
        reeval()
        save(false)
    }

    /**
     * uno stallo sul canale [key]: la linea è debole solo finché nell'ultima mezz'ora ci sono stalli su almeno 2 canali
     * diversi (solo in memoria: dopo mezz'ora senza blocchi, o riaprendo l'app, la qualità torna piena)
     */
    @Synchronized fun stall(key: String) {
        load()
        val now = SystemClock.elapsedRealtime()
        stalls.add(now to key)
        stalls.removeAll { now - it.first > STALL_WINDOW }
        while (stalls.size > 50) stalls.removeAt(0)
    }

    @Synchronized private fun stallWeak(): Boolean {
        val now = SystemClock.elapsedRealtime()
        stalls.removeAll { now - it.first > STALL_WINDOW }
        return stalls.size >= 2 && stalls.map { it.second }.distinct().size >= 2
    }

    /** ricalcolo con una misura di banda nuova (la data del giudizio è quella della misura) */
    private fun reeval() {
        val now = SystemClock.elapsedRealtime()
        val quiet = stalls.none { now - it.first <= 20 * 60_000L }
        val c = when {
            bw in 1L until 4_000_000L -> "weak"
            bw >= 15_000_000L && quiet -> "good"
            bw > 0 -> "normal"
            else -> return
        }
        cls = c
        clsAt = System.currentTimeMillis()
        dirty = true
    }

    /** su disco al più ogni 30 s; [force] = allo stop del lettore */
    @Synchronized fun save(force: Boolean) {
        if (!dirty) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - savedAt < 30_000) return
        savedAt = now
        dirty = false
        Store.saveLine(cls, clsAt, bw)
    }

    /* ---------------- versione «a scatti» imparata (QualityPolicy.Heavy) ---------------- */
    @Volatile private var heavy: QualityPolicy.Heavy? = null
    @Volatile private var heavyLoaded = false

    private fun heavyState(): QualityPolicy.Heavy? {
        if (!heavyLoaded) {
            heavy = try { QualityPolicy.Heavy.load(Store.heavy) } catch (_: Exception) { null }
            heavyLoaded = true
        }
        return heavy
    }

    /** immagine a scatti (decodifica hardware) sulla versione [q] del canale [key]: 2 canali in 7 giorni ⇒ si parte un gradino sotto */
    @Synchronized fun heavy(q: Int, key: String) {
        val h = QualityPolicy.heavyNote(heavyState(), q, key, System.currentTimeMillis())
        if (h == heavy) return
        heavy = h
        try { Store.heavy = h?.save() ?: "" } catch (_: Exception) {}
    }

    /** tetto imparato per la versione di partenza (4 = nessuno) */
    fun heavyCap(): Int = QualityPolicy.heavyCap(heavyState(), System.currentTimeMillis())

    /* ---------------- schermo ---------------- */
    @Volatile private var screen = -1

    /** schermo 4K (modalità fisica dello schermo, letta una volta): sugli schermi 1080p il 4K non si vede, si parte dal FHD */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun screen4k(ctx: Context): Boolean {
        if (screen < 0) screen = try {
            val p = androidx.media3.common.util.Util.getCurrentDisplayModeSize(ctx)
            if (minOf(p.x, p.y) >= 2000) 1 else 0
        } catch (_: Throwable) { 0 }
        return screen == 1
    }
}
