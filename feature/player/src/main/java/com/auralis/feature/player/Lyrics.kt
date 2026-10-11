package com.auralis.feature.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.auralis.core.database.TrackEntity

data class LyricLine(val timeMs: Long?, val text: String)
data class LyricsDocument(val lines: List<LyricLine>, val synchronized: Boolean, val source: String)

object LyricsParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val metadata = Regex("^\\[(ar|al|ti|au|by|offset|re|ve):", RegexOption.IGNORE_CASE)

    fun parse(text: String, source: String): LyricsDocument? {
        val cleaned = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val timed = buildList {
            cleaned.lineSequence().forEach { raw ->
                val matches = stamp.findAll(raw).toList()
                if (matches.isEmpty()) return@forEach
                val words = stamp.replace(raw, "").trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) { 1 -> fraction.toLongOrNull()?.times(100); 2 -> fraction.toLongOrNull()?.times(10); 3 -> fraction.toLongOrNull(); else -> 0 } ?: 0
                    add(LyricLine(minutes * 60_000 + seconds * 1_000 + millis, words))
                }
            }
        }.sortedBy { it.timeMs }
        if (timed.isNotEmpty()) return LyricsDocument(timed, true, source)
        val plain = cleaned.lineSequence().map(String::trim)
            .filter { it.isNotBlank() && !metadata.containsMatchIn(it) }
            .map { LyricLine(null, it) }.toList()
        return plain.takeIf { it.isNotEmpty() }?.let { LyricsDocument(it, false, source) }
    }
}

class LocalLyricsLoader(private val context: Context) {
    fun load(track: TrackEntity): LyricsDocument? {
        readSidecar(track)?.let { return LyricsParser.parse(it, "LRC file") }
        readEmbeddedId3(track)?.let { return LyricsParser.parse(it, "Embedded lyrics") }
        return null
    }

    private fun readSidecar(track: TrackEntity): String? = runCatching {
        val uri = Uri.parse(track.contentUri)
        if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
        val id = track.documentId
        val separator = id.lastIndexOfAny(charArrayOf('/', ':'))
        if (separator < 0) return@runCatching null
        val base = track.filename.substringBeforeLast('.', track.filename)
        val siblingId = id.substring(0, separator + 1) + "$base.lrc"
        val sibling = DocumentsContract.buildDocumentUriUsingTree(uri, siblingId)
        context.contentResolver.openInputStream(sibling)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val chars = CharArray(1_048_577)
            val count = reader.read(chars)
            if (count in 1..1_048_576) String(chars, 0, count) else null
        }
    }.getOrNull()

    /** Reads the standard ID3v2 USLT frame used for unsynchronized embedded lyrics in MP3 files. */
    private fun readEmbeddedId3(track: TrackEntity): String? = runCatching {
        if (!track.filename.endsWith(".mp3", true)) return@runCatching null
        val bytes = context.contentResolver.openInputStream(Uri.parse(track.contentUri))?.use { input ->
            val header = ByteArray(10)
            if (input.read(header) != 10 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return@use null
            val size = syncSafe(header, 6).coerceAtMost(2 * 1024 * 1024)
            val body = ByteArray(size)
            var read = 0
            while (read < size) { val n = input.read(body, read, size - read); if (n < 0) break; read += n }
            if (read == size) header + body else null
        } ?: return@runCatching null
        findUslt(bytes)
    }.getOrNull()

    private fun findUslt(tag: ByteArray): String? {
        val version = tag[3].toInt()
        var offset = 10
        while (offset + 10 <= tag.size) {
            val id = String(tag, offset, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            val size = if (version == 4) syncSafe(tag, offset + 4) else
                ((tag[offset + 4].toInt() and 255) shl 24) or ((tag[offset + 5].toInt() and 255) shl 16) or
                    ((tag[offset + 6].toInt() and 255) shl 8) or (tag[offset + 7].toInt() and 255)
            if (size <= 0 || offset + 10 + size > tag.size) break
            if (id == "USLT") {
                val frame = tag.copyOfRange(offset + 10, offset + 10 + size)
                if (frame.size < 5) return null
                val charset = when (frame[0].toInt()) { 1, 2 -> Charsets.UTF_16; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                val payload = frame.copyOfRange(4, frame.size)
                val separator = if (charset == Charsets.UTF_16) findDoubleZero(payload) else payload.indexOf(0)
                val start = if (separator < 0) 0 else separator + if (charset == Charsets.UTF_16) 2 else 1
                return String(payload, start.coerceAtMost(payload.size), payload.size - start.coerceAtMost(payload.size), charset).trim('\u0000', ' ')
            }
            offset += 10 + size
        }
        return null
    }

    private fun findDoubleZero(bytes: ByteArray): Int {
        for (i in 0 until bytes.lastIndex) if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
        return -1
    }
    private fun syncSafe(bytes: ByteArray, start: Int) = ((bytes[start].toInt() and 0x7f) shl 21) or
        ((bytes[start + 1].toInt() and 0x7f) shl 14) or ((bytes[start + 2].toInt() and 0x7f) shl 7) or
        (bytes[start + 3].toInt() and 0x7f)
}
