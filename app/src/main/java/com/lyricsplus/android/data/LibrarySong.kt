package com.lyricsplus.android.data

data class LibrarySong(val key: String, val track: NowPlaying, val source: String, val favorite: Boolean)

fun NowPlaying.libraryKey(): String = listOf(track, artist, album, durationSeconds).joinToString("|")
