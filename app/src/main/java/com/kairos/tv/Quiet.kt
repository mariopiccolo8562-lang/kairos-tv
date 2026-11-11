package com.kairos.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * Aggiornamento senza conferma (11 novembre 2026, scelta del titolare): con Android 12 e successivi la versione nuova, già
 * scaricata e verificata da [Updater], si installa da sola con PackageInstaller quando il cliente esce dall'app, senza
 * nessuna schermata. Android lo permette solo se l'app aggiorna se stessa, ha il permesso «Installa app sconosciute»,
 * dichiara UPDATE_PACKAGES_WITHOUT_USER_ACTION e l'APK è costruito per un Android abbastanza recente (targetSdk 35: fino
 * ad Android 17 compreso). Mai durante la visione né con la VPN che serve anche ad altre app.
 * Un tentativo per versione: se Android chiede comunque la conferma (alcuni produttori) o l'installazione non riesce,
 * la sessione si annulla e al ritorno nell'app resta la proposta di sempre («Installa ora» e conferma di Android).
 * Su Android 11 e precedenti (tutte le Fire TV) non cambia nulla.
 */
object Quiet {
    /** dopo quanto, uscito il cliente, si prova (se nel frattempo torna nell'app non succede nulla) */
    private const val WAIT_MS = 10_000L

    /** la versione [code] la installa l'aggiornamento senza conferma: Android 12+, permesso, nessun tentativo già fatto per
     *  questa versione, APK pronto o in arrivo in background (funzione pura: provata in QuietTest) */
    fun handles(sdk: Int, canInstall: Boolean, tried: Int, code: Int, ready: Boolean, willFetch: Boolean): Boolean =
        sdk >= 31 && canInstall && tried != code && (ready || willFetch)

    /** una VPN accesa che serve anche ad altre app non si spegne installando (quella solo di questa app sì: al ritorno riparte) */
    fun vpnOk(active: Boolean, appOnly: Boolean): Boolean = !active || appOnly

    /** riga in più nella proposta se l'installazione senza conferma della versione [code] è stata bloccata su un Samsung
     *  (esito 2 = STATUS_FAILURE_BLOCKED: di solito il «Blocco automatico») */
    fun hint(failCode: Int, failSt: Int, code: Int, maker: String): String =
        if (code != 0 && failCode == code && failSt == 2 && maker.equals("samsung", ignoreCase = true))
            "\n\nSe Android blocca l'installazione: spegni «Blocco automatico» in Impostazioni › Sicurezza e privacy, poi riprova."
        else ""

    /** su questo dispositivo si può provare adesso */
    fun possible(): Boolean = Build.VERSION.SDK_INT >= 31 && Remote.canInstall()

    private var job: Runnable? = null

    /** il cliente è uscito dall'app: tra [WAIT_MS] parte [go] (sul thread principale), a meno che non torni prima */
    fun schedule(go: () -> Unit) {
        cancel()
        if (!possible()) return
        val r = Runnable { job = null; if (App.started == 0) go() }
        job = r
        App.main.postDelayed(r, WAIT_MS)
    }

    fun cancel() {
        job?.let { App.main.removeCallbacks(it) }
        job = null
    }

    /**
     * Installa [apk] (già verificato da [ApkCheck]) senza conferma, da un thread di lavoro. Prima di confermare ricontrolla
     * [away]: se il cliente è tornato nell'app la sessione si annulla e non succede nulla. Vero se l'installazione è partita.
     */
    fun install(ctx: Context, apk: File, code: Int, away: () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val pi = ctx.packageManager.packageInstaller
        var id = -1
        try {
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            p.setAppPackageName(ctx.packageName)
            p.setSize(apk.length())
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            id = pi.createSession(p)
            pi.openSession(id).use { s ->
                FileInputStream(apk).use { inp ->
                    s.openWrite("base.apk", 0, apk.length()).use { out ->
                        inp.copyTo(out, 64 * 1024)
                        s.fsync(out)
                    }
                }
                if (!away()) { s.abandon(); return false }
                val done = Intent(ctx, QuietDone::class.java).putExtra("code", code)
                val pend = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pend.intentSender)
            }
            return true
        } catch (t: Throwable) {
            if (id >= 0) try { pi.abandonSession(id) } catch (_: Throwable) {}
            android.util.Log.w("KairosTV", "quiet", t)
            return false
        }
    }
}

/** esito dell'aggiornamento senza conferma (se riesce lo riceve già la versione nuova) */
class QuietDone : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (st) {
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android vuole comunque la conferma: con il cliente fuori dall'app non si mostra nulla, resta la proposta di sempre
                val sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (sid >= 0) try { ctx.packageManager.packageInstaller.abandonSession(sid) } catch (_: Throwable) {}
            }
            else -> try { Store.quietFailed(i.getIntExtra("code", 0), st) } catch (_: Throwable) {}
        }
    }
}
