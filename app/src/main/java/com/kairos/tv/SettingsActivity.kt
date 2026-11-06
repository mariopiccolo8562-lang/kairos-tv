package com.kairos.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Impostazioni essenziali: account, collegare un'altra TV, profili, playlist, VPN, liste, aggiornamenti */
class SettingsActivity : BaseActivity() {
    private lateinit var rows: LinearLayout
    private var syncSub: TextView? = null
    private var wasRunning = false
    // durante l'aggiornamento cambia solo la riga «Aggiorna liste» (niente sfarfallio del cursore)
    private val syncL: () -> Unit = {
        if (!isFinishing) {
            if (Sync.running == wasRunning) syncSub?.text = syncText() else build()
        }
    }

    private fun syncText(): String = when {
        Sync.running -> "In corso… ${Sync.pct}%"
        Sync.error.isNotEmpty() -> "Ultimo tentativo non riuscito: " + Sync.error
        Store.syncAt > 0 -> "Ultimo aggiornamento alle " + Ui.hm(Store.syncAt)
        else -> "Scarica di nuovo canali, film e serie"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        rows = findViewById(R.id.rows)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        Sync.listen(syncL)
        build()
        rows.getChildAt(0)?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        Sync.unlisten(syncL)
    }

    override fun onStateChanged() { build() }

    private fun build() {
        val s = Panel.state ?: return
        val a = s.account
        val focusedIdx = (0 until rows.childCount).firstOrNull { rows.getChildAt(it).hasFocus() } ?: -1
        rows.removeAllViews()

        val pr = K.profile()
        val name = pr?.name ?: s.name.ifEmpty { "Il mio account" }
        Ui.avatar(findViewById(R.id.avatar), name, pr?.avatar ?: "a1", 26)
        findViewById<TextView>(R.id.accName).text = name
        val lines = ArrayList<String>()
        lines.add(if (s.expires.isEmpty()) "Abbonamento attivo" else "Scadenza: " + Ui.date(s.expires))
        if (a != null) lines.add("Dispositivi: ${a.conns.size} di ${a.maxConn}")
        if (a != null && !a.primary && a.owner.isNotEmpty()) lines.add("Collegato a: " + a.owner)
        lines.add("Codice TV: " + Store.code)
        lines.add(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + (if (App.lite) " · modalità leggera" else ""))
        findViewById<TextView>(R.id.accInfo).text = lines.joinToString("\n")

        if (a != null && a.primary && s.perm("link")) {
            row(R.drawable.ic_link, "Collega un'altra TV", "Genera un codice a 6 cifre da scrivere sulla nuova TV", "${a.conns.size}/${a.maxConn}") { linkCode() }
        }
        if (K.profilesOn()) {
            row(R.drawable.ic_user, "Profili", "Chi guarda, profili bambini", pr?.name ?: "") {
                open(ProfilesActivity::class.java) { putExtra("fromSettings", true) }
            }
        }
        if (K.canSwitchPl()) {
            val cur = K.active().firstOrNull()
            row(R.drawable.ic_layers, "Playlist", "Scegli quale lista usare", cur?.name ?: "") { choosePl() }
        }
        if (s.vpnMode != "off" && s.vpnConf.isNotEmpty()) {
            if (s.vpnMode == "force") row(R.drawable.ic_shield, "VPN", KxVpn.message.ifEmpty { "Attivata dal tuo fornitore" }, "Sempre attiva", null)
            else if (s.perm("vpn")) row(R.drawable.ic_shield, "VPN", KxVpn.message.ifEmpty { "Protegge la connessione dell'app" }, if (Store.vpnUser) "Attiva" else "Spenta") {
                Store.vpnUser = !Store.vpnUser
                if (Store.vpnUser) Vpns.retry()
                Vpns.apply(this)
                App.main.postDelayed({ if (!isFinishing) build() }, 1500)
                build()
            }
        }
        if (s.messages.isNotEmpty()) {
            row(R.drawable.ic_mail, "Messaggi", "Avvisi del tuo fornitore", s.messages.size.toString()) {
                Ui.dialog(this, "Messaggi", s.messages.joinToString("\n\n") { (if (it.title.isNotEmpty()) it.title + "\n" else "") + it.body })
            }
        }
        wasRunning = Sync.running
        syncSub = row(R.drawable.ic_refresh, "Aggiorna liste", syncText(), "") { Sync.force(); build() }
        row(R.drawable.ic_tv, "Anteprima canali", "OK una volta: il canale parte nel riquadro (versione più leggera) · OK di nuovo: schermo intero", (if (Store.preview == "off") "Spenta" else "Al primo OK") + (if (Remote.imposed("preview")) " · fornitore" else "")) { if (Remote.imposed("preview")) Ui.toast("Impostazione scelta dal tuo fornitore") else choosePreview() }
        val liteVal = when (Store.liteMode) { "on" -> "Sempre"; "max" -> "Leggerissima"; "off" -> "Mai"; else -> "Automatica" } + (if (Remote.imposed("lite")) " · fornitore" else "")
        row(R.drawable.ic_bolt, "Modalità leggera", "Per chiavette e TV vecchie: niente animazioni, immagini e memoria ridotte", liteVal) { if (Remote.imposed("lite")) Ui.toast("Impostazione scelta dal tuo fornitore") else chooseLite() }
        row(R.drawable.ic_shield, "Controllo genitori", "Le categorie per adulti chiedono il PIN", if (Parental.on()) "Attivo" else "Spento") {
            if (Parental.on()) Parental.ask(this, "PIN per disattivare") { Store.parental = false; Parental.unlocked = false; build() }
            else { Store.parental = true; Parental.unlocked = false; build(); if (Parental.pinIsDefault()) Parental.choose(this) { build() } }
        }
        row(R.drawable.ic_shield, "PIN del controllo genitori", if (Parental.pinIsDefault()) "Ora è 0000: scegline uno tuo" else "Cambia il PIN", if (Parental.pinIsDefault()) "0000" else "••••") {
            if (Parental.pinIsDefault()) Parental.choose(this) { build() }
            else Parental.ask(this, "PIN attuale") { Parental.choose(this) { build() } }
        }
        val upd = Updater.pending
        row(R.drawable.ic_refresh, "Aggiornamenti dell'app", "Versione installata " + BuildConfig.VERSION_NAME + (if (upd != null) (if (Updater.ready != null) " · scaricata la " else " · pronta la ") + upd.ver else ""), if (upd != null) (if (Updater.ready != null) "Installa" else "Aggiorna") else "") {
            if (upd != null) Updater.download(this, upd.url)
            else { Ui.toast("Controllo…"); bg { Updater.check(this) } }
        }
        val bgVal = (if (Store.bgUpdate) "Sì" else "No") + (if (Remote.imposed("bgupd")) " · fornitore" else "")
        row(R.drawable.ic_refresh, "Aggiornamenti in background", "La versione nuova si scarica da sola su Wi-Fi o cavo: a te resta solo «Installa»", bgVal) {
            if (Remote.imposed("bgupd")) Ui.toast("Impostazione scelta dal tuo fornitore")
            else { Store.bgUpdate = !Store.bgUpdate; Ui.toast(if (Store.bgUpdate) "La versione nuova si scaricherà da sola" else "Scaricheremo la versione nuova solo quando accetti"); build() }
        }
        row(R.drawable.ic_info, "Privacy e condizioni", "Informativa del tuo fornitore", "") {
            bg { val t = Panel.privacyText(); ui { Ui.dialog(this, "Privacy e condizioni", t.ifEmpty { "Nessuna informativa disponibile." }) } }
        }
        if (focusedIdx >= 0) rows.getChildAt(focusedIdx.coerceAtMost(rows.childCount - 1))?.requestFocus()
    }

