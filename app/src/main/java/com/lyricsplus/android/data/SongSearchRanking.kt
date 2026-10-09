package com.lyricsplus.android.data

import java.text.Normalizer
import java.util.Locale

private fun String.searchNormalized(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Popularity never promotes a partial-token hit over a complete query match. */
fun sortSongSearchMatches(matches: List<SongSearchMatch>, query: String, popular: Boolean): List<SongSearchMatch> {
    if (!popular) return matches.sortedByDescending { songSearchScore(it.track, query) }
    val whole = query.searchNormalized()
    val parts = query.trim().split(Regex("[\\s·/，,]+" )).map { it.searchNormalized() }.filter { it.isNotBlank() }
    fun tier(track: NowPlaying): Int {
        val title = track.track.searchNormalized()
        val artist = track.artist.searchNormalized()
        val fields = listOf(title, artist, title + artist, artist + title)
        if (whole.isNotBlank() && fields.any { it.contains(whole) }) return 3
        if (parts.isNotEmpty() && parts.all { part -> fields.any { it.contains(part) } }) return 2
        return 1
    }
    fun heat(match: SongSearchMatch): Double = (match.popularity ?: 0).toDouble() +
        match.sourceRanks.filterKeys { it != "LRCLIB" }.values.sumOf { 15.0 / (it.coerceAtLeast(0) + 1) } +
        (match.sources.size - 1).coerceAtLeast(0) * 2.0
    return matches.sortedWith(compareByDescending<SongSearchMatch> { tier(it.track) }
        .thenByDescending { heat(it) }.thenByDescending { songSearchScore(it.track, query) })
}

/** Search the full phrase plus either side, then rank against title AND artist. */
fun songSearchQueries(query: String): List<String> {
    val parts = query.trim().split(Regex("[\\s·/，,]+" )).filter { it.isNotBlank() }
    return (listOf(query.trim()) + if (parts.size > 1) listOf(parts.drop(1).joinToString(" "), parts.dropLast(1).joinToString(" ")) else emptyList())
        .filter { it.isNotBlank() }.distinct().take(3)
}

fun songSearchScore(track: NowPlaying, query: String): Int {
    val title = track.track.searchNormalized()
    val artist = track.artist.searchNormalized()
    val whole = query.searchNormalized()
    if (whole.isBlank()) return 0
    val parts = query.trim().split(Regex("[\\s·/，,]+" )).map { it.searchNormalized() }.filter { it.isNotBlank() }
    val fields = listOf(title, artist, title + artist, artist + title)
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
