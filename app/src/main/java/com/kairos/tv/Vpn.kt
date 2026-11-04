package com.kairos.tv

import android.content.Context
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.util.concurrent.Executors

/**
 * VPN WireGuard (libreria ufficiale com.wireguard.android:tunnel, GoBackend).
 * La configurazione (testo wg-quick) arriva dal pannello del rivenditore tramite l'interfaccia web.
 * Se la configurazione non indica IncludedApplications/ExcludedApplications, nella VPN passa
 * solo il traffico di questa app (le altre app del dispositivo non vengono toccate).
 * La configurazione e la chiave privata non vengono mai scritte nei log né salvate su disco.
 * Stati: off | connecting | on | error | denied
 */
object KxVpn {
    @Volatile
    var state: String = "off"
        private set

    @Volatile
    var message: String = ""
        private set

    /** (stato, messaggio) a ogni cambiamento */
    @Volatile var listener: ((String, String) -> Unit)? = null

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kx-vpn").apply { isDaemon = true } }
    private val lock = Any()
    private var backend: GoBackend? = null
    @Volatile private var supportedCache: Boolean? = null
    @Volatile private var userStop = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = "kairos"
        override fun onStateChange(newState: Tunnel.State) {
            // VPN chiusa dal sistema (altra VPN attivata, permesso revocato dalle impostazioni…)
            if (newState == Tunnel.State.DOWN && !userStop && state == "on") set("off", "La VPN è stata disattivata dal sistema")
        }
    }

    private fun backend(ctx: Context): GoBackend = synchronized(lock) {
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }

    /** la libreria nativa di WireGuard si carica su questo dispositivo */
    fun supported(ctx: Context): Boolean {
        supportedCache?.let { return it }
        val ok = try { backend(ctx); true } catch (t: Throwable) { false }
        supportedCache = ok
        return ok
    }

    /** controllo veloce della configurazione prima di chiedere il permesso ad Android; null = valida */
    fun check(conf: String): String? = try {
        parse(conf, "")
        null
    } catch (e: BadConfigException) {
        badText(e)
    } catch (t: Throwable) {
        "Configurazione VPN non valida"
    }

    /** da chiamare dopo VpnService.prepare() (permesso già concesso) */
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
                else set("error", backendText(e.reason))
            } catch (e: BadConfigException) {
                set("error", badText(e))
            } catch (t: Throwable) {
                set("error", "VPN non avviata (" + t.javaClass.simpleName + ")")
            }
        }
    }

    fun stop() {
        userStop = true
        // sempre in coda dopo un eventuale avvio in corso (stesso thread): altrimenti, fermata prima che l'avvio
        // creasse il motore, la VPN restava accesa con lo stato "off"
        exec.execute {
            val b = synchronized(lock) { backend }
            if (b != null) try { b.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            set("off", if (b != null) "VPN disattivata" else "")
        }
    }

    fun active(): Boolean = state == "on" || state == "connecting"

    fun waitingConsent() = set("connecting", "Conferma la richiesta di Android per attivare la VPN")
    fun denied() = set("denied", "Permesso VPN negato: la VPN non è stata attivata")
    fun fail(msg: String) = set("error", msg)

    private fun set(st: String, msg: String) {
        state = st
        message = msg
        try { listener?.invoke(st, msg) } catch (_: Throwable) {}
    }

    /** [pkg] non vuoto: senza regole sulle app, nella VPN passa solo questa app */
    private fun parse(conf: String, pkg: String): Config {
        val text = conf.removePrefix("﻿")
        val c = Config.parse(text.byteInputStream())
        if (pkg.isEmpty()) return c
        val itf = c.getInterface()
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

    private fun badText(e: BadConfigException): String {
        val where = try { e.getLocation().getName() } catch (_: Throwable) { "" }
        val why = when (e.getReason()) {
            BadConfigException.Reason.INVALID_KEY -> "chiave non valida"
            BadConfigException.Reason.INVALID_NUMBER -> "numero non valido"
            BadConfigException.Reason.INVALID_VALUE -> "valore non valido"
            BadConfigException.Reason.MISSING_ATTRIBUTE -> "valore mancante"
            BadConfigException.Reason.MISSING_SECTION -> "sezione mancante"
            BadConfigException.Reason.SYNTAX_ERROR -> "errore di sintassi"
            BadConfigException.Reason.UNKNOWN_ATTRIBUTE -> "voce sconosciuta"
            BadConfigException.Reason.UNKNOWN_SECTION -> "sezione sconosciuta"
            else -> "errore"
        }
        return "Configurazione VPN non valida" + (if (where.isNotEmpty()) " ($where: $why)" else " ($why)")
    }

    private fun backendText(r: BackendException.Reason?): String = when (r) {
        BackendException.Reason.UNABLE_TO_START_VPN -> "Impossibile avviare il servizio VPN"
        BackendException.Reason.TUN_CREATION_ERROR -> "Impossibile creare la VPN (è attiva un'altra VPN?)"
        BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "Errore del motore WireGuard"
        BackendException.Reason.DNS_RESOLUTION_FAILURE -> "Server VPN non raggiungibile"
        BackendException.Reason.TUNNEL_MISSING_CONFIG -> "Configurazione VPN mancante"
        BackendException.Reason.VPN_NOT_AUTHORIZED -> "Permesso VPN non concesso"
        else -> "Errore VPN"
    }
}
