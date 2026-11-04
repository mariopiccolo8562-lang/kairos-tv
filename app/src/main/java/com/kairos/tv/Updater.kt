package com.kairos.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
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
 * Aggiornamento automatico dalle release di GitHub (kairos-tv.json).
 * Si controlla a ogni apertura dell'app (al massimo ogni 10 minuti) e ogni ora mentre resta aperta;
 * l'avviso compare da solo appena si è in un menu (mai sopra la visione) e, con «Più tardi»,
 * torna alla prossima apertura.
 */
object Updater {
    class Upd(val code: Int, val ver: String, val url: String)

    private val busy = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    /** versione nuova trovata e non ancora installata */
    @Volatile var pending: Upd? = null
        private set
    /** già proposta in questa apertura dell'app */
    @Volatile private var askedCode = 0
    @Volatile private var lastCheck = 0L

    /** l'app torna in primo piano: nuovo controllo e, se c'è già una versione nuova, la si propone di nuovo */
    fun onForeground() {
        askedCode = 0
        maybeCheck(10 * 60_000L)
    }

    /** chiamato anche dal battito ogni 20 s: il controllo vero parte solo ogni [gap] */
    fun maybeCheck(gap: Long = 60 * 60_000L) {
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < gap) return
        if (!checking.compareAndSet(false, true)) return
        lastCheck = now
        bg { try { check(null) } finally { checking.set(false) } }
    }

    /** [manual] = schermata da cui l'utente ha chiesto il controllo (riceve anche «nessun aggiornamento») */
    fun check(manual: Activity?) {
        val apk = Panel.update()?.optJSONObject("apk")
        val code = apk?.optInt("versionCode", 0) ?: 0
        val url = apk?.optString("url", "") ?: ""
        val ver = apk?.optString("version", "") ?: ""
        if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) {
            val u = Upd(code, ver, url)
            pending = u
            ui { if (manual != null) prompt(manual, u) else offer(App.top) }
        } else {
            if (apk != null) pending = null
            else if (manual == null) lastCheck = SystemClock.elapsedRealtime() - 55 * 60_000L // rete giù: si riprova tra 5 minuti
            if (manual != null) ui { Ui.toast(if (apk == null) "Impossibile controllare gli aggiornamenti adesso" else "Hai già l'ultima versione (${BuildConfig.VERSION_NAME})") }
        }
    }

    /** propone la versione nuova (una volta per apertura) appena si è in un menu; ogni schermata la chiama all'apertura */
    fun offer(a: Activity?) {
        val u = pending ?: return
        if (a == null || a.isFinishing || a is PlayerActivity) return
        if (askedCode == u.code || busy.get()) return
        askedCode = u.code
        prompt(a, u)
    }

    private fun prompt(a: Activity, u: Upd) {
        if (a.isFinishing) return
        Ui.dialog(a, "Nuova versione disponibile", "È pronta la versione ${u.ver} di ${a.getString(R.string.app_name)}. L'aggiornamento richiede meno di un minuto.", "Aggiorna ora", "Più tardi") {
            download(a, u.url)
        }
    }

    fun download(a: Activity, url: String) {
        if (!busy.compareAndSet(false, true)) return
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
                // Android 5-6: l'installer non legge i file privati dell'app → cache esterna
                val dir = (if (Build.VERSION.SDK_INT >= 24) File(a.cacheDir, "apk") else (a.externalCacheDir ?: File(a.cacheDir, "apk"))).apply { mkdirs() }
                val out = File(dir, "kairos-tv-update.apk")
                val c = Http.open(url, connectMs = 20_000, readMs = 30_000)
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
                                fo.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = ((done * 100) / total).toInt().coerceAtMost(99)
                                    if (p != last) { last = p; ui { bar.progress = p; txt.text = "Scaricamento… $p%" } }
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) { out.delete(); throw Exception("download interrotto: controlla la connessione e riprova") }
                } finally { c.disconnect() }
                val head = ByteArray(2)
                FileInputStream(out).use { it.read(head) }
                if (out.length() < 300_000 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) { out.delete(); throw Exception("il link non porta a un file APK") }
                ApkCheck.verify(a, out)?.let { msg -> out.delete(); throw Exception(msg) }
                ui { try { d?.dismiss() } catch (_: Exception) {}; install(a, out) }
            } catch (e: Exception) {
                ui { try { d?.dismiss() } catch (_: Exception) {}; Ui.toast("Aggiornamento non riuscito: " + (e.message ?: "errore")) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun install(a: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Ui.toast("Consenti a ${a.getString(R.string.app_name)} di installare app, poi riprova l'aggiornamento")
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in tries) { try { a.startActivity(i); return } catch (_: Exception) {} }
            return
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, a.packageName + ".files", apk) else Uri.fromFile(apk)
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
