package com.kairos.tv

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Controlli sull'APK scaricato prima di aprire l'installer:
 * - deve essere più recente della versione installata (evita il ciclo "aggiorna alla stessa versione")
 * - deve essere firmato con la stessa chiave (altrimenti Android rifiuta l'installazione)
 * Restituisce null se si può installare, altrimenti il messaggio da mostrare.
 * Un file non leggibile o di un'altra app viene rifiutato; se manca solo la firma il controllo viene saltato (decide Android).
 */
object ApkCheck {
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        // file non leggibile (download interrotto o rovinato): meglio dirlo subito che far fallire l'installer
        val info = archiveInfo(pm, apk.absolutePath, 0) ?: return "Il file scaricato è danneggiato: riprova l'aggiornamento"
        if (info.packageName != ctx.packageName) return "Il file scaricato non è un aggiornamento di questa app: controlla il link dell'APK nel pannello"
        val newCode = code(info)
        val myCode = BuildConfig.VERSION_CODE.toLong()
        if (newCode == myCode) return "È la stessa versione già installata"
        if (newCode < myCode) return "La versione scaricata (${info.versionName ?: newCode}) è più vecchia di quella installata"
        val mine = certs(installedInfo(pm, ctx.packageName))
        val theirs = certs(archiveInfo(pm, apk.absolutePath, sigFlags()))
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            return "L'aggiornamento è firmato con una chiave diversa: disinstalla l'app e installa la nuova versione dallo store"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun sigFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = try {
        pm.getPackageArchiveInfo(path, flags)
    } catch (_: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        pm.getPackageInfo(pkg, sigFlags())
    } catch (_: Throwable) { null }

    /** impronte SHA-256 dei certificati (compresa la cronologia in caso di rotazione della chiave) */
    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo?): Set<String> {
        if (p == null) return emptySet()
        val list = ArrayList<Signature>()
        if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo
            if (si != null) {
                val a = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                if (a != null) list.addAll(a)
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
