package com.kairos.tv

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** HTTP minimale con HttpURLConnection: nessuna libreria, redirect anche tra http e https, gzip */
object Http {
    val UA: String by lazy {
        // solo caratteri ASCII: alcuni box hanno nomi di modello che l'intestazione HTTP rifiuta
        val model = Build.MODEL.filter { it.code in 32..126 }.ifEmpty { "Android" }
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; $model) KairosTV/${BuildConfig.VERSION_NAME}"
    }

    class Resp(val code: Int, val text: String)

    fun open(url: String, method: String = "GET", body: String? = null, connectMs: Int = 15_000, readMs: Int = 30_000): HttpURLConnection {
        var target = URL(url)
        var hops = 0
        while (true) {
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (body == null && code in 300..399 && hops < 6) {
                val loc = c.getHeaderField("Location")
                if (loc != null) {
                    c.disconnect()
                    target = URL(target, loc)
                    hops++
                    continue
                }
            }
            return c
        }
    }

    fun stream(c: HttpURLConnection): InputStream {
        val raw: InputStream = if (c.responseCode >= 400) (c.errorStream ?: ByteArrayInputStream(ByteArray(0))) else c.inputStream
        return if ("gzip".equals(c.contentEncoding, ignoreCase = true)) GZIPInputStream(raw, 32 * 1024) else raw
    }

    fun text(url: String, method: String = "GET", body: String? = null, connectMs: Int = 15_000, readMs: Int = 30_000): Resp {
        val c = open(url, method, body, connectMs, readMs)
        try {
            val t = stream(c).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Resp(c.responseCode, t)
        } finally {
            c.disconnect()
        }
    }
}

object Net {
    /** potenza del Wi-Fi (dBm) per la diagnostica del pannello; 0 se non si sa */
    @Suppress("DEPRECATION")
    fun rssi(): Int = try {
        val wm = App.I.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val r = wm.connectionInfo?.rssi ?: 0
        if (r in -120..-1) r else 0
    } catch (_: Exception) { 0 }

    @Suppress("DEPRECATION")
    fun type(): String = try {
        val cm = App.I.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetworkInfo
        when {
            n == null || !n.isConnected -> "none"
            n.type == ConnectivityManager.TYPE_WIFI -> "wifi"
            n.type == ConnectivityManager.TYPE_ETHERNET -> "eth"
            n.type == ConnectivityManager.TYPE_MOBILE -> "mobile"
            else -> ""
        }
    } catch (_: Exception) { "" }
}
