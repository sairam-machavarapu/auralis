package com.auralis.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

data class AlbumSummary(
    val id: Long, val title: String, @ColumnInfo(name = "album_artist") val albumArtist: String,
    val year: Int?, @ColumnInfo(name = "artwork_path") val artworkPath: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)
data class ArtistSummary(val name: String, @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "album_count") val albumCount: Int, @ColumnInfo(name = "artwork_path") val artworkPath: String?)
data class GenreSummary(val name: String, @ColumnInfo(name = "track_count") val trackCount: Int)

@Dao
interface FolderDao {
    @Query("SELECT * FROM library_folders ORDER BY created_at, id") fun observeAll(): Flow<List<LibraryFolderEntity>>
    @Query("SELECT * FROM library_folders ORDER BY created_at, id") suspend fun getAll(): List<LibraryFolderEntity>
    @Query("SELECT * FROM library_folders WHERE uri = :uri") suspend fun find(uri: String): LibraryFolderEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(folder: LibraryFolderEntity): Long
    @Query("UPDATE library_folders SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: Long, enabled: Boolean)
    @Query("UPDATE library_folders SET display_name = :name WHERE uri = :uri") suspend fun rename(uri: String, name: String)
    @Query("UPDATE tracks SET available = 0 WHERE folder_id = :id") suspend fun markTracksUnavailable(id: Long)
    @Query("DELETE FROM library_folders WHERE id = :id") suspend fun delete(id: Long)
    @Transaction suspend fun remove(id: Long) { markTracksUnavailable(id); delete(id) }
    @Query("SELECT * FROM library_folders WHERE id = :id") suspend fun get(id: Long): LibraryFolderEntity?
    @Query("UPDATE library_folders SET last_scan_at = :time WHERE id = :id AND enabled = 1") suspend fun scanned(id: Long, time: Long)
}

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(track: TrackEntity): Long
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: Long): TrackEntity?
    @Query("SELECT * FROM tracks WHERE content_uri = :uri LIMIT 1") suspend fun findContentUri(uri: String): TrackEntity?
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.id = tracks_fts.rowid WHERE tracks_fts MATCH :query AND available = 1 ORDER BY title COLLATE NOCASE")
    suspend fun search(query: String): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE folder_id = :folderId") suspend fun inFolder(folderId: Long): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE document_id = :documentId AND substr(content_uri, 1, length(:providerPrefix)) = :providerPrefix LIMIT 1")
    suspend fun findDocument(documentId: String, providerPrefix: String): TrackEntity?
    @Query("UPDATE tracks SET available = 0 WHERE id IN (:ids)") suspend fun markUnavailable(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1")
    fun observeAvailableCount(): Flow<Int>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 ORDER BY tracks.title COLLATE NOCASE, tracks.id")
    fun observeAvailable(): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.album_id = :albumId ORDER BY COALESCE(tracks.disc_no, 1), COALESCE(tracks.track_no, 9999), tracks.title COLLATE NOCASE, tracks.id")
    fun observeAlbumTracks(albumId: Long): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.artist = :artist COLLATE NOCASE ORDER BY tracks.album COLLATE NOCASE, COALESCE(tracks.disc_no, 1), COALESCE(tracks.track_no, 9999), tracks.title COLLATE NOCASE")
    fun observeArtistTracks(artist: String): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.genre = :genre COLLATE NOCASE ORDER BY tracks.title COLLATE NOCASE")
    fun observeGenreTracks(genre: String): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.id = tracks_fts.rowid INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks_fts MATCH :query AND tracks.available = 1 AND library_folders.enabled = 1 ORDER BY tracks.title COLLATE NOCASE LIMIT 250")
    fun observeSearch(query: String): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 ORDER BY tracks.added_at DESC LIMIT 12")
    fun observeRecentlyAdded(): Flow<List<TrackEntity>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.play_count > 0 ORDER BY tracks.play_count DESC, tracks.last_played_at DESC LIMIT 12")
    fun observeMostPlayed(): Flow<List<TrackEntity>>
    @Query("UPDATE tracks SET favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: Long, favorite: Boolean)
    @Query("SELECT favorite FROM tracks WHERE id = :id") suspend fun isFavorite(id: Long): Boolean?
    @Query("UPDATE tracks SET last_played_at = :time, play_count = play_count + 1 WHERE id = :id") suspend fun recordPlayed(id: Long, time: Long)
}

@Dao
interface PlaybackDao {
    @Insert suspend fun insertHistory(entry: PlayHistoryEntity): Long
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updated_at DESC") fun observeAll(): Flow<List<PlaylistEntity>>
    @Insert suspend fun insert(playlist: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE id = :id") suspend fun rename(id: Long, name: String, updatedAt: Long)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC") fun observeAll(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun get(id: Long): DownloadEntity?
    @Insert suspend fun insert(item: DownloadEntity): Long
    @Update suspend fun update(item: DownloadEntity)
    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id") suspend fun setStatus(id: Long, status: String, error: String? = null)
    @Query("UPDATE downloads SET status = :status, progress = :progress, bytes_done = :done, bytes_total = :total, temp_path = :tempPath, error = NULL WHERE id = :id")
    suspend fun progress(id: Long, status: String, progress: Float, done: Long, total: Long?, tempPath: String?)
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM albums WHERE title = :title AND album_artist = :artist LIMIT 1") suspend fun album(title: String, artist: String): AlbumEntity?
    @Insert suspend fun insertAlbum(album: AlbumEntity): Long
    @Update suspend fun updateAlbum(album: AlbumEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertArtist(artist: ArtistEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGenre(genre: GenreEntity): Long
    @Query("SELECT albums.id, albums.title, albums.album_artist, albums.year, albums.artwork_path, COUNT(tracks.id) AS track_count FROM albums JOIN tracks ON tracks.album_id = albums.id JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 GROUP BY albums.id ORDER BY albums.title COLLATE NOCASE, albums.id")
    fun observeAlbums(): Flow<List<AlbumSummary>>
    @Query("SELECT tracks.artist AS name, COUNT(tracks.id) AS track_count, COUNT(DISTINCT tracks.album_id) AS album_count, MAX(albums.artwork_path) AS artwork_path FROM tracks JOIN library_folders ON tracks.folder_id = library_folders.id LEFT JOIN albums ON tracks.album_id = albums.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.artist != '' GROUP BY tracks.artist COLLATE NOCASE ORDER BY tracks.artist COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistSummary>>
    @Query("SELECT tracks.genre AS name, COUNT(tracks.id) AS track_count FROM tracks JOIN library_folders ON tracks.folder_id = library_folders.id WHERE tracks.available = 1 AND library_folders.enabled = 1 AND tracks.genre != '' GROUP BY tracks.genre COLLATE NOCASE ORDER BY tracks.genre COLLATE NOCASE")
    fun observeGenres(): Flow<List<GenreSummary>>
}

@Database(entities = [LibraryFolderEntity::class, TrackEntity::class, AlbumEntity::class,
    ArtistEntity::class, GenreEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class,
    PlayHistoryEntity::class, QueueEntity::class, RepositoryEntity::class, ExtensionEntity::class,
    DownloadEntity::class, TrackFtsEntity::class], version = 4, exportSchema = true)
abstract class AuralisDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun tracks(): TrackDao
    abstract fun catalog(): CatalogDao
    abstract fun playback(): PlaybackDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_document_id` ON `tracks` (`document_id`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN temp_path TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'audio/mpeg'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN wifi_only INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** Version 1 is the library baseline; version 2 adds the synchronized search index. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `tracks_fts` USING FTS4(`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `album_artist` TEXT NOT NULL, `genre` TEXT NOT NULL, `filename` TEXT NOT NULL, tokenize=unicode61, content=`tracks`)")
                db.execSQL("INSERT INTO tracks_fts(tracks_fts) VALUES('rebuild')")
                // Keep the migration self-contained, including when run by MigrationTestHelper.
                // Room's onPostMigrate uses the same names with IF NOT EXISTS.
                for (event in listOf("UPDATE", "DELETE")) {
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_tracks_fts_BEFORE_$event BEFORE $event ON tracks BEGIN DELETE FROM tracks_fts WHERE docid=OLD.rowid; END")
                }
                for (event in listOf("UPDATE", "INSERT")) {
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_tracks_fts_AFTER_$event AFTER $event ON tracks BEGIN INSERT INTO tracks_fts(docid,title,artist,album,album_artist,genre,filename) VALUES(NEW.rowid,NEW.title,NEW.artist,NEW.album,NEW.album_artist,NEW.genre,NEW.filename); END")
                }
            }
        }
        @Volatile private var instance: AuralisDatabase? = null
        fun get(context: Context): AuralisDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AuralisDatabase::class.java, "auralis.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
