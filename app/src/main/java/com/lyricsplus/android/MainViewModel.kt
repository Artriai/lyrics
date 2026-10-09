package com.lyricsplus.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsplus.android.analytics.AnonymousStats
import com.lyricsplus.android.data.*
import com.lyricsplus.android.lyrics.LyricsCacheDatabase
import com.lyricsplus.android.lyrics.LyricsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

private const val PREF_AUTO_CHECK_UPDATES = "auto_check_updates"

data class LyricsUiState(
    val nowPlaying: NowPlaying = NowPlaying(),
    val playback: PlaybackAnchor = PlaybackAnchor(),
    val lyrics: List<LyricsLine> = emptyList(),
    val isLoadingLyrics: Boolean = false,
    val message: String = "选择一首歌，开始提词",
    val playbackSource: String = "独立提词",
    val lyricsOffsetMs: Long = 0L,
    val readingMode: Int = 1,
    val keepScreenOn: Boolean = true,
    val activeLyricsSource: String = "未加载",
    val isInitializing: Boolean = true,
    val inAppFontScale: Float = 1.0f,
    val anonymousStatsEnabled: Boolean = true,
    val anonymousStatsAvailable: Boolean = false,
    val autoCheckUpdatesEnabled: Boolean = true,
    val library: List<LibrarySong> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<NowPlaying> = emptyList(),
    val isSearching: Boolean = false,
    val searchMessage: String = "",
    val libraryPage: Int = 0, // 0 = lyrics, 1 = saved songs, 2 = search
    val isScrubbing: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val lyricsProvider = LyricsProvider.getInstance(application)
    private val libraryDb = LyricsCacheDatabase(application)
    private val prefs = application.getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(LyricsUiState(
        readingMode = prefs.getInt("reading_mode", 1),
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        inAppFontScale = prefs.getFloat("in_app_font_scale", 1f),
        anonymousStatsEnabled = AnonymousStats.isEnabled(application),
        anonymousStatsAvailable = AnonymousStats.isAvailable(),
        autoCheckUpdatesEnabled = prefs.getBoolean(PREF_AUTO_CHECK_UPDATES, true)
    ))
    val uiState = _uiState.asStateFlow()
    private var timeline = CueTimeline()
    private var lyricsJob: Job? = null
    private var searchJob: Job? = null
    private var resumeAfterScrub = false

    init {
        viewModelScope.launch(Dispatchers.Default) { lyricsProvider.preWarm() }
        viewModelScope.launch {
            reloadLibrary()
            val last = _uiState.value.library.firstOrNull { it.key == prefs.getString("last_song", null) }
            if (last != null) selectSong(last.track, prefs.getLong("last_position", 0L))
            else _uiState.update { it.copy(isInitializing = false) }
        }
        viewModelScope.launch {
            var persistTick = 0
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                if (timeline.running && timeline.positionAt(now) >= timeline.durationMs) {
                    timeline = timeline.pause(now)
                    publishClock()
                    saveProgress()
                }
                if (++persistTick >= 20) {
                    persistTick = 0
                    if (timeline.running) saveProgress()
                }
            }
        }
        if (_uiState.value.autoCheckUpdatesEnabled) checkForUpdates(silent = true)
    }

    fun openLibrary() = _uiState.update { it.copy(libraryPage = 1) }
    fun openSearch() = _uiState.update { it.copy(libraryPage = 2) }
    fun backToLyrics() = _uiState.update { it.copy(libraryPage = 0) }
    fun backFromLibrary() = _uiState.update { it.copy(libraryPage = if (it.libraryPage == 2) 1 else 0) }

    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = query, searchResults = emptyList(), searchMessage = "", isSearching = false) }
    }

    fun searchSongs() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchMessage = "", searchResults = emptyList()) }
            val result = lyricsProvider.searchSongs(query)
            ensureActive()
            _uiState.update { state -> result.fold(
                onSuccess = { state.copy(isSearching = false, searchResults = it,
                    searchMessage = if (it.isEmpty()) "没有找到歌曲，试试歌名加歌手" else "") },
                onFailure = { state.copy(isSearching = false, searchMessage = "搜索失败，请检查网络后重试") }
            ) }
        }
    }

    fun selectSong(track: NowPlaying, restorePosition: Long = 0L) {
        lyricsJob?.cancel()
        timeline = CueTimeline()
        resumeAfterScrub = false
        val coloredTrack = track.withCuePalette()
        _uiState.update { it.copy(nowPlaying = coloredTrack, lyrics = emptyList(),
            playback = PlaybackAnchor(), isLoadingLyrics = true, isInitializing = false,
            lyricsOffsetMs = 0, isScrubbing = false, libraryPage = 0, message = "正在加载歌词…") }
        lyricsJob = viewModelScope.launch {
            val result = lyricsProvider.findSyncedLyrics(track)
            ensureActive()
            result.fold(onSuccess = { resolved ->
                if (resolved.source == "纯音乐" || resolved.lyrics.isEmpty()) {
                    _uiState.update { it.copy(isLoadingLyrics = false, message = "没有找到可用歌词，请尝试其他版本") }
                    return@fold
                }
                val lastLine = resolved.lyrics.maxOf { it.startTimeMs }
                val duration = maxOf(track.durationSeconds * 1000L, lastLine + 8000L)
                timeline = CueTimeline(durationMs = duration).seek(restorePosition, SystemClock.elapsedRealtime())
                _uiState.update { it.copy(lyrics = resolved.lyrics, activeLyricsSource = resolved.source,
                    isLoadingLyrics = false, message = "点击播放开始提词；长按歌词上下拖动校准进度") }
                publishClock()
                withContext(Dispatchers.IO) {
                    lyricsProvider.saveToCache(track, resolved.lyrics, resolved.source)
                    libraryDb.saveLibrarySong(track, resolved.source)
                }
                reloadLibrary()
                saveProgress()
            }, onFailure = {
                _uiState.update { it.copy(isLoadingLyrics = false, message = "歌词加载失败，请检查网络后重试") }
            })
        }
    }

    fun togglePlayback() {
        if (_uiState.value.lyrics.isEmpty() || _uiState.value.isScrubbing) return
        val now = SystemClock.elapsedRealtime()
        timeline = if (timeline.running) timeline.pause(now) else timeline.resume(now)
        publishClock()
        saveProgress()
    }

    fun restartCue() {
        timeline = timeline.seek(0L, SystemClock.elapsedRealtime())
        _uiState.update { it.copy(lyricsOffsetMs = 0L) }
        publishClock()
        saveProgress()
    }

    fun skipToNext() {
        val songs = _uiState.value.library
        if (songs.isEmpty()) { openLibrary(); return }
        val index = songs.indexOfFirst { it.key == _uiState.value.nowPlaying.libraryKey() }
        selectSong(songs[(index + 1) % songs.size].track)
    }

    fun beginScrub() {
        if (_uiState.value.lyrics.isEmpty()) return
        resumeAfterScrub = timeline.running
        timeline = timeline.pause(SystemClock.elapsedRealtime())
        _uiState.update { it.copy(isScrubbing = true) }
        publishClock()
    }

    fun seekCue(positionMs: Long) {
        timeline = timeline.seek(positionMs - _uiState.value.lyricsOffsetMs, SystemClock.elapsedRealtime())
        publishClock()
    }

    fun endScrub() {
        if (!_uiState.value.isScrubbing) return
        timeline = timeline.finishScrub(resumeAfterScrub, SystemClock.elapsedRealtime())
        resumeAfterScrub = false
        _uiState.update { it.copy(isScrubbing = false) }
        publishClock()
        saveProgress()
    }

    private fun publishClock() {
        _uiState.update { it.copy(playback = PlaybackAnchor(timeline.running, timeline.positionMs, timeline.anchorMs)) }
    }

    private fun saveProgress() {
        if (_uiState.value.lyrics.isEmpty()) return
        prefs.edit().putString("last_song", _uiState.value.nowPlaying.libraryKey())
            .putLong("last_position", timeline.positionAt(SystemClock.elapsedRealtime())).apply()
    }

    private suspend fun reloadLibrary() {
        val songs = withContext(Dispatchers.IO) { libraryDb.librarySongs() }
        _uiState.update { it.copy(library = songs) }
    }

    fun toggleFavorite(song: LibrarySong) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { libraryDb.setFavorite(song.key, !song.favorite) }
            reloadLibrary()
        }
    }

    fun removeSong(song: LibrarySong) {
        viewModelScope.launch {
            if (song.key == _uiState.value.nowPlaying.libraryKey()) {
                lyricsJob?.cancel()
                timeline = CueTimeline()
                _uiState.update { it.copy(nowPlaying = NowPlaying(), lyrics = emptyList(), playback = PlaybackAnchor(), isLoadingLyrics = false) }
                prefs.edit().remove("last_song").remove("last_position").apply()
            }
            withContext(Dispatchers.IO) { lyricsProvider.clearCache(song.track); libraryDb.removeLibrarySong(song.key) }
            reloadLibrary()
        }
    }

    fun switchLyricsSource() {
        val state = _uiState.value
        if (!state.nowPlaying.hasTrack || state.isLoadingLyrics) return
        val sources = listOf("网易云音乐", "QQ音乐", "LRCLIB")
        val nextSource = sources[(sources.indexOf(state.activeLyricsSource) + 1) % sources.size]
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLyrics = true) }
            val result = lyricsProvider.findSyncedLyricsForSource(state.nowPlaying, nextSource)
            ensureActive()
            result.fold(onSuccess = { resolved ->
                if (resolved.lyrics.isEmpty() || resolved.lyrics.all { it.text.contains("纯音乐") }) {
                    toast("这个歌词源没有可用歌词")
                } else {
                    _uiState.update { it.copy(lyrics = resolved.lyrics, activeLyricsSource = nextSource) }
                    timeline = timeline.copy(durationMs = maxOf(state.nowPlaying.durationSeconds * 1000L, resolved.lyrics.maxOf { it.startTimeMs } + 8000L))
                    withContext(Dispatchers.IO) {
                        lyricsProvider.saveToCache(state.nowPlaying, resolved.lyrics, nextSource)
                        libraryDb.saveLibrarySong(state.nowPlaying, nextSource)
                    }
                    reloadLibrary()
                    publishClock()
                }
            }, onFailure = { toast("暂时无法从这个歌词源获取歌词") })
            _uiState.update { it.copy(isLoadingLyrics = false) }
        }
    }

    fun adjustOffset(deltaMs: Long) {
        _uiState.update { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs + deltaMs).coerceIn(-120_000L, 120_000L)) }
    }

    private fun toast(message: String) = Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) toast("正在检查 lyrics 更新…")
            val release = withContext(Dispatchers.IO) {
                runCatching {
                    val request = okhttp3.Request.Builder()
                        .url("https://api.github.com/repos/Artriai/lyrics-plus-android/releases?per_page=100")
                        .header("User-Agent", "lyrics-android").build()
                    com.lyricsplus.android.lyrics.HttpClient.okHttpClient.newCall(request).execute().use { response ->
                        check(response.isSuccessful)
                        val array = JSONArray(response.body?.string().orEmpty())
                        (0 until array.length()).map { array.getJSONObject(it) }
                            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith("lyrics-v") }
                            .maxByOrNull { it.optString("tag_name").substringAfterLast("-build-").substringBefore('-').toLongOrNull() ?: 0L }
                    }
                }
            }
            release.fold(onSuccess = { latest ->
                val number = latest?.optString("tag_name")?.substringAfterLast("-build-")?.substringBefore('-')?.toLongOrNull() ?: 0L
                if (latest != null && number > BuildConfig.LYRICS_BUILD_NUMBER) {
                    toast("发现 lyrics 新版本，即将前往下载…")
                    delay(1500)
                    getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.getString("html_url"))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else if (!silent) toast("已是最新版本（lyrics ${BuildConfig.VERSION_NAME}）")
            }, onFailure = { if (!silent) toast("检查更新失败，请稍后重试") })
        }
    }

    private fun NowPlaying.withCuePalette(): NowPlaying {
        if (backgroundStart != null) return this
        val hue = ((track + artist).hashCode().toLong().and(0x7fffffff) % 360).toFloat()
        fun color(h: Float, s: Float, v: Float): String = "#%06X".format(0xffffff and android.graphics.Color.HSVToColor(floatArrayOf(h % 360, s, v)))
        return copy(backgroundStart = color(hue, .24f, .38f), backgroundEnd = color(hue + 45f, .30f, .22f), backgroundAccent = color(hue + 20f, .30f, .48f))
    }
    fun adjustInAppFontScale(delta: Float) {
        _uiState.update { state ->
            val nextScale = (state.inAppFontScale + delta).coerceIn(0.5f, 2.5f)
            prefs.edit().putFloat("in_app_font_scale", nextScale).apply()
            state.copy(inAppFontScale = nextScale)
        }
    }

    fun cycleReadingMode() {
        _uiState.update { state ->
            val nextMode = (state.readingMode + 1) % 3
            prefs.edit().putInt("reading_mode", nextMode).apply()
            AnonymousStats.trackReadingModeChanged(getApplication(), nextMode)
            state.copy(readingMode = nextMode)
        }
    }

    fun toggleKeepScreenOn() {
        _uiState.update { state ->
            val nextVal = !state.keepScreenOn
            prefs.edit().putBoolean("keep_screen_on", nextVal).apply()
            AnonymousStats.trackFeatureToggle(getApplication(), "keep_screen_on", nextVal)
            state.copy(keepScreenOn = nextVal)
        }
    }

    fun toggleAnonymousStats() {
        val context = getApplication<Application>()
        _uiState.update { state ->
            val nextVal = !state.anonymousStatsEnabled
            AnonymousStats.setEnabled(context, nextVal)
            if (nextVal) {
                AnonymousStats.trackFeatureToggle(context, "anonymous_stats", true)
            }
            state.copy(anonymousStatsEnabled = nextVal)
        }
    }

    fun toggleAutoCheckUpdates() {
        val context = getApplication<Application>()
        val nextVal = !_uiState.value.autoCheckUpdatesEnabled
        prefs.edit().putBoolean(PREF_AUTO_CHECK_UPDATES, nextVal).apply()
        AnonymousStats.trackFeatureToggle(context, "auto_check_updates", nextVal)
        _uiState.update { state ->
            state.copy(autoCheckUpdatesEnabled = nextVal)
        }
        if (nextVal) {
            checkForUpdates(silent = true)
        }
    }

    fun rotatePaletteColors() {
        _uiState.update { state ->
            val nowPlaying = state.nowPlaying
            if (nowPlaying.paletteTemplates.isNotEmpty()) {
                val nextIndex = (nowPlaying.colorStyleIndex + 1) % nowPlaying.paletteTemplates.size
                val nextPalette = nowPlaying.paletteTemplates[nextIndex]
                state.copy(
                    nowPlaying = nowPlaying.copy(
                        colorStyleIndex = nextIndex,
                        backgroundStart = nextPalette.start,
                        backgroundEnd = nextPalette.end,
                        backgroundAccent = nextPalette.accent
                    )
                )
            } else {
                val start = nowPlaying.backgroundStart
                val end = nowPlaying.backgroundEnd
                val accent = nowPlaying.backgroundAccent
                if (start != null && end != null) {
                    val nextStart = start.shiftColorHue(120f)
                    val nextEnd = end.shiftColorHue(120f)
                    val nextAccent = accent?.shiftColorHue(120f) ?: nextStart.shiftColorHue(60f)
                    state.copy(
                        nowPlaying = nowPlaying.copy(
                            backgroundStart = nextStart,
                            backgroundEnd = nextEnd,
                            backgroundAccent = nextAccent
                        )
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun String.shiftColorHue(degrees: Float): String {
        return runCatching {
            val color = android.graphics.Color.parseColor(this)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + degrees + 360f) % 360f
            val nextColor = android.graphics.Color.HSVToColor(hsv)
            "#%02X%02X%02X".format(
                android.graphics.Color.red(nextColor),
                android.graphics.Color.green(nextColor),
                android.graphics.Color.blue(nextColor)
            )
        }.getOrDefault(this)
    }

}
