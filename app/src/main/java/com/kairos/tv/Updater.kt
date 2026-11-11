package com.kairos.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggiornamento automatico: file degli aggiornamenti da store-k.download (/get/tv.json) o dai domini di riserva,
 * niente pannello né GitHub (vedi [UpdFeed]); APK dal link che c'è dentro, poi dalle altre basi.
 * Si controlla a ogni apertura dell'app (al massimo ogni 10 minuti) e ogni ora mentre resta aperta.
 * Aggiornamento in background (scelta del cliente o imposta dal pannello, acceso di serie): la versione nuova
 * si scarica e si verifica in silenzio (solo su Wi-Fi o cavo, mai durante la visione); quando è pronta l'app
 * propone «Installa ora» appena si è in un menu e al cliente resta solo la conferma di Android.
 * Dal pannello: «Invia aggiornamento» (upd nel battito) o il comando «update» fanno partire subito il controllo.
 * Android 12 e successivi (11 novembre 2026): niente proposta, la versione pronta si installa da sola senza conferma quando
 * il cliente esce dall'app (vedi [Quiet]); se non riesce, si torna alla proposta. L'aggiornamento obbligatorio resta.
 */
object Updater {
    class Upd(val code: Int, val ver: String, val url: String)

    private val busy = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    /** versione nuova trovata e non ancora installata */
    @Volatile var pending: Upd? = null
        private set
    /** APK della versione [pending] già scaricato e verificato */
    @Volatile var ready: File? = null
        private set
    /** già proposta in questa apertura dell'app */
    @Volatile private var askedCode = 0
    /** la proposta normale in corso: finestra, schermata che la mostra e risposta del cliente (Installa, Aggiorna, Più tardi) */
    private var prompted: AlertDialog? = null
    private var promptOwner: java.lang.ref.WeakReference<Activity>? = null
    private var answered = false
    /** azione automatica (es. il film che parte da solo) rimandata finché il cliente non risponde: parte con «Più tardi» */
    private var afterLater: (() -> Unit)? = null
    /** «Installa ora» senza il permesso di Android: al ritorno dalle impostazioni l'installazione riparte da sola */
    @Volatile private var afterPerm: File? = null
    /** «Aggiorna ora» mentre la versione si sta già scaricando in background: si installa appena è pronta */
    @Volatile private var installWhenReady = false

    /**
     * All'avvio, anche senza rete: la versione trovata in un'apertura precedente (e l'APK già scaricato e verificato)
     * torna subito. Così l'aggiornamento obbligatorio blocca l'app prima che si possa guardare qualsiasi cosa, e quello
     * normale si propone appena compare la prima schermata, senza aspettare il controllo in rete.
     */
    fun restore() {
        val code = Store.updCode
        if (code == 0) return
        val url = Store.updUrl
        if (code <= BuildConfig.VERSION_CODE || !url.startsWith("https://")) { Store.clearUpd(); return } // già installata
        pending = Upd(code, Store.updVer, url)
        val path = Store.updFile
        if (path.isEmpty()) return
        val f = File(path)
        if (f.exists() && f.length() == Store.updLen && f.length() >= 300_000) ready = f else Store.saveUpdFile("", 0L)
    }

    /** APK pronto (scaricato e verificato): lo si ricorda anche su disco */
    private fun markReady(f: File) {
        ready = f
        Store.saveUpdFile(f.absolutePath, f.length())
    }
    @Volatile private var lastCheck = 0L
    @Volatile private var prefetchFailed = 0

