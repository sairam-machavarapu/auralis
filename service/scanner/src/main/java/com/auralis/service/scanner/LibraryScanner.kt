package com.auralis.service.scanner

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.auralis.core.database.*
import kotlinx.coroutines.*
import java.util.Locale

data class ScanIssue(val file: String, val message: String)
data class ScanProgress(val checked: Int = 0, val indexed: Int = 0, val unchanged: Int = 0,
    val errorCount: Int = 0, val folder: String = "", val stage: String = "Scanning folders",
    val issues: List<ScanIssue> = emptyList())

/** Blocking provider/metadata work is isolated to IO; writes are bounded transactions. */
class LibraryScanner(private val db: AuralisDatabase, private val source: DocumentSource,
    private val reader: MetadataReader, private val artwork: ArtworkCache) {
    suspend fun scan(onProgress: suspend (ScanProgress) -> Unit): ScanProgress = withContext(Dispatchers.IO) {
        var progress = ScanProgress()
        val issues = mutableListOf<ScanIssue>()
        var lastEmit = 0L
        suspend fun emit(force: Boolean = false) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (force || now - lastEmit >= 250) { lastEmit = now; onProgress(progress.copy(issues = issues.toList())) }
        }
        fun issue(name: String, message: String) {
            progress = progress.copy(errorCount = progress.errorCount + 1)
            if (issues.size < 100) issues.add(ScanIssue(name.take(160), message.take(240)))
        }
        val folders = db.folders().getAll().filter { it.enabled }
        for (folder in folders) {
            ensureActive()
            progress = progress.copy(folder = folder.displayName)
            emit(true)
            val tree = Uri.parse(folder.uri)
            val oldTracks = db.tracks().inFolder(folder.id).associateBy { it.documentId }
            val seen = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            val directories = ArrayDeque<String>()
            directories.add(DocumentsContract.getTreeDocumentId(tree))
            val pending = mutableListOf<TrackEntity>()
            var complete = true
            suspend fun flush() {
                if (pending.isEmpty()) return
                db.withTransaction {
                    if (db.folders().get(folder.id)?.enabled != true) return@withTransaction
                    for (metadata in pending) {
                        val current = db.tracks().findDocument(metadata.documentId, "content://${tree.authority}/")
                        val catalog = db.catalog()
                        var albumId: Long? = null
                        if (metadata.album.isNotBlank()) {
                            val artist = metadata.albumArtist.ifBlank { metadata.artist }
                            val existingAlbum = catalog.album(metadata.album, artist)
                            val album = AlbumEntity(title = metadata.album, albumArtist = artist, year = metadata.year,
                                genre = metadata.genre.takeIf { it.isNotBlank() }, artworkHash = metadata.artworkHash,
                                artworkPath = metadata.artworkHash?.let { artwork.file(it).absolutePath })
                            albumId = existingAlbum?.id ?: catalog.insertAlbum(album)
                            if (existingAlbum != null && metadata.artworkHash != null && existingAlbum.artworkHash != metadata.artworkHash)
                                catalog.updateAlbum(album.copy(id = existingAlbum.id))
                        }
                        if (metadata.artist.isNotBlank()) catalog.insertArtist(ArtistEntity(name = metadata.artist, normalizedName = metadata.artist.lowercase(Locale.ROOT)))
                        if (metadata.genre.isNotBlank()) catalog.insertGenre(GenreEntity(name = metadata.genre, normalizedName = metadata.genre.lowercase(Locale.ROOT)))
                        db.tracks().upsert(metadata.copy(id = current?.id ?: 0, albumId = albumId,
                            addedAt = current?.addedAt ?: metadata.addedAt, favorite = current?.favorite ?: false,
                            playCount = current?.playCount ?: 0, lastPlayedAt = current?.lastPlayedAt))
                    }
                }
                pending.clear()
            }
            while (directories.isNotEmpty()) {
                ensureActive()
                if (db.folders().get(folder.id)?.enabled != true) { complete = false; break }
                val parent = directories.removeFirst()
                if (!visited.add(parent)) continue
                val children = try { source.children(tree, parent) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    complete = false
                    issue(folder.displayName, if (error is SecurityException) "Folder access was removed. Reconnect it in Folders." else "A folder could not be read. Existing songs were kept.")
                    emit(true); continue
                }
                for (document in children) {
                    ensureActive()
                    if (document.directory) { if (document.id !in visited) directories.add(document.id); continue }
                    if (!document.audio || !seen.add(document.id)) continue
                    progress = progress.copy(checked = progress.checked + 1)
                    val old = oldTracks[document.id]
                    if (old != null && old.available && document.modified > 0 && document.size > 0 &&
                        old.fileMtime == document.modified && old.fileSize == document.size && old.filename == document.name &&
                        (old.artworkHash?.let(artwork::contains) != false)) {
                        progress = progress.copy(unchanged = progress.unchanged + 1)
                    } else {
                        try {
                            val result = reader.read(document, folder.id)
                            pending.add(result.track)
                            progress = progress.copy(indexed = progress.indexed + 1)
                            result.warning?.let { issue(document.name, it) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            issue(document.name, if (error is SecurityException) "Read access was denied." else "Could not read audio metadata. The file may be damaged or unsupported.")
                            if (old != null) db.tracks().markUnavailable(listOf(old.id))
                        }
                    }
                    if (pending.size >= 50) flush()
                    emit()
                }
                flush()
            }
            flush()
            if (complete) db.withTransaction {
                if (db.folders().get(folder.id)?.enabled == true) {
                    val missing = oldTracks.values.filter { it.documentId !in seen }.map { it.id }
                    missing.chunked(200).forEach { db.tracks().markUnavailable(it) }
                    db.folders().scanned(folder.id, System.currentTimeMillis())
                }
            }
        }
        progress = progress.copy(folder = "", stage = if (progress.errorCount == 0) "Scan complete" else "Scan complete with issues", issues = issues.toList())
        emit(true)
        progress
    }
}
