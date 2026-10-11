package com.auralis.feature.player

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlaybackMiniPlayer(state: PlaybackUiState, librarySongCount: Int, toggle: () -> Unit,
    previous: () -> Unit, next: () -> Unit,
    onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, state.artworkUri) {
        value = loadArtworkThumbnail(state.artworkUri?.path, 192)
    }
    GlassSurface(modifier.fillMaxWidth().clickable(enabled = state.hasMedia, role = Role.Button,
        onClickLabel = "Open Now Playing", onClick = onExpand), radius = 28.dp, strong = true, showRim = false) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), state.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Text(if (state.hasMedia) state.title.take(1).uppercase() else "♪", color = p.accent,
                            style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, color = p.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.hasMedia) state.artist else "$librarySongCount songs in your library", color = p.secondary,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MiniControl("Previous", state.hasMedia, previous) { SkipMiniGlyph(true) }
                Box(Modifier.size(48.dp).clickable(enabled = state.hasMedia, role = Role.Button,
                    onClickLabel = if (state.playing) "Pause" else "Play", onClick = toggle)
                    .semantics { contentDescription = if (!state.hasMedia) "Play, unavailable" else if (state.playing) "Pause" else "Play" },
                    contentAlignment = Alignment.Center) {
                    AnimatedContent(state.playing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "playPause") { playing ->
                        PlaybackGlyph(playing)
                    }
                }
                MiniControl("Next", state.hasMedia, next) { SkipMiniGlyph(false) }
            }
            if (state.hasMedia && state.durationMs > 0) {
                val fraction = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(3.dp)) {
                    drawRoundRect(p.text.copy(alpha=.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height/2))
                    drawRoundRect(p.accent, size = androidx.compose.ui.geometry.Size(size.width*fraction,size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height/2))
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}

@Composable private fun MiniControl(label: String, enabled: Boolean, click: () -> Unit, glyph: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = click)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { glyph() }
}

@Composable private fun SkipMiniGlyph(previous: Boolean) {
    val color = LocalGlassPalette.current.text
    Canvas(Modifier.size(20.dp)) {
        val dir = if (previous) 1f else -1f
        val cx = size.width/2
        drawLine(color, Offset(cx-dir*6.dp.toPx(),3.dp.toPx()), Offset(cx-dir*6.dp.toPx(),size.height-3.dp.toPx()),2.dp.toPx())
        val path=Path().apply { moveTo(cx+dir*6.dp.toPx(),3.dp.toPx()); lineTo(cx-dir*4.dp.toPx(),size.height/2); lineTo(cx+dir*6.dp.toPx(),size.height-3.dp.toPx()); close() }
        drawPath(path,color)
    }
}

@Composable private fun PlaybackGlyph(playing: Boolean) {
    val color = LocalGlassPalette.current.text
    Canvas(Modifier.size(24.dp)) {
        if (playing) {
            drawRoundRect(color, Offset(size.width * .2f, size.height * .12f),
                androidx.compose.ui.geometry.Size(size.width * .22f, size.height * .76f),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(color, Offset(size.width * .58f, size.height * .12f),
                androidx.compose.ui.geometry.Size(size.width * .22f, size.height * .76f),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        } else {
            val path = Path().apply { moveTo(size.width * .25f, size.height * .12f); lineTo(size.width * .82f, size.height * .5f); lineTo(size.width * .25f, size.height * .88f); close() }
            drawPath(path, color)
        }
    }
}
