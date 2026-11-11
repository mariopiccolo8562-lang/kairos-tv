package com.kairos.tv

import android.app.Activity
import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.util.concurrent.Executors

/**
 * VPN WireGuard (libreria ufficiale, come in KAIROS TV). La configurazione arriva dal pannello
 * ed è tenuta solo in memoria. Senza regole sulle app nella VPN passa solo il traffico di questa app.
 * Stati: off | connecting | on | error | denied
 */
object KxVpn {
    @Volatile var state: String = "off"
        private set
    @Volatile var message: String = ""
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kl-vpn").apply { isDaemon = true } }
    private val lock = Any()
    private var backend: GoBackend? = null
    @Volatile private var supportedCache: Boolean? = null
    @Volatile private var userStop = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = "kairoslite"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && !userStop && state == "on") set("off", "La VPN è stata disattivata dal sistema")
        }
    }

    private fun backend(ctx: Context): GoBackend = synchronized(lock) {
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }

    fun supported(ctx: Context): Boolean {
        supportedCache?.let { return it }
        val ok = try { backend(ctx); true } catch (t: Throwable) { false }
        supportedCache = ok
        return ok
    }

    fun start(ctx: Context, conf: String) {
        val app = ctx.applicationContext
        userStop = false
        set("connecting", "Connessione alla VPN…")
        exec.execute {
            try {
                val cfg = parse(conf, app.packageName)
                backend(app).setState(tunnel, Tunnel.State.UP, cfg)
                if (!userStop) set("on", "VPN attiva")
            } catch (e: BackendException) {
                if (e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) set("denied", "Permesso VPN non concesso")
                else set("error", "VPN non avviata")
            } catch (e: BadConfigException) {
                set("error", "Configurazione VPN non valida")
            } catch (t: Throwable) {
                set("error", "VPN non avviata (" + t.javaClass.simpleName + ")")
            }
        }
    }

    fun stop() {
        userStop = true
        exec.execute {
            val b = synchronized(lock) { backend }
            if (b != null) try { b.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            set("off", if (b != null) "VPN disattivata" else "")
        }
    }

    fun active(): Boolean = state == "on" || state == "connecting"
    /** la VPN accesa serve solo a questa app (di serie: IncludedApplications = il pacchetto dell'app); vedi [Quiet.vpnOk] */
    @Volatile var appOnly = true
        private set
    fun waitingConsent() = set("connecting", "Conferma la richiesta di Android per attivare la VPN")
    fun denied() = set("denied", "Permesso VPN negato: la VPN non è stata attivata")
    fun fail(msg: String) = set("error", msg)

    private fun set(st: String, msg: String) { state = st; message = msg }

    private fun parse(conf: String, pkg: String): Config {
        val text = conf.removePrefix("﻿")
        val c = Config.parse(text.byteInputStream())
        val itf = c.getInterface()
        val inc = itf.getIncludedApplications()
        appOnly = (inc.isEmpty() && itf.getExcludedApplications().isEmpty()) || (inc.size == 1 && inc.contains(pkg))
        if (itf.getIncludedApplications().isEmpty() && itf.getExcludedApplications().isEmpty()) {
            val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
            val i = lines.indexOfFirst { it.substringBefore('#').trim().equals("[Interface]", ignoreCase = true) }
            if (i >= 0) {
                lines.add(i + 1, "IncludedApplications = $pkg")
                return Config.parse(lines.joinToString("\n").byteInputStream())
            }
        }
        return c
    }
}

/** Decide se la VPN va accesa: «Sempre attiva» dal pannello, oppure «Consentita» e accesa dal cliente */
object Vpns {
    const val REQ = 4711
    private var current: String? = null
    private var pending: String? = null
    private var asked = false
    /** permesso negato: non si richiede più finché il cliente non lo chiede dalle Impostazioni */
    @Volatile private var refused = false

    fun retry() { refused = false }

    fun wanted(): String? {
        val s = Panel.state ?: return null
        if (!s.active || s.vpnConf.isEmpty()) return null
        return when (s.vpnMode) {
            "force" -> s.vpnConf
            "allow" -> if (s.perm("vpn") && Store.vpnUser) s.vpnConf else null
            else -> null
        }
    }

    /** «Sempre attiva»: liste e visione aspettano la VPN (al massimo 6 s). Da chiamare fuori dal thread principale. */
    fun awaitForced() {
        if (Panel.state?.vpnMode != "force") return
        val end = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < end) {
            val st = KxVpn.state
            if (st == "on" || st == "error" || st == "denied" || refused) return
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        }
    }

    fun apply(a: Activity?) {
        val conf = wanted()
        // DNS cifrato: solo senza la VPN del fornitore (con la VPN il DNS passa già nel tunnel)
        DnsShield.sync(a, conf != null)
        if (conf == null) {
            if (KxVpn.active() || KxVpn.state == "on") KxVpn.stop()
            current = null
            return
        }
        if (KxVpn.active() && current == conf) return
        if (!KxVpn.supported(App.I)) { KxVpn.fail("VPN non supportata su questo dispositivo"); return }
        val i = try { VpnService.prepare(App.I) } catch (_: Exception) { null }
        if (i == null) {
            current = conf
            KxVpn.start(App.I, conf)
        } else if (a != null && !asked && !refused) {
            asked = true
            pending = conf
            KxVpn.waitingConsent()
            try { a.startActivityForResult(i, REQ) } catch (_: Exception) { asked = false; KxVpn.fail("VPN non disponibile su questo dispositivo") }
        }
    }

    fun onConsent(ok: Boolean) {
        asked = false
        val c = pending ?: return
        pending = null
        if (ok) { current = c; KxVpn.start(App.I, c) } else {
            refused = true
            if (Panel.state?.vpnMode == "allow") Store.vpnUser = false
            KxVpn.denied()
        }
    }
}
