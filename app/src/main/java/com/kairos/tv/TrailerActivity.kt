package com.kairos.tv

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Trailer di YouTube dentro l'app (da questa versione, come in KAIROS Ultimate): pagina con il lettore ufficiale incorporato,
 * in un processo a parte che si chiude all'uscita, così la memoria del browser interno torna subito libera.
 * Se la pagina non parte entro 15 secondi o YouTube rifiuta l'incorporamento, si apre l'app YouTube del dispositivo.
 * OK = pausa/riprendi, sinistra/destra = 10 secondi, Indietro = chiudi.
 */
class TrailerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var message: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var leaving = false
    private var dead = false
    private var videoId = ""

    companion object {
        fun start(activity: Activity, id: String, title: String) {
            val video = Yt.id(id) ?: return
            activity.startActivity(Intent(activity, TrailerActivity::class.java)
                .putExtra("id", video)
                .putExtra("title", title))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        videoId = intent.getStringExtra("id").orEmpty()
        if (Yt.id(videoId) == null) { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // processo a parte: cartella dei dati del browser separata da quella del processo principale (obbligatorio da Android 9
        // se anche l'app principale ha usato una WebView); va detto prima di creare la pagina
        if (android.os.Build.VERSION.SDK_INT >= 28) try { WebView.setDataDirectorySuffix("trailer") } catch (_: Throwable) {}
        web = try { WebView(this) } catch (_: Throwable) { openYoutubeApp(); return }
        web.apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    dead = true
                    try { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() } catch (_: Throwable) {}
                    if (!leaving) { leaving = true; finish() }
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Kx")
            isFocusable = true
        }
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            text = "Caricamento trailer…\n" + intent.getStringExtra("title").orEmpty()
            gravity = Gravity.CENTER
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(message, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        web.loadDataWithBaseURL("https://kairos.player/", page(videoId), "text/html", "utf-8", null)
        handler.postDelayed({ if (!ready && !leaving) openYoutubeApp() }, 15_000L)
    }

    private fun page(id: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style></head>
<body><div id="p"></div><script src="https://www.youtube.com/iframe_api"></script><script>
var pl;function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{videoId:'$id',host:'https://www.youtube-nocookie.com',
playerVars:{autoplay:1,controls:1,rel:0,playsinline:1,modestbranding:1,fs:0,iv_load_policy:3,hl:'it',cc_lang_pref:'it',origin:'https://kairos.player'},
events:{onReady:function(e){Kx.ready();e.target.playVideo();},onError:function(e){Kx.error(String(e.data));},onStateChange:function(e){if(e.data===1)Kx.ready();if(e.data===0)Kx.ended();}}});}
function kx(k){try{if(!pl||!pl.getPlayerState)return;if(k==='ok'){pl.getPlayerState()===1?pl.pauseVideo():pl.playVideo();}else if(k==='l'){pl.seekTo(Math.max(0,pl.getCurrentTime()-10),true);}else if(k==='r'){pl.seekTo(pl.getCurrentTime()+10,true);}}catch(e){}}
</script></body></html>"""

    private inner class Bridge {
        @JavascriptInterface fun ready() {
            handler.post { if (!ready && !isFinishing) { ready = true; message.visibility = View.GONE } }
        }
        @JavascriptInterface fun ended() { handler.post { if (!leaving) { leaving = true; finish() } } }
        @JavascriptInterface fun error(code: String) { handler.post { if (!leaving) openYoutubeApp() } }
    }

    private fun openYoutubeApp() {
        if (leaving || isFinishing) return
        leaving = true
        val watch = "https://www.youtube.com/watch?v=$videoId"
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")),
            Intent(Intent.ACTION_VIEW, Uri.parse(watch)).setPackage("com.google.android.youtube.tv"),
            Intent(Intent.ACTION_VIEW, Uri.parse(watch)).setPackage("com.amazon.firetv.youtube"),
            Intent(Intent.ACTION_VIEW, Uri.parse(watch)),
        )
        for (intent in intents) {
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return } catch (_: Exception) {}
        }
        Toast.makeText(this, "Trailer non disponibile su questo dispositivo", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "ok"
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "l"
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "r"
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { leaving = true; finish(); return true }
                else -> null
            }
            if (key != null) {
                if (::web.isInitialized && !dead) web.evaluateJavascript("kx('$key')", null)
                return true
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized && !dead) {
            try { web.evaluateJavascript("try{pl&&pl.pauseVideo()}catch(e){}", null); web.onPause() } catch (_: Throwable) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized && !dead) try { web.onResume() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized && !dead) {
            try {
                web.loadUrl("about:blank")
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            } catch (_: Throwable) {}
        }
        super.onDestroy()
        handler.post { android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}
