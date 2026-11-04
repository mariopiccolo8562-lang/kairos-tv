package com.kairos.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Prima schermata: mostra il codice del dispositivo (come KAIROS TV) e attende che il fornitore
 * lo attivi dal pannello. In alternativa il cliente collega la TV con il codice a 6 cifre generato
 * da un'altra TV già attiva. Se l'abbonamento è già attivo si passa subito alla home.
 */
class ActivationActivity : BaseActivity() {
    override val needsActive = false

    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var hero: TextView
    private var routed = false
    private var privacyShown = false
    private var alive = false
    private val poll = Runnable { tick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // aggiornamento dalla versione 1 (web): si riprende il codice di prima, così il cliente resta attivo
        if (Legacy.needed(this)) {
            setContentView(R.layout.activity_activation)
            findViewById<TextView>(R.id.status).text = "Aggiornamento in corso…"
            Legacy.run(this) { recreate() }
            return
        }
        val cached = Panel.loadCached()
        if (cached != null && cached.active && !intent.getBooleanExtra("stay", false)) {
            // partenza immediata con l'ultimo stato; il pannello viene ricontrollato in background
            route()
            bg { Pulse.refresh() }
            return
        }
        setContentView(R.layout.activity_activation)
        status = findViewById(R.id.status)
        retry = findViewById(R.id.retry)
        hero = findViewById(R.id.hero)
        findViewById<TextView>(R.id.code).text = Store.code
        findViewById<TextView>(R.id.version).text = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        val brand = cached?.setting("brand").orEmpty()
        if (brand.isNotEmpty()) findViewById<TextView>(R.id.brand).text = brand
        val link = findViewById<Button>(R.id.link)
        link.setOnClickListener { askPin() }
        findViewById<Button>(R.id.privacy).setOnClickListener { showPrivacy() }
        retry.setOnClickListener { tick() }
        Ui.zoom(link, 1.03f)
        link.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (routed) return
        alive = true
        tick()
    }

    override fun onPause() {
        super.onPause()
        alive = false
        App.main.removeCallbacks(poll)
    }

    private fun tick() {
        App.main.removeCallbacks(poll)
        if (routed || !::status.isInitialized || Legacy.running) return
        if (Store.server.isEmpty()) {
            say("Server non configurato: l'app va compilata con l'indirizzo del pannello (KAIROS_SERVER_URL).", true)
            return
        }
        bg {
            val s = try { Panel.register() } catch (e: Exception) {
                ui { say((e.message ?: "Errore di rete") + ". Riprovo tra poco…", true); later(8_000) }
                return@bg
            }
            ui { show(s) }
        }
    }

    private fun later(ms: Long) {
        if (alive && !routed) App.main.postDelayed(poll, ms)
    }

    private fun show(s: State) {
        if (isFinishing || routed) return
        when (s.status) {
            "active" -> { route(); return }
            "maintenance" -> say(s.maintMsg.ifEmpty { "Servizio in manutenzione: torna disponibile tra poco." } + "\nL'app riparte da sola appena finisce.", true)
            "locked" -> say("Questo codice è già usato da un altro dispositivo. Chiedi al tuo fornitore di sbloccarlo dal pannello.", true)
            "expired" -> say("Abbonamento scaduto il ${Ui.date(s.expires)}." + contact(s), true)
            "disabled" -> say("Abbonamento sospeso dal fornitore." + contact(s), true)
            else -> say("In attesa di attivazione…", false)
        }
        hero.text = if (s.status == "maintenance") "Manutenzione in corso." else "Attiva la tua TV."
        findViewById<View>(R.id.codeBox).visibility = if (s.status == "maintenance") View.GONE else View.VISIBLE
        if (!Store.privacyOk && !privacyShown && s.status != "maintenance") { privacyShown = true; showPrivacy() }
        // in attesa ogni 12 s (come KAIROS TV); bloccato, scaduto o sospeso: ogni minuto
        later(when (s.status) { "pending" -> 12_000L; "maintenance" -> 30_000L; else -> 60_000L })
    }

    private fun contact(s: State): String {
        val c = s.setting("supportContact")
        val price = s.setting("price")
        return (if (price.isNotEmpty()) "\nRinnovo: $price." else "") + (if (c.isNotEmpty()) "\nContatto: $c" else "")
    }

    private fun say(t: String, warn: Boolean) {
        status.text = t
        findViewById<View>(R.id.dot).setBackgroundResource(if (warn) R.drawable.dot_red else R.drawable.dot_accent)
        retry.visibility = if (warn) View.VISIBLE else View.GONE
    }

    private fun askPin() {
        Ui.input(this, "Collega a un abbonamento attivo", "Codice a 6 cifre", true) { pin ->
            val p = pin.filter { it.isDigit() }
            if (p.length != 6) { Ui.toast("Il codice è di 6 cifre"); return@input }
            say("Collegamento in corso…", false)
            bg {
                val err = Panel.link(p)
                ui {
                    if (err != null) { say(err, true); Ui.dialog(this, "Collegamento non riuscito", err) } else tick()
                }
            }
        }
    }

    private fun showPrivacy() {
        bg {
            val t = Panel.privacyText().ifEmpty { "Usando l'app accetti le condizioni del servizio del tuo fornitore. L'app è solo un lettore: i contenuti arrivano dalle playlist assegnate dal fornitore." }
            ui {
                Ui.dialog(this, "Privacy e condizioni", t, "Ho capito") { Store.privacyOk = true }
            }
        }
    }

    private fun route() {
        if (routed) return
        routed = true
        App.main.removeCallbacks(poll)
        Sync.start()
        val next = if (K.needsProfile()) ProfilesActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
