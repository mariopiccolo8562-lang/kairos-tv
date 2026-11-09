package com.kairos.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Controllo remoto dal pannello Kairos Control.
 * - Impostazioni imposte (rc): arrivano con l'apertura (register) e con ogni battito; vincono su quelle scelte
 *   nell'app, che le mostra come «scelte dal fornitore». Oggi: modalità leggera, anteprima canali, aggiornamento in background,
 *   Home (categorie in primo piano e nascoste, vedi [HomeCfg]), riquadro della qualità nel lettore (vedi [QBox]).
 * - Comandi (cmds): riavvia, svuota cache e riavvia, aggiorna liste, apri impostazioni, aggiorna l'app, chiudi. L'app li esegue
 *   e li conferma al pannello (POST /api/player/cmd), senza avvisi al cliente: si vedono solo gli effetti (riavvio, chiusura,
 *   impostazioni aperte, schermata di Android del permesso, scelta del nuovo PIN).
 * - L'app riporta le sue impostazioni attuali (cfg) e la versione della configurazione applicata (rcv).
 */
object Remote {
    private var cache: JSONObject? = null
    private var cacheRaw: String? = null

    fun rc(): JSONObject {
        val raw = Store.rcJson
        if (raw != cacheRaw || cache == null) { cacheRaw = raw; cache = try { if (raw.isEmpty()) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() } }
        return cache!!
    }
    private fun str(k: String): String? = rc().optString(k, "").ifEmpty { null }
    /** modalità leggera imposta dal pannello (auto | on | max | off), null = decide il cliente */
    val lite: String? get() = str("lite")?.takeIf { it == "auto" || it == "on" || it == "max" || it == "off" }
    /** anteprima canali imposta dal pannello (click | off) */
    val preview: String? get() = str("preview")?.takeIf { it == "click" || it == "off" }
    /** aggiornamento in background imposto dal pannello (on | off) */
    val bgUpdate: Boolean? get() = str("bgupd")?.let { if (it == "on") true else if (it == "off") false else null }
    /** aggiornamento obbligatorio deciso dal pannello: giorni di grazia dal primo avviso, null = il cliente può rimandare */
    val forceDays: Int? get() = when (str("force")) { "now" -> 0; "3d" -> 3; "7d" -> 7; else -> null }
    /** finestra «Privacy e condizioni»: skip = il fornitore ha deciso di non mostrarla */
    val privacy: String? get() = str("privacy")?.takeIf { it == "show" || it == "skip" }
    /** riquadro della qualità nel lettore (voce «qbox»): off = mai, noq = senza la sigla, full = come sempre (vedi [QBox]) */
    val qbox: String get() = QBox.mode(str("qbox"))
    fun imposed(k: String): Boolean = str(k) != null

