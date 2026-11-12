package com.kairos.tv

import java.net.URI

object Yt {
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun id(value: String): String? {
        val raw = value.trim()
        if (idPattern.matches(raw)) return raw
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in setOf("http", "https")) return null
        val hostOk = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
        if (!hostOk) return null
        val queryId = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "v") pair[1] else null
        }
        val pathId = Regex("^/(?:embed|shorts)/([^/?#]+)$").find(uri.path.orEmpty())?.groupValues?.get(1)
            ?: if (host == "youtu.be") uri.path.orEmpty().trim('/').takeIf { '/' !in it } else null
        return (queryId ?: pathId)?.takeIf(idPattern::matches)
    }
}
