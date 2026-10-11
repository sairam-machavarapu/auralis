package com.auralis.core.database

import androidx.room.*

@Entity(tableName = "library_folders", indices = [Index(value = ["uri"], unique = true)])
data class LibraryFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long? = null,
)

@Entity(tableName = "albums", indices = [Index(value = ["title", "album_artist"])])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String,
    @ColumnInfo(name = "album_artist") val albumArtist: String = "",
    val year: Int? = null, val genre: String? = null,
    @ColumnInfo(name = "artwork_path") val artworkPath: String? = null,
    @ColumnInfo(name = "artwork_hash") val artworkHash: String? = null,
)

@Entity(tableName = "tracks", indices = [Index(value = ["content_uri"], unique = true), Index("document_id"), Index("album_id"), Index("folder_id"), Index("artist"), Index("genre")],
    foreignKeys = [ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["album_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = LibraryFolderEntity::class, parentColumns = ["id"], childColumns = ["folder_id"], onDelete = ForeignKey.SET_NULL)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    val title: String, val artist: String = "", val album: String = "",
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String = "",
    val genre: String = "", val filename: String,
    val year: Int? = null,
    @ColumnInfo(name = "track_no") val trackNo: Int? = null,
    @ColumnInfo(name = "disc_no") val discNo: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    val codec: String? = null, val format: String? = null, val bitrate: Int? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    val channels: Int? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "file_mtime") val fileMtime: Long = 0,
    @ColumnInfo(name = "artwork_hash") val artworkHash: String? = null,
    val available: Boolean = true,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long? = null,
    @ColumnInfo(name = "play_count") val playCount: Long = 0,
    val favorite: Boolean = false,
)

@Fts4(contentEntity = TrackEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tracks_fts")
data class TrackFtsEntity(
    val title: String, val artist: String, val album: String,
    @ColumnInfo(name = "album_artist") val albumArtist: String,
    val genre: String, val filename: String,
)

@Entity(tableName = "artists", indices = [Index(value = ["normalized_name"], unique = true)])
data class ArtistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String)

@Entity(tableName = "genres", indices = [Index(value = ["normalized_name"], unique = true)])
data class GenreEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String)

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val description: String = "", @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlist_id", "position"], indices = [Index("track_id")],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)])
data class PlaylistTrackEntity(@ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long, val position: Int)

@Entity(tableName = "play_history", indices = [Index("track_id")], foreignKeys = [ForeignKey(entity = TrackEntity::class,
    parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)])
data class PlayHistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long, @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null, @ColumnInfo(name = "position_ms") val positionMs: Long = 0)

@Entity(tableName = "queue", indices = [Index("track_id"), Index(value = ["position"], unique = true)],
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)])
data class QueueEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long, val position: Int, @ColumnInfo(name = "added_at") val addedAt: Long)

@Entity(tableName = "repositories", indices = [Index(value = ["url"], unique = true)])
data class RepositoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val url: String,
    val enabled: Boolean = true, val trusted: Boolean = false,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null, @ColumnInfo(name = "last_error") val lastError: String? = null)

@Entity(tableName = "extensions", indices = [Index("repository_id")], foreignKeys = [ForeignKey(entity = RepositoryEntity::class,
    parentColumns = ["id"], childColumns = ["repository_id"], onDelete = ForeignKey.SET_NULL)])
data class ExtensionEntity(@PrimaryKey val id: String,
    @ColumnInfo(name = "repository_id") val repositoryId: Long?, val name: String, val version: String, val status: String,
    @ColumnInfo(name = "install_path") val installPath: String?, val sha256: String?,
    @ColumnInfo(name = "permissions_granted") val permissionsGranted: String = "[]",
    @ColumnInfo(name = "installed_at") val installedAt: Long?, @ColumnInfo(name = "updated_at") val updatedAt: Long)

@Entity(tableName = "downloads", indices = [Index("extension_id")], foreignKeys = [ForeignKey(entity = ExtensionEntity::class,
    parentColumns = ["id"], childColumns = ["extension_id"], onDelete = ForeignKey.SET_NULL)])
data class DownloadEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "extension_id") val extensionId: String?, @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String, @ColumnInfo(name = "target_uri") val targetUri: String?, val status: String,
    val progress: Float = 0f, @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long? = null, val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long, @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "temp_path") val tempPath: String? = null, @ColumnInfo(name = "mime_type") val mimeType: String = "audio/mpeg",
    @ColumnInfo(name = "wifi_only") val wifiOnly: Boolean = false, @ColumnInfo(name = "retry_count") val retryCount: Int = 0)
