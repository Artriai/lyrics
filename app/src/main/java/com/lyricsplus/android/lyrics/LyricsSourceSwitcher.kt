package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal val lyricsSourceOrder = listOf("网易云音乐", "QQ音乐", "LRCLIB")

internal fun sourcesAfter(current: String): List<String> {
    val index = lyricsSourceOrder.indexOf(current.filterNot(Char::isWhitespace))
    return if (index < 0) lyricsSourceOrder else
        (1 until lyricsSourceOrder.size).map { lyricsSourceOrder[(index + it) % lyricsSourceOrder.size] }
}

private val inlineTiming = Regex("<\\d{1,2}:\\d{2}[.:]\\d{1,3}>|\\(\\d+,\\d+(?:,\\d+)?\\)")
private val instrumentalLabels = setOf("纯音乐", "纯音乐 / 无歌词", "纯音乐, 请欣赏", "纯音乐，请欣赏")

internal fun List<LyricsLine>.hasUsableLyrics(): Boolean = any { line ->
    val text = line.text.replace(inlineTiming, "").trim().trim('♪', '♫').trim()
    text.isNotEmpty() && text !in instrumentalLabels
}

internal sealed class LyricsSourceSwitchResult {
    data class Switched(val lyrics: CachedLyricsResult) : LyricsSourceSwitchResult()
    data class Unavailable(val networkFailure: Boolean) : LyricsSourceSwitchResult()
}

/** Try each alternative once. Cancellation belongs to the caller, never to a failed source. */
internal suspend fun findNextLyricsSource(
    current: String,
    timeoutMs: Long = 20_000L,
    fetch: suspend (String) -> Result<CachedLyricsResult>
): LyricsSourceSwitchResult {
    var networkFailure = false
    for (source in sourcesAfter(current)) {
        coroutineContext.ensureActive()
        try {
            val result = withTimeoutOrNull(timeoutMs) { fetch(source) }
                ?: throw IOException("歌词源请求超时")
            coroutineContext.ensureActive()
            val lyrics = result.getOrThrow()
            if (lyrics.lyrics.hasUsableLyrics()) {
                return LyricsSourceSwitchResult.Switched(lyrics.copy(source = source))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            networkFailure = networkFailure || generateSequence<Throwable>(failure) { it.cause }
                .any { it is IOException }
        }
    }
    return LyricsSourceSwitchResult.Unavailable(networkFailure)
}