    private fun row(icon: Int, title: String, sub: String, value: String, click: (() -> Unit)?): TextView {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, rows, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.sub).text = sub
        v.findViewById<TextView>(R.id.value).text = value
        if (click != null) v.setOnClickListener { click() }
        rows.addView(v)
        return v.findViewById(R.id.sub)
    }

    private fun linkCode() {
        bg {
            val r = try { Panel.linkCode() } catch (e: Exception) { ui { Ui.dialog(this, "Collega un'altra TV", e.message ?: "Errore") }; return@bg }
            ui {
                Ui.bigCode(this, "Collega un'altra TV", r.optString("pin").chunked(3).joinToString(" "),
                    "Sulla nuova TV apri l'app, scegli «Collega con codice a 6 cifre» e scrivi questo codice.\nVale 10 minuti e si usa una volta sola.")
            }
        }
    }

    private fun choosePl() {
        val all = K.playlists()
        val cur = K.active().firstOrNull()?.id
        Ui.choose(this, "Playlist", all.map { it.name }.toTypedArray(), all.indexOfFirst { it.id == cur }) { i ->
            Store.plChoice = all[i].id
            Sync.force()
            build()
        }
    }

    private fun choosePreview() {
        val opts = arrayOf("Al primo OK (consigliata): il secondo OK apre lo schermo intero", "Spenta: OK apre subito lo schermo intero")
        val keys = arrayOf("light", "off")
        Ui.choose(this, "Anteprima nella lista canali", opts, if (Store.preview == "off") 1 else 0) { i ->
            Store.preview = keys[i]
            build()
        }
    }

    private fun chooseLite() {
        val opts = arrayOf("Automatica (consigliata)", "Sempre attiva", "Leggerissima: liste senza loghi, canali in HD", "Mai")
        val keys = arrayOf("auto", "on", "max", "off")
        Ui.choose(this, "Modalità leggera", opts, keys.indexOf(Store.liteMode).coerceAtLeast(0)) { i ->
            val wasMax = App.max
            Store.liteMode = keys[i]
            App.lite = App.I.detectLite()
            App.max = Store.liteMode == "max"
            Ui.toast(when {
                App.max -> "Leggerissima attiva"
                App.lite -> "Modalità leggera attiva"
                else -> "Modalità leggera spenta"
            })
            // la memoria per le immagini si sceglie all'avvio: con «Leggerissima» si avvisa di riaprire l'app
            if (App.max != wasMax) Ui.dialog(this, "Modalità leggera", "Per liberare tutta la memoria chiudi e riapri Kairos TV.")
            build()
        }
    }
}
