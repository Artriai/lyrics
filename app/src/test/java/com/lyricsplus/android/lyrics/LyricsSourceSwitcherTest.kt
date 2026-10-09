package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LyricsSourceSwitcherTest {
    private fun lyrics(source: String, text: String = "正常歌词") =
        CachedLyricsResult(listOf(LyricsLine(0L, text)), source)

    @Test fun skipsFailedNextSourceInsteadOfGettingStuck() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = findNextLyricsSource("网易云音乐") { source ->
            attempted += source
            if (source == "QQ音乐") Result.failure(IllegalStateException("未找到歌词"))
            else Result.success(lyrics(source))
        }
        assertEquals(listOf("QQ音乐", "LRCLIB"), attempted)
        assertEquals("LRCLIB", (result as LyricsSourceSwitchResult.Switched).lyrics.source)
    }

    @Test fun succeedsImmediatelyWithoutRequestingUnneededSources() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = findNextLyricsSource("LRCLIB") { source ->
            attempted += source
            Result.success(lyrics(source))
        }
        assertEquals(listOf("网易云音乐"), attempted)
        assertTrue(result is LyricsSourceSwitchResult.Switched)
    }

    @Test fun distinguishesMissingLyricsFromNetworkFailure() = runBlocking {
        val missing = findNextLyricsSource("网易云音乐") { Result.failure(IllegalStateException("未找到同步歌词")) }
        val network = findNextLyricsSource("网易云音乐") { Result.failure(IOException("网络错误")) }
        assertEquals(LyricsSourceSwitchResult.Unavailable(false), missing)
        assertEquals(LyricsSourceSwitchResult.Unavailable(true), network)
    }

    @Test fun skipsInstrumentalPlaceholderButKeepsRealLyricsContainingTheWord() = runBlocking {
        val result = findNextLyricsSource("网易云音乐") { source ->
            Result.success(lyrics(source, if (source == "QQ音乐") "♪ 纯音乐 ♪" else "这是纯音乐吗，听我唱"))
        }
        assertEquals("LRCLIB", (result as LyricsSourceSwitchResult.Switched).lyrics.source)
        assertFalse(emptyList<LyricsLine>().hasUsableLyrics())
        assertFalse(listOf(LyricsLine(0L, "♪")).hasUsableLyrics())
    }

    @Test fun timeoutMovesOnToTheOtherSource() = runBlocking {
        val result = findNextLyricsSource("网易云音乐", timeoutMs = 20) { source ->
            if (source == "QQ音乐") delay(100)
            Result.success(lyrics(source))
        }
        assertEquals("LRCLIB", (result as LyricsSourceSwitchResult.Switched).lyrics.source)
    }

    @Test fun cancellationIsNeverTreatedAsAnUnavailableSource() = runBlocking {
        var attempted = 0
        try {
            findNextLyricsSource("网易云音乐") { attempted++; throw CancellationException("切换歌曲") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, attempted)
        }
    }

    @Test fun normalizesSourceLabelsAndNeverRetriesTheCurrentSource() {
        assertEquals(listOf("LRCLIB", "网易云音乐"), sourcesAfter("QQ 音乐"))
        assertEquals(lyricsSourceOrder, sourcesAfter("未加载"))
    }
}
