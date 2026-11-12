package com.kairos.tv

import org.json.JSONObject
import java.net.URL

object Portals {
    private const val BAD_MS = 30 * 60_000L
    private const val PROBE_MS = 6 * 60 * 60_000L

    private fun origin(value: String): String = try {
        Playlist.origin(URL(Playlist.fixUrl(value)))
    } catch (_: Exception) { "" }

    private fun hostKey(value: String): String = value.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/').lowercase()

    fun cands(p: Playlist): List<String> {
        val main = p.host.ifBlank { p.url }
        return (listOf(main) + p.alts).mapNotNull { origin(it).takeIf(String::isNotEmpty) }.distinctBy(::hostKey)
    }

    fun host(p: Playlist): String = selectHost(p.mode, cands(p), Store.code, Store.portalChoice(p.id), ::isBad)

    internal fun selectHost(mode: String, candidates: List<String>, code: String, chosen: String, bad: (String) -> Boolean): String {
        val main = candidates.firstOrNull().orEmpty()
        fun saved() = chosen.takeIf { it.isNotEmpty() && candidates.any { candidate -> candidate == it && !bad(candidate) } }
        return when (mode) {
            "main" -> main
            "alt" -> candidates.getOrElse(1) { main }
            "rot" -> saved() ?: PortalRotation.firstHealthy(candidates, code, bad) ?: main
            else -> saved() ?: candidates.firstOrNull { !bad(it) } ?: main
        }
    }

    fun markBad(origin: String, ms: Long) {
        val normalized = origin(origin)
        if (normalized.isNotEmpty()) Store.setPortalBadUntil(normalized, System.currentTimeMillis() + ms)
    }

    fun isBad(origin: String): Boolean {
        val normalized = origin(origin)
        return normalized.isNotEmpty() && Store.portalBadUntil(normalized) > System.currentTimeMillis()
    }

    fun next(p: Playlist): Pair<String, String>? {
        if (p.mode != "auto" && p.mode != "rot") return null
        val candidates = if (p.mode == "rot") PortalRotation.order(cands(p), Store.code) else cands(p)
        if (candidates.size < 2) return null
        val old = host(p)
        val current = candidates.indexOfFirst { hostKey(it) == hostKey(old) }
        if (current < 0) return null
        markBad(old, BAD_MS)
        val next = (1..candidates.size).asSequence()
            .map { candidates[(current + it) % candidates.size] }
            .firstOrNull { !isBad(it) } ?: return null
        Store.setPortalChoice(p.id, next)
        return old to next
    }

    fun name(p: Playlist, origin: String): String {
        val i = cands(p).indexOfFirst { hostKey(it) == hostKey(origin) }
        return when {
            i == 0 -> p.mainName
            i > 0 -> p.altNames.getOrNull(i - 1).orEmpty()
            else -> ""
        }
    }

    fun probe(pls: List<Playlist>) {
        if (App.top is PlayerActivity) return
        for (p in pls) {
            if (App.top is PlayerActivity || (p.mode != "auto" && p.mode != "rot")) continue
            val candidates = cands(p)
            if (candidates.size < 2) continue
            val now = System.currentTimeMillis()
            if (now - Store.portalProbeAt(p.id) < PROBE_MS) continue
            val credentials = p.xt ?: continue
            var best = ""
            var fastest = Long.MAX_VALUE
            for (candidate in candidates) {
                if (App.top is PlayerActivity) break
                if (isBad(candidate)) continue
                val started = System.nanoTime()
                try {
                    val url = "$candidate/player_api.php?username=${Playlist.enc(credentials.second)}&password=${Playlist.enc(credentials.third)}"
                    val response = Http.text(url, connectMs = 5_000, readMs = 8_000)
                    if (response.code != 200) continue
                    val info = JSONObject(response.text).optJSONObject("user_info") ?: continue
                    val auth = info.opt("auth") ?: continue
                    if (auth == false || auth.toString() == "0" || auth.toString().equals("false", true)) continue
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    if (elapsed < fastest) { fastest = elapsed; best = candidate }
                } catch (_: Exception) {}
            }
            Store.setPortalProbeAt(p.id, System.currentTimeMillis())
            if (best.isNotEmpty()) Store.setPortalChoice(p.id, best)
        }
    }
}

object PortalRotation {
    fun order(candidates: List<String>, deviceCode: String): List<String> {
        if (candidates.size < 2) return candidates
        var hash = 0x811C9DC5.toInt()
        deviceCode.forEach { hash = (hash xor it.code) * 0x01000193 }
        val offset = Math.floorMod(hash, candidates.size)
        return candidates.drop(offset) + candidates.take(offset)
    }

    fun firstHealthy(candidates: List<String>, deviceCode: String, bad: (String) -> Boolean): String? =
        order(candidates, deviceCode).firstOrNull { !bad(it) }
}
