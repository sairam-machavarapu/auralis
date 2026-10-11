package com.auralis.feature.player

import org.junit.Assert.*
import org.junit.Test

class LyricsParserTest {
    @Test fun parsesMultipleLrcTimestampsAndFractions() {
        val result = LyricsParser.parse("[00:01.20][00:03.005]Hello\n[01:02]World", "test")!!
        assertTrue(result.synchronized)
        assertEquals(listOf(1200L, 3005L, 62000L), result.lines.map { it.timeMs })
        assertEquals(listOf("Hello", "Hello", "World"), result.lines.map { it.text })
    }

    @Test fun fallsBackToPlainLyricsAndDropsLrcMetadata() {
        val result = LyricsParser.parse("[ar:Artist]\nFirst line\n\nSecond line", "test")!!
        assertFalse(result.synchronized)
        assertEquals(listOf("First line", "Second line"), result.lines.map { it.text })
    }
}
