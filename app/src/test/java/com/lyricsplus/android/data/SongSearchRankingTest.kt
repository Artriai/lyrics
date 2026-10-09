package com.lyricsplus.android.data

import org.junit.Assert.*
import org.junit.Test

class SongSearchRankingTest {
    @Test fun combinesTitleAndArtistInEitherOrder() {
        val wanted = NowPlaying(track = "晴天", artist = "周杰伦")
        val other = NowPlaying(track = "夜曲", artist = "周杰伦")
        for (query in listOf("周杰伦 晴天", "晴天 周杰伦", "周杰伦晴天", "周杰伦 晴", "周杰伦晴", "晴周杰伦")) {
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
    @Test fun translatedEvaTitleRanksOriginalBeforeCoversAndFiltersUnrelatedSongs() {
        // Metadata returned by NetEase for "残酷天使"; don't hardcode query-specific ranking.
        val original = SongSearchMatch(NowPlaying(track = "残酷な天使のテーゼ", artist = "高橋洋子"),
            listOf("网易云音乐"), 100, mapOf("网易云音乐" to 0),
            aliases = listOf("TV动画《新世纪福音战士》片头曲", "残酷天使的行动纲领"))
        val cover = original.copy(track = original.track.copy(artist = "中川翔子"), popularity = 85,
            aliases = listOf("残酷天使的提纲"))
        val chineseVersion = SongSearchMatch(NowPlaying(track = "残酷天使的行动纲领（粤语版）", artist = "翻唱歌手"),
            listOf("网易云音乐"), 25)
        val unrelated = SongSearchMatch(NowPlaying(track = "天使之翼", artist = "Other"), listOf("QQ音乐"), 100)
        for (popular in listOf(true, false)) {
            val sorted = sortSongSearchMatches(listOf(unrelated, chineseVersion, cover, original), "残酷天使", popular)
            assertEquals(original, sorted.first())
            assertFalse(sorted.contains(unrelated))
        }
    }

    @Test fun aliasesWorkForAnyLanguageAndCanBeCombinedWithArtist() {
        val song = SongSearchMatch(NowPlaying(track = "君の知らない物語", artist = "supercell"),
            listOf("网易云音乐"), aliases = listOf("你不知道的故事"))
        val other = SongSearchMatch(NowPlaying(track = "Other song", artist = "supercell"), listOf("QQ音乐"))
        for (query in listOf("你不知道", "supercell 你不知道", "你不知道supercell", "supercell你不知道")) {
            assertEquals(song, sortSongSearchMatches(listOf(other, song), query, true).first())
        }
        val artist = song.copy(artistAliases = listOf("超级细胞"))
        assertTrue(songSearchScore(artist, "超级细胞 你不知道") > songSearchScore(song, "超级细胞 你不知道"))
    }

    @Test fun mergingKeepsTranslationsEvenIfSparseProviderWasFirst() {
        val track = NowPlaying(track = "Song", artist = "Singer")
        val merged = mergeSongSearchMatches(listOf(
            SongSearchMatch(track, listOf("QQ音乐")),
            SongSearchMatch(track, listOf("网易云音乐"), aliases = listOf("译名", "别名"), artistAliases = listOf("歌手译名")),
            SongSearchMatch(track, listOf("网易云音乐"), aliases = listOf("译名"))
        )).single()
        assertEquals(listOf("译名", "别名"), merged.aliases)
        assertEquals(listOf("歌手译名"), merged.artistAliases)
        assertEquals(merged, sortSongSearchMatches(listOf(merged), "译名", true).single())
    }

}
