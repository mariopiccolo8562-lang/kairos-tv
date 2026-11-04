package com.kairos.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * KAIROS Player — contenitore Android.
 * - interfaccia web locale (assets/www) oppure la versione aggiornata scaricata dal pannello (files/www)
 * - player nativo a schermo intero (PlayerActivity) e anteprima nativa nel riquadro della Live TV
 * - aggiornamento dell'APK, speed test e richieste HTTP native
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var lastBack = 0L
    @Volatile private var uiOk = false
    private var isResumed = false

    companion object {
        private const val HOME = "file:///android_asset/www/index.html"
    }

    private val otaDir: File get() = File(filesDir, "www")
    private val otaFile: File get() = File(otaDir, "index.html")

    /** ritorno dal player nativo: posizione, durata, motore scelto, risoluzione */
    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        adoptBack() // se il player non l'ha già restituito mentre si chiudeva
        Handoff.engine?.let { Handoff.engine = null; it.release() } // mai preso dal player (avvio fallito): niente audio orfano
        endHandoff()
        uncoverUi()
        val r = res.data?.getStringExtra(PlayerActivity.RESULT) ?: return@registerForActivityResult
        js("window.kxPlayerClosed && window.kxPlayerClosed(${JSONObject.quote(r)})")
    }

    /**
     * Il video tornato dallo schermo intero continua nel riquadro dell'anteprima senza ricaricarsi.
     * Una nuova vista nasce nel riquadro, sotto la finestra (trasparente) del player che mostra ancora l'ultimo
     * fotogramma nello stesso punto; quando la nuova ha disegnato il video, la vecchia viene tolta e [onShown]
     * chiude il player. Nessun buco tra le due.
     */
    private fun adoptBack(onShown: Runnable? = null) {
        val b = Handoff.back ?: return
        Handoff.back = null
        endHandoff()
        val box = Handoff.box
        if (previewEngine != null && previewEngine !== b) { previewEngine?.release(); previewBox.removeAllViews() }
        previewEngine = b
        b.l = previewListener
        previewId = Handoff.id
        previewAlt = ""
        previewTriedAlt = false
        if (box != null) {
            previewBox.layoutParams = FrameLayout.LayoutParams(box[2], box[3]).apply { leftMargin = box[0]; topMargin = box[1] }
            previewBox.visibility = View.VISIBLE
        }
        if (b.view.parent !== previewBox) {
            val v = b.moveTo(this, true, if (SurfaceFix.needed(this)) 3500L else 1200L) { b.dropOldView(); onShown?.run() }
            previewBox.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else onShown?.run()
        // se entro pochi secondi la pagina non chiede di nuovo l'anteprima (utente uscito dalla Live), si ferma
        ui.postDelayed({ if (previewEngine === b && !previewWanted) stopPreview() }, 4000)
        previewWanted = false
    }
    @Volatile private var previewWanted = false

    /* passaggio anteprima ↔ schermo intero: il player (finestra trasparente sopra) chiama questi metodi */
    private var handoffBusy = false
    private val handoffHost = object : Handoff.Host {
        override fun adoptNow(onShown: Runnable): Boolean {
            if (isFinishing || isDestroyed || Handoff.back == null) return false
            adoptBack(onShown)
            return true
        }
        override fun fullShown() { endHandoff() }
        override fun coverUi() { if (!isDestroyed) coverUiNow(false) }
    }

    /** lo schermo intero mostra il video: via l'anteprima ingrandita rimasta sotto (ormai coperta) */
    private fun endHandoff() {
        if (!handoffBusy) return
        handoffBusy = false
        val keep = previewEngine?.view
        for (i in previewBox.childCount - 1 downTo 0) if (previewBox.getChildAt(i) !== keep) previewBox.removeViewAt(i)
        if (previewEngine == null) previewBox.visibility = View.GONE
    }

    /* velo nero sopra l'interfaccia: niente lampi della pagina tra schermo intero e app (film, VLC, altro canale) */
    private var uiVeil: View? = null
    private fun coverUiNow(fade: Boolean) {
        val v = uiVeil ?: View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; isFocusable = false }.also {
            uiVeil = it
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        v.animate().cancel()
        v.bringToFront()
        v.visibility = View.VISIBLE
        if (fade && v.alpha < 1f) v.animate().alpha(1f).setStartDelay(0).setDuration(140).start() else v.alpha = 1f
    }
    private fun uncoverUi() {
        val v = uiVeil ?: return
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        v.animate().alpha(0f).setStartDelay(160).setDuration(260).withEndAction { v.visibility = View.GONE }.start()
    }

    /** WebView pronta: non ancora creata (WebView di sistema in aggiornamento) o morta (processo grafico chiuso) → niente chiamate */
    private var webDead = false
    private val webOk: Boolean get() = ::web.isInitialized && !webDead
    private fun js(code: String) { runOnUiThread { if (webOk) web.evaluateJavascript(code, null) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("kairos", MODE_PRIVATE)
        applyOrientation(prefs.getString("orient", "auto") ?: "auto")

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // ispezione remota (chrome://inspect) solo nelle build di debug, mai nell'APK pubblicato
        try { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) } catch (_: Exception) {}
        web = try { WebView(this) } catch (e: Throwable) {
            // WebView di sistema assente o in aggiornamento (capita dopo un aggiornamento di Fire OS): messaggio invece di un crash
            setContentView(TextView(this).apply {
                setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE); textSize = 22f; gravity = android.view.Gravity.CENTER; setPadding(60, 60, 60, 60)
                text = "Il sistema sta aggiornando un suo componente (WebView).\nAttendi un minuto e riapri l'app."
            })
            return
        }
        web.setBackgroundColor(Color.BLACK)
        // nessun layer forzato: la WebView disegna direttamente (un layer hardware aggiunge una copia a schermo intero per ogni frame, pesante su Fire TV)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isFocusable = false }
        root.addView(previewBox, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        hideSystemUi()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true // consente al player di leggere playlist da qualsiasi server
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString KairosPlayer/${BuildConfig.VERSION_NAME} KairosTV/${BuildConfig.VERSION_NAME}"
            // sicurezza: niente content://, niente finestre/popup aperti da script, niente posizione
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "KairosNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // il player non deve mai navigare fuori dai propri file (il ponte KairosNative è esposto a ogni pagina caricata)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAppUrl(request.url.toString())) return false
                // link esterno toccato dall'utente nella pagina principale: si apre nel browser, mai dentro l'app
                if (request.isForMainFrame && request.hasGesture()) openExternal(request.url)
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !isAppUrl(url)

            /** pagine esterne dentro un iframe: bloccate (vedrebbero anche il ponte KairosNative) */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (request.isForMainFrame) return null
                val s = request.url.scheme ?: return null
                if (!s.equals("http", true) && !s.equals("https", true)) return null
                val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: return null
                if (!accept.startsWith("text/html")) return null // immagini, script, XHR/fetch passano come sempre
                return android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap<String, String>(), java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // la WebView morta non si può più usare: tolta e distrutta, poi la schermata si ricrea
                webDead = true
                try { (view.parent as? ViewGroup)?.removeView(view); view.destroy() } catch (_: Throwable) {}
                ui.post { if (!isFinishing && !isDestroyed) recreate() }
                return true
            }
        }

        thread(name = "kx-clean") {
            try { netDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
            try {
                for (d in listOfNotNull(File(cacheDir, "apk"), externalCacheDir)) {
                    val f = File(d, "kairos-update.apk")
                    if (f.exists() && System.currentTimeMillis() - f.lastModified() > 10 * 60_000L && !apkBusy.get()) f.delete()
                }
            } catch (_: Exception) {}
        }
        // registrazioni e VPN: notifiche verso la pagina; le registrazioni programmate vengono riattivate
        KxVpn.listener = vpnListener
        loadUi()
        web.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!webOk) { finish(); return }
                web.evaluateJavascript("(window.kxBack && window.kxBack()) ? 1 : 0") { r ->
                    if (r != "1") {
                        val now = System.currentTimeMillis()
                        if (now - lastBack < 2000) finish()
                        else {
                            lastBack = now
                            Toast.makeText(this@MainActivity, "Premi di nuovo Indietro per uscire", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Interfaccia aggiornabile (OTA)                                      */
    /* ------------------------------------------------------------------ */
    private fun loadUi() {
        val fails = prefs.getInt("ui_fail", 0)
        val sameApk = prefs.getInt("ui_apk", -1) == BuildConfig.VERSION_CODE
        val useOta = otaFile.exists() && sameApk && fails < 2
        if (otaFile.exists() && !useOta) resetOta(markBad = fails >= 2 && sameApk)
        uiOk = false
        if (useOta) {
            prefs.edit().putInt("ui_fail", fails + 1).apply()
            web.loadUrl("file://" + otaFile.absolutePath)
            // se la versione scaricata non parte entro 15 secondi, torna a quella inclusa nell'APK
            ui.postDelayed({ if (!uiOk) { resetOta(markBad = true); web.loadUrl(HOME) } }, 15_000)
        } else web.loadUrl(HOME)
    }

    /** [markBad]: la versione scaricata non partiva → non va più riapplicata (evita un ciclo di riavvii) */
    private fun resetOta(markBad: Boolean = false) {
        if (markBad) prefs.getString("ui_ver", null)?.let { prefs.edit().putString("ui_bad", it).apply() }
        otaDir.deleteRecursively()
        prefs.edit().remove("ui_ver").remove("ui_apk").putInt("ui_fail", 0).apply()
    }

    /* ------------------------------------------------------------------ */
    /* Anteprima nativa nel riquadro della Live TV                         */
    /* ------------------------------------------------------------------ */
    private var previewEngine: ExoEngine? = null
    private var previewId = ""
    private var previewAlt = ""
    private var previewTriedAlt = false

    private val previewListener = object : EngineListener {
        override fun onBuffering(buffering: Boolean) { js("window.kxPreviewState && kxPreviewState('${if (buffering) "loading" else "ready"}')") }
        override fun onReady() {
            val h = previewEngine?.videoHeight() ?: 0
            js("window.kxPreviewState && kxPreviewState('ready'); window.kxPreviewInfo && kxPreviewInfo(${JSONObject.quote(previewId)}, $h)")
        }
        override fun onError(message: String) {
            ui.post {
                if (!previewTriedAlt && previewAlt.isNotEmpty()) { previewTriedAlt = true; previewEngine?.play(previewAlt, 0) }
                else js("window.kxPreviewState && kxPreviewState('error')")
            }
        }
        override fun onEnded() {}
    }

    private fun showPreview(json: String) {
        if (handoffBusy) return // il video sta passando allo schermo intero: nessuna seconda connessione
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val vw = o.optDouble("vw", 0.0)
        if (vw <= 0 || web.width == 0) return
        val scale = web.width / vw
        val lp = FrameLayout.LayoutParams((o.optInt("w") * scale).toInt(), (o.optInt("h") * scale).toInt())
        lp.leftMargin = (o.optInt("x") * scale).toInt()
        lp.topMargin = (o.optInt("y") * scale).toInt()
        previewBox.layoutParams = lp
        previewBox.visibility = View.VISIBLE
        previewWanted = true
        Handoff.box = intArrayOf(lp.leftMargin, lp.topMargin, lp.width, lp.height)
        val e = previewEngine ?: ExoEngine(this, previewListener, userAgent(), PlayPrefs("auto", "fast", "fit", live = true), overlay = true).also {
            previewEngine = it
            previewBox.addView(it.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val id = o.optString("id")
        if (id == previewId && e.isActive()) { js("window.kxPreviewState && kxPreviewState('ready')"); return } // già in onda
        previewId = id
        previewAlt = o.optString("a")
        previewTriedAlt = false
        e.play(o.optString("u"), 0)
    }

    private fun stopPreview() {
        boxAnim?.cancel()
        previewEngine?.stop()
        previewId = ""
        // durante il passaggio allo schermo intero il riquadro ingrandito (ultimo fotogramma) deve restare visibile
        // finché il player non mostra il video: nasconderlo qui (es. onPause) faceva comparire la pagina web
        if (!handoffBusy) previewBox.visibility = View.GONE
    }

    /* il riquadro dell'anteprima si allarga fino a riempire lo schermo (come nelle app TV) */
    private var boxAnim: android.animation.ValueAnimator? = null
    private fun growBox(cancelled: () -> Unit = {}, done: () -> Unit) {
        val lp0 = previewBox.layoutParams as FrameLayout.LayoutParams
        val x0 = lp0.leftMargin; val y0 = lp0.topMargin; val w0 = lp0.width; val h0 = lp0.height
        val W = root.width; val H = root.height
        if (W <= 0 || w0 <= 0) { done(); return }
        boxAnim?.cancel()
        boxAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                previewBox.layoutParams = FrameLayout.LayoutParams((w0 + (W - w0) * f).toInt(), (h0 + (H - h0) * f).toInt()).apply {
                    leftMargin = (x0 * (1 - f)).toInt(); topMargin = (y0 * (1 - f)).toInt()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var c = false
                override fun onAnimationCancel(animation: android.animation.Animator) { c = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (c) cancelled() else done() }
            })
            start()
        }
    }

    private fun launchPlayer(noAnim: Boolean) {
        Handoff.host = handoffHost
        val i = Intent(this@MainActivity, PlayerActivity::class.java)
        if (noAnim) playerLauncher.launch(i, androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this, 0, 0))
        else playerLauncher.launch(i)
    }

    /** schermo intero: se l'anteprima mostra già quel canale, lo stesso video si allarga e continua */
    /** OK premuto due volte di fila: un solo player (due istanze sovrapposte = due video e doppio audio) */
    private var playerAskT = 0L
    private fun startPlayer(o: JSONObject) {
        val t = android.os.SystemClock.elapsedRealtime()
        if (t - playerAskT < 900) return
        playerAskT = t
        val e = previewEngine
        var handed = false
        try {
            if (o.optString("kind") == "live" && o.optString("engine") != "vlc" && e != null && previewBox.visibility == View.VISIBLE && e.isActive()) {
                if (o.optString("id") == previewId) {
                    handed = true
                    growBox(cancelled = {
                        // animazione interrotta (riquadro chiuso o app in pausa): il player parte comunque, oppure si rinuncia
                        if (isResumed) { Handoff.box = null; stopPreview(); coverUiNow(true); launchPlayer(false) }
                        else { PlayerActivity.pending = null; PlayerActivity.pendingJson = null }
                    }) {
                        try {
                            // Il riquadro, ora a tutto schermo, resta qui con il video (poi con l'ultimo fotogramma)
                            // finché il player sopra non ha disegnato il video sulla sua superficie: solo allora
                            // chiama fullShown() e il riquadro sparisce. Prima la vista veniva staccata subito e
                            // onPause nascondeva il riquadro: si vedeva la pagina/il nero fino al primo fotogramma.
                            e.l = NoopListener
                            Handoff.engine = e
                            Handoff.id = previewId
                            handoffBusy = true
                            previewEngine = null
                            previewId = ""
                            launchPlayer(noAnim = true)
                        } catch (x: Exception) {
                            handoffBusy = false
                            if (Handoff.engine === e) { Handoff.engine = null; previewEngine = e; e.l = previewListener }
                            stopPreview(); coverUiNow(true); launchPlayer(false)
                        }
                    }
                }
            }
        } catch (x: Exception) { handed = false }
        // uscendo, nessun riquadro in cui tornare; velo nero: la pagina non lampeggia prima del player
        if (!handed) { Handoff.box = null; stopPreview(); coverUiNow(true); launchPlayer(false) }
    }

    /** layout orizzontale fisso (telefoni e tablet); sulle TV non cambia nulla */
    private fun applyOrientation(mode: String) {
        requestedOrientation = if (mode == "land") android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun userAgent(): String = try {
        WebSettings.getDefaultUserAgent(this) + " KairosPlayer/" + BuildConfig.VERSION_NAME + " KairosTV/" + BuildConfig.VERSION_NAME
    } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) KairosPlayer/" + BuildConfig.VERSION_NAME + " KairosTV/" + BuildConfig.VERSION_NAME }

    /* ------------------------------------------------------------------ */
    /* Aggiornamento APK                                                   */
    /* ------------------------------------------------------------------ */
    private val apkBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun downloadAndInstall(url: String) {
        if (!apkBusy.compareAndSet(false, true)) return // un solo download alla volta
        thread(name = "kx-apk") {
            try {
                val dir = if (Build.VERSION.SDK_INT >= 24) File(cacheDir, "apk") else (externalCacheDir ?: cacheDir)
                dir.mkdirs()
                val out = File(dir, "kairos-update.apk")
                var target = URL(url)
                var conn = target.openConnection() as HttpURLConnection
                var hops = 0
                while (true) {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    if (code in 300..399 && loc != null && hops < 8) {
                        val next = URL(target, loc)
                        if (target.protocol == "https" && next.protocol != "https") throw Exception("il link dell'APK rimanda a un indirizzo non sicuro (http)")
                        conn.disconnect(); target = next; hops++
                        conn = target.openConnection() as HttpURLConnection
                        continue
                    }
                    if (code != 200) throw Exception(if (code == 404) "file non trovato (HTTP 404): il link dell'APK non è pubblico o è sbagliato" else "HTTP $code")
                    break
                }
                val total = conn.contentLength.toLong()
                var done = 0L
                var last = -1
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fo ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt().coerceAtMost(99)
                                if (p != last) { last = p; js("window.kxApkCb && kxApkCb($p)") }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (total > 0 && done != total) { out.delete(); throw Exception("download interrotto: controlla la connessione e riprova") }
                // deve essere davvero un APK (un link sbagliato spesso scarica una pagina web)
                val head = ByteArray(2)
                java.io.FileInputStream(out).use { it.read(head) }
                if (out.length() < 500_000 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                    out.delete(); throw Exception("il link non porta a un file APK")
                }
                // stessa versione (o più vecchia) o firma diversa: l'installer non va aperto (evita il ciclo di aggiornamenti)
                ApkCheck.verify(this, out)?.let { msg -> out.delete(); throw Exception(msg) }
                js("window.kxApkCb && kxApkCb(100)")
                runOnUiThread { launchInstaller(out) }
            } catch (e: Exception) {
                js("window.kxApkCb && kxApkCb(-1, ${JSONObject.quote(e.message ?: e.javaClass.simpleName)})")
            } finally {
                apkBusy.set(false)
            }
        }
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Consenti a ${getString(R.string.app_name)} di installare app, poi premi Indietro", Toast.LENGTH_LONG).show()
            pendingApk = apk
            js("window.kxApkCb && kxApkCb(-2)")
            // Fire TV: la schermata per singola app non sempre esiste → si apre quella generale
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in tries) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
            return
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(this, "$packageName.files", apk) else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Installazione non disponibile su questo dispositivo", Toast.LENGTH_LONG).show() }
    }

    private var pendingApk: File? = null

    /* ------------------------------------------------------------------ */
    /* Download su file (playlist grandi) + cache dei contenuti            */
    /* ------------------------------------------------------------------ */
    private val netDir: File get() = File(cacheDir, "net").apply { mkdirs() }
    private val libDir: File get() = File(filesDir, "lib").apply { mkdirs() }
    private val httpPool = java.util.concurrent.Executors.newFixedThreadPool(6)

    /** scarica [url] in un file e restituisce (stato, percorso). Il testo non passa dal ponte JS: molto più veloce. */
    private fun fetchToFile(id: String, url: String, ua: String) {
        httpPool.execute {
            var status = -1
            var path = ""
            var err = ""
            try {
                var target = URL(url)
                var hops = 0
                var conn = target.openConnection() as HttpURLConnection
                while (true) {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 45_000
                    conn.setRequestProperty("User-Agent", ua.ifBlank { userAgent() })
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    if (code in 300..399 && loc != null && hops < 6) {
                        conn.disconnect(); target = URL(target, loc); hops++
                        conn = target.openConnection() as HttpURLConnection
                        continue
                    }
                    status = code
                    break
                }
                val raw = (if (status >= 400) conn.errorStream else conn.inputStream)
                val input = if (raw != null && "gzip".equals(conn.contentEncoding, true)) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
                val out = File(netDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")
                input?.use { inp -> FileOutputStream(out).use { fo -> inp.copyTo(fo, 128 * 1024) } }
                conn.disconnect()
                path = "file://" + out.absolutePath
            } catch (e: Exception) {
                status = -1
                err = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            }
            js("window.kxFileCb && window.kxFileCb(${JSONObject.quote(id)}, $status, ${JSONObject.quote(path)}, ${JSONObject.quote(err)})")
        }
    }


    /* ------------------------------------------------------------------ */
    /* Guida TV completa (XMLTV): letta in streaming, si tengono solo i     */
    /* canali della lista e le prossime ore → file JSON piccolo per l'app   */
    /* ------------------------------------------------------------------ */
    @Volatile private var epgRunning = false
    private val epgFile: File get() = File(filesDir, "epg.json")
    private val RX_CC = Regex("^[\\s|\\[\\](){}#*.]*([A-Z]{2}|ITA|UK|USA|ESP|FRA|GER|DEU|ALB|POR|NLD|POL|ROM|TUR|SUI|AUT|BEL|GRE)\\s*[|:\\])-]+\\s*")
    private val RX_BR = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
    private val RX_QUAL = Regex("(?<![a-z0-9_])(fhd|uhd|hd|sd|4k|8k|hevc|h ?26[45]|1080[pi]?|720p?|576[pi]?|480p?|2160p?|[0-9]{2}fps|fps|backup|bkp|raw|multi|vip|hq|lq|low|high|test|new|nuovo|orig|original|feed|direct|dash)(?![a-z0-9_])")
    private val RX_MARKS = Regex("\\p{M}+")
    private val RX_NONALNUM = Regex("[^a-z0-9+]+")

    /** stessa normalizzazione dei nomi usata dall'app (logoKey): "IT| RAI 1 HD" → "rai1" */
    private fun epgKey(name: String): String {
        var s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(RX_MARKS, "")
        RX_CC.find(s)?.let { s = s.substring(it.range.last + 1) }
        s = s.lowercase(java.util.Locale.ROOT).replace(RX_BR, " ").replace("&", " and ").replace(RX_QUAL, " ").replace(RX_NONALNUM, "")
        return if (s.length >= 2) s else ""
    }

    /** "20261102033500 +0100" → secondi UTC */
    private fun xmltvTime(v: String?): Long {
        if (v == null || v.length < 12) return 0
        return try {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(v.substring(0, 4).toInt(), v.substring(4, 6).toInt() - 1, v.substring(6, 8).toInt(), v.substring(8, 10).toInt(), v.substring(10, 12).toInt(), if (v.length >= 14 && v[12].isDigit()) v.substring(12, 14).toInt() else 0)
            var t = c.timeInMillis / 1000
            val si = (12 until v.length).firstOrNull { v[it] == '+' || v[it] == '-' }
            val tz = if (si != null) v.substring(si).trim() else ""
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val sign = if (tz[0] == '-') -1 else 1
                t -= sign * (tz.substring(1, 3).toInt() * 3600 + tz.substring(3, 5).toInt() * 60)
            }
            t
        } catch (e: Exception) { 0 }
    }

    private fun downloadTo(url: String, out: File) {
        var target = URL(url)
        var hops = 0
        var conn = target.openConnection() as HttpURLConnection
        while (true) {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", userAgent())
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            if (code in 300..399 && loc != null && hops < 6) { conn.disconnect(); target = URL(target, loc); hops++; conn = target.openConnection() as HttpURLConnection; continue }
            if (code != 200) throw Exception("HTTP $code")
            break
        }
        val raw = conn.inputStream
        val input = if ("gzip".equals(conn.contentEncoding, true)) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
        input.use { inp -> FileOutputStream(out).use { fo -> inp.copyTo(fo, 256 * 1024) } }
        conn.disconnect()
    }

    private class Prog(val s: Long, val e: Long, val t: String, val d: String)
    private val EPG_MAX_TOTAL = 90_000
    private val EPG_MAX_CH = 48

    private fun parseXmltv(f: File, ids: Set<String>, names: Set<String>, map: JSONObject, progs: HashMap<String, ArrayList<Prog>>, from: Long, to: Long, already: Int): Int {
        val raw = java.io.BufferedInputStream(java.io.FileInputStream(f), 256 * 1024)
        raw.mark(4)
        val b1 = raw.read(); val b2 = raw.read()
        raw.reset()
        val input: java.io.InputStream = if (b1 == 0x1f && b2 == 0x8b) java.util.zip.GZIPInputStream(raw, 256 * 1024) else raw // file .xml.gz
        var n = 0
        input.use { stream ->
            val p = android.util.Xml.newPullParser()
            p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(stream, null)
            val matched = HashSet<String>()
            var chId: String? = null
            var chKeys = ArrayList<String>()
            var inProg = false
            var pCh = ""; var pStart = 0L; var pStop = 0L; var title = ""; var desc = ""
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { chId = p.getAttributeValue(null, "id"); chKeys = ArrayList() }
                        "display-name" -> if (chId != null) { val k = epgKey(p.nextText() ?: ""); if (k.isNotEmpty()) chKeys.add(k) }
                        "programme" -> {
                            pCh = p.getAttributeValue(null, "channel") ?: ""
                            inProg = false
                            if (pCh in matched) {
                                pStart = xmltvTime(p.getAttributeValue(null, "start")); pStop = xmltvTime(p.getAttributeValue(null, "stop"))
                                inProg = pStop > from && pStart < to && pStop > pStart
                                title = ""; desc = ""
                            }
                        }
                        "title" -> if (inProg && title.isEmpty()) title = (p.nextText() ?: "").trim()
                        "desc" -> if (inProg && desc.isEmpty()) desc = (p.nextText() ?: "").trim().take(200)
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = chId
                            if (id != null) {
                                val keys = ArrayList<String>()
                                val low = id.lowercase(java.util.Locale.ROOT)
                                if (low in ids) keys.add("id:$low")
                                for (k in chKeys) if (k in names) keys.add("n:$k")
                                val k2 = epgKey(id.substringBeforeLast('.'))
                                if (k2.isNotEmpty() && k2 in names) keys.add("n:$k2")
                                if (keys.isNotEmpty()) { matched.add(id); map.put(id, org.json.JSONArray(keys.distinct())) }
                            }
                            chId = null
                        }
                        "programme" -> if (inProg) {
                            inProg = false
                            val list = progs.getOrPut(pCh) { ArrayList() }
                            // niente doppioni (due sorgenti con lo stesso canale) e limiti per non esaurire la memoria
                            if (list.size < EPG_MAX_CH && already + n < EPG_MAX_TOTAL && list.none { it.s == pStart }) { list.add(Prog(pStart, pStop, title, desc)); n++ }
                        }
                    }
                }
                ev = p.next()
            }
        }
        return n
    }

    private fun buildEpg(id: String, urlsJson: String, wantJson: String, hoursAhead: Int) {
        fun cb(ok: Boolean, n: Int, err: String) = js("window.kxEpgCb && kxEpgCb(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(if (ok) "file://" + epgFile.absolutePath else "")}, $n, ${JSONObject.quote(err)})")
        if (epgRunning) { cb(false, 0, "già in corso"); return }
        epgRunning = true
        thread(name = "kx-epg") {
            var n = 0
            val errs = ArrayList<String>()
            try {
                val urls = org.json.JSONArray(urlsJson)
                val want = JSONObject(wantJson)
                val ids = HashSet<String>(); val names = HashSet<String>()
                want.optJSONArray("ids")?.let { a -> for (i in 0 until a.length()) ids.add(a.optString(i).lowercase(java.util.Locale.ROOT)) }
                want.optJSONArray("names")?.let { a -> for (i in 0 until a.length()) names.add(a.optString(i)) }
                val now = System.currentTimeMillis() / 1000
                val map = JSONObject()
                val progs = HashMap<String, ArrayList<Prog>>()
                for (u in 0 until urls.length()) {
                    val f = File(cacheDir, "epg_$u.xml")
                    try { downloadTo(urls.getString(u), f); n += parseXmltv(f, ids, names, map, progs, now - 2 * 3600, now + hoursAhead * 3600L, n) }
                    catch (e: Throwable) { errs.add(if (e is OutOfMemoryError) "guida troppo grande" else e.message ?: e.javaClass.simpleName) }
                    finally { f.delete() }
                }
                if (n > 0) {
                    // scrittura in streaming: nessuna stringa gigante in memoria
                    val tmp = File(filesDir, "epg.tmp")
                    android.util.JsonWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8), 128 * 1024)).use { w ->
                        w.beginObject()
                        w.name("t").value(now)
                        w.name("map").beginObject()
                        val it = map.keys()
                        while (it.hasNext()) { val k = it.next(); val a = map.getJSONArray(k); w.name(k).beginArray(); for (i in 0 until a.length()) w.value(a.getString(i)); w.endArray() }
                        w.endObject()
                        w.name("p").beginObject()
                        for ((k, list) in progs) {
                            if (list.isEmpty()) continue
                            w.name(k).beginArray()
                            for (pr in list.sortedBy { it.s }) { w.beginArray(); w.value(pr.s); w.value(pr.e); w.value(pr.t); w.value(pr.d); w.endArray() }
                            w.endArray()
                        }
                        w.endObject()
                        w.endObject()
                    }
                    tmp.renameTo(epgFile)
                    cb(true, n, "")
                } else cb(false, 0, errs.firstOrNull() ?: "nessun programma trovato per i tuoi canali")
            } catch (e: Throwable) { cb(false, 0, if (e is OutOfMemoryError) "guida troppo grande per questo dispositivo" else e.message ?: e.javaClass.simpleName) }
            finally { epgRunning = false }
        }
    }

    private fun libFile(key: String) = File(libDir, key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + ".json")

    /* ------------------------------------------------------------------ */
    /* Registrazioni, VPN e sicurezza della WebView                        */
    /* ------------------------------------------------------------------ */
    private val vpnListener: (String, String) -> Unit = { st, msg -> js("window.kxVpnCb && kxVpnCb(${JSONObject.quote(st)}, ${JSONObject.quote(msg)})") }

    /** consenso di Android per la VPN (VpnService.prepare): la configurazione resta solo in memoria */
    private var vpnConf: String? = null
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val c = vpnConf
        vpnConf = null
        if (c != null && res.resultCode == RESULT_OK) KxVpn.start(this, c) else KxVpn.denied()
    }

    private fun startVpn(conf: String) {
        val bad = KxVpn.check(conf)
        if (bad != null) { KxVpn.fail(bad); return }
        val consent = try { android.net.VpnService.prepare(this) } catch (e: Exception) {
            KxVpn.fail("Questo dispositivo non permette di usare una VPN"); return
        }
        if (consent == null) { KxVpn.start(this, conf); return }
        vpnConf = conf
        KxVpn.waitingConsent()
        try { vpnLauncher.launch(consent) } catch (e: Exception) {
            vpnConf = null
            KxVpn.fail("Questo dispositivo non permette di usare una VPN")
        }
    }

    /** solo i file dell'app: asset dell'APK o interfaccia aggiornata in files/www (percorso canonico, niente "..") */
    private fun isAppUrl(u: String): Boolean {
        if (u.startsWith("file:///android_asset/")) return !u.contains("..")
        if (!u.startsWith("file://")) return false
        return try {
            val p = Uri.parse(u).path ?: return false
            File(p).canonicalPath.startsWith(otaDir.canonicalPath + File.separator)
        } catch (_: Exception) { false }
    }

    private fun openExternal(u: Uri) {
        val s = u.scheme?.lowercase() ?: return
        if (s != "http" && s != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Nessun browser disponibile su questo dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    /* ------------------------------------------------------------------ */

    /** Tasti canale +/- del telecomando → zapping nel player */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> "ChannelUp"
            KeyEvent.KEYCODE_CHANNEL_DOWN -> "ChannelDown"
            KeyEvent.KEYCODE_MENU -> "ContextMenu"
            else -> null
        }
        if (key != null) {
            if (event.action == KeyEvent.ACTION_DOWN && webOk) {
                web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'$key',bubbles:true}))", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        isResumed = false
        stopPreview()
        if (webOk) web.onPause()
        super.onPause()
    }

    override fun onResume() {
        isResumed = true
        super.onResume()
        if (!webOk) return
        web.onResume()
        web.requestFocus()
        js("window.kxResume && kxResume()")
        // tornando dalle impostazioni "installa app sconosciute"
        val apk = pendingApk
        if (apk != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) { pendingApk = null; launchInstaller(apk) }
    }

    override fun onDestroy() {
        if (KxVpn.listener === vpnListener) KxVpn.listener = null
        if (isFinishing) {
            // uscendo dall'app la VPN (che riguarda solo questa app) si spegne, salvo registrazioni in corso o programmate
            if (KxVpn.active()) KxVpn.stop()
            // video passato tra le schermate e non più ripreso da nessuno: niente audio orfano dopo la chiusura
            Handoff.back?.let { Handoff.back = null; if (it !== previewEngine) it.release() }
            Handoff.engine?.let { Handoff.engine = null; if (it !== previewEngine) it.release() }
            if (Handoff.host === handoffHost) Handoff.host = null
        }
        ui.removeCallbacksAndMessages(null)
        previewEngine?.release()
        previewEngine = null
        httpPool.shutdownNow()
        if (webOk) { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    /** Funzioni esposte al player come window.KairosNative */
    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun versionCode(): Int = BuildConfig.VERSION_CODE
        /** Kairos TV (l'interfaccia nasconde le funzioni che non ha) */
        @JavascriptInterface fun edition(): String = "tv"
        @JavascriptInterface fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(70)
        /** rete (wifi/eth/mobile, segnale Wi-Fi) e dati della visione in corso, per il pannello "Online ora" */
        @JavascriptInterface fun netInfo(): String = NetInfo.json(applicationContext)
        @JavascriptInterface fun playStats(): String = PlayStats.json()
        /** problemi della visione per «Errori applicazione» (letti e svuotati) */
        @JavascriptInterface fun playEvents(): String = PlayStats.drainEvents()
        @JavascriptInterface fun openUrl(url: String) { runOnUiThread { try { openExternal(Uri.parse(url)) } catch (_: Exception) {} } }
        /** TV (Android TV / Google TV / Fire TV): la pagina non usa l'interfaccia touch anche se il puntatore è "coarse" */
        @JavascriptInterface fun isTv(): Boolean = try {
            (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
                packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        } catch (_: Exception) { false }
        @JavascriptInterface fun exit() { runOnUiThread { finish() } }

        /** l'interfaccia è partita correttamente */
        @JavascriptInterface fun uiReady() { uiOk = true; prefs.edit().putInt("ui_fail", 0).apply() }
        @JavascriptInterface fun uiVersion(): String = prefs.getString("ui_ver", "") ?: ""

        /** installa una nuova versione dell'interfaccia scaricata dal pannello e la ricarica */
        @JavascriptInterface fun uiBad(): String = prefs.getString("ui_bad", "") ?: ""
        @JavascriptInterface fun applyUi(html: String, version: String) {
            if (version == prefs.getString("ui_bad", "")) return
            try {
                otaDir.mkdirs()
                File(otaDir, "index.tmp").writeText(html, Charsets.UTF_8)
                val cfg = try { assets.open("www/config.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
                File(otaDir, "config.js").writeText(cfg, Charsets.UTF_8)
                File(otaDir, "index.tmp").renameTo(otaFile)
                prefs.edit().putString("ui_ver", version).putInt("ui_apk", BuildConfig.VERSION_CODE).putInt("ui_fail", 0).apply()
                runOnUiThread { loadUi() }
            } catch (e: Exception) { resetOta() }
        }

        /** prepara la nuova interfaccia senza ricaricare: verrà usata al prossimo avvio */
        @JavascriptInterface fun stageUi(html: String, version: String) {
            if (version == prefs.getString("ui_bad", "")) return
            try {
                otaDir.mkdirs()
                File(otaDir, "index.tmp").writeText(html, Charsets.UTF_8)
                val cfg = try { assets.open("www/config.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
                File(otaDir, "config.js").writeText(cfg, Charsets.UTF_8)
                File(otaDir, "index.tmp").renameTo(otaFile)
                prefs.edit().putString("ui_ver", version).putInt("ui_apk", BuildConfig.VERSION_CODE).putInt("ui_fail", 0).apply()
            } catch (e: Exception) { resetOta() }
        }
        @JavascriptInterface fun setOrientation(mode: String) {
            prefs.edit().putString("orient", mode).apply()
            runOnUiThread { applyOrientation(mode) }
        }
        @JavascriptInterface fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

        /** Avvia il player nativo (ExoPlayer / VLC) */
        @JavascriptInterface fun play(payload: String) {
            // la lista (anche migliaia di canali) si analizza qui, sul thread del ponte JS: il thread dell'interfaccia non si blocca
            val j = try { JSONObject(payload) } catch (e: Exception) { return }
            PlayerActivity.pendingJson = j
            PlayerActivity.pending = null
            // al thread dell'interfaccia servono solo tipo, motore e id del canale
            val head = JSONObject().put("kind", j.optString("kind")).put("engine", j.optString("engine"))
                .put("id", j.optJSONArray("items")?.optJSONObject(j.optInt("index"))?.optString("id") ?: "")
            runOnUiThread { startPlayer(head) }
        }

        @JavascriptInterface fun preview(json: String) { runOnUiThread { showPreview(json) } }
        @JavascriptInterface fun previewStop() { runOnUiThread { previewWanted = false; stopPreview() } }
        /** «Disconnetti» dal pannello: chiude il player a schermo intero, se aperto */
        @JavascriptInterface fun playerClose() { runOnUiThread { PlayerActivity.current?.killByPanel() } }
        @JavascriptInterface fun installApk(url: String) { downloadAndInstall(url) }
        /** VPN WireGuard (testo wg-quick dal pannello). Stato in window.kxVpnCb(state, message) */
        @JavascriptInterface fun vpnSupported(): Boolean = KxVpn.supported(this@MainActivity)
        @JavascriptInterface fun vpnStart(conf: String) { runOnUiThread { startVpn(conf) } }
        @JavascriptInterface fun vpnStop() { KxVpn.stop() }
        @JavascriptInterface fun vpnState(): String = KxVpn.state

        /** scarica un indirizzo in un file locale; risposta in window.kxFileCb(id, status, path, error) */
        @JavascriptInterface fun httpFile(id: String, url: String, ua: String) { fetchToFile(id, url, ua) }
        /** cancella un file scaricato con httpFile */
        @JavascriptInterface fun dropFile(path: String) {
            try { val f = File(Uri.parse(path).path ?: return).canonicalFile; if (f.path.startsWith(netDir.canonicalPath + File.separator)) f.delete() } catch (_: Exception) {}
        }
        /** cache della libreria (canali, film, serie) per un avvio istantaneo */
        @JavascriptInterface fun cachePut(key: String, data: String) {
            // scrittura fuori dal thread del ponte (la pagina non resta bloccata) e in streaming (nessuna seconda copia in memoria)
            httpPool.execute {
                try {
                    val f = libFile(key); val t = File(f.path + ".tmp")
                    java.io.OutputStreamWriter(java.io.FileOutputStream(t), Charsets.UTF_8).buffered(64 * 1024).use { it.write(data) }
                    if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
                } catch (_: Exception) {}
            }
        }
        @JavascriptInterface fun cachePath(key: String): String { val f = libFile(key); return if (f.exists()) "file://" + f.absolutePath else "" }
        /** guida TV completa: urls = sorgenti XMLTV, want = {ids:[tvg-id], names:[nomi normalizzati]} */
        @JavascriptInterface fun epgBuild(id: String, urls: String, want: String, hours: Int) { buildEpg(id, urls, want, hours.coerceIn(6, 72)) }
        @JavascriptInterface fun epgPath(): String = if (epgFile.exists()) "file://" + epgFile.absolutePath else ""
        @JavascriptInterface fun cacheClear() { try { libDir.deleteRecursively() } catch (_: Exception) {} }

        /** Richiesta HTTP fatta dall'app (senza limiti CORS). Risposta in window.kxHttpCb(id, status, body). */
        @JavascriptInterface fun http(id: String, method: String, url: String, body: String, ua: String) {
            httpPool.execute { // al massimo 6 richieste insieme (prima un thread nuovo per ogni chiamata)
                var status = -1
                var text: String
                try {
                    var target = URL(url)
                    var hops = 0
                    var conn: HttpURLConnection = target.openConnection() as HttpURLConnection
                    while (true) {
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 30_000
                        conn.requestMethod = if (method == "POST") "POST" else "GET"
                        conn.setRequestProperty("User-Agent", ua.ifBlank { "KairosPlayer/" + BuildConfig.VERSION_NAME + " KairosTV/" + BuildConfig.VERSION_NAME })
                        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                        if (method == "POST") {
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        }
                        val code = conn.responseCode
                        val loc = conn.getHeaderField("Location")
                        if (code in 300..399 && loc != null && hops < 5 && method != "POST") {
                            conn.disconnect()
                            target = URL(target, loc)
                            hops++
                            conn = target.openConnection() as HttpURLConnection
                            continue
                        }
                        status = code
                        break
                    }
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val out = ByteArrayOutputStream()
                    stream?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 80 * 1024 * 1024) break
                            out.write(buf, 0, n)
                        }
                    }
                    conn.disconnect()
                    text = out.toString("UTF-8")
                } catch (e: Throwable) {
                    status = -1
                    // dispositivi con poca memoria: una risposta enorme non deve chiudere l'app
                    text = if (e is OutOfMemoryError) "risposta troppo grande per la memoria del dispositivo" else e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                val js = try { "window.kxHttpCb && window.kxHttpCb(${JSONObject.quote(id)}, $status, ${JSONObject.quote(text)})" }
                    catch (e: OutOfMemoryError) { "window.kxHttpCb && window.kxHttpCb(${JSONObject.quote(id)}, -1, 'risposta troppo grande per la memoria del dispositivo')" }
                runOnUiThread { if (webOk) web.evaluateJavascript(js, null) }
            }
        }
    }
}
