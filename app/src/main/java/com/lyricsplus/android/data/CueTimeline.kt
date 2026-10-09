package com.lyricsplus.android.data

/** A monotonic, audio-independent clock. Wall-clock changes never move the lyrics. */
data class CueTimeline(
    val positionMs: Long = 0L,
    val anchorMs: Long = 0L,
    val running: Boolean = false,
    val durationMs: Long = 0L
) {
    fun positionAt(nowMs: Long): Long =
        (positionMs + if (running) (nowMs - anchorMs).coerceAtLeast(0L) else 0L)
            .coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun pause(nowMs: Long): CueTimeline = copy(positionMs = positionAt(nowMs), anchorMs = nowMs, running = false)

    fun resume(nowMs: Long): CueTimeline = copy(
        positionMs = if (positionAt(nowMs) >= durationMs) 0L else positionAt(nowMs),
        anchorMs = nowMs,
        running = durationMs > 0L
    )

    fun finishScrub(wasRunning: Boolean, nowMs: Long): CueTimeline =
        if (wasRunning && positionAt(nowMs) < durationMs) resume(nowMs) else pause(nowMs)

    fun seek(position: Long, nowMs: Long): CueTimeline = copy(
        positionMs = position.coerceIn(0L, durationMs.coerceAtLeast(0L)), anchorMs = nowMs
    )
}
