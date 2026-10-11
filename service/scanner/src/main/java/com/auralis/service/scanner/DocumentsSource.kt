package com.auralis.service.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class AudioDocument(val id: String, val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long) {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    val audio get() = mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in
        setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aif", "aiff", "alac")
}

interface DocumentSource {
    fun children(tree: Uri, parentId: String): List<AudioDocument>
}

class AndroidDocumentSource(context: Context) : DocumentSource {
    private val resolver = context.applicationContext.contentResolver
    override fun children(tree: Uri, parentId: String): List<AudioDocument> {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(AudioDocument(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), cursor.getString(1) ?: "Untitled",
                        cursor.getString(2) ?: "application/octet-stream", if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        if (cursor.isNull(4)) 0 else cursor.getLong(4)))
                }
            }
        } ?: throw IllegalStateException("The folder provider did not return its files.")
    }
}