    /** permesso di Android «Installa app sconosciute» (serve per installare gli aggiornamenti): nessuna app può darselo da sola */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || App.I.packageManager.canRequestPackageInstalls()
    fun openInstallPerm() {
        Ui.toast("Attiva «Consenti da questa fonte» per gli aggiornamenti dell'app, poi premi INDIETRO")
        val tries = listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + App.I.packageName)), Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { App.I.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
    }

    /** nuova configurazione dal pannello: si salva; se cambia la modalità leggera l'app si riavvia (mai durante la visione) */
    fun apply(rc: JSONObject?, rcv: String) {
        val raw = rc?.toString() ?: ""
        if (raw == Store.rcJson && rcv == Store.rcv) return
        val liteBefore = Store.liteMode
        Store.rcJson = raw
        Store.rcv = rcv
        cacheRaw = null
        ui { relite(liteBefore) }
    }

    /**
     * Modalità leggera effettiva ricalcolata (configurazione nuova o permesso «lite» cambiato): se cambia davvero
     * l'app si riavvia (mai durante la visione), altrimenti la schermata si riallinea (es. Home dal pannello).
     */
    fun relite(liteBefore: String) {
        val wasLite = App.lite
        val wasMax = App.max
        App.lite = App.I.detectLite()
        App.max = Store.liteMode == "max"
        if (Store.liteMode != liteBefore && (App.lite != wasLite || App.max != wasMax) && App.top !is PlayerActivity) restart()
        else (App.top as? BaseActivity)?.stateChanged()
    }
    fun applyRaw(rcStr: String?, rcv: String) {
        if (rcStr == null) return
        apply(try { JSONObject(rcStr) } catch (_: Exception) { JSONObject() }, rcv)
    }

    /** impostazioni attuali dell'app, con le stesse chiavi del pannello */
    fun snapshot(): JSONObject = JSONObject()
        .put("lite", Store.liteMode)
        .put("liteOn", if (App.max) "max" else if (App.lite) "on" else "off")
        .put("preview", Store.preview)
        .put("bgupd", if (Store.bgUpdate) "on" else "off")
        .put("inst", if (canInstall()) "on" else "off")

    /** risposta del battito: configurazione e comandi */
    fun onBeat(j: JSONObject) {
        if (j.has("rc")) applyRaw(j.optString("rc"), j.optString("rcv"))
        // «Invia aggiornamento» dal pannello (o aggiornamento automatico scelto dal fornitore)
        val upd = j.optLong("upd", 0)
        if (upd > 0 && upd != Store.updSeen) { Store.updSeen = upd; Updater.fromPanel() }
        val cmds: JSONArray = j.optJSONArray("cmds") ?: return
        for (i in 0 until cmds.length()) { val c = cmds.optJSONObject(i) ?: continue; run(c.optLong("id"), c.optString("c")) }
    }

    /** comandi già eseguiti (o in corso) in questa apertura: un doppione rimandato dal pannello si conferma soltanto */
    private val seen = CmdSeen()

    private fun send(id: Long, ok: Boolean, info: String) { bg { try { Panel.cmdAck(id, ok, info) } catch (_: Exception) {} } }

    private fun ack(id: Long, ok: Boolean, info: String) {
        seen.finish(id, ok, info)
        send(id, ok, info)
    }

    /**
     * Conferma, poi [then] (riavvio o chiusura) appena la conferma è arrivata, al massimo dopo [maxMs]: prima l'app si
     * riavviava 0,7 s dopo averla mandata e con la rete lenta la conferma si perdeva (il pannello rimandava il comando)
     */
    private fun ackThen(id: Long, info: String, maxMs: Long, then: () -> Unit) {
        seen.finish(id, true, info)
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = Runnable { if (once.compareAndSet(false, true)) then() }
        App.main.postDelayed(go, maxMs)
        net { try { Panel.cmdAck(id, true, info) } catch (_: Exception) {} finally { App.main.post(go) } }
    }

    private fun run(id: Long, c: String) {
        ui {
            val was = seen.arrive(id)
            if (was === CmdSeen.BUSY) return@ui // ancora in corso (es. controllo dell'aggiornamento): la conferma arriva a lavoro finito
            if (was != null) { send(id, was.ok, was.info); return@ui } // già eseguito, la conferma si era persa: solo la conferma
            val watching = App.top is PlayerActivity
            when (c) {
                "reload" -> ackThen(id, "riavvio", 2_500) { restart() }
                "cache" -> ackThen(id, "cache svuotata", 2_500) { bg { clearAll(); ui { restart() } } }
                "sync" -> if (watching) ack(id, false, "visione in corso: rimandato") else { Sync.force(); ack(id, true, "liste in aggiornamento") }
                "settings" -> {
                    if (watching) App.top?.finish()
                    val i = Intent(App.I, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    App.I.startActivity(i)
                    ack(id, true, "impostazioni aperte")
                }
                "update" -> Updater.fromPanel { info -> ack(id, true, info) }
                "perm" -> if (canInstall()) ack(id, true, "permesso già concesso") else if (watching) ack(id, false, "visione in corso: rimandato") else { openInstallPerm(); ack(id, true, "schermata del permesso aperta sulla TV") }
                // PIN del controllo genitori: torna 0000 senza avvisi sul «fornitore», ma il blocco non resta spento in silenzio —
                // l'app chiede subito di sceglierne uno nuovo (come alla prima installazione, con «Più tardi»); durante la visione a lettore chiuso
                "pin" -> {
                    Parental.reset(); ack(id, true, "PIN riportato a 0000")
                    val a = App.top
                    if (a != null && a !is PlayerActivity) App.main.postDelayed({ Parental.askNow(a) }, 1000) else Parental.askPending = true
                }
                // prima il «vado in sfondo» (il pannello la vede subito chiusa; al massimo ~1,5 s di attesa), poi si chiude come sempre
                "exit" -> ackThen(id, "chiusa", 2_500) { Pulse.leaving { try { App.top?.finishAffinity() } catch (_: Exception) {}; App.main.postDelayed({ exitProcess(0) }, 300) } }
                else -> ack(id, false, "comando non supportato")
            }
        }
    }

    /** chiude e riapre l'app (stessa schermata iniziale di un avvio normale) */
    fun restart() {
        val i = App.I.packageManager.getLaunchIntentForPackage(App.I.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        App.I.startActivity(i)
        App.main.postDelayed({ exitProcess(0) }, 200)
    }

    /** liste, immagini e file temporanei: tutto da riscaricare */
    private fun clearAll() {
        try { Db.clear(Db.w) } catch (_: Exception) {}
        try { Store.syncSig = ""; Store.syncAt = 0 } catch (_: Exception) {}
        try { Glide.get(App.I).clearDiskCache() } catch (_: Throwable) {}
        try { App.I.cacheDir.deleteRecursively() } catch (_: Exception) {}
    }
}
