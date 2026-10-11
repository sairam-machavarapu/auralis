package com.auralis.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.core.ui.GlassSurface
import com.auralis.core.ui.LocalGlassPalette
import com.auralis.core.ui.LocalReduceMotion
import com.auralis.core.ui.loadArtworkThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.min

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NowPlayingScreen(
    state: PlaybackUiState,
    onCollapseDragStart: () -> Unit,
    onCollapseEnd: (Boolean, Float) -> Unit,
    toggle: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    seekTo: (Long) -> Unit,
    toggleShuffle: () -> Unit,
    cycleRepeat: () -> Unit,
    toggleFavorite: () -> Unit,
    moveQueue: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlassPalette.current
    val reduceMotion = LocalReduceMotion.current
    val bitmap = rememberArtworkBitmap(state.artworkUri)
    val extracted = rememberAmbientColor(state.artworkUri, bitmap, p.dark, p.accent)
    val ambient by animateColorAsState(
        extracted,
        animationSpec = tween(if (reduceMotion) 0 else 600),
        label = "ambientColor",
    )
    var queueOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier.fillMaxSize().background(if (p.dark) Color(0xFF07090D) else Color(0xFFF4F0EA))) {
        val sheetHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val artworkSize = min(maxWidth.value - 56f, maxHeight.value * .43f).coerceAtLeast(180f).dp
        var sheetVisualDragY by remember { mutableFloatStateOf(0f) }
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.verticalGradient(listOf(
                ambient.copy(alpha = if (p.dark) .62f else .38f),
                (if (p.dark) Color(0xFF07090D) else Color(0xFFFFFBF5)).copy(alpha = .96f),
            )))
            drawRect(Brush.radialGradient(
                listOf(ambient.copy(alpha = .34f), Color.Transparent),
                center = Offset(size.width * .5f, size.height * .32f),
                radius = size.width * .78f,
            ))
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .graphicsLayer { translationY = sheetVisualDragY }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var headerDragY by remember { mutableFloatStateOf(0f) }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .pointerInput(sheetHeightPx) {
                    val velocity = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { at ->
                            headerDragY = 0f; velocity.resetTracking();
                            velocity.addPosition(0L, at); onCollapseDragStart()
                        },
                        onDrag = { change, amount ->
                            change.consume(); velocity.addPosition(change.uptimeMillis, change.position)
                            headerDragY = (headerDragY + amount.y).coerceAtLeast(0f)
                            sheetVisualDragY = headerDragY
                        },
                        onDragEnd = {
                            val vy = velocity.calculateVelocity().y
                            val distance = (headerDragY / sheetHeightPx).coerceIn(0f, 1f)
                            onCollapseEnd(vy > 1200f || (vy >= -1200f && distance >= .5f), distance)
                            headerDragY = 0f; sheetVisualDragY = 0f
                        },
                        onDragCancel = {
                            val distance = (headerDragY / sheetHeightPx).coerceIn(0f, 1f)
                            onCollapseEnd(distance >= .5f, distance)
                            headerDragY = 0f; sheetVisualDragY = 0f
                        },
                    )
                }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clickable(onClickLabel = "Close Now Playing") { onCollapseEnd(true, 0f) },
                    contentAlignment = Alignment.Center) {
                    Text("⌄", color = p.text, style = MaterialTheme.typography.headlineMedium)
                }
                Text("NOW PLAYING", Modifier.weight(1f), color = p.secondary,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.height(8.dp))

            var dragX by remember { mutableFloatStateOf(0f) }
            var dragY by remember { mutableFloatStateOf(0f) }
            var previewDirection by remember { mutableIntStateOf(0) }
            val previewItem = when (previewDirection) {
                1 -> state.queue.getOrNull(state.queueIndex + 1)
                -1 -> state.queue.getOrNull(state.queueIndex - 1)
                else -> null
            }
            val previewBitmap = rememberArtworkBitmap(previewItem?.artworkUri)
            val artworkShape = RoundedCornerShape(28.dp)
            Box(
                Modifier.size(artworkSize)
                    .clip(artworkShape)
                    .background(ambient.copy(alpha = .22f))
                    .pointerInput(state.queueIndex) {
                        detectDragGestures(
                            onDragStart = { dragX = 0f; dragY = 0f; previewDirection = 0; onCollapseDragStart() },
                            onDrag = { change, amount ->
                                change.consume(); dragX += amount.x; dragY += amount.y
                                val direction = if (dragX < 0f) 1 else if (dragX > 0f) -1 else 0
                                if (direction != previewDirection) previewDirection = direction
                                if (abs(dragY) > abs(dragX) && dragY > 0) {
                                    sheetVisualDragY = dragY
                                }
                            },
                            onDragEnd = {
                                when {
                                    abs(dragX) > size.width * .16f -> if (dragX < 0) next() else previous()
                                    dragY > sheetHeightPx * .5f -> onCollapseEnd(true, (dragY / sheetHeightPx).coerceIn(0f, 1f))
                                    else -> onCollapseEnd(false, (dragY / sheetHeightPx).coerceIn(0f, 1f))
                                }
                                dragX = 0f; dragY = 0f; previewDirection = 0; sheetVisualDragY = 0f
                            },
                            onDragCancel = {
                                val distance = (dragY / sheetHeightPx).coerceIn(0f, 1f)
                                onCollapseEnd(distance >= .5f, distance)
                                dragX = 0f; dragY = 0f; previewDirection = 0; sheetVisualDragY = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it.asImageBitmap(), state.title,
                    Modifier.fillMaxSize().graphicsLayer { translationX = dragX }, contentScale = ContentScale.Crop) }
                    ?: Text(state.title.take(1).uppercase(), Modifier.graphicsLayer { translationX = dragX }, color = p.text,
                        style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                previewItem?.let { item ->
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        translationX = dragX + if (previewDirection == 1) size.width else -size.width
                    }, contentAlignment = Alignment.Center) {
                        previewBitmap?.let { Image(it.asImageBitmap(), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Text("♪", color = p.text, style = MaterialTheme.typography.displayLarge)
                    }
                    GlassSurface(Modifier.align(Alignment.BottomCenter).padding(16.dp), radius = 100.dp, strong = true) {
                        Text((if (previewDirection == 1) "Next · " else "Previous · ") + item.title,
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = p.text,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(state.title, color = p.text, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(state.artist.ifBlank { "Unknown artist" }, color = p.secondary,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text(state.quality, color = p.secondary.copy(alpha = .78f),
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerChip("Shuffle", state.shuffle, toggleShuffle)
                PlayerChip(when (state.repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat" },
                    state.repeatMode != 0, cycleRepeat)
                PlayerChip(if (state.favorite) "♥ Saved" else "♡ Favorite", state.favorite) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress); toggleFavorite()
                }
                PlayerChip("Queue ${state.queueSize}", false) { queueOpen = true }
                PlayerChip("Lyrics", lyricsOpen) { lyricsOpen = true }
            }
            Spacer(Modifier.height(14.dp))

            LiquidSeekBar(state.positionMs, state.durationMs, ambient, seekTo)
            Spacer(Modifier.height(24.dp))

            GlassSurface(Modifier.fillMaxWidth(), radius = 40.dp, strong = true) {
                Box(Modifier.matchParentSize().clip(RoundedCornerShape(40.dp))
                    .background(ambient.copy(alpha = if (p.dark) .22f else .14f)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TransportButton("Previous", enabled = state.queueIndex > 0 || state.positionMs > 0, onClick = previous) {
                        SkipGlyph(previous = true, p.text)
                    }
                    TransportButton(if (state.playing) "Pause" else "Play", enabled = state.hasMedia,
                        large = true, onClick = toggle) { PlaybackControlGlyph(state.playing, p.text) }
                    TransportButton("Next", enabled = state.queueIndex + 1 < state.queueSize, onClick = next) {
                        SkipGlyph(previous = false, p.text)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false },
        containerColor = if (p.dark) Color(0xF21A2825) else Color(0xF2F3EEE6),
        contentColor = p.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("Up next", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Drag a song to reorder", color = p.secondary)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(state.queue, key = { _, item -> "${item.index}-${item.title}" }) { index, item ->
                    var drag by remember { mutableFloatStateOf(0f) }
                    GlassSurface(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .shadow(if (drag != 0f) 18.dp else 0.dp, RoundedCornerShape(18.dp))
                        .pointerInput(index, state.queue.size) {
                            detectDragGestures(
                                onDrag = { change, amount ->
                                    change.consume(); drag += amount.y
                                    val threshold = 44.dp.toPx()
                                    if (drag > threshold && index < state.queue.lastIndex) { moveQueue(index, index + 1); drag = 0f }
                                    else if (drag < -threshold && index > 0) { moveQueue(index, index - 1); drag = 0f }
                                },
                                onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f })
                        }, radius = 18.dp, strong = item.current) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (item.current) "▶" else "${index + 1}", color = if (item.current) p.accent else p.secondary,
                                modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
                            Column(Modifier.weight(1f)) {
                                Text(item.title.ifBlank { "Unknown title" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (item.current) FontWeight.Bold else FontWeight.Medium)
                                Text(item.artist.ifBlank { "Unknown artist" }, color = p.secondary, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("☰", color = p.secondary, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (lyricsOpen) LyricsSheet(state, ambient, seekTo) { lyricsOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSheet(state: PlaybackUiState, accent: Color, seekTo: (Long) -> Unit, dismiss: () -> Unit) {
    val p = LocalGlassPalette.current
    val document = state.lyrics
    val listState = rememberLazyListState()
    var follow by remember(state.mediaId) { mutableStateOf(true) }
    val activeIndex = remember(document, state.positionMs) {
        if (document?.synchronized == true) document.lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= state.positionMs }
        else -1
    }
    LaunchedEffect(activeIndex, follow) {
        if (follow && activeIndex >= 0) listState.animateScrollToItem(activeIndex, -180)
    }
    ModalBottomSheet(onDismissRequest = dismiss,
        containerColor = if (p.dark) Color(0xFA0A1211) else Color(0xFAF4F0EA), contentColor = p.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(document?.source ?: state.title, color = p.secondary, style = MaterialTheme.typography.bodySmall)
                }
                if (document?.synchronized == true) PlayerChip(if (follow) "Following" else "Resume", follow) { follow = !follow }
            }
            when {
                state.lyricsLoading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = accent)
                }
                document == null -> Box(Modifier.fillMaxWidth().height(320.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("No local lyrics found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Add an .lrc file beside the song using the same filename, or embed lyrics in an MP3 file.",
                            color = p.secondary, textAlign = TextAlign.Center)
                    }
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 600.dp),
                    contentPadding = PaddingValues(vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(document.lines, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                        val active = index == activeIndex
                        Text(line.text.ifBlank { "♪" }, Modifier.fillMaxWidth()
                            .clickable(enabled = line.timeMs != null, onClickLabel = "Seek to this lyric") {
                                line.timeMs?.let(seekTo); follow = true
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = if (active) accent else p.secondary.copy(alpha = if (document.synchronized) .72f else 1f),
                            style = if (active) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun PlayerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalGlassPalette.current
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
        .background(if (selected) p.accent.copy(alpha = .28f) else p.text.copy(alpha = .06f))
        .clickable(onClickLabel = label, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) p.accent else p.secondary,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LiquidSeekBar(positionMs: Long, durationMs: Long, accent: Color, seekTo: (Long) -> Unit) {
    val p = LocalGlassPalette.current
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var draggedFraction by remember { mutableFloatStateOf(0f) }
    val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = if (dragging) draggedFraction else played
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((durationMs * fraction).roundToLong()), color = p.text,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("-${formatTime((durationMs - durationMs * fraction).roundToLong())}", color = p.secondary,
                style = MaterialTheme.typography.labelMedium)
        }
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value -> seekTo((durationMs * value.coerceIn(0f, 1f)).roundToLong()); true }
                    contentDescription = "Playback position"
                }
                .pointerInput(durationMs, widthPx) {
                    detectDragGestures(
                        onDragStart = { at -> dragging = true; draggedFraction = (at.x / widthPx).coerceIn(0f, 1f) },
                        onDrag = { change, amount ->
                            change.consume(); draggedFraction = (draggedFraction + amount.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = { dragging = false; seekTo((durationMs * draggedFraction).roundToLong()) },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            val y = size.height / 2
            val end = (size.width * fraction).coerceAtLeast(0f)
            drawRoundRect(p.text.copy(alpha = .12f), Offset(0f, y - 7.dp.toPx()),
                Size(size.width, 14.dp.toPx()), CornerRadius(20.dp.toPx()))
            if (end > 0f) drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha=.62f), accent)),
                Offset(0f, y - 7.dp.toPx()), Size(end, 14.dp.toPx()), CornerRadius(20.dp.toPx()))
            drawCircle(Color.Black.copy(alpha=.28f), if (dragging) 14.dp.toPx() else 11.dp.toPx(), Offset(end, y))
            drawCircle(accent, if (dragging) 11.dp.toPx() else 9.dp.toPx(), Offset(end, y))
            drawCircle(Color.White.copy(alpha=.9f), 3.dp.toPx(), Offset(end, y))
        }
    }
}

@Composable
private fun TransportButton(label: String, enabled: Boolean, large: Boolean = false,
    onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(Modifier.size(if (large) 72.dp else 54.dp).clip(CircleShape)
        .background(LocalGlassPalette.current.text.copy(alpha = if (large) .10f else .03f))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { glyph() }
}

@Composable private fun PlaybackControlGlyph(playing: Boolean, color: Color) = Canvas(Modifier.size(30.dp)) {
    if (playing) {
        drawRoundRect(color, Offset(size.width * .20f, size.height * .12f), Size(size.width * .22f, size.height * .76f), CornerRadius(3.dp.toPx()))
        drawRoundRect(color, Offset(size.width * .58f, size.height * .12f), Size(size.width * .22f, size.height * .76f), CornerRadius(3.dp.toPx()))
    } else drawPath(Path().apply { moveTo(size.width * .24f, size.height * .10f); lineTo(size.width * .84f, size.height * .5f); lineTo(size.width * .24f, size.height * .90f); close() }, color)
}

@Composable private fun SkipGlyph(previous: Boolean, color: Color) = Canvas(Modifier.size(27.dp)) {
    val direction = if (previous) 1f else -1f
    val center = size.width / 2
    drawLine(color, Offset(center - direction * 8.dp.toPx(), 5.dp.toPx()), Offset(center - direction * 8.dp.toPx(), size.height - 5.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    val path = Path().apply {
        moveTo(center + direction * 8.dp.toPx(), 5.dp.toPx())
        lineTo(center - direction * 5.dp.toPx(), size.height / 2)
        lineTo(center + direction * 8.dp.toPx(), size.height - 5.dp.toPx())
        close()
    }
    drawPath(path, color)
}

@Composable
private fun rememberArtworkBitmap(uri: android.net.Uri?): Bitmap? {
    return produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            loadArtworkThumbnail(uri?.path, 1024)
        }
    }.value
}

private val ambientColorCache = ConcurrentHashMap<String, Color>()

@Composable
private fun rememberAmbientColor(uri: android.net.Uri?, bitmap: Bitmap?, dark: Boolean, fallback: Color): Color {
    val key = "${uri}:${dark}"
    return produceState(ambientColorCache[key] ?: fallback, key, bitmap) {
        if (bitmap != null) value = withContext(Dispatchers.Default) {
            ambientColorCache.getOrPut(key) { bitmap.ambientColor(dark) }
        }
    }.value
}

private fun Bitmap.ambientColor(dark: Boolean): Color {
    val scaled = Bitmap.createScaledBitmap(this, 18, 18, true)
    var red = 0L; var green = 0L; var blue = 0L; var count = 0L
    for (y in 0 until scaled.height) for (x in 0 until scaled.width) {
        val pixel = scaled.getPixel(x, y)
        red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++
    }
    if (scaled !== this) scaled.recycle()
    val factor = if (dark) .52f else 1.10f
    return Color((red / count * factor).toInt().coerceIn(0, 255),
        (green / count * factor).toInt().coerceIn(0, 255),
        (blue / count * factor).toInt().coerceIn(0, 255))
}

private fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
