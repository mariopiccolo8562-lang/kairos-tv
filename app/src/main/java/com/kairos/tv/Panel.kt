package com.kairos.tv

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/* ====================================================================== */
/* Dati che arrivano dal pannello Kairos (/api/player/register)            */
/* ====================================================================== */

class Playlist(
    val id: String, val name: String, val type: String, val url: String,
    val host: String, val user: String, val pass: String, val tag: String,
) {
    val isXtream: Boolean get() = type == "xtream" && host.isNotEmpty() && user.isNotEmpty()

    /** server Xtream (anche ricavato da un link M3U get.php?username=…&password=…): guida TV e schede */
    val xt: Triple<String, String, String>? by lazy {
        if (isXtream) Triple(fixUrl(host).trimEnd('/'), user, pass)
        else try {
            val u = URL(fixUrl(url))
            val q = query(u.query ?: "")
            val us = q["username"]
            val pw = q["password"]
            if (!us.isNullOrEmpty() && !pw.isNullOrEmpty() && u.path.endsWith(".php", ignoreCase = true)) Triple(origin(u), us, pw) else null
        } catch (_: Exception) { null }
    }

    fun api(action: String, extra: String = ""): String? {
        val x = xt ?: return null
        return "${x.first}/player_api.php?username=${enc(x.second)}&password=${enc(x.third)}&action=$action$extra"
    }

    fun liveUrl(sid: String, ext: String): String? = xt?.let { "${it.first}/live/${enc(it.second)}/${enc(it.third)}/$sid.$ext" }
    fun movieUrl(sid: String, ext: String): String? = xt?.let { "${it.first}/movie/${enc(it.second)}/${enc(it.third)}/$sid.${ext.ifEmpty { "mp4" }}" }
    fun episodeUrl(id: String, ext: String): String? = xt?.let { "${it.first}/series/${enc(it.second)}/${enc(it.third)}/$id.${ext.ifEmpty { "mp4" }}" }

    fun origin(): String = try { origin(URL(fixUrl(if (isXtream) host else url))) } catch (_: Exception) { "" }

    /** firma senza il server: se il pannello cambia DNS la lista non si ricarica da capo */
    fun sig(): String = id + "|" + type + "|" + user + "|" + pass + "|" + (if (isXtream) "" else pathOf(url))

    /** indirizzo salvato con il server vecchio → server attuale della playlist (cambio DNS dal pannello) */
    fun fix(stored: String): String {
        val old = Store.plOrigin(id)
        val now = origin()
        return if (old.isNotEmpty() && now.isNotEmpty() && old != now && stored.startsWith(old)) now + stored.substring(old.length) else stored
    }

    companion object {
        fun from(o: JSONObject) = Playlist(
            o.optString("id"), o.optString("name"), o.optString("type"), o.optString("url"),
            o.optString("host"), o.optString("username"), o.optString("password"), o.optString("tag"),
        )
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun fixUrl(u: String): String {
            val t = u.trim()
            return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
        }
        fun origin(u: URL): String = u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
        fun pathOf(u: String): String = try { val x = URL(fixUrl(u)); x.path + "?" + (x.query ?: "") } catch (_: Exception) { u }
        fun query(q: String): Map<String, String> = q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else try {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (_: Exception) { null }
        }.toMap()
    }
}

class Profile(val id: String, var name: String, var avatar: String, var kids: Boolean, var pl: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("avatar", avatar).put("kids", kids).put("pl", pl)
    companion object {
        fun from(o: JSONObject) = Profile(o.optString("id"), o.optString("name"), o.optString("avatar", "a1").ifEmpty { "a1" }, o.optBoolean("kids"), o.optString("pl", "all").ifEmpty { "all" })
    }
}

class Msg(val id: String, val title: String, val body: String, val level: String)
class Conn(val name: String, val model: String, val me: Boolean)

