package com.lyricsplus.android.data

import org.junit.Assert.*
import org.junit.Test

class SongSearchRankingTest {
    @Test fun combinesTitleAndArtistInEitherOrder() {
        val wanted = NowPlaying(track = "晴天", artist = "周杰伦")
        val other = NowPlaying(track = "夜曲", artist = "周杰伦")
        for (query in listOf("周杰伦 晴天", "晴天 周杰伦", "周杰伦晴天", "周杰伦 晴")) {
            assertTrue(query, songSearchScore(wanted, query) > songSearchScore(other, query))
        }
        assertEquals(listOf("周杰伦 晴天", "晴天", "周杰伦"), songSearchQueries("周杰伦 晴天"))
    }

    @Test fun partialWordsAndOneTypoStillRankRelevantSongFirst() {
        val wanted = NowPlaying(track = "only my railgun", artist = "fripSide")
        val other = NowPlaying(track = "other song", artist = "fripSide")
        assertTrue(songSearchScore(wanted, "fripside railgn") > songSearchScore(other, "fripside railgn"))
        assertTrue(songSearchScore(wanted, "railg") > songSearchScore(other, "railg"))
    }
}
