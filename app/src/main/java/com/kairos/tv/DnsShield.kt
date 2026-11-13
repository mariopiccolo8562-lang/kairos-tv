package com.kairos.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * DNS cifrato (DNS su HTTPS), deciso dal pannello con la voce «sdns» (on | off; vuoto = spento).
 *
 * Normalmente l'app chiede al DNS dell'operatore a quale indirizzo corrisponde il nome di un server, in chiaro. Con il DNS
 * cifrato la stessa domanda viaggia dentro una connessione https verso un servizio DNS pubblico (1.1.1.1, poi 8.8.8.8, poi
 * 9.9.9.9): l'operatore non la legge e non può cambiare la risposta.
 *
 * Come: un'interfaccia locale di Android (VpnService) che vale SOLO per questa app e instrada SOLO l'indirizzo finto del
 * DNS ([DnsShieldService.DNS]). Ci passano le domande DNS e nient'altro: video, liste e immagini vanno diretti come prima,
 * quindi non rallenta e non pesa. Vale per tutto quello che l'app apre (ExoPlayer, VLC, liste, immagini).
 *
 * A prova di guasto: se i servizi non rispondono per [DnsShieldService.GIVE_UP] domande di fila l'interfaccia si chiude e
 * torna il DNS normale; si riprova dopo 10 minuti. Con la VPN del fornitore accesa non serve (il DNS passa già nel tunnel)
 * e resta spento; se sul dispositivo c'è un'altra VPN non la si tocca.
 * Android chiede il permesso una volta, con la stessa finestra della VPN.
 */
object DnsShield {
    const val REQ = 4712
    /** off | asking | on | denied | error | other */
    @Volatile var state = "off"; private set
    private var asked = false
    @Volatile private var refused = false
    @Volatile private var retryAt = 0L

    fun wanted(): Boolean = Remote.sdns && Panel.state?.active == true

    val message: String get() = when (state) {
        "on" -> "Attivo"
        "asking" -> "Conferma la richiesta di Android"
        "denied" -> "Permesso negato: resta il DNS normale"
        "error" -> "Non riuscito: resta il DNS normale"
        "other" -> "C'è già una VPN attiva sul dispositivo"
        else -> "Spento"
    }

    /** il cliente ha toccato la riga nelle Impostazioni: si può richiedere di nuovo */
    fun retry() { refused = false; retryAt = 0L }

    /** nuova schermata: una richiesta rimasta senza risposta si può rifare */
    fun forgetAsk() { if (asked) { asked = false; if (state == "asking") state = "off" } }

    internal fun report(s: String) { state = s }

    internal fun giveUp() { retryAt = System.currentTimeMillis() + 10 * 60_000L; state = "error" }

    /** da [Vpns.apply]. vpn = la VPN del fornitore è richiesta: il DNS passa già nel tunnel */
    fun sync(a: Activity?, vpn: Boolean) {
        if (!wanted() || vpn) { stop(); return }
        if (state == "on") return
        if (refused || System.currentTimeMillis() < retryAt) return
        if (otherVpn()) { state = "other"; return }
        val i = try { VpnService.prepare(App.I) } catch (_: Exception) { state = "error"; return }
        if (i == null) start()
        else if (a != null && !asked) {
            asked = true; state = "asking"
            ConsentIntro.show(a, { a.startActivityForResult(i, REQ) }) { asked = false; state = "error" }
        }
    }

    fun onConsent(ok: Boolean) {
        asked = false
        if (!ok) { refused = true; state = "denied"; return }
        if (wanted()) start() else state = "off"
    }

    private fun start() {
        try { App.I.startService(Intent(App.I, DnsShieldService::class.java)) } catch (_: Exception) { state = "error" }
    }

    fun stop() {
        if (state == "on") try { App.I.startService(Intent(App.I, DnsShieldService::class.java).setAction(DnsShieldService.STOP)) } catch (_: Exception) {}
        state = "off"
    }

    /** un'altra VPN già attiva sul dispositivo: accendere la nostra la spegnerebbe */
    private fun otherVpn(): Boolean = try {
        if (Build.VERSION.SDK_INT < 23) false else {
            val cm = App.I.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            n != null && cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) { false }
}

/** la domanda DNS, così com'è, a un servizio DNS su HTTPS (RFC 8484); gli indirizzi sono numerici: non serve un DNS per trovarli */
object Doh {
    private val UP = arrayOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query")
    @Volatile private var first = 0

    fun ask(q: ByteArray): ByteArray? {
        for (k in UP.indices) {
            val i = (first + k) % UP.size
            val a = post(UP[i], q)
            if (a != null) { first = i; return a }
        }
        return null
    }

    private fun post(u: String, q: ByteArray): ByteArray? {
        var c: HttpsURLConnection? = null
        return try {
            c = URL(u).openConnection() as HttpsURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.setFixedLengthStreamingMode(q.size)
            c.outputStream.use { it.write(q) }
            if (c.responseCode != 200) { c.disconnect(); null }
            else c.inputStream.use { s -> val b = s.readBytes(); if (b.size in 12..4000) b else null } // connessione lasciata aperta: la prossima domanda la riusa
        } catch (_: Exception) { try { c?.disconnect() } catch (_: Exception) {}; null }
    }
}

class DnsShieldService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private val fails = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { close(); stopSelf(); return START_NOT_STICKY }
        if (tun == null) open()
        return START_NOT_STICKY
    }

    private fun open() {
        try {
            val b = Builder().setSession("DNS cifrato").setMtu(4096)
                .addAddress(LOCAL, 32).addDnsServer(DNS).addRoute(DNS, 32)
            b.addAllowedApplication(packageName) // solo questa app
            b.setBlocking(true)
            val pfd = b.establish() ?: throw IllegalStateException("permesso mancante")
            tun = pfd; fails.set(0)
            pool = Executors.newFixedThreadPool(3)
            thread(name = "kx-dns", isDaemon = true) { pump(pfd) }
            DnsShield.report("on")
        } catch (_: Throwable) { DnsShield.report("error"); close(); stopSelf() }
    }

    private fun pump(pfd: ParcelFileDescriptor) {
        val inp = FileInputStream(pfd.fileDescriptor)
        val out = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(4096)
        try {
            while (tun === pfd) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val q = DnsWire.parse(buf, n) ?: continue
                val ex = pool ?: break
                ex.execute {
                    val ans = Doh.ask(q.payload)
                    if (ans != null) {
                        fails.set(0)
                        val pkt = DnsWire.reply(q, ans)
                        synchronized(out) { try { out.write(pkt) } catch (_: Exception) {} }
                    } else if (fails.incrementAndGet() >= GIVE_UP && tun === pfd) {
                        // i servizi non rispondono: si chiude e torna il DNS normale
                        DnsShield.giveUp(); close(); stopSelf()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun close() {
        val p = tun; tun = null
        try { p?.close() } catch (_: Exception) {}
        try { pool?.shutdownNow() } catch (_: Exception) {}
        pool = null
    }

    override fun onRevoke() { close(); DnsShield.report("off"); super.onRevoke() }

    override fun onDestroy() { close(); if (DnsShield.state == "on") DnsShield.report("off"); super.onDestroy() }

    companion object {
        const val STOP = "kx.dns.stop"
        /** indirizzo finto del DNS: l'unico instradato nell'interfaccia */
        const val DNS = "10.111.222.2"
        const val LOCAL = "10.111.222.1"
        const val GIVE_UP = 6
    }
}
