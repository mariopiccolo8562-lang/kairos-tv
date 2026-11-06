package com.kairos.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock

/**
 * Kairos Adaptive v1 (KAIROS_ADAPTIVE.md): classe del dispositivo (D / N / O), classe della linea
 * (weak / normal / good, salvata con la data e che torna «normal» dopo 12 ore senza misure nuove) e banda
 * misurata (media mobile esponenziale, peso 0,2 al campione nuovo, salvata al più ogni 30 s e allo stop).
 * La classe effettiva è la peggiore delle due e decide buffer, offset dalla diretta e ABR del lettore
 * (Players.build); si ricalcola a ogni avvio di canale o titolo e, se è cambiata, il lettore si ricrea.
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
    private const val DECAY = 12 * 3600_000L
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

    /** weak / normal / good in questo momento (giudizio vecchio di oltre 12 ore: «normal») */
    fun line(): String {
        load()
        return if (cls != "normal" && System.currentTimeMillis() - clsAt > DECAY) "normal" else cls
    }

    /** classe effettiva: la peggiore fra dispositivo e linea */
    fun cls(): Int {
        val d = device()
        val l = line()
        return when {
            d == "D" || l == "weak" -> WEAK
            d == "O" && l == "good" -> GOOD
            else -> NORMAL
        }
    }

    /** stima iniziale del misuratore di banda: quella salvata (×0,8) oppure 2 / 6 / 12 Mbps per classe */
    fun initialBps(cls: Int): Long {
        load()
        if (bw > 0) return bw * 8 / 10
        return when (cls) { WEAK -> 2_000_000L; GOOD -> 12_000_000L; else -> 6_000_000L }
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

    /** uno stallo sul canale [key]: pesa sulla classe solo se gli stalli recenti sono su almeno 2 canali diversi */
    @Synchronized fun stall(key: String) {
        load()
        val now = SystemClock.elapsedRealtime()
        stalls.add(now to key)
        stalls.removeAll { now - it.first > STALL_WINDOW }
        reeval()
        save(false)
    }

    /** ricalcolo con una misura nuova: senza misure la classe salvata resta (e decade dopo 12 ore) */
    private fun reeval() {
        val now = SystemClock.elapsedRealtime()
        val recent = stalls.filter { now - it.first <= STALL_WINDOW }
        val stallWeak = recent.size >= 2 && recent.map { it.second }.distinct().size >= 2
        val quiet = stalls.none { now - it.first <= 20 * 60_000L }
        val c = when {
            stallWeak || bw in 1L until 4_000_000L -> "weak"
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
}
