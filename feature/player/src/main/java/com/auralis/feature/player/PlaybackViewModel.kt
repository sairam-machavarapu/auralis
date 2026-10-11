package com.auralis.feature.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.auralis.core.database.TrackEntity
import com.auralis.core.database.AuralisDatabase
import com.auralis.core.database.PlayHistoryEntity
import com.auralis.service.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val playing: Boolean = false,
    val title: String = "Nothing playing",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val mediaId: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val quality: String = "",
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val favorite: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val lyrics: LyricsDocument? = null,
    val lyricsLoading: Boolean = false,
)

data class QueueItem(val index: Int, val title: String, val artist: String, val artworkUri: Uri?, val current: Boolean)

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
    private val future = MediaController.Builder(application, token).buildAsync()
    private var controller: MediaController? = null
    private val database = AuralisDatabase.get(application)
    private var lastHistoryId: String? = null
    private var favoriteMediaId: String? = null
    private var favoriteValue = false
    private var cachedQueue: List<QueueItem> = emptyList()
    private var cachedQueueCount = -1
    private var cachedQueueIndex = -1
    private val lyricsLoader = LocalLyricsLoader(application)
    private var lyricsMediaId: String? = null
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(error: PlaybackException) {
            mutableMessages.tryEmit("Couldn't play this file. ${if (controller?.hasNextMediaItem() == true) "Skipping to the next song." else "Playback stopped."}")
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            invalidateQueue()
            loadLyrics(item?.mediaId)
            val id = item?.mediaId
            if (id != null && id != lastHistoryId) {
                lastHistoryId = id
                viewModelScope.launch {
                    id.toLongOrNull()?.let { trackId ->
                        val now = System.currentTimeMillis()
                        database.playback().insertHistory(PlayHistoryEntity(trackId = trackId, startedAt = now))
                        database.tracks().recordPlayed(trackId, now)
                    }
                    publish(controller ?: return@launch)
                }
            }
        }
    }

    private fun loadLyrics(mediaId: String?) {
        if (mediaId == null || mediaId == lyricsMediaId) return
        lyricsMediaId = mediaId
        mutableState.update { it.copy(lyrics = null, lyricsLoading = true) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val lyrics = mediaId.toLongOrNull()?.let { database.tracks().get(it) }?.let(lyricsLoader::load)
            if (lyricsMediaId == mediaId) mutableState.update { it.copy(lyrics = lyrics, lyricsLoading = false) }
        }
    }

    init {
        viewModelScope.launch {
            runCatching { future.await() }.onSuccess { connected ->
                controller = connected
                connected.addListener(listener)
                publish(connected)
                while (isActive) {
                    publish(connected)
                    delay(if (connected.isPlaying) 250 else 750)
                }
            }.onFailure { mutableMessages.tryEmit("Playback service is unavailable.") }
        }
    }

    fun playQueue(tracks: List<TrackEntity>, index: Int) {
        val player = controller ?: run {
            mutableMessages.tryEmit("Playback is still connecting.")
            return
        }
        if (tracks.isEmpty() || index !in tracks.indices) return
        val items = tracks.map { track ->
            val artwork = track.artworkHash?.let { Uri.fromFile(File(getApplication<Application>().cacheDir, "artwork/$it.art")) }
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.contentUri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifBlank { "Unknown artist" })
                    .setAlbumTitle(track.album)
                    .setArtworkUri(artwork)
                    .setExtras(Bundle().apply { putString("quality", buildQuality(track)) })
                    .build())
                .build()
        }
        invalidateQueue()
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
    }

    fun toggle() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun previous() {
        controller?.let { player ->
            if (player.currentPosition > 3_000) player.seekTo(0) else player.seekToPreviousMediaItem()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }

    fun seekTo(positionMs: Long) {
        controller?.let { player ->
            val end = player.duration.takeIf { it > 0 } ?: 0
            player.seekTo(PlaybackRules.boundedSeek(positionMs, end))
        }
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() { controller?.let { player ->
        player.repeatMode = PlaybackRules.nextRepeatMode(player.repeatMode)
    } }

    fun toggleFavorite() {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val favorite = !mutableState.value.favorite
        favoriteMediaId = player.currentMediaItem?.mediaId
        favoriteValue = favorite
        mutableState.update { it.copy(favorite = favorite) }
        viewModelScope.launch { database.tracks().setFavorite(id, favorite) }
    }

    fun moveQueue(from: Int, to: Int) {
        val player = controller ?: return
        if (PlaybackRules.validQueueMove(from, to, player.mediaItemCount)) {
            invalidateQueue()
            player.moveMediaItem(from, to)
        }
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val mediaId = player.currentMediaItem?.mediaId
        loadLyrics(mediaId)
        val id = mediaId?.toLongOrNull()
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        if (cachedQueueCount != player.mediaItemCount || cachedQueueIndex != queueIndex) {
            cachedQueue = (0 until player.mediaItemCount).map { index ->
                val item = player.getMediaItemAt(index)
                QueueItem(index, item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri,
                    index == queueIndex)
            }
            cachedQueueCount = player.mediaItemCount
            cachedQueueIndex = queueIndex
        }
        mutableState.value = PlaybackUiState(
            connected = true,
            hasMedia = player.currentMediaItem != null,
            playing = player.isPlaying,
            title = metadata.title?.toString()?.ifBlank { "Unknown title" } ?: "Nothing playing",
            artist = metadata.artist?.toString().orEmpty(),
            artworkUri = metadata.artworkUri,
            mediaId = mediaId.orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            queueSize = player.mediaItemCount,
            queueIndex = queueIndex,
            quality = metadata.extras?.getString("quality").orEmpty(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            favorite = if (mediaId == favoriteMediaId) favoriteValue else false,
            queue = cachedQueue,
            lyrics = mutableState.value.lyrics,
            lyricsLoading = mutableState.value.lyricsLoading,
        )
        if (id != null && mediaId != favoriteMediaId) {
            favoriteMediaId = mediaId
            favoriteValue = false
            viewModelScope.launch {
            val favorite = database.tracks().isFavorite(id) ?: false
                if (controller?.currentMediaItem?.mediaId == mediaId) {
                    favoriteValue = favorite
                    mutableState.update { it.copy(favorite = favorite) }
                }
            }
        }
    }

    private fun invalidateQueue() {
        cachedQueueCount = -1
        cachedQueueIndex = -1
    }

    private fun buildQuality(track: TrackEntity): String {
        return PlaybackRules.quality(track.format, track.codec, track.bitDepth, track.sampleRate)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
        super.onCleared()
    }
}
