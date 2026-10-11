package com.auralis.feature.downloads

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.auralis.core.database.*
import com.auralis.service.extensions.ExtensionInstaller
import com.auralis.service.extensions.ExtensionPermissionStore
import com.auralis.service.extensions.RegistrySchemas
import com.auralis.service.scanner.AndroidMetadataReader
import com.auralis.service.scanner.ArtworkCache
import com.auralis.service.scanner.AudioDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class ImportedTrack(val uri: Uri, val trackId: Long)

/** Accepts download requests only from an enabled extension with an explicit download grant. */
class AuthorizedProviderAdapter(private val context: Context) {
    fun verify(extensionId: String, sourceUrl: String) {
        val installed = ExtensionInstaller(context, "1.0.0").installed().firstOrNull { it.id == extensionId && it.enabled }
            ?: throw IllegalArgumentException("The source extension is not installed or enabled.")
        val manifest = RegistrySchemas.parseManifest(File(installed.installPath, "manifest.json").readText())
        if ("download" !in manifest.permissions || "download" !in ExtensionPermissionStore(context).granted(extensionId, manifest.permissions))
            throw SecurityException("Download permission is not granted for this extension.")
        val host = java.net.URI(sourceUrl).host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("The download address is invalid.")
        if (manifest.networkHosts.none { allowed -> host == allowed.lowercase(Locale.ROOT) || host.endsWith(".${allowed.lowercase(Locale.ROOT)}") })
            throw SecurityException("The extension is not allowed to download from this host.")
    }
}

class AuthorizedImporter(private val context: Context, private val db: AuralisDatabase = AuralisDatabase.get(context)) {
    private val resolver = context.contentResolver

    suspend fun import(job: DownloadEntity, source: File): ImportedTrack = withContext(Dispatchers.IO) {
        validateAudio(source)
        val folder = db.folders().getAll().firstOrNull { it.enabled && hasWriteGrant(Uri.parse(it.uri)) }
            ?: throw ImportFailure("Choose or reconnect a writable music folder in Settings.")
        val tree = Uri.parse(folder.uri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val finalName = availableName(parent, safeName(job.title), extensionFor(job.mimeType))
        val temporaryName = ".auralis-${UUID.randomUUID()}.part"
        val temporary = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", temporaryName)
            ?: throw ImportFailure("The selected folder could not create a file.")
        var finalUri: Uri? = null
        try {
            resolver.openOutputStream(temporary, "w")?.use { output -> source.inputStream().use { it.copyTo(output, 64 * 1024) } }
                ?: throw ImportFailure("The selected folder could not be written.")
            finalUri = DocumentsContract.renameDocument(resolver, temporary, finalName)
                ?: throw ImportFailure("The selected folder could not finish the import.")
            val id = DocumentsContract.getDocumentId(finalUri)
            val metadata = AndroidMetadataReader(context, ArtworkCache(context)).read(
                AudioDocument(id, finalUri, finalName, job.mimeType, source.length(), System.currentTimeMillis()), folder.id).track
            val trackId = index(metadata)
            ImportedTrack(finalUri, trackId)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, finalUri ?: temporary) }
            throw if (error is ImportFailure) error else ImportFailure(importReason(error))
        }
    }

    private fun hasWriteGrant(uri: Uri) = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

    private fun validateAudio(file: File) {
        if (!file.isFile || file.length() <= 0) throw ImportFailure("The downloaded file is empty.")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (!audio) throw ImportFailure("The downloaded file does not contain supported audio.")
        } catch (error: ImportFailure) { throw error }
        catch (_: Exception) { throw ImportFailure("The downloaded audio file is damaged or unsupported.") }
        finally { runCatching { extractor.release() } }
    }

    private fun existingNames(parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
        }.orEmpty()
    }

    private fun availableName(parent: Uri, base: String, extension: String): String {
        val names = existingNames(parent)
        return ImportRules.availableName(base, extension, names)
    }

    private suspend fun index(track: TrackEntity): Long = db.withTransaction {
        val catalog = db.catalog()
        var albumId: Long? = null
        if (track.album.isNotBlank()) {
            val artist = track.albumArtist.ifBlank { track.artist }
            val old = catalog.album(track.album, artist)
            val artwork = track.artworkHash?.let { ArtworkCache(context).file(it).absolutePath }
            val album = AlbumEntity(title = track.album, albumArtist = artist, year = track.year,
                genre = track.genre.takeIf(String::isNotBlank), artworkHash = track.artworkHash, artworkPath = artwork)
            albumId = old?.id ?: catalog.insertAlbum(album)
            if (old != null && track.artworkHash != null && old.artworkHash != track.artworkHash) catalog.updateAlbum(album.copy(id = old.id))
        }
        if (track.artist.isNotBlank()) catalog.insertArtist(ArtistEntity(name = track.artist, normalizedName = track.artist.lowercase(Locale.ROOT)))
        if (track.genre.isNotBlank()) catalog.insertGenre(GenreEntity(name = track.genre, normalizedName = track.genre.lowercase(Locale.ROOT)))
        db.tracks().upsert(track.copy(albumId = albumId, addedAt = System.currentTimeMillis(), available = true))
    }

    private fun safeName(value: String) = value.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "Auralis download" }.take(100)
    private fun extensionFor(mime: String) = when (mime.lowercase()) {
        "audio/flac" -> "flac"; "audio/ogg", "audio/opus" -> "ogg"; "audio/mp4", "audio/m4a" -> "m4a"; else -> "mp3"
    }
    private fun importReason(error: Exception) = when (error) {
        is SecurityException -> "Folder access was removed. Reconnect it in Settings."
        else -> "The file could not be added to your music folder."
    }
}

class ImportFailure(message: String) : Exception(message)

internal object ImportRules {
    fun availableName(base: String, extension: String, existing: Set<String>): String {
        var candidate = "$base.$extension"
        var number = 2
        while (candidate in existing) candidate = "$base ($number).$extension".also { number++ }
        return candidate
    }
}