    /**
     * Controlli e scaricamenti in background su un thread loro, a bassa priorità: non occupano i thread che leggono
     * l'archivio per le schermate (all'apertura dell'app servono a liste e locandine).
     */
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kl-upd").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun work(block: () -> Unit) {
        exec.execute { try { block() } catch (t: Throwable) { android.util.Log.w("KairosTV", "upd", t) } }
    }

    /** controllo al ritorno in primo piano: non urgente, parte qualche secondo dopo che l'app è comparsa */
    private val fgCheck = Runnable { maybeCheck(10 * 60_000L) }

    /** l'app torna in primo piano: nuovo controllo e, se c'è già una versione nuova, la si propone di nuovo */
    fun onForeground() {
        Quiet.cancel()
        askedCode = 0
        answered = false
        App.main.removeCallbacks(fgCheck)
        App.main.postDelayed(fgCheck, 5_000)
    }

    /** il cliente è uscito dall'app: tra qualche secondo, se non torna, la versione pronta si installa da sola (Android 12+) */
    fun onBackground() = Quiet.schedule { quietNow() }

    /** la versione [u] la installa l'aggiornamento senza conferma: niente proposta al cliente (resta quella obbligatoria);
     *  se dopo 3 giorni non si è ancora installata (app mai chiusa) torna la proposta di sempre */
    private fun quietWay(u: Upd): Boolean = System.currentTimeMillis() - firstSeen(u) < 3 * 86_400_000L &&
        Quiet.handles(Build.VERSION.SDK_INT, Remote.canInstall(), Store.quietTried, u.code, ready?.exists() == true, Store.bgUpdate && !metered() && prefetchFailed < 3)

    /** cliente fuori dall'app da qualche secondo: installazione senza conferma della versione pronta (un tentativo per versione) */
    private fun quietNow() {
        val u = pending ?: return
        val f = ready ?: return
        if (!f.exists() || Store.quietTried == u.code || busy.get() || watching()) return
        if (!Quiet.vpnOk(KxVpn.active(), KxVpn.appOnly)) return
        work {
            if (App.started != 0 || Store.quietTried == u.code || !f.exists()) return@work
            Store.quietTried = u.code
            val started = Quiet.install(App.I, f, u.code) { App.started == 0 }
            // il cliente è tornato mentre si preparava: nessuna installazione, si riprova alla prossima uscita
            if (!started && App.started != 0) Store.quietTried = 0
        }
    }

    /** chiamato anche dal battito ogni 20 s: il controllo vero parte solo ogni [gap] */
    fun maybeCheck(gap: Long = 60 * 60_000L) {
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < gap) return
        if (!checking.compareAndSet(false, true)) return
        lastCheck = now
        work { try { check(null) } finally { checking.set(false) } }
    }

    /** il pannello chiede l'aggiornamento (upd nel battito o comando «update»): controllo subito, scaricamento in background e proposta */
    fun fromPanel(done: ((String) -> Unit)? = null) {
        if (!checking.compareAndSet(false, true)) { done?.invoke("controllo già in corso"); return }
        lastCheck = SystemClock.elapsedRealtime()
        work {
            try {
                val u = check(null, force = true)
                done?.invoke(if (u == null) "già aggiornata (" + BuildConfig.VERSION_NAME + ")" else if (quietWay(u)) "versione " + u.ver + (if (ready != null) " pronta" else " in scaricamento") + ": si installa da sola quando il cliente esce dall'app" else when { App.top is PlayerActivity -> "versione " + u.ver + ": proposta a fine visione"; ready != null -> "versione " + u.ver + " pronta: proposta al cliente"; else -> "versione " + u.ver + " in scaricamento" })
            } finally { checking.set(false) }
        }
    }

    /** [manual] = schermata da cui l'utente ha chiesto il controllo (riceve anche «nessun aggiornamento»); [force] = anche su rete a consumo */
    fun check(manual: Activity?, force: Boolean = false): Upd? {
        val apk = Panel.update()?.optJSONObject("apk")
        val code = apk?.optInt("versionCode", 0) ?: 0
        val url = apk?.optString("url", "") ?: ""
        val ver = apk?.optString("version", "") ?: ""
        if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) {
            val u = Upd(code, ver, url)
            firstSeen(u)
            if (pending?.code != code) { ready = null; prefetchFailed = 0; Store.saveUpdFile("", 0L) }
            pending = u
            if (Store.updCode != code || Store.updVer != ver || Store.updUrl != url) Store.saveUpd(code, ver, url)
            // già scaricato in un'apertura precedente; verificato una volta sola (firma e versione: è un lavoro pesante sulle chiavette)
            if (ready?.exists() != true) staged(u)?.let { markReady(it) }
            if (ready == null && manual == null && (Store.bgUpdate || force) && (force || !metered()) && prefetchFailed < 3) prefetch(u)
            ui { if (manual != null) prompt(manual, u) else offer(App.top) }
            return u
        }
        if (apk != null) { pending = null; ready = null; Store.clearUpd(); ui { try { lock?.dismiss() } catch (_: Exception) {}; lock = null } }
        else if (manual == null) lastCheck = SystemClock.elapsedRealtime() - 55 * 60_000L // rete giù: si riprova tra 5 minuti
        if (manual != null) ui { Ui.toast(if (apk == null) "Impossibile controllare gli aggiornamenti adesso" else "Hai già l'ultima versione (${BuildConfig.VERSION_NAME})") }
        return null
    }

    private fun dir(): File = (if (Build.VERSION.SDK_INT >= 24) File(App.I.cacheDir, "apk") else (App.I.externalCacheDir ?: File(App.I.cacheDir, "apk"))).apply { mkdirs() }
    private fun fileFor(u: Upd) = File(dir(), "kairos-tv-${u.code}.apk")

    /** APK di [u] scaricato in precedenza e ancora valido (firma e versione giuste) */
    private fun staged(u: Upd): File? {
        val f = fileFor(u)
        if (!f.exists()) return null
        if (f.length() < 300_000 || ApkCheck.verify(App.I, f) != null) { f.delete(); return null }
        return f
    }

    /** rete a consumo (dati mobili, hotspot): niente download non richiesti */
    private fun metered(): Boolean = try {
        (App.I.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** visione iniziata durante lo scaricamento in background: si interrompe e si riprova più tardi */
    private class Paused : Exception()

    /** un lettore è attivo (schermo intero o anteprima nella lista canali): la banda serve al video */
    private fun watching(): Boolean = App.top is PlayerActivity || Players.previewing

    /** scaricamento in background (chiamato da un thread di lavoro): nessuna finestra; quando è pronto si propone «Installa ora» */
    private fun prefetch(u: Upd) {
        if (watching()) return // mai durante la visione: la banda serve al video
        if (!busy.compareAndSet(false, true)) return
        try {
            val out = fileFor(u)
            fetch(u.url, out, null) { watching() }
            markReady(out)
            prefetchFailed = 0
            cleanOld(out)
            ui {
                val a = App.top
                // il cliente aveva già premuto «Aggiorna ora»: si installa subito (mai sopra la visione: lì si propone al ritorno)
                if (installWhenReady && a != null && a !is PlayerActivity && !a.isFinishing) { installWhenReady = false; install(a, out) }
                else { askedCode = 0; offer(a) }
                if (App.started == 0) onBackground() // scaricata con l'app in background: si installa da sola (Android 12+)
            }
        } catch (_: Paused) {
            try { fileFor(u).delete() } catch (_: Exception) {}
            lastCheck = SystemClock.elapsedRealtime() - 55 * 60_000L // non è un errore: si riprova tra 5 minuti
        } catch (e: Exception) {
            prefetchFailed++ // si riprova al prossimo controllo (massimo tre volte per versione)
            if (installWhenReady) { installWhenReady = false; ui { Ui.toast("Aggiornamento non riuscito: " + (e.message ?: "errore")) } }
        } finally { busy.set(false) }
    }

    /** vecchi APK scaricati: via tutti tranne [keep] */
    private fun cleanOld(keep: File?) {
        try { dir().listFiles()?.forEach { if (it != keep && it.name.endsWith(".apk")) it.delete() } } catch (_: Exception) {}
    }

    /**
     * scarica l'APK del link [url] del file degli aggiornamenti in [out] e lo verifica; se non riesce (rete, errore HTTP,
     * file sbagliato) lo stesso APK dalle altre basi dei download (/get/tv.apk), vedi
     * [UpdFeed.apkUrls]. [progress] riceve 0-99; [pause] = vero se bisogna interrompere (visione iniziata): non è un errore,
     * si riprova più tardi senza passare alle altre basi.
     */
    private fun fetch(url: String, out: File, progress: ((Int) -> Unit)?, pause: (() -> Boolean)? = null) =
        UpdFeed.firstOk(UpdFeed.apkUrls(url, BuildConfig.DL_BASE, "tv", Panel.dlExtra()), { it is Paused }) {
            if (pause?.invoke() == true) throw Paused() // visione iniziata: nessun altro tentativo adesso
            fetchOne(it, out, progress, pause)
        }

    /** scarica [url] in [out] e lo verifica (un solo indirizzo) */
    private fun fetchOne(url: String, out: File, progress: ((Int) -> Unit)?, pause: (() -> Boolean)?) {
        val c = Http.open(url, connectMs = 10_000, readMs = 30_000)
        try {
            if (c.responseCode != 200) throw Exception(if (c.responseCode == 404) "file non trovato (HTTP 404)" else "HTTP ${c.responseCode}")
            val total = c.contentLength.toLong()
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                FileOutputStream(out).use { fo ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (pause != null && pause()) throw Paused()
                        fo.write(buf, 0, n)
                        done += n
                        if (total > 0 && progress != null) {
                            val p = ((done * 100) / total).toInt().coerceAtMost(99)
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) { out.delete(); throw Exception("download interrotto: controlla la connessione e riprova") }
        } finally { c.disconnect() }
        val head = ByteArray(2)
        FileInputStream(out).use { it.read(head) }
        if (out.length() < 300_000 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) { out.delete(); throw Exception("il link non porta a un file APK") }
        ApkCheck.verify(App.I, out)?.let { msg -> out.delete(); throw Exception(msg) }
    }

    /** primo avviso al cliente di [u]: da lì contano i giorni di grazia dell'aggiornamento obbligatorio */
    private fun firstSeen(u: Upd): Long {
        if (Store.updFirstCode != u.code || Store.updFirstAt == 0L) { Store.updFirstCode = u.code; Store.updFirstAt = System.currentTimeMillis() }
        return Store.updFirstAt
    }

    /** l'app va bloccata: termine scaduto e APK già scaricato e verificato (mai per un problema di rete) */
    fun must(): Boolean {
        val u = pending ?: return false
        val days = Remote.forceDays ?: return false
        if (ready?.exists() != true) return false
        return System.currentTimeMillis() - firstSeen(u) >= days * 86_400_000L
    }

    /** propone la versione nuova (una volta per apertura, sempre se obbligatoria) appena si è in un menu; ogni schermata la chiama all'apertura */
    fun offer(a: Activity?) {
        if (a == null || a.isFinishing || a is PlayerActivity) return
        // di ritorno dalle impostazioni di Android dopo «Installa ora» senza permesso: l'installazione riparte da sola
        afterPerm?.let { f ->
            afterPerm = null
            if (Remote.canInstall()) { if (f.exists()) { install(a, f); return } }
            else Ui.toast("Manca ancora il permesso «Installa app sconosciute» per ${a.getString(R.string.app_name)}: senza, l'aggiornamento non si può installare")
        }
        val u = pending ?: return
        if (must()) { if (!busy.get()) prompt(a, u); return }
        if (quietWay(u)) return // Android 12+: si installa da sola quando il cliente esce dall'app
        if (busy.get()) return
        // già proposta in questa apertura: si ripete solo se la finestra è andata persa senza risposta (schermata chiusa sotto di lei)
        if (askedCode == u.code && (answered || stillShown())) return
        askedCode = u.code
        prompt(a, u)
    }

    /** la proposta normale è ancora davanti al cliente (anche se coperta dal lettore: la ritrova tornando indietro) */
    private fun stillShown(): Boolean {
        val d = prompted ?: return false
        val o = promptOwner?.get() ?: return false
        return d.isShowing && !o.isFinishing && !o.isDestroyed
    }

    /**
     * Azioni che partono da sole (es. «Riproduci» dal riquadro grande apre la scheda e fa partire il film): con una proposta
     * d'aggiornamento aperta su [a] si aspetta la risposta, e [go] parte con «Più tardi»; con l'aggiornamento obbligatorio mai.
     */
    fun whenFree(a: Activity, go: () -> Unit) {
        if (must()) return
        if (!answered && stillShown() && promptOwner?.get() === a) { afterLater = go; return }
        go()
    }

    private fun later() {
        answered = true
        val go = afterLater
        afterLater = null
        val o = promptOwner?.get()
        if (go != null && o != null && !o.isFinishing && !o.isDestroyed) go()
    }

    /** finestra della proposta normale: si sa se il cliente ha risposto e su quale schermata è aperta */
    private fun proposal(a: Activity, title: String, msg: String, ok: String, onOk: () -> Unit) {
        answered = false
        afterLater = null
        val d = try {
            AlertDialog.Builder(a).setTitle(title).setMessage(msg)
                .setPositiveButton(ok) { _, _ -> answered = true; afterLater = null; onOk() }
                .setNegativeButton("Più tardi") { _, _ -> later() }
                .setOnCancelListener { later() }
                .show()
        } catch (_: Exception) { null }
        if (d == null) { askedCode = 0; return } // non è comparsa: si ripropone alla prossima schermata
        prompted = d
        promptOwner = java.lang.ref.WeakReference(a)
    }

    @Volatile private var lock: AlertDialog? = null

    private fun prompt(a: Activity, u: Upd) {
        if (a.isFinishing) { askedCode = 0; return }
        firstSeen(u)
        val r = ready
        if (must() && r != null) {
            // aggiornamento obbligatorio: si va avanti solo installando (niente «Più tardi», il tasto indietro non chiude)
            if (lock?.isShowing == true && lock?.ownerActivity === a) return
            try { lock?.dismiss() } catch (_: Exception) {}
            val d = AlertDialog.Builder(a)
                .setTitle("Aggiornamento ${u.ver} necessario")
                .setMessage("Per continuare a usare ${a.getString(R.string.app_name)} va installata la versione nuova: è già scaricata e verificata. Premi Installa e conferma nella schermata di Android, ci vuole meno di un minuto." + Quiet.hint(Store.quietFailCode, Store.quietFailSt, u.code, Build.MANUFACTURER ?: ""))
                .setCancelable(false)
                .setPositiveButton("Installa ora", null)
                .setNegativeButton("Chiudi l'app") { _, _ -> a.finishAffinity() }
                .create()
            d.setOwnerActivity(a)
            d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { install(a, r) } } // la finestra resta finché l'installer non riavvia l'app
            lock = d
            try { d.show() } catch (_: Exception) {}
            (a as? BaseActivity)?.onBlocked() // niente video dietro la finestra che blocca l'app (anteprima dei canali)
            return
        }
        if (r != null && r.exists()) {
            proposal(a, "Aggiornamento ${u.ver} pronto", "La versione nuova di ${a.getString(R.string.app_name)} è già scaricata e verificata: premi Installa e conferma nella schermata di Android. Ci vuole meno di un minuto." + Quiet.hint(Store.quietFailCode, Store.quietFailSt, u.code, Build.MANUFACTURER ?: ""), "Installa ora") {
                install(a, r)
            }
        } else {
            proposal(a, "Nuova versione disponibile", "È pronta la versione ${u.ver} di ${a.getString(R.string.app_name)}. L'aggiornamento richiede meno di un minuto.", "Aggiorna ora") {
                download(a, u.url)
            }
        }
    }

    fun download(a: Activity, url: String) {
        // già scaricato in background: si passa subito all'installazione
        val u = pending
        val r = ready
        if (u != null && u.url == url && r != null && r.exists()) { install(a, r); return }
        if (!busy.compareAndSet(false, true)) {
            // la versione si sta già scaricando in background: non si perde la richiesta, si installa appena è pronta
            installWhenReady = true
            Ui.toast("L'aggiornamento si sta già scaricando: appena è pronto parte l'installazione")
            return
        }
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(a, 24)
        box.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 8))
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val txt = TextView(a)
        txt.text = "Scaricamento…"
        txt.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.muted))
        box.addView(bar)
        box.addView(txt)
        val d: AlertDialog? = try { AlertDialog.Builder(a).setTitle("Aggiornamento").setView(box).setCancelable(false).show() } catch (_: Exception) { null }
        bg {
            try {
                val out = if (u != null && u.url == url) fileFor(u) else File(dir(), "kairos-tv-update.apk")
                fetch(url, out, { p -> ui { bar.progress = p; txt.text = "Scaricamento… $p%" } })
                if (u != null && u.url == url) markReady(out)
                ui { try { d?.dismiss() } catch (_: Exception) {}; install(a, out) }
            } catch (e: Exception) {
                ui { try { d?.dismiss() } catch (_: Exception) {}; Ui.toast("Aggiornamento non riuscito: " + (e.message ?: "errore")) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun install(a: Activity, apk: File) {
        if (!Remote.canInstall()) {
            afterPerm = apk // al ritorno nell'app l'installazione riparte da sola (vedi [offer])
            Ui.toast("Per installare l'aggiornamento serve il permesso «Installa app sconosciute»: attivalo per ${a.getString(R.string.app_name)} e torna indietro, l'installazione riparte da sola")
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in tries) { try { a.startActivity(i); return } catch (_: Exception) {} }
            return
        }
        val uri: Uri = try {
            if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, a.packageName + ".files", apk) else Uri.fromFile(apk)
        } catch (_: Exception) { Ui.toast("Installazione non disponibile su questo dispositivo"); return }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { a.startActivity(i) } catch (_: Exception) { Ui.toast("Installazione non disponibile su questo dispositivo") }
    }
}

