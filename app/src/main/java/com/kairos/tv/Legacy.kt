package com.kairos.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.io.File

/**
 * Passaggio dalla versione 1 di Kairos TV (interfaccia web dentro l'app) a questa nativa.
 * La versione 1 teneva codice e segreto del dispositivo nella memoria della pagina web (localStorage,
 * chiavi kx_code / kx_secret): dopo l'aggiornamento l'app nativa non li vedeva e creava un codice nuovo,
 * e il cliente andava ricollegato dal pannello. Qui, una volta sola, si leggono con una WebView invisibile
 * sulla stessa origine (file://) e si riprende lo STESSO codice: l'abbonamento resta attivo.
 */
object Legacy {
    private val CODE_RE = Regex("^KX[A-Z0-9]{2}-[A-Z0-9]{4}$")
    private val SECRET_RE = Regex("^[A-Za-z0-9_-]{32,100}$")
    @Volatile var running = false
        private set

    /** c'è una vecchia memoria web da cui recuperare? */
    fun needed(a: Activity): Boolean {
        if (Store.legacyDone) return false
        // il codice attuale è già attivo (cliente ricollegato a mano): si tiene quello, niente da recuperare
        if (Panel.loadCached()?.active == true || !File(a.applicationInfo.dataDir, "app_webview").exists()) { Store.legacyDone = true; return false }
        return true
    }

    /** [done] (true = codice della versione precedente ripreso) */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(a: Activity, done: (Boolean) -> Unit) {
        running = true
        var finished = false
        var wv: WebView? = null
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            running = false
            Store.legacyDone = true
            try { wv?.destroy() } catch (_: Throwable) {}
            done(ok)
        }
        try {
            val w = WebView(a)
            wv = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.allowFileAccess = true
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript("(function(){try{return JSON.stringify([localStorage.getItem('kx_code'),localStorage.getItem('kx_secret')]);}catch(e){return '[]';}})()") { raw ->
                        var ok = false
                        try {
                            // risposta: stringa JSON che contiene l'array; i valori sono a loro volta JSON ("KXAB-7Q2M")
                            val arr = JSONArray(JSONArray("[$raw]").getString(0))
                            fun v(i: Int): String { val x = arr.optString(i, ""); return if (x.startsWith("\"")) JSONArray("[$x]").getString(0) else x }
                            val code = v(0).trim().uppercase()
                            val secret = v(1).trim()
                            if (CODE_RE.matches(code) && SECRET_RE.matches(secret) && (code != Store.code || secret != Store.secret)) {
                                Store.adopt(code, secret)
                                ok = true
                            }
                        } catch (_: Throwable) {}
                        finish(ok)
                    }
                }
            }
            // stessa origine della vecchia interfaccia (file:///android_asset/www/index.html)
            w.loadDataWithBaseURL("file:///android_asset/www/index.html", "<html><body></body></html>", "text/html", "utf-8", null)
            App.main.postDelayed({ finish(false) }, 6000)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
