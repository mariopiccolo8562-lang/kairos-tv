package com.kairos.tv

import android.os.SystemClock
import org.json.JSONObject

/**
 * Collegamento continuo col pannello mentre l'app è aperta:
 * - battito ogni 60 s nei menu, ogni 20 s durante la visione (Utenti online, «Disconnetti»), sempre con «vis: true»
 * - app in sfondo (nessuna sua schermata visibile): i battiti si fermano e parte un solo «vado in sfondo» («vis: false»),
 *   così il pannello la mostra subito non in linea; al ritorno in primo piano battito subito
 * - se il pannello cambia qualcosa (DNS, permessi, VPN, limiti, messaggi) l'app si riallinea subito
 * - ogni 20 minuti si rilegge lo stato (scadenza, playlist)
 */
object Pulse {
    class Watch(val state: String, val title: String, val ch: String, val chId: String, val group: String, val pl: String)

    @Volatile var watching: Watch? = null
    /** statistiche della visione in corso (le fornisce il player) */
    @Volatile var stats: (() -> JSONObject?)? = null
    @Volatile private var on = false
    /** ultimo battito accettato dal pannello (limite dei 15 s): un battito fallito, rifiutato o «vado in sfondo» non lo sposta */
    @Volatile private var lastHb = 0L
    @Volatile private var lastSig = ""
    private var lastReg = SystemClock.elapsedRealtime()
    @Volatile private var kickAck = 0L
    private var pv = ""
    /** battito chiesto subito (ritorno in primo piano, inizio o fine visione) e non ancora partito */
    @Volatile private var want = false
    /** battito in viaggio: non ne parte un secondo insieme (il pannello lo rifiuterebbe) */
    @Volatile private var sending = false
    /** il pannello ha chiesto di aspettare (429 con «wait»): prima di questo istante non parte nessun battito */
    @Volatile private var holdUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            beat(false)
            Updater.maybeCheck()
            App.main.postDelayed(this, 20_000)
        }
    }

    /** battito chiesto e rimandato (limite dei 15 s, 429 del pannello, battito ancora in viaggio) */
    private val soon = Runnable { if (want) beat(false) }

    private fun later(ms: Long) {
        App.main.removeCallbacks(soon)
        App.main.postDelayed(soon, ms.coerceIn(500L, 60_000L))
    }

    fun foreground(fg: Boolean) {
        on = fg
        App.main.removeCallbacks(tick)
        App.main.removeCallbacks(soon)
        if (fg) {
            // ritorno in primo piano: battito «vis: true» entro un secondo, anche se il minuto dei menu non è passato
            want = true
            later(1_000)
            App.main.postDelayed(tick, 4_000)
            Updater.onForeground()
        } else {
            want = false
            watching = null
            away()
        }
    }

    /** chiamato dal player quando inizia o finisce una visione */
    fun watch(w: Watch?) {
        watching = w
        beat(true)
    }

    fun beat(force: Boolean) {
        if (!on) return // in sfondo nessun battito: l'ultimo («vado in sfondo») è già partito
        if (force) want = true
        val st = Panel.state ?: return
        if (!st.active) return
        val w = watching
        val sig = (w?.state ?: "idle") + "|" + (w?.chId ?: "")
        val now = SystemClock.elapsedRealtime()
        if (!want && sig == lastSig && now - lastHb < (if (w == null) 60_000 else 20_000)) return
        // il pannello accetta al massimo un battito ogni 15 s: quello chiesto subito parte appena si può, non si perde
        val gap = maxOf(15_000 - (now - lastHb), holdUntil - now)
        if (gap > 0) { if (want) later(gap + 500); return }
        if (sending) { if (want) later(2_000); return }
        want = false
        sending = true
        lastSig = sig
        val screen = App.top?.javaClass?.simpleName?.removeSuffix("Activity")?.lowercase() ?: ""
        val st2 = if (w != null) try { stats?.invoke() } catch (_: Exception) { null } else null
        val ack = kickAck
        val vpnOn = KxVpn.state == "on"
        // sui thread di rete: il battito non resta in coda dietro alle letture dell'archivio
        net {
            var j: JSONObject? = null
            try {
                val (c, r) = Panel.heartbeat(w, ack, vpnOn, screen, st2, vis = true)
                when {
                    c == 200 && r != null -> { lastHb = SystemClock.elapsedRealtime(); j = r }
                    // troppo presto per il pannello: si riprova dopo i secondi che indica
                    c == 429 -> {
                        val s = (r?.optInt("wait", 15) ?: 15).coerceIn(1, 60) * 1000L
                        holdUntil = SystemClock.elapsedRealtime() + s
                        lastSig = ""
                        want = true
                        later(s + 300)
                    }
                    // non riuscito: l'istante dell'ultimo battito resta quello di prima e al prossimo giro si riprova
                    else -> lastSig = ""
                }
            } finally {
                sending = false
            }
            j?.let { handle(it) }
        }
    }

    /**
     * L'app va in sfondo: un solo battito «vis: false» con stato «idle» (stessi campi di sempre), sui thread di rete.
     * Non sposta l'istante dell'ultimo battito; la risposta si ignora (comandi e configurazione arrivano al ritorno).
     */
    private fun away() {
        val st = Panel.state ?: return
        if (!st.active) return
        val ack = kickAck
        val vpnOn = KxVpn.state == "on"
        net {
            // un battito partito poco prima arriva per primo: dopo il «vado in sfondo» l'app non deve risultare di nuovo aperta
            var n = 0
            while (sending && n++ < 30) try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            Panel.heartbeat(null, ack, vpnOn, "", null, vis = false)
        }
    }

    private fun handle(j: JSONObject) {
        Remote.onBeat(j) // controllo remoto: impostazioni imposte e comandi
        val kick = j.optLong("kick", 0)
        if (kick > 0) {
            kickAck = kick
            if (kick != Store.kickSeen) {
                Store.kickSeen = kick
                ui { (App.top as? BaseActivity)?.onKicked() }
            }
        }
        val s = Panel.state ?: return
        var changed = false
        val vm = j.optString("vm", "")
        if (vm.isNotEmpty() && vm != s.vpnMode) changed = true
        val acc = s.account
        if (j.has("ms") && acc != null && j.optInt("ms") != acc.maxStreams) changed = true
        val pm = j.optString("pm", "")
        if (pm.isNotEmpty() && acc != null && pm.split("|").take(2).joinToString("|") != acc.plMode + "|" + acc.plDefault) changed = true
        if (j.has("ph")) {
            val off = try { val o = JSONObject(j.optString("ph").ifEmpty { "{}" }); o.keys().asSequence().toList().sorted().joinToString(",") } catch (_: Exception) { "" }
            if (off != s.offPerms()) changed = true
        }
        val nv = j.optString("pv", "")
        if (nv.isNotEmpty()) { if (pv.isNotEmpty() && nv != pv) changed = true; pv = nv }
        // manutenzione accesa dal pannello: si passa subito alla schermata di attesa
        if (j.optInt("mt", 0) == 1) changed = true
        val unseen = j.optInt("unseen", -1)
        if (unseen > K.unseenMessages().size) changed = true
        if (changed || SystemClock.elapsedRealtime() - lastReg > 20 * 60_000L) refresh()
    }

    /** rilegge lo stato dal pannello e riallinea l'app */
    fun refresh() {
        lastReg = SystemClock.elapsedRealtime()
        val s = try { Panel.register() } catch (_: Exception) { return }
        ui { applyState(s) }
    }

    fun applyState(s: State) {
        val a = App.top as? BaseActivity
        if (!s.active) { a?.goActivation(); return }
        Vpns.apply(a)
        if (App.top !is PlayerActivity) Sync.start() // mai durante la visione
        a?.stateChanged()
    }
}