/** Controlli sull'APK scaricato (stessi di KAIROS TV): app giusta, versione più nuova, stessa firma */
object ApkCheck {
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val info = archiveInfo(pm, apk.absolutePath, 0) ?: return "Il file scaricato è danneggiato: riprova"
        if (info.packageName != ctx.packageName) return "Il file scaricato non è un aggiornamento di questa app"
        val newCode = code(info)
        val myCode = BuildConfig.VERSION_CODE.toLong()
        if (newCode == myCode) return "È la stessa versione già installata"
        if (newCode < myCode) return "La versione scaricata è più vecchia di quella installata"
        val mine = certs(installedInfo(pm, ctx.packageName))
        val theirs = certs(archiveInfo(pm, apk.absolutePath, sigFlags()))
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) return "L'aggiornamento è firmato con una chiave diversa: disinstalla l'app e installala di nuovo dallo store"
        return null
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun sigFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = try { pm.getPackageArchiveInfo(path, flags) } catch (_: Throwable) { null }

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try { pm.getPackageInfo(pkg, sigFlags()) } catch (_: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo?): Set<String> {
        if (p == null) return emptySet()
        val list = ArrayList<Signature>()
        if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo
            if (si != null) {
                val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                if (arr != null) list.addAll(arr)
            }
        }
        if (list.isEmpty()) p.signatures?.let { list.addAll(it) }
        val out = HashSet<String>()
        for (s in list) {
            try {
                val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                out.add(d.joinToString("") { String.format("%02x", it) })
            } catch (_: Exception) {}
        }
        return out
    }
}
