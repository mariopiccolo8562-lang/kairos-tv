package com.kairos.tv

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object TitleMatch {
    private val leading = Regex("^(?:(?:\\|\\s*IT\\s*\\||IT\\s*[-|:]|(?:4K|UHD|FHD|HD)\\s*\\|)\\s*)+", RegexOption.IGNORE_CASE)
    private val year = Regex("\\s*\\(\\d{4}\\)\\s*$")
    private val quality = Regex("\\b(?:4K|UHD|FHD|HD|ITA|SUB\\s+ITA)\\b", RegexOption.IGNORE_CASE)
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    fun norm(value: String): String {
        var cleaned = value.trim().replace(leading, "")
        cleaned = quality.replace(cleaned, " ")
        cleaned = year.replace(cleaned, "")
        cleaned = Normalizer.normalize(cleaned.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return punctuation.replace(cleaned, " ").replace(spaces, " ").trim()
    }

    fun matches(localName: String, localYear: Int?, trendTitle: String, trendYear: Int?): Boolean {
        val local = norm(localName)
        if (local.isEmpty() || local != norm(trendTitle)) return false
        return localYear == null || trendYear == null || abs(localYear - trendYear) <= 1
    }
}
