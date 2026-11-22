package com.hikaru.parkingalert.sms

import java.text.Normalizer
import java.util.Locale

/**
 * Pure keyword matching logic, kept free of Android APIs so it can be unit tested.
 *
 * Matching is intentionally simple: normalise width/whitespace/case, then look for
 * each keyword as a substring. Marketing messages with an opt-out marker are skipped.
 */
object KeywordMatcher {

    private val whitespace = Regex("\\s+")
    private val zeroWidth = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val marketingMarkers = listOf(
        "退订",
        "回复td",
        "回t退订",
        "拒收",
        "unsubscribe",
    )

    data class MatchResult(
        val matchedKeywords: List<String>,
        val shouldAlert: Boolean,
        val ignoredAsMarketing: Boolean = false,
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(zeroWidth, "")
            .replace(whitespace, "")
            .lowercase(Locale.ROOT)

    fun isMarketing(text: String): Boolean {
        val normalized = normalize(text)
        return marketingMarkers.any { normalized.contains(it) }
    }

    fun match(
        body: String,
        keywords: List<String>,
        strictMode: Boolean,
        ignoreMarketing: Boolean,
    ): MatchResult {
        val cleaned = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { normalize(it) }

        if (ignoreMarketing && isMarketing(body)) {
            return MatchResult(emptyList(), shouldAlert = false, ignoredAsMarketing = true)
        }

        val normalizedBody = normalize(body)
        val hits = cleaned.filter { normalizedBody.contains(normalize(it)) }
        val shouldAlert = if (strictMode) hits.size >= 2 else hits.isNotEmpty()
        return MatchResult(hits, shouldAlert)
    }
}
