package com.lyricsplus.android.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.MainViewModel
import kotlinx.coroutines.delay
import androidx.compose.ui.composed
import kotlin.math.max
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.lyricsplus.android.data.libraryKey

private val AppBackground = Color(0xFF101010)
private val Accent = Color(0xFF4AD295)
private val Panel = Color(0xFF181A19)
private val Outline = Color(0x1FFFFFFF)

@Composable
fun LyricsPlusApp(
    viewModel: MainViewModel,
    webController: LyricsWebController
) {
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme {
        LyricsOverlay(
            state = uiState,
            webController = webController,
            viewModel = viewModel,
            onOpenSearch = viewModel::openSearch,
            onOpenLibrary = viewModel::openLibrary
        )
    }
}

@Composable
private fun LyricsOverlay(
    state: LyricsUiState,
    webController: LyricsWebController,
    viewModel: MainViewModel,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isMultiPane = isLandscape || (configuration.screenWidthDp >= 600)

    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    val isRightAligned = isMultiPane

    LaunchedEffect(webController.isReady, state.nowPlaying) {
        webController.pushTrack(state.nowPlaying)
    }

    LaunchedEffect(webController.isReady, state.lyrics) {
        webController.pushLyrics(state.lyrics)
    }

    LaunchedEffect(webController.isReady, state.lyrics) {
        webController.pushPlayback(state.playback.currentPositionMs(), state.playback.isPlaying)
    }

    // Throttle playback pushes to WebView: the JS renderer extrapolates position
    // via performance.now(), so we only need periodic sync updates (~150ms minimum interval)
    // to avoid overwhelming the JS bridge and competing with the rendering tick loop.
    var lastPlaybackPushMs by remember { mutableStateOf(0L) }
    LaunchedEffect(webController.isReady, state.playback) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPlaybackPushMs
        if (elapsed < 150L) {
            delay(150L - elapsed)
        }
        lastPlaybackPushMs = SystemClock.elapsedRealtime()
        webController.pushPlayback(state.playback.currentPositionMs(), state.playback.isPlaying)
    }

    LaunchedEffect(webController.isReady, state.readingMode) {
        webController.pushReadingMode(state.readingMode)
    }

    LaunchedEffect(webController.isReady, isRightAligned) {
        webController.pushRightAligned(isRightAligned)
    }

    LaunchedEffect(webController.isReady, state.inAppFontScale) {
        webController.pushInAppFontScale(state.inAppFontScale)
    }

    // Push safe area insets (system bars + display cutout) to the WebView
    // Use ViewCompat to get actual insets including display cutout (camera notch)
    val safeInsetConfig = remember(configuration) {
        configuration.orientation // trigger recomposition on rotation
    }
    LaunchedEffect(webController.isReady, safeInsetConfig) {
        val webView = webController.webView
        val density = webView.context.resources.displayMetrics.density

        // Try to get actual window insets via ViewCompat (includes display cutout)
        val windowInsets = androidx.core.view.ViewCompat.getRootWindowInsets(webView)
        if (windowInsets != null) {
            val typeMask = androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            val insets = windowInsets.getInsets(typeMask)
            val topDp = if (density > 0) (insets.top / density).toInt() else 28
            val rightDp = if (density > 0) (insets.right / density).toInt() else 0
            val bottomDp = if (density > 0) (insets.bottom / density).toInt() else 0
            val leftDp = if (density > 0) (insets.left / density).toInt() else 0
            webController.pushSafeInsets(topDp, rightDp, bottomDp, leftDp)
        } else {
            // Fallback: use system resource values
            val resources = webView.context.resources
            val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarPx = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 0
            val statusBarDp = if (density > 0) (statusBarPx / density).toInt() else 28
            webController.pushSafeInsets(statusBarDp, 0, 0, 0)
        }
    }

    var isExpanded by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var headerHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(webController.isReady, isMultiPane, headerHeightPx, webController.isFullLyricsMode) {
        webController.pushHeaderHeight(if (isMultiPane || webController.isFullLyricsMode) 0 else headerHeightPx)
    }
    BackHandler(enabled = state.nowPlaying.hasTrack && state.libraryPage == 0 && webController.isFullLyricsMode && !isExpanded && !showAbout) { webController.toggleLyricsMode() }
    BackHandler(enabled = isExpanded && state.nowPlaying.hasTrack) { isExpanded = false }
    BackHandler(enabled = showAbout) { showAbout = false }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(
            if (state.libraryPage != 0 || showAbout) Modifier.clearAndSetSemantics { } else Modifier
        )) {
            AndroidView(factory = {
                webController.webView.apply {
                    (parent as? ViewGroup)?.removeView(this)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }, modifier = Modifier.fillMaxSize())

            if (!state.nowPlaying.hasTrack) {
                LyricsHome(onOpenSearch, onOpenLibrary)
            } else {
                if (state.lyrics.isEmpty()) {
                    EmptyOverlay(state, onOpenSearch,
                        Modifier.fillMaxSize().background(AppBackground)
                            .statusBarsPadding().navigationBarsPadding().padding(28.dp))
                }
                val currentSong = state.library.firstOrNull { it.key == state.nowPlaying.libraryKey() }
                if (isMultiPane && !webController.isFullLyricsMode) {
                    Row(Modifier.fillMaxSize().zIndex(1f).graphicsLayer {}) {
                        Column(Modifier.weight(.45f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                            .padding(24.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AlbumArtView(state.nowPlaying.albumArt, state.nowPlaying.backgroundStart,
                                state.nowPlaying.backgroundEnd, Modifier.size(150.dp))
                            Spacer(Modifier.height(20.dp))
                            TrackTitle(state, true)
                            Spacer(Modifier.height(16.dp))
                            CueControls(state, viewModel, currentSong)
                        }
                        Spacer(Modifier.weight(.55f))
                    }
                } else if (!webController.isFullLyricsMode) {
                    Row(Modifier.align(Alignment.TopCenter).zIndex(1f).graphicsLayer {}.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                        .onGloballyPositioned { headerHeightPx = it.size.height },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { TrackTitle(state, false) }
                        Spacer(Modifier.width(16.dp))
                        CueControls(state, viewModel, currentSong)
                    }
                }
            }
            if (webController.debugMessage.startsWith("error:", true)) {
                DebugChip(webController.debugMessage, Modifier.align(Alignment.BottomStart)
                    .navigationBarsPadding().padding(10.dp))
            }
            if (isExpanded && state.nowPlaying.hasTrack) {
                Box(Modifier.fillMaxSize().zIndex(2f).noRippleClickable { isExpanded = false })
                Column(Modifier.align(Alignment.BottomEnd).zIndex(2f).graphicsLayer {}.navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 80.dp)
                    .heightIn(max = (configuration.screenHeightDp - 120).coerceAtLeast(160).dp)
                    .background(Color(0xEE161A18), RoundedCornerShape(18.dp))
                    .border(1.dp, Outline, RoundedCornerShape(18.dp))
                    .width(256.dp).padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.nowPlaying.hasTrack && state.lyrics.isNotEmpty()) {
                        MenuActionRow("切换歌词源 · ${state.activeLyricsSource}", "♫") { viewModel.switchLyricsSource() }
                        MenuActionRow("重新取色", "◈") { viewModel.rotatePaletteColors() }
                    }
                    val readingLabel = when (state.readingMode) { 0 -> "无注音"; 1 -> "罗马音"; else -> "振假名" }
                    MenuActionRow("注音 · $readingLabel", "あ") { viewModel.cycleReadingMode() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("↕", color = Color(0xB3FFFFFF), fontSize = 18.sp,
                            modifier = Modifier.width(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.width(12.dp))
                        Text("字号", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.adjustInAppFontScale(-.1f) }, modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("－", color = Color.White) }
                        Text("${(state.inAppFontScale * 100).toInt()}%", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                        TextButton(onClick = { viewModel.adjustInAppFontScale(.1f) }, modifier = Modifier.size(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋", color = Color.White) }
                    }
                    MenuActionRow("关于项目", "ⓘ") { isExpanded = false; showAbout = true }
                }
            }
            if (state.nowPlaying.hasTrack) Box(Modifier.align(Alignment.BottomEnd).zIndex(3f).graphicsLayer {}.navigationBarsPadding().padding(24.dp)
                .size(44.dp).background(if (isExpanded) Accent else Color(0x55323634), CircleShape)
                .semantics { contentDescription = if (isExpanded) "关闭设置" else "设置" }
                .noRippleClickable { isExpanded = !isExpanded }, contentAlignment = Alignment.Center) {
                Text(if (isExpanded) "✕" else "⚙", color = if (isExpanded) Color.Black else Color.White,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (state.libraryPage != 0) LyricsLibraryPage(state, viewModel, Modifier.fillMaxSize().zIndex(4f).graphicsLayer {})
        if (showAbout) LyricsAboutPage(viewModel, onBack = { showAbout = false }, modifier = Modifier.fillMaxSize().zIndex(5f).graphicsLayer {})
    }
}

@Composable
private fun TrackTitle(state: LyricsUiState, centered: Boolean) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(state.nowPlaying.track, color = Color.White, fontSize = if (centered) 22.sp else 28.sp,
            lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.nowPlaying.artist, color = Color(0xB3FFFFFF), fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CueControls(state: LyricsUiState, viewModel: MainViewModel, song: com.lyricsplus.android.data.LibrarySong?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp)
            .semantics { contentDescription = if (song?.favorite == true) "取消收藏当前歌曲" else "收藏当前歌曲" }
            .noRippleClickable { if (song != null) viewModel.toggleFavorite(song) }, contentAlignment = Alignment.Center) {
            Text(if (song?.favorite == true) "★" else "☆", fontSize = 24.sp,
                color = if (song?.favorite == true) Accent else Color.White.copy(alpha = if (song == null) .3f else 1f))
        }
        Box(Modifier.height(20.dp).width(1.dp).background(Color(0x26FFFFFF)))
        PlayPauseButton(state.playback.isPlaying, viewModel::togglePlayback)
    }
}

@Composable
private fun LyricsHome(onSearch: () -> Unit, onLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AppBackground).cuePageSwipe(onSearch, onLibrary)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(Brush.linearGradient(listOf(Color(0xFFD3A5FF), Color(0xFF8865E8))), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Text("L", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("lyrics", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(36.dp))
        HomeNavigation("→", "搜索歌词", "歌名 / 歌手 · 右滑", onSearch)
        Spacer(Modifier.height(12.dp))
        HomeNavigation("←", "歌词列表", "已添加与收藏 · 左滑", onLibrary)
        Spacer(Modifier.height(30.dp))
        Text("操作", color = Color(0xFF8D9490), fontSize = 12.sp)
        HomeInstruction("↕", "滑动歌词调整进度", "长按精细调整")
        HomeInstruction("◫", "点空白处切换歌词视图", "全部歌词里，点某句跳转")
        Text("仅滚动歌词，音乐由其他设备播放", color = Color(0xFF8D9490), fontSize = 12.sp,
            modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
    }
}

@Composable
private fun HomeNavigation(arrow: String, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp))
        .border(1.dp, Outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Color(0xFF8D9490), fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        Text(arrow, color = Accent, fontSize = 24.sp)
    }
}

@Composable
private fun HomeInstruction(symbol: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = Accent, fontSize = 20.sp, modifier = Modifier.width(34.dp))
        Column {
            Text(title, color = Color(0xFFDDE2DF), fontSize = 14.sp)
            Text(detail, color = Color(0xFF8D9490), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private fun Modifier.cuePageSwipe(onRight: () -> Unit, onLeft: () -> Unit): Modifier = pointerInput(onRight, onLeft) {
    var distance = 0f
    detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { _, delta -> distance += delta },
        onDragEnd = { if (distance > 72.dp.toPx()) onRight() else if (distance < -72.dp.toPx()) onLeft() })
}

@Composable
private fun DebugChip(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message.take(90),
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun DebugOverlay(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "歌词渲染调试",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 36.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color(0xFF8D9490),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyOverlay(state: LyricsUiState, onOpenSearch: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start) {
        Text(if (state.isLoadingLyrics) "正在加载歌词…" else state.message,
            color = Color.White, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        Text(if (state.isLoadingLyrics) "正在准备提词与注音" else "试试其他录音版本或歌词来源",
            color = Color(0x998D9490), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        if (!state.isLoadingLyrics) Button(onClick = onOpenSearch,
            modifier = Modifier.padding(top = 22.dp), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
            Text("选择其他歌曲")
        }
    }
}

@Composable
private fun MenuActionRow(
    label: String,
    emoji: String,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(emoji, color = Color(0xB3FFFFFF), fontSize = 18.sp,
            modifier = Modifier.width(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .semantics { contentDescription = if (isPlaying) "暂停提词" else "开始提词" }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val barWidth = size.width * 0.18f
                val gap = size.width * 0.22f
                val h = size.height * 0.55f
                val startY = size.height * 0.225f
                
                // Left bar
                drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.26f, startY),
                    size = Size(barWidth, h)
                )
                // Right bar
                drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.26f + barWidth + gap, startY),
                    size = Size(barWidth, h)
                )
            }
        } else {
            Canvas(modifier = Modifier.size(24.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.22f)
                    lineTo(size.width * 0.82f, size.height * 0.5f)
                    lineTo(size.width * 0.32f, size.height * 0.78f)
                    close()
                }
                drawPath(path, color = Color.White)
            }
        }
    }
}

@Composable
private fun AlbumArtView(
    bitmap: android.graphics.Bitmap?,
    startColorHex: String?,
    endColorHex: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .background(Color(0xFF181A19), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Album Art",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            val startColor = startColorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF2C3E50)
            val endColor = endColorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF101010)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(startColor, endColor)
                        ),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎵",
                    fontSize = 42.sp
                )
            }
        }
    }
}

// Shared by the lyric controls; retained when the retired floating overlay was removed.
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
