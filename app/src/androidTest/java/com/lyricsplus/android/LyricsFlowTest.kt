package com.lyricsplus.android

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.libraryKey
import com.lyricsplus.android.lyrics.LyricsCacheDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LyricsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineLibraryFavoritesSearchAndCueControls() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.deleteDatabase("lyrics_cache.db")
        app.getSharedPreferences("lyrics_plus_prefs", 0).edit().clear().putBoolean("auto_check_updates", false).commit()
        app.getSharedPreferences("lyrics_plus_stats", 0).edit().putBoolean("enabled", false).commit()
        val track = NowPlaying(track = "这一首想唱的歌", artist = "lyrics", album = "我的歌单", durationSeconds = 60)
        val db = LyricsCacheDatabase(app)
        db.saveLyrics(track.libraryKey(), listOf(
            LyricsLine(0, "让歌词跟着节奏", "保留熟悉的界面与动效"),
            LyricsLine(4000, "这一句正在唱", "长按拖动校准提词时间"),
            LyricsLine(8000, "下一句慢慢出现", "左右滑动进入歌词列表")
        ), "网易云音乐")
        db.saveLibrarySong(track, "网易云音乐")

        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("歌词列表").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("歌词列表").performClick()
            compose.onNodeWithText(track.track).assertIsDisplayed()
            screenshot(app, "01-library")
            compose.onNodeWithContentDescription("收藏 ${track.track}").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("取消收藏 ${track.track}").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(LyricsCacheDatabase(app).librarySongs().single().favorite)
            compose.onNodeWithContentDescription("管理 ${track.track}").performClick()
            compose.onNodeWithText("删除歌词").assertIsDisplayed()
            screenshot(app, "04-song-menu")
            compose.onNodeWithText("删除歌词").performClick()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText(track.track).assertIsDisplayed()

            compose.onNodeWithText("搜索并添加歌词").performClick()
            compose.onNodeWithText("搜索歌词").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("周杰伦")
            compose.onNodeWithText("周杰伦").assertIsDisplayed()
            screenshot(app, "02-search")
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText(track.track).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("开始提词").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("开始提词").performClick()
            compose.onNodeWithContentDescription("暂停提词").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("开始提词").assertIsDisplayed()
            screenshot(app, "03-lyrics")

            // Reopen to verify saved lyrics, favorite and selected song survive activity recreation.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(track.track).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(track.track).assertIsDisplayed()
            assertTrue(LyricsCacheDatabase(app).librarySongs().single().favorite)
            assertEquals(3, LyricsCacheDatabase(app).getLyrics(track.libraryKey())!!.lyrics.size)
        } finally { scenario.close(); db.close() }
    }

    private fun screenshot(app: android.app.Application, name: String) {
        compose.waitForIdle()
        Thread.sleep(700) // Allow WebView painting and the existing fade/scroll animations to settle.
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        // Shared images survive Gradle's automatic uninstall of the test/target APKs.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/lyrics-ui")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(uri)!!.use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        app.contentResolver.update(uri, values, null, null)
    }
}
