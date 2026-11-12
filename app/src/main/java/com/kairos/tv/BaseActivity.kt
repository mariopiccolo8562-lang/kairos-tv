package com.kairos.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Base di tutte le schermate: schermo intero, stato dell'abbonamento, VPN, «Disconnetti» dal pannello */
abstract class BaseActivity : Activity() {
    /** le schermate dei contenuti richiedono un abbonamento attivo */
    open val needsActive = true

    private var clockView: TextView? = null
    private val clockTick = object : Runnable {
        override fun run() {
            clockView?.text = Ui.hm(System.currentTimeMillis())
            shieldRefresh()
            App.main.postDelayed(this, 20_000)
        }
    }
    private var shieldView: ImageView? = null

    /** Scudo VPN nella barra in alto. Compare solo se il fornitore lo ha permesso dal pannello: VPN «Consentita» + permesso
     *  «Accendere/spegnere la VPN». Con «Sempre attiva» è un semplice indicatore (acceso, non si tocca); altrimenti è nascosto. */
    fun vpnShield(v: ImageView?) {
        shieldView = v ?: return
        v.setOnClickListener { vpnToggle() }
        shieldRefresh()
    }
    fun shieldRefresh() {
        val v = shieldView ?: return
        val s = Panel.state
        val ok = s != null && s.active && s.vpnConf.isNotEmpty()
        val can = ok && s!!.vpnMode == "allow" && s.perm("vpn")
        val forced = ok && s!!.vpnMode == "force"
        // la VPN la gestisce il fornitore (sempre attiva, consentita senza permesso, spenta): lo scudo c'è lo stesso e si può
        // premere, per leggere «VPN gestita dal fornitore» (voce «shield» del pannello)
        val managed = s != null && s.active && s.vpnShield
        if (!can && !forced && !managed) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        v.isFocusable = true; v.isClickable = true
        val st = KxVpn.state
        val on = st == "on" || (forced && KxVpn.active())
        v.imageTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFF2EE59D.toInt() else if (st == "connecting") 0xFFF5B041.toInt() else 0xCCFFFFFF.toInt())
        v.alpha = if (on || st == "connecting") 1f else 0.7f
        v.contentDescription = if (!can) "VPN gestita dal fornitore" else if (on) "VPN attiva: premi per spegnerla" else "VPN spenta: premi per accenderla"
    }
    private fun vpnToggle() {
        val s = Panel.state ?: return
        if (!(s.active && s.vpnConf.isNotEmpty() && s.vpnMode == "allow" && s.perm("vpn"))) { Ui.toast("VPN gestita dal fornitore"); return }
        Store.vpnUser = !Store.vpnUser
        if (Store.vpnUser) Vpns.retry()
        Ui.toast(if (Store.vpnUser) "Attivo la VPN…" else "VPN spenta")
        Vpns.apply(this)
        shieldRefresh()
        for (ms in longArrayOf(800, 2_000, 4_000, 8_000)) App.main.postDelayed({ if (!isFinishing) shieldRefresh() }, ms)
    }
    /** lo stato dal pannello è cambiato: prima lo scudo, poi la schermata (o «Chi guarda?» se il profilo in uso non c'è più) */
    fun stateChanged() { shieldRefresh(); if (!checkProfile()) onStateChanged() }

    /**
     * Profilo in uso tolto dal pannello (o profili appena creati e nessuno scelto): si torna a «Chi guarda?», come
     * all'apertura dell'app. Mai sopra la visione: il lettore lo lascia ai menu, che lo controllano al ritorno.
     */
    fun checkProfile(): Boolean {
        if (!needsActive || this is PlayerActivity || this is ProfilesActivity || isFinishing || !K.needsProfile()) return false
        startActivity(Intent(this, ProfilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        return true
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
            if (checkProfile()) return
            Vpns.apply(this)
        }
        if (clockView != null) { App.main.removeCallbacks(clockTick); clockTick.run() }
        shieldRefresh()
        Updater.offer(this) // versione nuova trovata mentre si guardava un canale: la si propone qui
        // PIN del controllo genitori reimpostato durante la visione: a lettore chiuso si chiede subito il nuovo PIN
        if (Parental.askPending && this !is PlayerActivity) App.main.postDelayed({ if (!isFinishing && App.top === this && Parental.askPending) Parental.askNow(this) }, 1000)
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(clockTick)
        FastScroll.resume() // mai immagini ferme nella schermata dopo
    }

    /** freccia tenuta premuta (scorrimento veloce): le immagini aspettano che la si lasci, vedi [FastScroll] */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        FastScroll.onKey(this, event)
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // schermata chiusa: le sue immagini tornano nella cache con il tetto (e i download ancora in corso si fermano)
        try { window.peekDecorView()?.let { releaseImages(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun releaseImages(v: View) {
        if (v is ImageView) Ui.release(v)
        else if (v is ViewGroup) for (i in 0 until v.childCount) releaseImages(v.getChildAt(i))
    }

    /** il lettore si sta aprendo sopra questa schermata: via le immagini grandi (sfondi, riquadro in evidenza), tornano al ritorno */
    open fun releaseBig() {}

    /** è comparsa la finestra dell'aggiornamento obbligatorio: niente video dietro di lei */
    open fun onBlocked() {}

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

    /** «Disconnetti» dal pannello: nei menu non c'è niente da chiudere (l'anteprima la ferma [LiveActivity]); nessun avviso al cliente */
    open fun onKicked() {}

    /** lo stato dal pannello è cambiato (playlist, permessi, messaggi…) */
    open fun onStateChanged() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Vpns.REQ) Vpns.onConsent(resultCode == RESULT_OK)
        if (requestCode == DnsShield.REQ) DnsShield.onConsent(resultCode == RESULT_OK)
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
