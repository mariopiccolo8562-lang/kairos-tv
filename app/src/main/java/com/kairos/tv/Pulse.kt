package com.kairos.tv

import android.os.SystemClock
import org.json.JSONObject

/**
 * Collegamento continuo col pannello mentre l'app è aperta:
 * - battito ogni 60 s nei menu, ogni 20 s durante la visione (Utenti online, «Disconnetti»)
 * - se il pannello cambia qualcosa (DNS, permessi, VPN, limiti, messaggi) l'app si riallinea subito
 * - ogni 20 minuti si rilegge lo stato (scadenza, playlist)
 */
object Pulse {
    class Watch(val state: String, val title: String, val ch: String, val chId: String, val group: String, val pl: String)

    @Volatile var watching: Watch? = null
    /** statistiche della visione in corso (le fornisce il player) */
    @Volatile var stats: (() -> JSONObject?)? = null
    private var on = false
    private var lastHb = 0L
    private var lastSig = ""
    private var lastReg = SystemClock.elapsedRealtime()
    private var kickAck = 0L
    private var pv = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            beat(false)
            Updater.maybeCheck()
            App.main.postDelayed(this, 20_000)
        }
    }

    fun foreground(fg: Boolean) {
        on = fg
        App.main.removeCallbacks(tick)
        if (fg) {
            App.main.postDelayed(tick, 4_000)
            Updater.onForeground()
        } else {
            watching = null
        }
    }

    /** chiamato dal player quando inizia o finisce una visione */
    fun watch(w: Watch?) {
        watching = w
        beat(true)
    }

    fun beat(force: Boolean) {
        val st = Panel.state ?: return
        if (!st.active) return
        val w = watching
        val sig = (w?.state ?: "idle") + "|" + (w?.chId ?: "")
        val now = SystemClock.elapsedRealtime()
        if (!force && sig == lastSig && now - lastHb < (if (w == null) 60_000 else 20_000)) return
        if (now - lastHb < 15_000) return // il pannello accetta al massimo un battito ogni 15 s
        lastSig = sig
        lastHb = now
        val screen = App.top?.javaClass?.simpleName?.removeSuffix("Activity")?.lowercase() ?: ""
        val st2 = if (w != null) try { stats?.invoke() } catch (_: Exception) { null } else null
        bg {
            val j = Panel.heartbeat(w, kickAck, KxVpn.state == "on", screen, st2) ?: return@bg
            handle(j)
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
        a?.onStateChanged()
    }
}
