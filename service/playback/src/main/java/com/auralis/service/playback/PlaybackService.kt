package com.auralis.service.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Owns playback independently of the activity and publishes Media3's system notification/session. */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private val persist = object : Runnable {
        override fun run() { savePlayback(); handler.postDelayed(this, 5_000) }
    }

    override fun onCreate() {
        super.onCreate()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audio, true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            setPauseAtEndOfMediaItems(false)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (hasNextMediaItem()) {
                        seekToNextMediaItem()
                        prepare()
                        play()
                    } else {
                        pause()
                    }
                }
            })
        }
        restorePlayback()
        session = MediaSession.Builder(this, player).build()
        handler.postDelayed(persist, 5_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(persist)
        savePlayback()
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun savePlayback() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val items = JSONArray()
        repeat(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val metadata = item.mediaMetadata
            items.put(JSONObject().apply {
                put("id", item.mediaId)
                put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                put("title", metadata.title?.toString().orEmpty())
                put("artist", metadata.artist?.toString().orEmpty())
                put("album", metadata.albumTitle?.toString().orEmpty())
                put("artwork", metadata.artworkUri?.toString().orEmpty())
                put("quality", metadata.extras?.getString("quality").orEmpty())
            })
        }
        getSharedPreferences("playback", MODE_PRIVATE).edit()
            .putString("queue", items.toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putBoolean("play", player.playWhenReady)
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    private fun restorePlayback() {
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        val raw = prefs.getString("queue", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val items = (0 until array.length()).mapNotNull { index ->
                val value = array.getJSONObject(index)
                val uri = value.optString("uri")
                if (uri.isBlank()) null else MediaItem.Builder()
                    .setMediaId(value.optString("id"))
                    .setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(value.optString("title"))
                        .setArtist(value.optString("artist"))
                        .setAlbumTitle(value.optString("album"))
                        .setArtworkUri(value.optString("artwork").takeIf(String::isNotBlank)?.let(Uri::parse))
                        .setExtras(Bundle().apply { putString("quality", value.optString("quality")) })
                        .build())
                    .build()
            }
            if (items.isNotEmpty()) {
                player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
                player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
                player.prepare()
                player.playWhenReady = prefs.getBoolean("play", false)
            }
        }.onFailure { prefs.edit().clear().apply() }
    }
}
