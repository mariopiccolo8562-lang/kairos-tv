package com.kairos.tv

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Impostazioni e dati locali (SharedPreferences, nessuna libreria in più) */
object Store {
    private lateinit var p: SharedPreferences
    private lateinit var pos: SharedPreferences

    fun init(c: Context) {
        p = c.getSharedPreferences("lite", Context.MODE_PRIVATE)
        pos = c.getSharedPreferences("lite_pos", Context.MODE_PRIVATE)
        if ((p.getString("code", "") ?: "").isEmpty() || (p.getString("secret", "") ?: "").isEmpty()) newIdentity()
    }

    /** codice di attivazione (stesso formato di KAIROS TV: KXAB-7Q2M) + segreto legato al dispositivo */
    private fun newIdentity() {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        fun ch() = a[r.nextInt(a.length)]
        val code = "KX" + ch() + ch() + "-" + ch() + ch() + ch() + ch()
        val b = ByteArray(32).also { r.nextBytes(it) }
        val secret = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        p.edit().putString("code", code).putString("secret", secret).apply()
    }

    val code: String get() = p.getString("code", "") ?: ""
    val secret: String get() = p.getString("secret", "") ?: ""

    private fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    /** indirizzo del pannello: quello della build, oppure quello nuovo indicato dal pannello stesso */
    var server: String
        get() = s("server").takeIf { it.startsWith("https://") } ?: BuildConfig.SERVER_URL
        set(v) = put("server", v)

    var stateJson: String
        get() = s("state")
        set(v) = put("state", v)
    var profileId: String
        get() = s("profile")
        set(v) = put("profile", v)
    /** auto | on | off */
    var liteMode: String
        get() = s("liteMode", "auto")
        set(v) = put("liteMode", v)
    /** anteprima nella lista canali: click (primo OK = riquadro, secondo OK = schermo intero) | off */
    var preview: String
        get() = "off" // Kairos TV: niente anteprima nella lista canali, OK apre subito lo schermo intero (più fluida)
        set(v) = put("previewMode", v)
    var plChoice: String
        get() = s("plChoice")
        set(v) = put("plChoice", v)
    var syncSig: String
        get() = s("syncSig")
        set(v) = put("syncSig", v)
    var syncAt: Long
        get() = p.getLong("syncAt", 0)
        set(v) = p.edit().putLong("syncAt", v).apply()
    var kickSeen: Long
        get() = p.getLong("kickSeen", 0)
        set(v) = p.edit().putLong("kickSeen", v).apply()
    var vpnUser: Boolean
        get() = p.getBoolean("vpnUser", false)
        set(v) = p.edit().putBoolean("vpnUser", v).apply()
    var privacyOk: Boolean
        get() = p.getBoolean("privacyOk", false)
        set(v) = p.edit().putBoolean("privacyOk", v).apply()
    var lastLiveCat: String
        get() = s("liveCat")
        set(v) = put("liveCat", v)
    var lastLiveId: Long
        get() = p.getLong("liveId", 0)
        set(v) = p.edit().putLong("liveId", v).apply()

    var seenMsgs: Set<String>
        get() = p.getStringSet("seenMsgs", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("seenMsgs", HashSet(v)).apply()

    /** server di una playlist M3U al momento del caricamento: se il pannello cambia DNS si sostituisce al volo */
    fun plOrigin(id: String): String = s("plo_$id")
    fun setPlOrigin(id: String, o: String) = put("plo_$id", o)

    private fun prof(): String = profileId.ifEmpty { "_" }

    /* ---------------- preferiti (per profilo) ---------------- */
    fun favs(kind: String): Set<String> = p.getStringSet("fav_${kind}_${prof()}", emptySet()) ?: emptySet()
    fun isFav(kind: String, k: String): Boolean = favs(kind).contains(k)
    fun toggleFav(kind: String, k: String): Boolean {
        val set = HashSet(favs(kind))
        val on = if (set.contains(k)) { set.remove(k); false } else { set.add(k); true }
        p.edit().putStringSet("fav_${kind}_${prof()}", set).apply()
        return on
    }

    /* ---------------- «Continua a guardare» (per profilo) ---------------- */
    fun recent(): List<JSONObject> {
        val t = s("recent_${prof()}")
        if (t.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(t)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveRecent(o: JSONObject) {
        val id = o.optString("id")
        val group = o.optString("group")
        val list = recent().filter { it.optString("id") != id && (group.isEmpty() || it.optString("group") != group) }.toMutableList()
        list.add(0, o)
        val a = JSONArray()
        list.take(20).forEach { a.put(it) }
        put("recent_${prof()}", a.toString())
    }

    fun removeRecent(id: String) {
        val a = JSONArray()
        recent().filter { it.optString("id") != id }.forEach { a.put(it) }
        put("recent_${prof()}", a.toString())
    }

    /* ---------------- posizione di visione di film ed episodi ---------------- */
    fun position(id: String): Long = pos.getLong(id, 0)
    fun duration(id: String): Long = pos.getLong("$id#d", 0)
    fun setPosition(id: String, v: Long, dur: Long = 0) {
        if (v <= 0) pos.edit().remove(id).remove("$id#d").apply()
        else pos.edit().putLong(id, v).putLong("$id#d", dur).apply()
    }
}