class Account(
    val primary: Boolean, val owner: String, val maxConn: Int, val conns: List<Conn>,
    val maxProfiles: Int, val profiles: List<Profile>, val canEdit: Boolean,
    val maxStreams: Int, val plMode: String, val plDefault: String,
) {
    companion object {
        fun from(o: JSONObject?): Account? {
            if (o == null) return null
            val cs = o.optJSONArray("conns") ?: JSONArray()
            val ps = o.optJSONArray("profiles") ?: JSONArray()
            return Account(
                o.optBoolean("primary", true), o.optString("owner"), o.optInt("maxConn", 1),
                (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.map { Conn(it.optString("name"), it.optString("model"), it.optBoolean("me")) },
                o.optInt("maxProfiles", 0), (0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.map { Profile.from(it) },
                o.optBoolean("canEdit", true), o.optInt("maxStreams", 0),
                if (o.optString("plMode") == "split") "split" else "merge", o.optString("plDefault"),
            )
        }
    }
}

class State(
    val status: String, val name: String, val expires: String, val settings: JSONObject,
    val playlists: List<Playlist>, val messages: List<Msg>, val account: Account?,
    val vpnMode: String, val vpnConf: String, val perms: JSONObject?,
    /** messaggio della manutenzione (status «maintenance») */
    val maintMsg: String = "",
) {
    val active: Boolean get() = status == "active"
    fun perm(k: String): Boolean = perms?.optBoolean(k, true) ?: true
    fun setting(k: String): String = settings.optString(k, "")
    /** permessi spenti dal pannello, in forma confrontabile con quella del battito */
    fun offPerms(): String {
        val p = perms ?: return ""
        val out = ArrayList<String>()
        val it = p.keys()
        while (it.hasNext()) { val k = it.next(); if (!p.optBoolean(k, true)) out.add(k) }
        return out.sorted().joinToString(",")
    }

    companion object {
        fun parse(j: JSONObject): State {
            val pls = j.optJSONArray("playlists") ?: JSONArray()
            val ms = j.optJSONArray("messages") ?: JSONArray()
            val vpn = j.optJSONObject("vpn")
            return State(
                j.optString("status", "pending"), j.optString("name"), j.optString("expires"),
                j.optJSONObject("settings") ?: JSONObject(),
                (0 until pls.length()).mapNotNull { pls.optJSONObject(it) }.map { Playlist.from(it) },
                (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }.map { Msg(it.optString("id"), it.optString("title"), it.optString("body"), it.optString("level")) },
                Account.from(j.optJSONObject("account")),
                vpn?.optString("mode", "off") ?: "off", vpn?.optString("conf", "") ?: "",
                j.optJSONObject("perms"),
                j.optString("maintMsg"),
            )
        }
        fun locked() = State("locked", "", "", JSONObject(), emptyList(), emptyList(), null, "off", "", null)
    }
}

/* ====================================================================== */
/* API del pannello                                                       */
/* ====================================================================== */

object Panel {
    @Volatile var state: State? = null

    class Err(msg: String) : Exception(msg)

    val base: String get() = Store.server.trimEnd('/')
    val model: String get() = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(80)
    /** versione mostrata nel pannello (Utenti online): si distingue da KAIROS TV */
    val ver: String get() = ("tv " + BuildConfig.VERSION_NAME).take(20)

    private fun auth(): JSONObject = JSONObject().put("code", Store.code).put("secret", Store.secret)

    private fun post(path: String, body: JSONObject, connectMs: Int = 12_000, readMs: Int = 20_000): Pair<Int, JSONObject?> {
        if (base.isEmpty()) throw Err("Server non configurato")
        val r = try { Http.text(base + path, "POST", body.toString(), connectMs, readMs) } catch (e: Exception) { throw Err("Impossibile raggiungere il server") }
        val j = try { JSONObject(r.text) } catch (_: Exception) { null }
        return r.code to j
    }

    /** ultimo stato salvato: l'app parte subito anche senza rete */
    fun loadCached(): State? {
        state?.let { return it }
        val t = Store.stateJson
        if (t.isEmpty()) return null
        return try { State.parse(JSONObject(t)).also { state = it } } catch (_: Exception) { null }
    }

    fun register(): State {
        val (code, j) = post("/api/player/register", auth().put("model", model))
        if (j == null) throw Err("Il server ha risposto con una pagina non valida (HTTP $code)")
        if (code == 403 && j.optString("status") == "locked") return State.locked().also { state = it; Store.stateJson = "" }
        if (code == 429) throw Err("Troppe richieste: riprovo tra poco")
        if (code >= 400) throw Err(j.optString("error").ifEmpty { "Errore del server (HTTP $code)" })
        val s = State.parse(j)
        if (j.has("rc")) Remote.apply(j.optJSONObject("rc"), j.optString("rcv")) // impostazioni imposte dal pannello
        // il pannello può indicare un nuovo indirizzo per sé stesso (Impostazioni → server)
        val mv = s.setting("serverUrl").trimEnd('/')
        if (mv.startsWith("https://") && mv != base) Store.server = mv
        // nella copia salvata resta solo la modalità VPN, mai la configurazione (solo in memoria)
        val cache = JSONObject(j.toString())
        cache.remove("vpn")
        cache.put("vpn", JSONObject().put("mode", s.vpnMode))
        // manutenzione: resta l'ultimo stato buono, l'abbonamento non è cambiato
        if (s.status != "maintenance") Store.stateJson = if (s.active) cache.toString() else ""
        state = s
        return s
    }

    fun heartbeat(w: Pulse.Watch?, kickAck: Long, vpnOn: Boolean, screen: String, stats: JSONObject?): JSONObject? {
        val b = auth()
            .put("state", w?.state ?: "idle").put("title", w?.title ?: "").put("ch", w?.ch ?: "").put("chId", w?.chId ?: "")
            .put("group", w?.group ?: "").put("pl", w?.pl ?: "").put("prof", K.profile()?.name ?: "")
            .put("ver", ver).put("ui", "").put("model", model).put("screen", screen.take(20))
            .put("net", JSONObject().put("type", Net.type()).apply { if (Net.type() == "wifi") Net.rssi().takeIf { it != 0 }?.let { put("rssi", it) } })
            .put("vpn", if (vpnOn) "on" else "off")
        // diagnostica in «Online ora»: codec, decodifica, blocchi, fotogrammi persi, qualità ridotta
        if (stats != null) b.put("stats", stats)
        if (kickAck > 0) b.put("kickAck", kickAck)
        b.put("cfg", Remote.snapshot()).put("rcv", Store.rcv) // controllo remoto: impostazioni attuali e configurazione applicata
        return try { val (c, j) = post("/api/player/heartbeat", b); if (c == 200) j else null } catch (_: Exception) { null }
    }

    /** conferma al pannello di un comando del controllo remoto */
    fun cmdAck(id: Long, ok: Boolean, info: String) {
        try { post("/api/player/cmd", auth().put("id", id).put("ok", ok).put("info", info.take(200))) } catch (_: Exception) {}
    }

    /** limite di visioni contemporanee: null = si può guardare, altrimenti il messaggio */
    fun stream(state: String, title: String): String? {
        val acc = this.state?.account ?: return null
        if (acc.maxStreams <= 0) return null
        return try {
            // come KAIROS TV: risposta entro 2,5 s, altrimenti si guarda comunque
            val (c, j) = post("/api/player/stream", auth().put("state", state).put("title", title), 2_500, 2_500)
            when {
                j == null -> null
                j.has("ok") && !j.optBoolean("ok", true) -> j.optString("error").ifEmpty { "Limite di visioni contemporanee raggiunto" }
                c == 403 -> j.optString("error").ifEmpty { null }
                else -> null
            }
        } catch (_: Exception) { null } // nel dubbio non si blocca la visione
    }

    /** codice a 6 cifre per collegare un'altra TV a questo abbonamento */
    fun linkCode(): JSONObject {
        val (c, j) = post("/api/player/linkcode", auth())
        if (j == null) throw Err("Risposta non valida (HTTP $c)")
        if (c >= 400 || j.optString("pin").isEmpty()) throw Err(j.optString("error").ifEmpty { "Non è stato possibile creare il codice" })
        return j
    }

    /** collega questa TV a un abbonamento già attivo: null = fatto, altrimenti l'errore */
    fun link(pin: String): String? = try {
        val (c, j) = post("/api/player/link", auth().put("pin", pin))
        if (c in 200..299 && j != null && j.optString("error").isEmpty()) null
        else j?.optString("error")?.ifEmpty { null } ?: "Codice non valido o scaduto"
    } catch (e: Exception) { e.message ?: "Errore di rete" }

    fun saveProfiles(list: List<Profile>): String? = try {
        val a = JSONArray()
        list.forEach { a.put(it.json()) }
        val (c, j) = post("/api/player/profiles", auth().put("profiles", a))
        if (c in 200..299 && j != null && j.optBoolean("ok")) null else j?.optString("error")?.ifEmpty { null } ?: "Salvataggio non riuscito"
    } catch (e: Exception) { e.message ?: "Errore di rete" }

    /** problemi di riproduzione → pannello, «Errori applicazione» */
    fun errors(items: JSONArray) {
        if (items.length() == 0) return
        try { post("/api/player/errors", auth().put("items", items).put("ver", ver).put("model", model)) } catch (_: Exception) {}
    }

    /**
     * Versione più recente di Kairos TV: kairos-tv.json dell'ultima release del repository kairos-tv
     * (pubblicato dal workflow a ogni tag vX.Y.Z). Non dipende da KAIROS TV.
     */
    fun update(): JSONObject? = try {
        val r = Http.text(BuildConfig.UPDATE_URL, connectMs = 12_000, readMs = 15_000)
        if (r.code != 200) null else {
            val j = JSONObject(r.text)
            val v = j.optString("version").trim().removePrefix("v")
            val m = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$").find(v)
            val code = m?.let { it.groupValues[1].toInt() * 10000 + it.groupValues[2].toInt() * 100 + it.groupValues[3].toInt() } ?: j.optInt("versionCode", 0)
            val url = j.optString("url")
            JSONObject().put("apk", if (code > 0 && url.startsWith("https://")) JSONObject().put("versionCode", code).put("version", v).put("url", url) else JSONObject.NULL)
        }
    } catch (_: Exception) { null }

    /** testo «Privacy e condizioni»: quello del pannello, altrimenti la pagina pubblica /privacy */
    fun privacyText(): String {
        val t = state?.setting("privacy") ?: ""
        if (t.isNotBlank()) return t
        return try {
            val r = Http.text("$base/privacy", connectMs = 10_000, readMs = 15_000)
            if (r.code != 200) "" else {
                val body = r.text.replace(Regex("(?is)<head.*?</head>|<style.*?</style>|<script.*?</script>"), "")
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(body).toString().trim()
            }
        } catch (_: Exception) { "" }
    }
}

/* ====================================================================== */
/* Account attuale: profilo, playlist in uso                              */
/* ====================================================================== */

object K {
    fun profile(): Profile? {
        val a = Panel.state?.account ?: return null
        val id = Store.profileId
        return a.profiles.find { it.id == id }
    }

    val kids: Boolean get() = profile()?.kids == true

    fun playlists(): List<Playlist> = Panel.state?.playlists ?: emptyList()

    fun pl(id: String): Playlist? = playlists().find { it.id == id }

    /** playlist da mostrare: quella del profilo, oppure (modalità «separate») quella scelta, oppure tutte unite */
    fun active(): List<Playlist> {
        val all = playlists()
        if (all.isEmpty()) return all
        val pr = profile()
        if (pr != null && pr.pl != "all") all.find { it.id == pr.pl }?.let { return listOf(it) }
        val acc = Panel.state?.account
        if (acc != null && acc.plMode == "split") {
            val c = Store.plChoice.takeIf { ch -> all.any { it.id == ch } }
                ?: acc.plDefault.takeIf { d -> all.any { it.id == d } } ?: all[0].id
            return all.filter { it.id == c }
        }
        return all
    }

    fun activeIds(): List<String> = active().map { it.id }

    fun canSwitchPl(): Boolean {
        val s = Panel.state ?: return false
        return s.perm("plSwitch") && s.account?.plMode == "split" && playlists().size > 1 && (profile()?.pl ?: "all") == "all"
    }

    fun profilesOn(): Boolean {
        val s = Panel.state ?: return false
        val a = s.account ?: return false
        return s.perm("profiles") && a.maxProfiles > 0
    }

    /** all'avvio si chiede «Chi guarda?» se l'account ha profili e nessuno è scelto */
    fun needsProfile(): Boolean {
        if (!profilesOn()) return false
        val a = Panel.state?.account ?: return false
        return a.profiles.isNotEmpty() && a.profiles.none { it.id == Store.profileId }
    }

    fun unseenMessages(): List<Msg> {
        val seen = Store.seenMsgs
        return (Panel.state?.messages ?: emptyList()).filter { !seen.contains(it.id) }
    }
}
