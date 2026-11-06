package com.kairos.tv

import android.content.Intent
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Controllo remoto dal pannello Kairos Control.
 * - Impostazioni imposte (rc): arrivano con l'apertura (register) e con ogni battito; vincono su quelle scelte
 *   nell'app, che le mostra come «scelte dal fornitore». Oggi: modalità leggera, anteprima canali, aggiornamento in background.
 * - Comandi (cmds): riavvia, svuota cache e riavvia, aggiorna liste, apri impostazioni, aggiorna l'app, chiudi. L'app li esegue
 *   e li conferma al pannello (POST /api/player/cmd).
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
    fun imposed(k: String): Boolean = str(k) != null

    /** nuova configurazione dal pannello: si salva; se cambia la modalità leggera l'app si riavvia (mai durante la visione) */
    fun apply(rc: JSONObject?, rcv: String) {
        val raw = rc?.toString() ?: ""
        if (raw == Store.rcJson && rcv == Store.rcv) return
        val liteBefore = Store.liteMode
        Store.rcJson = raw
        Store.rcv = rcv
        cacheRaw = null
        ui {
            val wasLite = App.lite
            val wasMax = App.max
            App.lite = App.I.detectLite()
            App.max = Store.liteMode == "max"
            if (Store.liteMode != liteBefore && (App.lite != wasLite || App.max != wasMax) && App.top !is PlayerActivity) restart()
            else (App.top as? BaseActivity)?.onStateChanged()
        }
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

    /** risposta del battito: configurazione e comandi */
    fun onBeat(j: JSONObject) {
        if (j.has("rc")) applyRaw(j.optString("rc"), j.optString("rcv"))
        // «Invia aggiornamento» dal pannello (o aggiornamento automatico scelto dal fornitore)
        val upd = j.optLong("upd", 0)
        if (upd > 0 && upd != Store.updSeen) { Store.updSeen = upd; Updater.fromPanel() }
        val cmds: JSONArray = j.optJSONArray("cmds") ?: return
        for (i in 0 until cmds.length()) { val c = cmds.optJSONObject(i) ?: continue; run(c.optLong("id"), c.optString("c")) }
    }

    private fun ack(id: Long, ok: Boolean, info: String) { bg { try { Panel.cmdAck(id, ok, info) } catch (_: Exception) {} } }

    private fun run(id: Long, c: String) {
        ui {
            val watching = App.top is PlayerActivity
            when (c) {
                "reload" -> { ack(id, true, "riavvio"); App.main.postDelayed({ restart() }, 700) }
                "cache" -> { ack(id, true, "cache svuotata"); App.main.postDelayed({ bg { clearAll(); ui { restart() } } }, 700) }
                "sync" -> if (watching) ack(id, false, "visione in corso: rimandato") else { Sync.force(); ack(id, true, "liste in aggiornamento") }
                "settings" -> {
                    if (watching) App.top?.finish()
                    val i = Intent(App.I, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    App.I.startActivity(i)
                    ack(id, true, "impostazioni aperte")
                }
                "update" -> Updater.fromPanel { info -> ack(id, true, info) }
                "exit" -> { ack(id, true, "chiusa"); App.main.postDelayed({ try { App.top?.finishAffinity() } catch (_: Exception) {}; App.main.postDelayed({ exitProcess(0) }, 300) }, 700) }
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
