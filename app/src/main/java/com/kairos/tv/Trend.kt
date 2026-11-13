package com.kairos.tv

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Classifica dei titoli di tendenza della settimana (13 novembre 2026): la stessa che il pannello dà a Kairos Pro,
 * STB, Smarters e Ultimate (/api/player/trending). Si riscarica al più ogni 6 ore; senza rete resta l'ultima.
 * Serve alla riga «Di tendenza» della Home: i titoli della classifica che la linea ha davvero, nell'ordine della classifica.
 */
object Trend {
    class Titles(val movies: List<Pair<String, Int?>>, val series: List<Pair<String, Int?>>)

    private const val MAX_AGE = 6 * 60 * 60_000L
    private val prefs get() = App.I.getSharedPreferences("trend", 0)

    /** mai sul thread principale (lì risponde null) */
    @Synchronized
    fun get(): Titles? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val body = prefs.getString("body", "").orEmpty()
        val at = prefs.getLong("at", 0L)
        val now = System.currentTimeMillis()
        if (at > 0 && now - at < MAX_AGE) return parse(body)
        val fresh = try {
            val r = Http.text(Panel.base + "/api/player/trending", connectMs = 4_000, readMs = 8_000)
            if (r.code in 200..299 && r.text.length < 200_000) r.text.takeIf { parse(it) != null } else null
        } catch (_: Exception) { null }
        // senza risposta si riprova tra un quarto d'ora, intanto vale l'ultima classifica
        prefs.edit().putString("body", fresh ?: body).putLong("at", if (fresh != null) now else now - MAX_AGE + 15 * 60_000L).apply()
        return parse(fresh ?: body)
    }

    fun parse(body: String): Titles? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            fun titles(key: String): List<Pair<String, Int?>> {
                val array = json.optJSONArray(key) ?: JSONArray()
                return (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    item.optString("t").trim().takeIf { it.isNotEmpty() }?.let { it to item.optInt("y").takeIf { y -> y > 0 } }
                }
            }
            if (json.optJSONArray("movies") == null) null else Titles(titles("movies"), titles("series"))
        } catch (_: Exception) { null }
    }
}
