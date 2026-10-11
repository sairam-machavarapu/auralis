package com.auralis.feature.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackRulesTest {
    @Test fun repeatCyclesOffAllOneOff() {
        assertEquals(Player.REPEAT_MODE_ALL, PlaybackRules.nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, PlaybackRules.nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackRules.nextRepeatMode(Player.REPEAT_MODE_ONE))
    }

    @Test fun seekIsAlwaysInsidePlayableDuration() {
        assertEquals(0L, PlaybackRules.boundedSeek(-5, 10_000))
        assertEquals(4_000L, PlaybackRules.boundedSeek(4_000, 10_000))
        assertEquals(10_000L, PlaybackRules.boundedSeek(20_000, 10_000))
        assertEquals(0L, PlaybackRules.boundedSeek(500, -1))
    }

    @Test fun queueMovesRejectInvalidAndNoOpIndexes() {
        assertTrue(PlaybackRules.validQueueMove(0, 2, 3))
        assertFalse(PlaybackRules.validQueueMove(1, 1, 3))
        assertFalse(PlaybackRules.validQueueMove(-1, 1, 3))
        assertFalse(PlaybackRules.validQueueMove(0, 3, 3))
    }

    @Test fun qualityLabelHandlesCommonAndHighResolutionTracks() {
        assertEquals("M4A  44.1 kHz", PlaybackRules.quality("m4a", null, null, 44_100))
        assertEquals("FLAC  24-bit  96 kHz", PlaybackRules.quality("flac", null, 24, 96_000))
        assertEquals("", PlaybackRules.quality(null, null, null, null))
    }
}
