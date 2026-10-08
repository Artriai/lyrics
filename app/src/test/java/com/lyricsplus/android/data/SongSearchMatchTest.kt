package com.lyricsplus.android.data

import org.junit.Assert.*
import org.junit.Test

class SongSearchMatchTest {
    @Test fun mergesMatchingSourcesAndPreservesBestMetadata() {
        val full = NowPlaying(track = "Song", artist = "Singer", album = "Album", durationSeconds = 200)
        val sparse = NowPlaying(track = "song", artist = "singer")
        val merged = mergeSongSearchMatches(listOf(
            SongSearchMatch(sparse, listOf("QQ音乐")),
            SongSearchMatch(full, listOf("网易云音乐")),
            SongSearchMatch(full, listOf("网易云音乐", "LRCLIB"))
        ))
        assertEquals(1, merged.size)
        assertEquals(listOf("QQ音乐", "网易云音乐", "LRCLIB"), merged.single().sources)
        assertEquals("Album", merged.single().track.album)
        assertEquals(200, merged.single().track.durationSeconds)
    }

    @Test fun differentRecordingsDoNotShareSourceLabels() {
        val a = NowPlaying(track = "Song", artist = "Singer", album = "Live", durationSeconds = 250)
        val b = a.copy(album = "Studio", durationSeconds = 200)
        val c = a.copy(durationSeconds = 280)
        val matches = listOf(a, b, c).map { SongSearchMatch(it, listOf("网易云音乐")) }
        assertEquals(3, mergeSongSearchMatches(matches).size)
    }
}
