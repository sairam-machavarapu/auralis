package com.auralis.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, AuralisDatabase::class.java)

    @Test fun migrationPreservesLibraryAndBuildsSearchIndex() = runBlocking {
        context.deleteDatabase("migration-test")
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO library_folders VALUES(7, 'content://test/tree/music', 'My music', 0, 100, 200)")
            execSQL("INSERT INTO tracks(id, content_uri, document_id, folder_id, title, artist, album, album_artist, genre, filename, duration_ms, file_size, file_mtime, available, added_at, play_count, favorite) VALUES(42, 'content://test/song', 'song', 7, 'Café Sunrise', 'Artist', 'Morning', 'Ensemble', 'Jazz', 'sunrise.flac', 180000, 10000, 200, 1, 100, 9, 1)")
            execSQL("INSERT INTO playlists VALUES(5, 'Saved mix', 'Keep me', 100, 200)")
            execSQL("INSERT INTO playlist_tracks VALUES(5, 42, 0)")
            execSQL("INSERT INTO play_history VALUES(1, 42, 100, 200, 170000)")
            execSQL("INSERT INTO queue VALUES(1, 42, 0, 100)")
            close()
        }
        helper.runMigrationsAndValidate("migration-test", 4, true, AuralisDatabase.MIGRATION_1_2, AuralisDatabase.MIGRATION_2_3, AuralisDatabase.MIGRATION_3_4).close()
        val db = Room.databaseBuilder(context, AuralisDatabase::class.java, "migration-test")
            .addMigrations(AuralisDatabase.MIGRATION_1_2, AuralisDatabase.MIGRATION_2_3, AuralisDatabase.MIGRATION_3_4).build()
        try {
            val track = db.tracks().get(42)!!
            assertTrue(track.favorite)
            assertEquals(9L, track.playCount)
            assertEquals(180000L, track.durationMs)
            assertFalse(db.folders().getAll().single().enabled)
            assertEquals(200L, db.folders().getAll().single().lastScanAt)
            for (term in listOf("cafe", "Artist", "Morning", "Ensemble", "Jazz", "flac")) {
                assertEquals("Search should find $term", 42L, db.tracks().search(term).single().id)
            }
            for (table in listOf("playlist_tracks", "play_history", "queue")) {
                db.openHelper.readableDatabase.query("SELECT track_id FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0))
                }
            }
            db.tracks().upsert(track.copy(title = "Evening"))
            assertTrue(db.tracks().search("Sunrise").isNotEmpty()) // filename still matches
            assertTrue(db.tracks().search("cafe").isEmpty())
            assertEquals(42L, db.tracks().search("Evening").single().id)
        } finally { db.close(); context.deleteDatabase("migration-test") }
    }

    @Test fun migrationThreeToFourPreservesDownloadAndAddsResumeFields() = runBlocking {
        val name = "download-migration-test"
        context.deleteDatabase(name)
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO downloads(id, extension_id, source_id, title, target_uri, status, progress, bytes_done, bytes_total, error, created_at, completed_at) VALUES(9, NULL, 'https://example.test/song.mp3', 'Saved song', NULL, 'paused', 0.5, 512, 1024, NULL, 100, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, AuralisDatabase.MIGRATION_3_4).close()
        val db = Room.databaseBuilder(context, AuralisDatabase::class.java, name)
            .addMigrations(AuralisDatabase.MIGRATION_3_4).build()
        try {
            val job = db.downloads().get(9)!!
            assertEquals("Saved song", job.title)
            assertEquals(512L, job.bytesDone)
            assertNull(job.tempPath)
            assertEquals("audio/mpeg", job.mimeType)
            assertFalse(job.wifiOnly)
            assertEquals(0, job.retryCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun freshDatabaseSynchronizesSearchAndRemovingFolderKeepsHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AuralisDatabase::class.java).build()
        try {
            val folderId = db.folders().insert(LibraryFolderEntity(uri = "content://test/tree/music", displayName = "Music"))
            assertEquals(-1L, db.folders().insert(LibraryFolderEntity(uri = "content://test/tree/music", displayName = "Duplicate")))
            val id = db.tracks().upsert(TrackEntity(contentUri = "content://test/one", documentId = "one", folderId = folderId,
                title = "Starlight", filename = "one.flac", favorite = true, playCount = 3))
            assertEquals(id, db.tracks().search("starlight").single().id)
            db.folders().remove(folderId)
            val retained = db.tracks().get(id)!!
            assertNull(retained.folderId)
            assertFalse(retained.available)
            assertTrue(retained.favorite)
            assertEquals(3L, retained.playCount)
            assertTrue(db.tracks().search("starlight").isEmpty())
            db.tracks().delete(id)
            db.openHelper.readableDatabase.query("SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH 'starlight'").use {
                assertFalse(it.moveToFirst())
            }
        } finally { db.close() }
    }

    @Test fun folderSelectionAndDisabledStateSurviveDatabaseReopen() = runBlocking {
        val name = "folder-restart-test"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, AuralisDatabase::class.java, name).build()
        try {
            val id = db.folders().insert(LibraryFolderEntity(uri = "content://test/tree/music", displayName = "Music"))
            db.folders().setEnabled(id, false)
            db.close()
            db = Room.databaseBuilder(context, AuralisDatabase::class.java, name).build()
            assertEquals(id, db.folders().getAll().single().id)
            assertEquals("content://test/tree/music", db.folders().getAll().single().uri)
            assertFalse(db.folders().getAll().single().enabled)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun missingPersistedPermissionIsReportedAndFolderCanBeRemoved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AuralisDatabase::class.java).build()
        try {
            db.folders().insert(LibraryFolderEntity(uri = "content://unavailable.test/tree/music", displayName = "Disconnected"))
            val repository = FolderRepository(context, db.folders())
            val folder = repository.folders.first().single()
            assertEquals(FolderAccess.PermissionLost, folder.access)
            repository.remove(folder.record)
            assertTrue(db.folders().getAll().isEmpty())
        } finally { db.close() }
    }

    @Test fun catalogViewsAndPrefixSearchStayFastAtTwentyThousandTracks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AuralisDatabase::class.java).build()
        try {
            val folder = db.folders().insert(LibraryFolderEntity(uri = "content://test/tree/large", displayName = "Large"))
            val album = db.catalog().insertAlbum(AlbumEntity(title = "Test Album", albumArtist = "Test Artist", genre = "Jazz"))
            db.withTransaction {
                repeat(20_000) { index ->
                    db.tracks().upsert(TrackEntity(contentUri = "content://test/$index", documentId = "$index", folderId = folder,
                        title = if (index == 731) "Needle Sunrise" else "Library Track $index", artist = "Test Artist",
                        album = "Test Album", albumId = album, albumArtist = "Test Artist", genre = "Jazz",
                        filename = "track-$index.flac"))
                }
            }
            assertEquals(20_000, db.tracks().observeAvailable().first().size)
            assertEquals(20_000, db.catalog().observeAlbums().first().single().trackCount)
            assertEquals(20_000, db.catalog().observeArtists().first().single().trackCount)
            assertEquals(20_000, db.catalog().observeGenres().first().single().trackCount)
            db.tracks().observeSearch("\"Library\"*").first() // warm Room and SQLite
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            val result = db.tracks().observeSearch("\"Needle\"*").first()
            val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            assertEquals(731, result.single().documentId.toInt())
            assertTrue("Prefix FTS took $elapsedMs ms", elapsedMs < 100.0)
        } finally { db.close() }
    }
}
