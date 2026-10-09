package com.lyricsplus.android.data

/** Sources that returned this song in search; actual lyric availability is checked on selection. */
data class SongSearchMatch(
    val track: NowPlaying,
    val sources: List<String>,
    val popularity: Int? = null,
    val sourceRanks: Map<String, Int> = emptyMap()
)

fun mergeSongSearchMatches(matches: List<SongSearchMatch>): List<SongSearchMatch> {
    val merged = mutableListOf<SongSearchMatch>()
    fun String.normalized() = lowercase().filterNot { it.isWhitespace() }
    for (match in matches) {
        val song = match.track
        val index = merged.indexOfFirst {
            val other = it.track
            other.track.normalized() == song.track.normalized() && other.artist.normalized() == song.artist.normalized() &&
                (other.album.isBlank() || song.album.isBlank() || other.album.normalized() == song.album.normalized()) &&
                (other.durationSeconds == 0 || song.durationSeconds == 0 || kotlin.math.abs(other.durationSeconds - song.durationSeconds) <= 7)
        }
        if (index < 0) merged.add(match.copy(sources = match.sources.distinct()))
        else {
            val old = merged[index]
            merged[index] = SongSearchMatch(old.track.copy(
                album = old.track.album.ifBlank { song.album },
                durationSeconds = old.track.durationSeconds.takeIf { it > 0 } ?: song.durationSeconds
            ), (old.sources + match.sources).distinct(),
                listOfNotNull(old.popularity, match.popularity).maxOrNull(),
                (old.sourceRanks.keys + match.sourceRanks.keys).associateWith { source ->
                    minOf(old.sourceRanks[source] ?: Int.MAX_VALUE, match.sourceRanks[source] ?: Int.MAX_VALUE)
                })
        }
    }
    return merged
}
