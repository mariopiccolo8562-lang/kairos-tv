package com.kairos.tv

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.request.RequestOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Kairos TV: app nativa leggera (viste Android classiche, nessuna WebView).
 * Su dispositivi con poca memoria parte da sola in «modalità leggera»: niente animazioni,
 * niente anteprima video nella lista canali, immagini a 16 bit e buffer più piccoli.
 */
class App : Application() {
    /** processo a parte del trailer (TrailerActivity): solo la pagina del video, niente archivio, pannello o immagini */
    private var trailerProcess = false
    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000').ifBlank { packageName } } catch (_: Exception) { packageName }

    override fun onCreate() {
        super.onCreate()
        trailerProcess = processName().endsWith(":trailer")
        if (trailerProcess) return
        I = this
        Store.init(this)
        // versione nuova già trovata (e APK già scaricato): vale subito, anche senza rete (blocco obbligatorio compreso)
        try { Updater.restore() } catch (_: Throwable) {}
        Db.init(this)
        // l'archivio si apre subito in sottofondo, mentre compare la prima schermata (mai sul thread principale)
        bg { Db.r }
        lite = detectLite()
        max = Store.liteMode == "max"
        weak = try { Line.device() == "D" } catch (_: Throwable) { false }
        // immagini (prima causa di memoria esaurita sulle chiavette da 1 GB): cache in memoria con un tetto per classe e
        // cache su disco nella cartella cache dell'app (Android la svuota da sola se manca spazio): D 150 MB, N/O 250 MB
        try {
            val mem = imageMemory()
            Glide.init(this, GlideBuilder()
                .setMemoryCache(LruResourceCache(mem))
                .setBitmapPool(LruBitmapPool(mem / 2))
                .setDiskCache(InternalCacheDiskCacheFactory(this, if (weak) 150L shl 20 else 250L shl 20))
                .setDefaultRequestOptions(RequestOptions().format(if (lite || weak) DecodeFormat.PREFER_RGB_565 else DecodeFormat.PREFER_ARGB_8888))
                .setLogLevel(android.util.Log.ERROR))
        } catch (_: Throwable) {}
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { alive.add(a) }
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1) Pulse.foreground(true)
            }
            override fun onActivityResumed(a: Activity) { top = a }
            override fun onActivityPaused(a: Activity) { if (top === a) top = null }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0) { started = 0; Pulse.foreground(false) }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { alive.remove(a) }
        })
    }

    /** cache delle immagini in memoria: «Leggerissima» 4 MB, leggera 6 MB, dispositivo debole 1/8 della memoria dell'app
     *  (al massimo 24 MB), gli altri 20 MB come prima */
    private fun imageMemory(): Long {
        if (max) return 4L shl 20
        if (lite) return if (weak) 4L shl 20 else 6L shl 20 // chiavetta da 1 GB: cache delle immagini più piccola (13 novembre 2026)
        if (weak) {
            val mc = try { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong() } catch (_: Exception) { 128L }
            return ((mc shl 20) / 8).coerceIn(4L shl 20, 24L shl 20)
        }
        return 20L shl 20
    }

    @Suppress("DEPRECATION") // i livelli «RUNNING_*» arrivano ancora sui dispositivi fino ad Android 13
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (trailerProcess) return
        // sistema a corto di memoria mentre l'app è in uso: i lavori pesanti in sottofondo (guida XMLTV) aspettano
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) memTightFor(3 * 60_000L)
        // memoria che scarseggia (o app in sfondo): le immagini in cache si buttano subito, video e liste vengono prima
        try {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Glide.get(this).clearMemory()
            else Glide.get(this).trimMemory(level)
        } catch (_: Throwable) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (trailerProcess) return
        memTightFor(5 * 60_000L)
        try { Glide.get(this).clearMemory() } catch (_: Throwable) {}
    }

    fun detectLite(): Boolean = when (Store.liteMode) {
        "on", "max" -> true
        "off" -> false
        else -> autoLite()
    }

    /** chiavette e box con meno di ~1,8 GB di RAM o Android vecchio (Fire OS 5/6) */
    fun autoLite(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return true
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem in 1 until 1_800_000_000L) return true
        return Build.VERSION.SDK_INT < 24
    }

    companion object {
        lateinit var I: App
        /** il lettore è la schermata in primo piano (usato dalla guida XMLTV: mai durante la visione) */
        fun isPlayer(a: Activity?): Boolean = a is PlayerActivity
        /** poca memoria segnalata dal sistema negli ultimi minuti */
        @Volatile private var memTightUntil = 0L
        val memTight: Boolean get() = android.os.SystemClock.elapsedRealtime() < memTightUntil
        fun memTightFor(ms: Long) { memTightUntil = android.os.SystemClock.elapsedRealtime() + ms }
        @Volatile var lite = false
        /** «Leggerissima»: per le chiavette più vecchie (liste senza loghi, immagini più piccole, canali in HD) */
        @Volatile var max = false
        /** dispositivo debole (classe D di Kairos Adaptive): immagini a 16 bit e più piccole anche fuori dalla modalità leggera */
        @Volatile var weak = false
        /** schermate aperte: entrando nel lettore quelle sotto liberano le immagini grandi (vedi [Ui.freeForPlayer]) */
        val alive: MutableSet<Activity> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
        val main = Handler(Looper.getMainLooper())
        val io: ExecutorService = Executors.newFixedThreadPool(3) { r -> Thread(r, "kl-io").apply { isDaemon = true } }
        var started = 0
        var top: Activity? = null
    }
}

fun bg(block: () -> Unit) {
    App.io.execute { try { block() } catch (t: Throwable) { Log.w("KairosTV", "bg", t) } }
}

/** rete lenta (guida TV, schede) su thread propri: non blocca le letture dell'archivio */
private val netPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "kl-net").apply { isDaemon = true } }
fun net(block: () -> Unit) {
    netPool.execute { try { block() } catch (t: Throwable) { Log.w("KairosTV", "net", t) } }
}

fun ui(block: () -> Unit) {
    App.main.post { try { block() } catch (t: Throwable) { Log.w("KairosTV", "ui", t) } }
}
