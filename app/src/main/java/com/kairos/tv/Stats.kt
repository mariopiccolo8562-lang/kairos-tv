package com.kairos.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject

/** Dati della visione in corso: l'interfaccia li manda al pannello ("Online ora"). Scritti dal lettore, letti dal ponte JS. */
object PlayStats {
    @Volatile var active = false
    @Volatile var engine = ""
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var fps = 0f
    @Volatile var bitrate = 0
    @Volatile var buffers = 0
    @Volatile var codec = ""
    @Volatile var decoder = ""
    @Volatile var dropped = 0L
    @Volatile var lastCodecError = ""
    /** ultimo recupero automatico (testo breve per il pannello) e qualità ridotta in corso */
    @Volatile var recovery = ""
    @Volatile var reduced = ""
    /** istanti dei blocchi (caricamento durante la visione) dell'ultima ora */
    private val stalls = java.util.ArrayDeque<Long>()
    /** problemi da mandare al pannello («Errori applicazione»): letti e svuotati dall'interfaccia */
    private val events = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    fun start(engineName: String) {
        active = true; engine = engineName; width = 0; height = 0; fps = 0f; bitrate = 0; buffers = 0
        codec = ""; decoder = ""; dropped = 0L; lastCodecError = ""
    }

    fun stop() { active = false; recovery = ""; reduced = "" }

    fun stall() { synchronized(stalls) { val t = System.currentTimeMillis(); stalls.addLast(t); while (stalls.isNotEmpty() && t - stalls.first > 3_600_000L) stalls.removeFirst() } }
    fun stallsLastHour(): Int = synchronized(stalls) { val t = System.currentTimeMillis(); while (stalls.isNotEmpty() && t - stalls.first > 3_600_000L) stalls.removeFirst(); stalls.size }

    fun event(o: JSONObject) { events.add(o); while (events.size > 30) events.poll() }
    fun drainEvents(): String {
        val a = org.json.JSONArray()
        while (true) { val o = events.poll() ?: break; a.put(o) }
        return if (a.length() == 0) "" else a.toString()
    }

    fun json(): String {
        if (!active) return ""
        return try {
            JSONObject().apply {
                put("res", if (width > 0 && height > 0) "${width}x$height" else if (height > 0) "${height}p" else "")
                put("fps", fps.toDouble())
                put("bitrate", bitrate)
                put("buffers", buffers)
                put("engine", engine)
                put("codec", codec)
                put("dec", if (decoder.isEmpty()) "" else if (isSoftware(decoder)) "sw" else "hw")
                put("dropped", dropped)
                put("stallsH", stallsLastHour())
                if (recovery.isNotEmpty()) put("rec", recovery)
                if (reduced.isNotEmpty()) put("reduced", reduced)
            }.toString()
        } catch (t: Throwable) { "" }
    }
}

/** Tipo di rete (wifi | eth | mobile | none) e, sul Wi-Fi, potenza del segnale e velocità del collegamento. */
object NetInfo {
    fun json(ctx: Context): String = try {
        val o = JSONObject()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var type = "none"
        if (Build.VERSION.SDK_INT >= 23) {
            val n = cm.activeNetwork
            val c = if (n != null) cm.getNetworkCapabilities(n) else null
            if (c != null) {
                type = when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> ""
                }
                o.put("down", c.linkDownstreamBandwidthKbps)
            }
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (ni != null && ni.isConnected) type = when (ni.type) {
                ConnectivityManager.TYPE_ETHERNET -> "eth"
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> ""
            }
        }
        if (type == "wifi") {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wi = wm.connectionInfo
                if (wi != null && wi.rssi in -126..-1) { o.put("rssi", wi.rssi); o.put("link", wi.linkSpeed) }
            } catch (_: Throwable) { }
        }
        o.put("type", type)
        o.toString()
    } catch (t: Throwable) { "" }
}

fun isSoftware(decoder: String): Boolean = decoder.startsWith("OMX.google") || decoder.startsWith("c2.android") || decoder.contains(".sw.", ignoreCase = true) || decoder.contains("ffmpeg", ignoreCase = true)

/** nome leggibile del codec video: H.264, HEVC, MPEG-2… */
fun codecName(mime: String?, codecs: String?): String {
    val m = (mime ?: "").lowercase()
    val c = (codecs ?: "").lowercase()
    return when {
        m.contains("hevc") || m.contains("h265") || c.startsWith("hvc") || c.startsWith("hev") -> "HEVC"
        m.contains("avc") || m.contains("h264") || c.startsWith("avc") -> "H.264"
        m.contains("mpeg2") -> "MPEG-2"
        m.contains("av01") || m.contains("av1") || c.startsWith("av01") -> "AV1"
        m.contains("vp9") || c.startsWith("vp09") -> "VP9"
        m.contains("mp4v") || m.contains("mpeg4") -> "MPEG-4"
        m.contains("dolby") || c.startsWith("dvh") -> "Dolby Vision"
        m.isNotEmpty() -> m.substringAfter('/').uppercase()
        else -> ""
    }
}
