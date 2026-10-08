package com.lyricsplus.android.data

import org.junit.Assert.*
import org.junit.Test

class CueTimelineTest {
    @Test fun pauseResumeExcludesTimeSpentPaused() {
        val playing = CueTimeline(durationMs = 120_000).resume(1000)
        val paused = playing.pause(6000)
        assertEquals(5000L, paused.positionAt(30_000))
        assertEquals(8000L, paused.resume(30_000).positionAt(33_000))
    }

    @Test fun seekingDuringPlaybackKeepsTheNewAnchor() {
        val playing = CueTimeline(durationMs = 120_000).resume(1000)
        assertEquals(42_000L, playing.seek(40_000, 9000).positionAt(11_000))
        assertEquals(0L, playing.seek(-500, 9000).positionAt(9000))
        assertEquals(120_000L, playing.seek(200_000, 9000).positionAt(9000))
    }

    @Test fun pausedSeekDoesNotStartPlayback() {
        val paused = CueTimeline(durationMs = 60_000).seek(15_000, 1000)
        assertFalse(paused.running)
        assertEquals(15_000L, paused.positionAt(90_000))
    }

    @Test fun scrubbingToTheEndStopsWithoutRestarting() {
        val atEnd = CueTimeline(durationMs = 10_000).seek(10_000, 1000).finishScrub(true, 1000)
        assertFalse(atEnd.running)
        assertEquals(10_000L, atEnd.positionAt(3000))
    }

    @Test fun scrubbingRestoresThePreviousPlayingState() {
        val middle = CueTimeline(durationMs = 10_000).seek(5000, 1000)
        assertTrue(middle.finishScrub(true, 1000).running)
        assertFalse(middle.finishScrub(false, 1000).running)
    }

    @Test fun endClampsAndPlayRestarts() {
        val finished = CueTimeline(durationMs = 10_000).resume(1000)
        assertEquals(10_000L, finished.positionAt(99_000))
        assertEquals(0L, finished.pause(99_000).resume(100_000).positionAt(100_000))
    }
}
