package com.kairos.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

/** Base di tutte le schermate: schermo intero, stato dell'abbonamento, VPN, «Disconnetti» dal pannello */
abstract class BaseActivity : Activity() {
    /** le schermate dei contenuti richiedono un abbonamento attivo */
    open val needsActive = true

    private var clockView: TextView? = null
    private val clockTick = object : Runnable {
        override fun run() {
            clockView?.text = Ui.hm(System.currentTimeMillis())
            App.main.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dopo una chiusura forzata di Android (poca memoria) si riparte dall'ultimo stato salvato
        if (Panel.state == null) Panel.loadCached()
        if (App.lite) overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (needsActive) {
            val s = Panel.state ?: Panel.loadCached()
            if (s == null || !s.active) { goActivation(); return }
            Vpns.apply(this)
        }
        if (clockView != null) { App.main.removeCallbacks(clockTick); clockTick.run() }
        Updater.offer(this) // versione nuova trovata mentre si guardava un canale: la si propone qui
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(clockTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    fun clock(v: TextView?) {
        clockView = v
        if (v != null) v.text = Ui.hm(System.currentTimeMillis())
    }

    fun goActivation() {
        if (this is ActivationActivity) return
        startActivity(Intent(this, ActivationActivity::class.java).putExtra("stay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** il fornitore ha premuto «Disconnetti» nel pannello */
    open fun onKicked() {
        Ui.dialog(this, "Visione interrotta", "Il tuo fornitore ha chiuso la visione su questo dispositivo.")
    }

    /** lo stato dal pannello è cambiato (playlist, permessi, messaggi…) */
    open fun onStateChanged() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Vpns.REQ) Vpns.onConsent(resultCode == RESULT_OK)
    }

    override fun finish() {
        super.finish()
        if (App.lite) overridePendingTransition(0, 0)
    }

    fun open(c: Class<out Activity>, fill: (Intent.() -> Unit)? = null) {
        val i = Intent(this, c)
        fill?.invoke(i)
        startActivity(i)
    }
}
