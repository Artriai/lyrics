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
    var removeTarget by remember { mutableStateOf<LibrarySong?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
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
                Text(if (searching) "找一首想唱的歌" else "${state.library.size} 首已保存 · 随时开始提词",
                    color = LibraryMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            TextButton(onClick = { keyboard?.hide(); viewModel.backFromLibrary() }) {
                Text(if (searching) "返回" else "提词 ›", color = LibraryAccent, fontWeight = FontWeight.Bold)
            }
        }

        if (searching) {
            OutlinedTextField(
                value = state.searchQuery, onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text("歌名或歌手") }, singleLine = true,
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
            Text("标签为匹配来源，加载时自动选择合适的歌词", color = LibraryMuted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
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
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.searchResults, key = { it.track.libraryKey() }) { match ->
                    val track = match.track
                    val saved = state.library.firstOrNull { it.key == track.libraryKey() }
                    SongRow(track = track, sources = match.sources, selected = state.nowPlaying.libraryKey() == track.libraryKey(),
                        onSelect = { keyboard?.hide(); viewModel.selectSong(track) },
                        trailing = {
                            Text(if (saved != null) "已保存" else "+", color = LibraryAccent,
                                fontSize = if (saved != null) 12.sp else 26.sp,
                                modifier = Modifier.padding(horizontal = 12.dp))
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
            val songs = state.library.filter { (!favoritesOnly || it.favorite) &&
                (filter.isBlank() || "${it.track.track} ${it.track.artist}".contains(filter, ignoreCase = true)) }
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
                        SongRow(song.track, state.nowPlaying.libraryKey() == song.key,
                            onSelect = { keyboard?.hide(); viewModel.selectSong(song.track) },
                            trailing = {
                                TextButton(onClick = { viewModel.toggleFavorite(song) },
                                    modifier = Modifier.semantics { contentDescription = if (song.favorite) "取消收藏 ${song.track.track}" else "收藏 ${song.track.track}" }) {
                                    Text(if (song.favorite) "★" else "☆", color = if (song.favorite) LibraryAccent else LibraryMuted, fontSize = 24.sp)
                                }
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true },
                                        modifier = Modifier.semantics { contentDescription = "管理 ${song.track.track}" }) {
                                        Text("⋯", color = LibraryMuted, fontSize = 22.sp)
                                    }
                                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                        DropdownMenuItem(text = { Text("删除歌词") }, onClick = { expanded = false; removeTarget = song })
                                    }
                                }
                            })
                    }
                }
            }
        }
    }
    removeTarget?.let { song ->
        AlertDialog(onDismissRequest = { removeTarget = null }, containerColor = LibraryPanel,
            title = { Text("删除这首歌词？", color = Color.White) },
            text = { Text(song.track.track, color = LibraryMuted) },
            confirmButton = { TextButton(onClick = { viewModel.removeSong(song); removeTarget = null }) { Text("删除", color = LibraryAccent) } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消", color = LibraryMuted) } })
    }
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
