package com.auralis.service.scanner

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Private, read-only virtual documents for repeatable debug benchmarks; absent from release. */
class FixtureProvider : DocumentsProvider() {
    companion object {
        @Volatile var count = 1000
        @Volatile var broken = true
        @Volatile var inaccessible = false
        @Volatile var rootDenied = false
        @Volatile var changedFirst = false
        @Volatile var tagged = false
    }
    private lateinit var wav: File
    private lateinit var mp3: File
    override fun onCreate(): Boolean {
        wav = File(context!!.cacheDir, "scan-fixture.wav")
        context!!.assets.open("scan-fixture.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        mp3 = File(context!!.cacheDir, "scan-tagged.mp3")
        context!!.assets.open("scan-tagged.mp3").use { input -> mp3.outputStream().use { input.copyTo(it) } }
        return true
    }
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    private fun row(cursor: MatrixCursor, id: String) {
        val directory = id == "root" || id == "disc" || id == "blocked"
        val file = if (tagged) mp3 else wav
        val values = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to if (directory) id else "$id.${if (tagged) "mp3" else "wav"}",
            Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else if (tagged) "audio/mpeg" else "audio/wav",
            Document.COLUMN_SIZE to if (directory) 0L else file.length(),
            Document.COLUMN_LAST_MODIFIED to if (changedFirst && id == "song-0") 2000L else 1000L, Document.COLUMN_FLAGS to 0)
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).also { row(it, documentId) }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == "root" || (parentDocumentId == "disc" && (documentId.startsWith("song-") || documentId == "broken"))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (rootDenied || parentDocumentId == "blocked") throw FileNotFoundException("Fixture directory unavailable")
        return MatrixCursor(projection ?: columns).also {
            if (parentDocumentId == "root") { row(it, "disc"); if (inaccessible) row(it, "blocked") }
            else { repeat(count) { i -> row(it, "song-$i") }; if (broken) row(it, "broken") }
        }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r") { "Fixtures are read only" }
        if (documentId == "broken") throw FileNotFoundException("Fixture unreadable audio")
        return ParcelFileDescriptor.open(if (tagged) mp3 else wav, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
