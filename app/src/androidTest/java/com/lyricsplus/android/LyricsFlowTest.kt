package com.lyricsplus.android

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.lyricsplus.android.data.SongSearchMatch
import com.lyricsplus.android.data.mergeSongSearchMatches
import com.lyricsplus.android.ui.LyricsLibraryPage
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
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            LyricsLine(4000, "这一句正在唱", "上下滑动调整提词时间"),
            LyricsLine(8000, "下一句慢慢出现", "左右滑动进入歌词列表")
        ), "网易云音乐")
        db.saveLibrarySong(track, "网易云音乐")
        val disposable = track.copy(track = "左滑立即删除的歌曲")
        db.saveLyrics(disposable.libraryKey(), listOf(LyricsLine(0, "临时歌词")), "网易云音乐")
        db.saveLibrarySong(disposable, "网易云音乐")

        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索歌词").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("设置").assertCountEquals(0)
            screenshot(app, "00-home")
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.onNodeWithText("歌词列表").assertIsDisplayed()
            compose.onNodeWithText(track.track).assertIsDisplayed()
            screenshot(app, "01-library")
            compose.onNodeWithContentDescription("收藏 ${track.track}").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("取消收藏 ${track.track}").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(LyricsCacheDatabase(app).librarySongs().first { it.key == track.libraryKey() }.favorite)
            compose.onNodeWithText(disposable.track).performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(disposable.track).fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithText("删除这首歌词？").assertCountEquals(0)
            assertFalse(LyricsCacheDatabase(app).librarySongs().any { it.key == disposable.libraryKey() })
            screenshot(app, "04-swipe-delete")
            compose.onNodeWithText(track.track).assertIsDisplayed()

            back(scenario)
            compose.onNodeWithText("搜索歌词").performClick()
            compose.onNodeWithText("搜索歌词").assertIsDisplayed()
            compose.onNodeWithText("热门").assertIsDisplayed().performClick()
            compose.onNodeWithText("匹配").assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("周杰伦")
            compose.onNodeWithText("周杰伦").assertIsDisplayed()
            screenshot(app, "02-search")
            back(scenario)
            compose.onNodeWithText("歌词", substring = false).assertIsDisplayed()
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.onNodeWithText(track.track).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("开始提词").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("开始提词").performClick()
            compose.onNodeWithContentDescription("暂停提词").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("开始提词").assertIsDisplayed()
            compose.waitUntil(10_000) {
                webJs(scenario, "document.querySelectorAll('.line').length === 3 && !!document.querySelector('.line.active')") == "true"
            }
            assertEquals("true", webJs(scenario, "(() => {const row=document.querySelector('.line.active');const r=row.getBoundingClientRect();return r.top>=0 && r.bottom<=innerHeight && getComputedStyle(row).filter==='none';})()"))
            screenshot(app, "03-lyrics")
            compose.onNodeWithContentDescription("取消收藏当前歌曲").assertIsDisplayed()
            compose.onNodeWithContentDescription("设置").assertIsDisplayed().performClick()
            compose.onAllNodesWithText("从头提词").assertCountEquals(0)
            compose.onAllNodesWithText("歌词提前").assertCountEquals(0)
            compose.onNodeWithText("关于项目").assertIsDisplayed()
            compose.onAllNodesWithText("检查更新").assertCountEquals(0)
            compose.onAllNodesWithText("屏幕常亮 · 开启").assertCountEquals(0)
            screenshot(app, "05-settings")
            compose.onNodeWithText("关于项目").performClick()
            compose.onNodeWithText("项目地址").assertIsDisplayed()
            compose.onNodeWithText("检查更新").assertIsDisplayed()
            compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .8f)) }
            compose.waitUntil(1000) { compose.onAllNodesWithText("★").fetchSemanticsNodes().isNotEmpty() }
            screenshot(app, "08-about")
            back(scenario)
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
            // A normal vertical swipe, without a hold, adjusts focused lyric time.
            compose.onRoot().performTouchInput {
                swipe(Offset(width * .3f, height * .7f), Offset(width * .3f, height * .5f), 250)
            }
            compose.waitUntil(10_000) { app.getSharedPreferences("lyrics_plus_prefs", 0).getLong("last_position", 0) > 0 }
            webJs(scenario, "window.LyricsPlus.toggleMode();window.LyricsPlus.toggleMode()")
            Thread.sleep(650)
            assertEquals("0", webJs(scenario, "document.getElementById('stage').scrollTop"))
            assertEquals("true", webJs(scenario, "(() => {const row=document.querySelector('.line.active');const r=row.getBoundingClientRect();return r.top>=0 && r.bottom<=innerHeight && getComputedStyle(row).filter==='none';})()"))
            webJs(scenario, "window.LyricsPlus.toggleMode()")
            compose.waitUntil(10_000) { webJs(scenario, "document.getElementById('stage').classList.contains('full-lyrics-mode')") == "true" }
            Thread.sleep(1100) // Ignore the compatibility click after the preceding swipe.
            webJs(scenario, "document.querySelector('.line[data-index=\"2\"]').click()")
            compose.waitUntil(10_000) { app.getSharedPreferences("lyrics_plus_prefs", 0).getLong("last_position", -1) == 8000L }
            compose.onAllNodesWithContentDescription("开始提词").assertCountEquals(0)
            compose.onAllNodesWithText(track.track).assertCountEquals(0)
            assertEquals("2", webJs(scenario, "document.querySelector('.line.active').dataset.index").trim('\"'))
            println("Full mode DOM: " + webJs(scenario, "JSON.stringify({scrollY:window.scrollY,innerHeight:innerHeight,clip:getComputedStyle(document.querySelector('.lyrics-viewport')).clipPath,padding:getComputedStyle(document.querySelector('.lyrics-viewport')).paddingTop,header:getComputedStyle(document.getElementById('stage')).getPropertyValue('--header-bottom')})"))
            screenshot(app, "06-full-lyrics")
            back(scenario)
            compose.waitUntil(10_000) { webJs(scenario, "document.getElementById('stage').classList.contains('full-lyrics-mode')") == "false" }
            compose.onNodeWithContentDescription("开始提词").assertIsDisplayed()
            compose.onRoot().performTouchInput { swipeRight() }
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText("搜索歌词").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("搜索歌词").assertIsDisplayed()
            back(scenario)
            compose.onNodeWithText(track.track).assertIsDisplayed()

            // Reopen to verify saved lyrics, favorite and selected song survive activity recreation.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(track.track).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(track.track).assertIsDisplayed()
            assertTrue(LyricsCacheDatabase(app).librarySongs().first { it.key == track.libraryKey() }.favorite)
            assertEquals(3, LyricsCacheDatabase(app).getLyrics(track.libraryKey())!!.lyrics.size)
        } finally { scenario.close(); db.close() }
    }

    @Test fun searchSourceLabelsShowMultipleMatches() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("lyrics_plus_prefs", 0).edit().putBoolean("auto_check_updates", false).commit()
        val track = NowPlaying(track = "同一首歌，多个来源", artist = "lyrics", album = "匹配来源", durationSeconds = 200)
        val matches = mergeSongSearchMatches(listOf("网易云音乐", "QQ音乐", "LRCLIB").map { SongSearchMatch(track, listOf(it)) })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView
                content.setContent { LyricsLibraryPage(LyricsUiState(libraryPage = 2, searchQuery = "想唱的歌", searchResults = matches), vm) }
            }
            compose.onNodeWithText("网易云音乐").assertIsDisplayed()
            compose.onNodeWithText("QQ音乐").assertIsDisplayed()
            compose.onNodeWithText("LRCLIB").assertIsDisplayed()
            compose.onNodeWithText(track.track).assertIsDisplayed()
            screenshot(app, "07-search-sources")
        } finally { scenario.close() }
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun webJs(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            fun find(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            find(activity.findViewById(android.R.id.content))!!.evaluateJavascript(script) { result.set(it); latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
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
        if (name == "03-lyrics") {
            // Check the actual WebView pixels, not just the Compose title/control layer.
            var brightPixels = 0
            var backgroundPixels = 0
            for (y in screenshot.height / 3 until screenshot.height * 3 / 4 step 3) {
                for (x in 0 until screenshot.width step 3) {
                    val pixel = screenshot.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    if (minOf(r, g, b) > 150) brightPixels++
                    if (maxOf(r, g, b) - minOf(r, g, b) > 8) backgroundPixels++
                }
            }
            assertTrue("The main lyric text must be painted", brightPixels > 100)
            assertTrue("The original gradient must be painted", backgroundPixels > 1000)
        }
        if (name == "03-lyrics") {
            val bounds = compose.onNodeWithContentDescription("取消收藏当前歌曲").fetchSemanticsNode().boundsInRoot
            var green = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(screenshot.height)) {
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(screenshot.width)) {
                    val pixel = screenshot.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    if (g > 130 && g > r * 1.4 && g > b * 1.1) green++
                }
            }
            assertTrue("Focused controls must be painted above the WebView", green > 20)
        }
    }
}
