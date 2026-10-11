package com.auralis.core.database

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class FolderAccess { Ready, PermissionLost, Unavailable }
data class MusicFolder(val record: LibraryFolderEntity, val access: FolderAccess)

/** Keeps the user's persisted SAF grant. Write access is used only by explicit imports. */
class FolderRepository(context: Context, private val dao: FolderDao) {
    private val resolver = context.applicationContext.contentResolver
    private val refresh = MutableStateFlow(0L)
    private val mutation = Mutex()
    val folders: Flow<List<MusicFolder>> = combine(dao.observeAll(), refresh) { records, _ ->
        records.map { MusicFolder(it, access(Uri.parse(it.uri))) }
    }.flowOn(Dispatchers.IO)

    fun refreshAccess() { refresh.update { it + 1 } }

    private fun hasPermission(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun directoryName(uri: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "This folder is no longer available." }
            check(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) { "Choose a folder to continue." }
            cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "Music folder"
        } ?: error("This folder is temporarily unavailable. Try again.")
    }

    private fun access(uri: Uri): FolderAccess {
        if (!hasPermission(uri)) return FolderAccess.PermissionLost
        return try { directoryName(uri); FolderAccess.Ready }
        catch (_: SecurityException) { FolderAccess.PermissionLost }
        catch (_: Exception) { FolderAccess.Unavailable }
    }

    suspend fun add(uri: Uri) = withContext(Dispatchers.IO) {
        mutation.withLock {
            require(DocumentsContract.isTreeUri(uri)) { "Choose a music folder to continue." }
            val alreadyGranted = hasPermission(uri)
            val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(uri, accessFlags)
            try {
                val name = directoryName(uri)
                val existing = dao.find(uri.toString())
                if (existing == null) dao.insert(LibraryFolderEntity(uri = uri.toString(), displayName = name))
                else dao.rename(uri.toString(), name)
            } catch (error: Exception) {
                if (!alreadyGranted) runCatching { resolver.releasePersistableUriPermission(uri, accessFlags) }
                throw error
            }
            refreshAccess()
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun remove(folder: LibraryFolderEntity) = withContext(Dispatchers.IO) {
        mutation.withLock {
            dao.remove(folder.id)
            // The grant may already have been revoked externally.
            try { resolver.releasePersistableUriPermission(Uri.parse(folder.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: SecurityException) { }
            refreshAccess()
        }
    }
}
