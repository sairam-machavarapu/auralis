package com.auralis.feature.player

import androidx.media3.common.Player

internal object PlaybackRules {
    fun nextRepeatMode(current: Int): Int = when (current) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    fun boundedSeek(requestedMs: Long, durationMs: Long): Long =
        requestedMs.coerceIn(0, durationMs.coerceAtLeast(0))

    fun validQueueMove(from: Int, to: Int, size: Int): Boolean =
        from in 0 until size && to in 0 until size && from != to

    fun quality(format: String?, codec: String?, bitDepth: Int?, sampleRate: Int?): String {
        val type = (format ?: codec).orEmpty().uppercase()
        val depth = bitDepth?.takeIf { it > 0 }?.let { "$it-bit" }
        val rate = sampleRate?.takeIf { it > 0 }?.let {
            val khz = it / 1000f
            if (khz % 1f == 0f) "${khz.toInt()} kHz" else "${"%.1f".format(khz)} kHz"
        }
        return listOfNotNull(type.ifBlank { null }, depth, rate).joinToString("  ")
    }
}
