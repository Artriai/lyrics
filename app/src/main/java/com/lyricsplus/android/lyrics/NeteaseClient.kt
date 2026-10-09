package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.SongSearchMatch
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

class NeteaseClient {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val searchResult = searchSongId(track) ?: error("网易云音乐未找到歌曲")
            val lyricJson = fetchLyrics(searchResult.first) ?: error("网易云音乐未找到歌词")
            if (lyricJson.optBoolean("nolyric", false)) {
                return@runCatching LyricsSearchResult(listOf(LyricsLine(0L, "♪ 纯音乐 ♪")), searchResult.second)
            }
            
            val yrcString = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val lrcString = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()

            val synced = parseNeteaseYrc(yrcString)
                .ifEmpty { parseNeteaseLrc(lrcString) }
                .ifEmpty { error("网易云音乐同步歌词为空") }

            val translation = parseNeteaseLrc(lyricJson.optJSONObject("tlyric")?.optString("lyric").orEmpty())

            val merged = mergeTranslation(synced, translation)
            LyricsSearchResult(merged, searchResult.second)
        }
    }

    suspend fun searchSongs(query: String): List<SongSearchMatch> = withContext(Dispatchers.IO) {
        val response = request("https://music.163.com/api/cloudsearch/pc?csrf_token=&type=1&offset=0&limit=30&s=" + query.urlEncode())
        check(response.code in 200..299) { "网易云音乐搜索失败 (${response.code})" }
        val songs = JSONObject(response.body).optJSONObject("result")?.optJSONArray("songs")
            ?: return@withContext emptyList()
        (0 until songs.length()).map { songs.getJSONObject(it) }.mapIndexed { rank, song ->
            SongSearchMatch(NowPlaying(track = song.optString("name"), artist = song.artistNames(),
                album = (song.optJSONObject("al") ?: song.optJSONObject("album"))?.optString("name").orEmpty(),
                durationSeconds = ((song.optLong("dt", song.optLong("duration")) + 500) / 1000).toInt()),
                listOf("网易云音乐"), popularity = if (song.has("pop") && !song.isNull("pop")) song.optDouble("pop").toInt().coerceIn(0, 100) else null,
                sourceRanks = mapOf("网易云音乐" to rank),
                aliases = song.searchAliases(),
                artistAliases = (song.optJSONArray("ar") ?: song.optJSONArray("artists"))?.let { artists ->
                    (0 until artists.length()).flatMap { artists.optJSONObject(it)?.searchAliases().orEmpty() }
                }.orEmpty())
        }.filter { it.track.track.isNotBlank() }
    }

    // Platforms often return the original title with the user's translated title in these fields.
    private fun JSONObject.searchAliases(): List<String> = listOf("alia", "alias", "tns", "transNames").flatMap { key ->
        optJSONArray(key)?.let { values -> (0 until values.length()).map { values.optString(it) } }.orEmpty()
    }.filter { it.isNotBlank() }.distinct()

    private fun searchSongId(track: NowPlaying): Pair<Long, Int>? {
        val title = cleanTitle(track.track)
        val queries = listOf("$title ${track.artist}".trim(), title).filter { it.isNotBlank() }.distinct()
        var networkFailure: java.io.IOException? = null
        for (query in queries) {
            val response = try {
                request("https://music.163.com/api/cloudsearch/pc?csrf_token=&type=1&offset=0&limit=30&s=" + query.urlEncode())
            } catch (failure: java.io.IOException) {
                networkFailure = failure
                continue
            }
            if (response.code !in 200..299) {
                networkFailure = java.io.IOException("网易云音乐搜索失败 (${response.code})")
                continue
            }
            val songs = JSONObject(response.body).optJSONObject("result")?.optJSONArray("songs") ?: continue
            val normalizedTitle = normalizeForCompare(title)
            val normalizedArtist = normalizeForCompare(track.artist)
            val normalizedAlbum = normalizeForCompare(track.album)
            val best = (0 until songs.length()).map { songs.getJSONObject(it) }.mapNotNull { song ->
                val name = normalizeForCompare(cleanTitle(song.optString("name")))
                val album = normalizeForCompare((song.optJSONObject("al") ?: song.optJSONObject("album"))?.optString("name").orEmpty())
                val artists = normalizeForCompare(song.artistNames())
                val durationMs = song.optLong("dt", song.optLong("duration", 0L))
                val durationDiff = if (track.durationSeconds > 0) abs(track.durationSeconds * 1000L - durationMs) else Long.MAX_VALUE
                val titleScore = when {
                    name.isBlank() || normalizedTitle.isBlank() -> 0
                    name == normalizedTitle -> 50
                    name.contains(normalizedTitle) || normalizedTitle.contains(name) -> 20
                    else -> 0
                }
                if (titleScore == 0 || song.optLong("id") <= 0) return@mapNotNull null
                var score = titleScore
                if (artists.isNotBlank() && normalizedArtist.isNotBlank()) {
                    if (artists == normalizedArtist) score += 40
                    else if (artists.contains(normalizedArtist) || normalizedArtist.contains(artists)) score += 25
                }
                if (album.isNotBlank() && album == normalizedAlbum) score += 20
                if (durationDiff < 3_000) score += 30 else if (durationDiff < 10_000) score += 10
                song.optLong("id") to score
            }.maxByOrNull { it.second }
            if (best != null) return best
        }
        networkFailure?.let { throw it }
        return null
    }

    private fun fetchLyrics(songId: Long): JSONObject? {
        val urls = listOf(
            "https://music.163.com/api/song/lyric/v1?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1",
            "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1"
        )

        return urls.firstNotNullOfOrNull { url ->
            val response = request(url)
            if (response.code !in 200..299) return@firstNotNullOfOrNull null
            val json = JSONObject(response.body)
            if (
                json.optJSONObject("lrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optJSONObject("yrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optJSONObject("tlyric")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optBoolean("nolyric", false)
            ) {
                json
            } else {
                null
            }
        }
    }

    private fun parseNeteaseYrc(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = LrcParser.parse(raw)
        return parsed.filterNot { line ->
            val cleanText = line.text.replace(yrcSyllableRegex, "").trim()
            cleanText.isBlank() || containsCredits(cleanText) || cleanText == "纯音乐, 请欣赏"
        }
    }

    private fun parseNeteaseLrc(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val match = timestampRegex.find(line.trim()) ?: return@mapNotNull null
                val text = line.replace(timestampRegex, "").trim()
                if (text.isBlank() || containsCredits(text) || text == "纯音乐, 请欣赏") return@mapNotNull null

                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val millis = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricsLine(minutes * 60_000 + seconds * 1_000 + millis, text)
            }
            .sortedBy { it.startTimeMs }
            .toList()
    }

    private fun mergeTranslation(base: List<LyricsLine>, translation: List<LyricsLine>): List<LyricsLine> {
        if (translation.isEmpty() || base.looksChinese()) return base

        return TimedLyricsMerger.mergeTranslation(
            base = base,
            translation = translation,
            lineFilter = { line -> comparableText(line.text).isNotBlank() }
        )
    }

    private fun request(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 LyricsPlusAndroid/0.1")
            .header("Referer", "https://music.163.com/")
            .build()

        return HttpClient.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw java.io.IOException("歌词服务请求失败 (${response.code})")
            }
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun JSONObject.artistNames(): String {
        val artists = optJSONArray("ar") ?: optJSONArray("artists") ?: return ""
        return (0 until artists.length())
            .joinToString(" ") { artists.getJSONObject(it).optString("name") }
    }

    private fun cleanTitle(value: String): String =
        value.replace(extraInfoRegex, "").replace(featRegex, "").trim()

    private fun normalizeForCompare(value: String): String =
        value.lowercase().replace(whitespaceRegex, "")

    private fun comparableText(value: String): String =
        value.replace(spacingRegex, "").replace(punctuationRegex, "")

    private fun List<LyricsLine>.looksChinese(): Boolean {
        val sample = joinToString("") { it.text }.take(200)
        if (sample.isBlank()) return false
        val han = sample.count { it in '\u4E00'..'\u9FFF' }
        val kana = sample.count { it in '\u3040'..'\u30FF' }
        return han > 0 && kana == 0
    }

    private fun containsCredits(text: String): Boolean = creditRegex.containsMatchIn(text)

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private companion object {
        val timestampRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val yrcSyllableRegex = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
        val extraInfoRegex = Regex("\\s*[\\[(（].*?(?:remaster|remastered|live|mono|stereo|version|edit|mix|feat\\.?|with).*?[\\]）)]\\s*", RegexOption.IGNORE_CASE)
        val featRegex = Regex("\\s+(feat\\.?|ft\\.?|with)\\s+.+$", RegexOption.IGNORE_CASE)
        val whitespaceRegex = Regex("\\s+")
        val spacingRegex = Regex("[　\\s]")
        val punctuationRegex = Regex("""[!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~？！，。、《》【】「」]""")
        val creditRegex = Regex(
            "^(\\s?作?\\s*词|\\s?作?\\s*曲|\\s?编\\s*曲?|\\s?监\\s*制?|.*编写|.*和音|.*和声|.*合声|.*提琴|.*录|.*工程|.*工作室|.*设计|.*剪辑|.*制作|.*发行|.*出品|.*后期|.*混音|.*缩混|原唱|翻唱|题字|文案|海报|古筝|二胡|钢琴|吉他|贝斯|笛子|鼓|弦乐|lrc|publish|vocal|guitar|program|produce|write|mix).*(:|：)",
            RegexOption.IGNORE_CASE
        )
    }
}
