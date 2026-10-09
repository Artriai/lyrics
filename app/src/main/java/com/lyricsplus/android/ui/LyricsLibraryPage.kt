package com.lyricsplus.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.MainViewModel
import com.lyricsplus.android.data.LibrarySong
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.libraryKey

private val LibraryBackground = Color(0xFF101010)
private val LibraryPanel = Color(0xFF181A19)
private val LibraryAccent = Color(0xFF4AD295)
private val LibraryOutline = Color(0x1FFFFFFF)
private val LibraryMuted = Color(0xFF8D9490)

@Composable
fun LyricsLibraryPage(state: LyricsUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val searching = state.libraryPage == 2
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val resultsScroll = rememberLazyListState()
    LaunchedEffect(state.searchPopular, state.searchResults) {
        // LazyColumn otherwise retains the previous first item's key after reordering.
        resultsScroll.scrollToItem(0)
    }
    BackHandler { keyboard?.hide(); viewModel.backFromLibrary() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = LibraryAccent, surface = LibraryPanel, surfaceContainer = LibraryPanel, background = LibraryBackground
    )) {
    Column(
        modifier.background(LibraryBackground)
            .pointerInput(searching) {
                var horizontal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontal = 0f },
                    onHorizontalDrag = { _, amount -> horizontal += amount },
                    onDragEnd = { if ((!searching && horizontal > 72.dp.toPx()) || (searching && horizontal < -72.dp.toPx())) { keyboard?.hide(); viewModel.backFromLibrary() } }
                )
            }
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (searching) "搜索歌词" else "歌词列表", color = Color.White,
                    fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        if (searching) {
            OutlinedTextField(
                value = state.searchQuery, onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text("歌名、歌手，或一起输入") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = libraryFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); viewModel.searchSongs() }),
                trailingIcon = {
                    TextButton(onClick = { keyboard?.hide(); viewModel.searchSongs() },
                        enabled = state.searchQuery.isNotBlank() && !state.isSearching) {
                        Text("搜索", color = if (state.searchQuery.isBlank()) LibraryMuted else LibraryAccent,
                            fontWeight = FontWeight.Bold)
                    }
                }
            )
            Row(Modifier.padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LibraryFilter("热门", state.searchPopular) { viewModel.setSearchPopular(true) }
                LibraryFilter("匹配", !state.searchPopular) { viewModel.setSearchPopular(false) }
            }
            AnimatedVisibility(state.isSearching, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = LibraryAccent, strokeWidth = 2.dp)
                    Text("正在搜索…", color = LibraryMuted)
                }
            }
            if (state.searchMessage.isNotBlank()) {
                Text(state.searchMessage, color = LibraryMuted, modifier = Modifier.padding(vertical = 16.dp))
            }
            if (!state.isSearching && state.searchResults.isNotEmpty()) {
                Text("${state.searchResults.size} 个结果", color = LibraryMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            LazyColumn(Modifier.weight(1f), state = resultsScroll, verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.searchResults, key = { it.track.libraryKey() }) { match ->
                    val track = match.track
                    val saved = state.library.firstOrNull { it.key == track.libraryKey() }
                    val adding = track.libraryKey() in state.addingSongs
                    SongRow(track = track, sources = match.sources, selected = state.nowPlaying.libraryKey() == track.libraryKey(),
                        onSelect = { keyboard?.hide(); viewModel.selectSong(track) },
                        trailing = {
                            TextButton(onClick = { if (saved == null && !adding) viewModel.addSongToLibrary(track) },
                                modifier = Modifier.semantics { contentDescription =
                                    if (saved != null) "已添加到列表 ${track.track}" else "添加到列表 ${track.track}" }) {
                                if (adding) CircularProgressIndicator(Modifier.size(20.dp), color = LibraryAccent, strokeWidth = 2.dp)
                                else Text(if (saved != null) "已保存" else "+", color = LibraryAccent,
                                    fontSize = if (saved != null) 12.sp else 26.sp)
                            }
                        })
                }
            }
        } else {
            Row(Modifier.padding(top = 18.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LibraryFilter("全部", !favoritesOnly) { favoritesOnly = false }
                LibraryFilter("收藏", favoritesOnly) { favoritesOnly = true }
            }
            if (state.library.isNotEmpty()) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                    placeholder = { Text("筛选已保存歌词") }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp), colors = libraryFieldColors())
            }
            val terms = filter.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val songs = state.library.filter { song -> (!favoritesOnly || song.favorite) &&
                terms.all { "${song.track.track} ${song.track.artist}".contains(it, ignoreCase = true) } }
            if (songs.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    Text(when { state.library.isEmpty() -> "还没有保存的歌词"; favoritesOnly -> "还没有收藏的歌词"; else -> "没有匹配的歌曲" },
                        color = Color.White, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (state.library.isEmpty()) "搜索并选歌后，歌词会自动保存在这里" else "试试其他筛选条件",
                        color = LibraryMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    if (state.library.isEmpty()) Button(onClick = viewModel::openSearch,
                        modifier = Modifier.padding(top = 24.dp), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LibraryAccent, contentColor = Color.Black)) {
                        Text("搜索歌词")
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(songs, key = { it.key }) { song ->
                        SwipeSongRow(onDelete = { viewModel.removeSong(song) }) {
                        SongRow(song.track, state.nowPlaying.libraryKey() == song.key,
                            onSelect = { keyboard?.hide(); viewModel.selectSong(song.track) },
                            trailing = {
                                TextButton(onClick = { viewModel.toggleFavorite(song) },
                                    modifier = Modifier.semantics { contentDescription = if (song.favorite) "取消收藏 ${song.track.track}" else "收藏 ${song.track.track}" }) {
                                    Text(if (song.favorite) "★" else "☆", color = if (song.favorite) LibraryAccent else LibraryMuted, fontSize = 24.sp)
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    }
}

@Composable
private fun SwipeSongRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    var distance by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().background(Color(0xFF5B292D), RoundedCornerShape(18.dp))) {
        Row(Modifier.matchParentSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("删除", color = Color.White, fontSize = 14.sp)
            Text("删除", color = Color.White, fontSize = 14.sp)
        }
        Box(Modifier.offset { IntOffset(distance.roundToInt(), 0) }.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                onDragCancel = { distance = 0f },
                onDragEnd = { if (kotlin.math.abs(distance) > 80.dp.toPx()) onDelete(); distance = 0f }
            )
        }) { content() }
    }
}

@Composable
private fun libraryFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    cursorColor = LibraryAccent, focusedBorderColor = LibraryAccent, unfocusedBorderColor = LibraryOutline,
    focusedContainerColor = LibraryPanel, unfocusedContainerColor = LibraryPanel,
    focusedPlaceholderColor = LibraryMuted, unfocusedPlaceholderColor = LibraryMuted
)

