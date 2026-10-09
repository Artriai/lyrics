package com.lyricsplus.android.data

import java.text.Normalizer
import java.util.Locale

private fun String.searchNormalized(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Relevance includes platform aliases/translations; popularity only breaks comparable matches. */
fun sortSongSearchMatches(matches: List<SongSearchMatch>, query: String, popular: Boolean): List<SongSearchMatch> {
    val whole = query.searchNormalized()
    if (whole.isBlank()) return matches
    val parts = query.searchParts()
    data class Ranked(val match: SongSearchMatch, val score: Int, val tier: Int, val heat: Double)
    val ranked = matches.map { match ->
        val fields = match.searchFields()
        val score = searchScore(fields, query)
        val tier = when {
            fields.any { it.contains(whole) } -> 3
            parts.isNotEmpty() && parts.all { part -> fields.any { it.contains(part) } } -> 2
            else -> 1
        }
        val heat = (match.popularity ?: 0).toDouble() +
            match.sourceRanks.filterKeys { it != "LRCLIB" }.values.sumOf { 15.0 / (it.coerceAtLeast(0) + 1) } +
            (match.sources.size - 1).coerceAtLeast(0) * 2.0
        Ranked(match, score, tier, heat)
    }.filter { it.score > 0 }
    val order = if (popular) compareByDescending<Ranked> { it.tier }.thenByDescending { it.heat }.thenByDescending { it.score }
        else compareByDescending<Ranked> { it.score }.thenByDescending { it.heat }
    return ranked.sortedWith(order).map { it.match }
}

private fun String.searchParts(): List<String> = trim().split(Regex("[\\s·/，,]+"))
    .map { it.searchNormalized() }.filter { it.isNotBlank() }

private fun SongSearchMatch.searchFields(): List<String> {
    val titles = (listOf(track.track) + aliases).map { it.searchNormalized() }.filter { it.isNotBlank() }.distinct()
    val artists = (listOf(track.artist) + artistAliases).map { it.searchNormalized() }.filter { it.isNotBlank() }.distinct()
    return (titles + artists + titles.flatMap { title -> artists.flatMap { artist -> listOf(title + artist, artist + title) } }).distinct()
}

/** Search the full phrase plus either side, then rank against title AND artist. */
fun songSearchQueries(query: String): List<String> {
    val parts = query.trim().split(Regex("[\\s·/，,]+" )).filter { it.isNotBlank() }
    return (listOf(query.trim()) + if (parts.size > 1) listOf(parts.drop(1).joinToString(" "), parts.dropLast(1).joinToString(" ")) else emptyList())
        .filter { it.isNotBlank() }.distinct().take(3)
}

fun songSearchScore(track: NowPlaying, query: String): Int = songSearchScore(SongSearchMatch(track, emptyList()), query)

fun songSearchScore(match: SongSearchMatch, query: String): Int = searchScore(match.searchFields(), query)

private fun searchScore(fields: List<String>, query: String): Int {
    val whole = query.searchNormalized()
    if (whole.isBlank() || fields.isEmpty()) return 0
    val parts = query.searchParts()
    var score = fields.maxOf { if (it == whole) 160 else if (it.contains(whole)) 110 else 0 }
    score += parts.sumOf { token ->
        fields.maxOf { field ->
            when {
                field == token -> 60
                field.contains(token) -> 45
                token.length >= 3 && approximateSubstring(field, token) -> 25
                else -> 0
            }
        }
    }
    if (parts.size > 1 && parts.all { token -> fields.any { it.contains(token) || (token.length >= 3 && approximateSubstring(it, token)) } }) score += 120
    return score
}

// One typo in a word/substring remains a fuzzy match; no exact-title filtering.
private fun approximateSubstring(text: String, token: String): Boolean {
    var previous = IntArray(token.length + 1) { it }
    for (character in text) {
        val current = IntArray(token.length + 1)
        for (j in 1..token.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (character == token[j - 1]) 0 else 1)
        }
        if (current[token.length] <= 1) return true
        previous = current
    }
    return false
}
