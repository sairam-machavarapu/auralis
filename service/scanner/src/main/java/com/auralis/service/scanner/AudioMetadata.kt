package com.auralis.service.scanner

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.auralis.core.database.TrackEntity
import java.io.File
import java.security.MessageDigest

class ArtworkCache(context: Context) {
    private val directory = File(context.cacheDir, "artwork")
    fun file(hash: String) = File(directory, "$hash.art")
    fun contains(hash: String) = file(hash).isFile
    fun save(bytes: ByteArray): String {
        require(bytes.size <= 16 * 1024 * 1024) { "Embedded artwork is larger than 16 MB." }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        directory.mkdirs()
        val target = file(hash)
        if (!target.exists()) {
            val temporary = File.createTempFile("cover-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.renameTo(target) || target.exists()) { "Artwork could not be cached." }
            } finally { temporary.delete() }
        }
        return hash
    }
}

data class ReadMetadata(val track: TrackEntity, val warning: String? = null)
fun interface MetadataReader { fun read(document: AudioDocument, folderId: Long): ReadMetadata }

class AndroidMetadataReader(private val context: Context, private val artwork: ArtworkCache) : MetadataReader {
    override fun read(document: AudioDocument, folderId: Long): ReadMetadata {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, document.uri)
            extractor.setDataSource(context, document.uri, null)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("No supported audio stream was found.")
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: String) = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
            val codec = format.getString(MediaFormat.KEY_MIME)
            val duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            val sampleRate = number(MediaFormat.KEY_SAMPLE_RATE) ?: if (Build.VERSION.SDK_INT >= 31)
                tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
            val bitDepth = (if (Build.VERSION.SDK_INT >= 31) tag(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null)
                ?: when (number(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> null
                }
            var warning: String? = null
            val hash = try { retriever.embeddedPicture?.let(artwork::save) }
                catch (_: Exception) { warning = "The song was indexed, but its artwork could not be cached."; null }
            val track = TrackEntity(contentUri = document.uri.toString(), documentId = document.id, folderId = folderId,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: document.name.substringBeforeLast('.'),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(), filename = document.name,
                year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                trackNo = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull(),
                discNo = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull(),
                durationMs = duration, codec = codec, format = document.name.substringAfterLast('.', "").uppercase(),
                bitrate = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: number(MediaFormat.KEY_BIT_RATE),
                sampleRate = sampleRate, bitDepth = bitDepth, channels = number(MediaFormat.KEY_CHANNEL_COUNT),
                fileSize = document.size, fileMtime = document.modified, artworkHash = hash)
            return ReadMetadata(track, warning)
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }
}