@Composable
private fun LibraryFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp),
        color = if (selected) LibraryAccent else LibraryPanel,
        border = if (selected) null else BorderStroke(1.dp, LibraryOutline)) {
        Text(label, color = if (selected) Color.Black else LibraryMuted, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun SongRow(track: NowPlaying, selected: Boolean, onSelect: () -> Unit, sources: List<String> = emptyList(), trailing: @Composable RowScope.() -> Unit) {
    Surface(color = LibraryPanel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) LibraryAccent.copy(alpha = .6f) else LibraryOutline),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onSelect).padding(start = 18.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(track.track, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist.ifBlank { "未知歌手" }, color = LibraryMuted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                if (track.album.isNotBlank() || track.durationSeconds > 0) {
                    val duration = if (track.durationSeconds > 0) "%d:%02d".format(track.durationSeconds / 60, track.durationSeconds % 60) else ""
                    Text(listOf(track.album, duration).filter { it.isNotBlank() }.joinToString(" · "),
                        color = LibraryMuted.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                if (sources.isNotEmpty()) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        sources.forEach { source ->
                            Text(source, color = LibraryAccent.copy(alpha = .9f), fontSize = 10.sp,
                                modifier = Modifier.background(LibraryAccent.copy(alpha = .08f), RoundedCornerShape(5.dp))
                                    .border(1.dp, LibraryAccent.copy(alpha = .2f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
            trailing()
        }
    }
}
