package com.auralis.service.scanner

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.core.database.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AuralisDatabase
    private lateinit var cache: ArtworkCache
    private lateinit var scanner: LibraryScanner
    private var folderId = 0L
    private lateinit var testActivity: android.app.Activity

    @Before fun setup() = runBlocking {
        testActivity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            android.content.Intent(context, ScannerTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        FixtureProvider.count = 1000; FixtureProvider.broken = true; FixtureProvider.inaccessible = false
        FixtureProvider.rootDenied = false; FixtureProvider.changedFirst = false; FixtureProvider.tagged = false
        db = Room.inMemoryDatabaseBuilder(context, AuralisDatabase::class.java).build()
        cache = ArtworkCache(context)
        scanner = LibraryScanner(db, AndroidDocumentSource(context), AndroidMetadataReader(context, cache), cache)
        folderId = db.folders().insert(LibraryFolderEntity(uri = "content://${context.packageName}.scanfixtures/tree/root", displayName = "Scanner fixtures"))
    }
    @After fun close() {
        if (::db.isInitialized) db.close()
        if (::testActivity.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { testActivity.finish() }
    }

    @Test fun thousandDocumentsIndexAndUnreadableFileDoesNotAbort() = runBlocking {
        val progress = mutableListOf<ScanProgress>()
        val started = android.os.SystemClock.elapsedRealtime()
        val result = scanner.scan { progress.add(it) }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        println("SCANNER_1000_MS=$elapsed")
        assertEquals(result.issues.toString(), 1000, result.indexed)
        assertEquals(1, result.errorCount)
        assertEquals(1000, db.tracks().inFolder(folderId).size)
        assertTrue(progress.size > 2)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.checked >= a.checked })
        val first = db.tracks().inFolder(folderId).first { it.documentId == "song-0" }
        assertEquals(44100, first.sampleRate)
        assertEquals(2, first.channels)
        assertEquals(16, first.bitDepth)
        assertTrue(first.durationMs > 0)
        assertEquals("audio/raw", first.codec)
        db.tracks().upsert(first.copy(favorite = true, playCount = 8))
        FixtureProvider.changedFirst = true
        val repeat = scanner.scan { }
        assertEquals(999, repeat.unchanged)
        assertEquals(1, repeat.indexed)
        val retained = db.tracks().get(first.id)!!
        assertTrue(retained.favorite)
        assertEquals(8L, retained.playCount)
        FixtureProvider.count = 999
        FixtureProvider.inaccessible = true
        scanner.scan { }
        assertTrue(db.tracks().inFolder(folderId).first { it.documentId == "song-999" }.available)
        FixtureProvider.inaccessible = false
        scanner.scan { }
        assertFalse(db.tracks().inFolder(folderId).first { it.documentId == "song-999" }.available)
        assertEquals(1000, db.tracks().inFolder(folderId).size)
    }

    @Test fun tagsAndEmbeddedArtworkAreReadAndDeduplicated() = runBlocking {
        FixtureProvider.count = 2; FixtureProvider.broken = false; FixtureProvider.tagged = true
        val source = AndroidDocumentSource(context)
        val tree = Uri.parse("content://${context.packageName}.scanfixtures/tree/root")
        val documents = source.children(tree, "disc")
        assertEquals(2, documents.size)
        AndroidMetadataReader(context, cache).read(documents.first(), folderId)
        val result = scanner.scan { }
        assertEquals(result.issues.toString(), 2, result.indexed)
        assertEquals(0, result.errorCount)
        val rows = db.tracks().inFolder(folderId)
        assertEquals("Scanner Sunrise", rows.first().title)
        assertEquals("Auralis Test Artist", rows.first().artist)
        assertEquals("Scanner Test Album", rows.first().album)
        assertEquals(2026, rows.first().year)
        assertEquals(3, rows.first().trackNo)
        assertNotNull(rows.first().artworkHash)
        assertEquals(rows.first().artworkHash, rows.last().artworkHash)
        assertTrue(cache.contains(rows.first().artworkHash!!))
        assertEquals(2, db.tracks().search("Sunrise").size)
        assertEquals(rows.first().albumId, rows.last().albumId)
    }

    @Test fun cancellationAndFolderFailuresNeverReconcileMissingTracks() = runBlocking {
        FixtureProvider.count = 3; FixtureProvider.broken = false
        assertEquals(3, scanner.scan { }.indexed)
        FixtureProvider.rootDenied = true
        assertEquals(1, scanner.scan { }.errorCount)
        assertTrue(db.tracks().inFolder(folderId).all { it.available })
        FixtureProvider.rootDenied = false
        FixtureProvider.count = 0
        try { scanner.scan { throw CancellationException("Test cancellation") }; fail("Cancellation should propagate") }
        catch (_: CancellationException) { }
        assertTrue(db.tracks().inFolder(folderId).all { it.available })
        db.folders().setEnabled(folderId, false)
        assertEquals(0, scanner.scan { }.checked)
        assertTrue(db.tracks().inFolder(folderId).all { it.available })
    }
}
