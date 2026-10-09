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

    @Test fun popularRailgunRanksAbovePartialHitsAndLessPopularFullMatches() {
        val railgun = SongSearchMatch(NowPlaying(track = "only my railgun", artist = "fripSide"),
            listOf("网易云音乐"), popularity = 100, sourceRanks = mapOf("网易云音乐" to 1))
        val hands = SongSearchMatch(NowPlaying(track = "ONLY MY HANDS", artist = "Other"),
            listOf("网易云音乐"), popularity = 95, sourceRanks = mapOf("网易云音乐" to 2))
        val partial = SongSearchMatch(NowPlaying(track = "ONLY", artist = "Singer"),
            listOf("网易云音乐"), popularity = 100, sourceRanks = mapOf("网易云音乐" to 0))
        assertEquals(railgun, sortSongSearchMatches(listOf(partial, hands, railgun), "only my", true).first())
        val exact = hands.copy(track = hands.track.copy(track = "only my"))
        assertEquals(exact, sortSongSearchMatches(listOf(railgun, exact), "only my", false).first())
    }

    @Test fun mergingPreservesHeatAndBestSourceRankAcrossQueryVariants() {
        val track = NowPlaying(track = "Song", artist = "Singer")
        val merged = mergeSongSearchMatches(listOf(
            SongSearchMatch(track, listOf("网易云音乐"), 95, mapOf("网易云音乐" to 4)),
            SongSearchMatch(track, listOf("网易云音乐"), 95, mapOf("网易云音乐" to 1)),
            SongSearchMatch(track, listOf("QQ音乐"), sourceRanks = mapOf("QQ音乐" to 0))
        )).single()
        assertEquals(95, merged.popularity)
        assertEquals(mapOf("网易云音乐" to 1, "QQ音乐" to 0), merged.sourceRanks)
    }
}
