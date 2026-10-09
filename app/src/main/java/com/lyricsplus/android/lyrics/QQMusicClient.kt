package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.SongSearchMatch
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class QQMusicClient {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val searchResult = searchSongMid(track) ?: error("QQ 音乐未找到歌曲")

            // Prefer QQ Music's first-party API. It returns encrypted QRC data,
            // which is decrypted locally without relying on a third-party service.
            val officialResult = runCatching {
                fetchOfficialLyrics(searchResult)
            }
            if (officialResult.isSuccess) {
                return@runCatching officialResult.getOrThrow()
            }

            // The legacy endpoint remains the last fallback for clients or songs
            // that do not expose PlayLyricInfo/QRC data.
            val lyricData = fetchLegacyLyrics(searchResult.mid)
                ?: error("QQ 音乐未找到歌词 (新版和旧版接口均失败)")
            
            val lyricBase64 = lyricData.optString("lyric").orEmpty()
            val transBase64 = lyricData.optString("trans").orEmpty()
            
            if (lyricBase64.isBlank()) error("QQ 音乐歌词为空")

            val rawLyricEncoded = String(android.util.Base64.decode(lyricBase64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val rawTransEncoded = if (transBase64.isNotBlank()) {
                String(android.util.Base64.decode(transBase64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else ""

            val rawLyric = unescapeHtml(rawLyricEncoded)
            val rawTrans = unescapeHtml(rawTransEncoded)

            val synced = LrcParser.parse(rawLyric).ifEmpty { error("QQ 音乐同步歌词为空") }
            val translation = LrcParser.parse(rawTrans).filter { line ->
                val clean = line.text.trim()
                !(clean.all { it == '/' } && clean.isNotEmpty())
            }

            val merged = mergeTranslation(synced, translation)
            LyricsSearchResult(merged, searchResult.score)
        }
    }

    private fun fetchOfficialLyrics(searchResult: QQMusicSearchResult): LyricsSearchResult {
        val data = fetchOfficialLyricData(searchResult.mid)
            ?: error("QQ 音乐新版歌词接口无数据")
        val encrypted = data.optInt("crypt") == 1

        fun decodeField(name: String): String {
            val value = data.optString(name).orEmpty()
            if (value.isBlank()) return ""

            val decrypted = if (encrypted) {
                QqMusicQrcDecryptor.decrypt(value)
            } else {
                value
            }
            return QrcPayloadParser.extract(decrypted)
        }

        val rawLyric = decodeField("lyric")
        if (rawLyric.isBlank()) error("QQ 音乐新版歌词为空")

        val synced = LrcParser.parse(rawLyric)
            .ifEmpty { error("QQ 音乐新版歌词解析为空") }
        val translation = LrcParser.parse(runCatching { decodeField("trans") }.getOrDefault("")).filter(::isUsefulAuxiliaryLine)
        val reading = LrcParser.parse(runCatching { decodeField("roma") }.getOrDefault(""))

        val mergedTranslation = mergeTranslation(synced, translation)
        val mergedReading = mergeReading(mergedTranslation, reading)
        return LyricsSearchResult(mergedReading, searchResult.score)
    }

    private fun isUsefulAuxiliaryLine(line: LyricsLine): Boolean {
        val clean = line.text.trim()
        return !(clean.all { it == '/' } && clean.isNotEmpty())
    }

    private fun unescapeHtml(text: String): String {
        if (text.isBlank()) return text
        val temp = text.replace("\n", "__NEWLINE_PLACEHOLDER__")
        val unescaped = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(temp, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(temp).toString()
        }
        return unescaped.replace("__NEWLINE_PLACEHOLDER__", "\n")
    }

    suspend fun searchSongs(query: String): List<SongSearchMatch> = withContext(Dispatchers.IO) {
        val url = QQ_MUSIC_SEARCH_API.toHttpUrl().newBuilder()
            .addQueryParameter("key", query).addQueryParameter("format", "json")
            .addQueryParameter("g_tk", "5381").addQueryParameter("uin", "0").build()
        val response = requestGet(url.toString())
        check(response.code in 200..299) { "QQ 音乐搜索失败 (${response.code})" }
        val json = JSONObject(response.body)
        check(json.optInt("code", -1) == 0) { "QQ 音乐搜索暂不可用" }
        val songs = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist")
            ?: return@withContext emptyList()
        (0 until songs.length()).map { songs.getJSONObject(it) }.mapIndexed { rank, song ->
            SongSearchMatch(NowPlaying(track = song.optString("name"), artist = song.optString("singer").replace("/", " ")),
                listOf("QQ音乐"), sourceRanks = mapOf("QQ音乐" to rank))
        }.filter { it.track.track.isNotBlank() }
    }

    private fun searchSongMid(track: NowPlaying): QQMusicSearchResult? {
        val queries = listOf("${track.track} ${track.artist}".trim(), track.track)
            .filter { it.isNotBlank() }.distinct()
        val normalizedTitle = track.track.lowercase().replace("\\s+".toRegex(), "")
        val normalizedArtist = track.artist.lowercase().replace("\\s+".toRegex(), "")
        var networkFailure: java.io.IOException? = null
        for (query in queries) {
            val url = QQ_MUSIC_SEARCH_API.toHttpUrl().newBuilder()
                .addQueryParameter("key", query).addQueryParameter("format", "json")
                .addQueryParameter("g_tk", "5381").addQueryParameter("uin", "0").build()
            val response = try { requestGet(url.toString()) } catch (failure: java.io.IOException) {
                networkFailure = failure
                continue
            }
            if (response.code !in 200..299) {
                networkFailure = java.io.IOException("QQ 音乐搜索失败 (${response.code})")
                continue
            }
            val json = JSONObject(response.body)
            if (json.optInt("code", -1) != 0) {
                networkFailure = java.io.IOException("QQ 音乐搜索接口返回错误")
                continue
            }
            val songs = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist") ?: continue
            var best: QQMusicSearchResult? = null
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val mid = song.optString("mid")
                val name = song.optString("name").lowercase().replace("\\s+".toRegex(), "")
                val artist = song.optString("singer").lowercase().replace("\\s+".toRegex(), "")
                if (mid.isBlank() || name.isBlank() || normalizedTitle.isBlank()) continue
                val titleScore = when {
                    name == normalizedTitle -> 50
                    name.contains(normalizedTitle) || normalizedTitle.contains(name) -> 20
                    else -> 0
                }
                val artistMatch = normalizedArtist.isNotBlank() && artist.isNotBlank() &&
                    (artist.contains(normalizedArtist) || normalizedArtist.contains(artist))
                if (titleScore == 0 || (titleScore < 50 && !artistMatch)) continue
                val score = titleScore + if (artistMatch) 40 else 0
                if (best == null || score > best.score) best = QQMusicSearchResult(mid, score)
            }
            if (best != null) return best
        }
        networkFailure?.let { throw it }
        return null
    }

    private data class QQMusicSearchResult(val mid: String, val score: Int)

    private fun fetchOfficialLyricData(songMid: String): JSONObject? {
        val params = JSONObject()
            .put("songMid", songMid)
            .put("crypt", 1)
            .put("lrc_t", 0)
            .put("qrc", 1)
            .put("qrc_t", 0)
            .put("trans", 1)
            .put("trans_t", 0)
            .put("roma", 1)
            .put("roma_t", 0)
            .put("type", 1)
            .put("ct", QQ_MUSIC_CLIENT_TYPE)
            .put("cv", QQ_MUSIC_CLIENT_VERSION)

        val requestData = JSONObject()
            .put("module", "music.musichallSong.PlayLyricInfo")
            .put("method", "GetPlayLyricInfo")
            .put("param", params)

        val common = JSONObject()
            .put("ct", QQ_MUSIC_CLIENT_TYPE)
            .put("cv", QQ_MUSIC_CLIENT_VERSION)
            .put("v", QQ_MUSIC_CLIENT_VERSION)
            .put("uin", "0")
            .put("format", "json")

        val payload = JSONObject()
            .put("comm", common)
            .put("req_0", requestData)

        val response = requestPost(
            url = MUSIC_U_API,
            jsonPayload = payload.toString(),
            userAgent = QQ_MUSIC_ANDROID_USER_AGENT
        )
        if (response.code !in 200..299) return null

        val requestResult = JSONObject(response.body).optJSONObject("req_0") ?: return null
        if (requestResult.optInt("code", -1) != 0) return null
        return requestResult.optJSONObject("data")
    }

    private fun fetchLegacyLyrics(songMid: String): JSONObject? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&g_tk=5381"
        val response = requestGet(url)
        if (response.code !in 200..299) return null
        
        return JSONObject(response.body)
    }

    private fun mergeTranslation(base: List<LyricsLine>, translation: List<LyricsLine>): List<LyricsLine> {
        return TimedLyricsMerger.mergeTranslation(base, translation)
    }

    private fun mergeReading(base: List<LyricsLine>, reading: List<LyricsLine>): List<LyricsLine> {
        return TimedLyricsMerger.mergeReading(base, reading)
    }

    private fun requestGet(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

    private fun requestPost(
        url: String,
        jsonPayload: String,
        userAgent: String = WEB_USER_AGENT
    ): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", userAgent)
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

    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QQ_MUSIC_SEARCH_API = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
        const val QQ_MUSIC_CLIENT_TYPE = 11
        const val QQ_MUSIC_CLIENT_VERSION = 14090008
        const val QQ_MUSIC_ANDROID_USER_AGENT = "QQMusic 14090008(android 15)"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
