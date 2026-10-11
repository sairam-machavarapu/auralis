package com.auralis.feature.library

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.core.database.*
import com.auralis.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File

enum class LibraryTab { Songs, Albums, Artists, Genres }
sealed interface LibraryDetail {
    data class Album(val album: AlbumSummary) : LibraryDetail
    data class Artist(val artist: ArtistSummary) : LibraryDetail
    data class Genre(val genre: GenreSummary) : LibraryDetail
}

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AuralisDatabase.get(application)
    private val loadError = MutableStateFlow<String?>(null)
    val libraryError = loadError.asStateFlow()
    private fun <T> Flow<List<T>>.reportErrors() = catch { loadError.value = "Your library could not be loaded."; emit(emptyList()) }
    val songs = db.tracks().observeAvailable().reportErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums = db.catalog().observeAlbums().reportErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val artists = db.catalog().observeArtists().reportErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val genres = db.catalog().observeGenres().reportErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val query = MutableStateFlow("")
    val searchQuery = query.asStateFlow()
    val searchResults = query.debounce(60).distinctUntilChanged().flatMapLatest { raw ->
        val match = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" }
        if (match.isBlank()) flowOf(emptyList()) else db.tracks().observeSearch(match)
    }.catch { loadError.value = "Search is unavailable right now."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun setQuery(value: String) { query.value = value.take(120) }
    fun albumTracks(id: Long) = db.tracks().observeAlbumTracks(id)
    fun artistTracks(name: String) = db.tracks().observeArtistTracks(name)
    fun genreTracks(name: String) = db.tracks().observeGenreTracks(name)
}

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, searchOnly: Boolean = false,
    currentTrackId: Long? = null, playing: Boolean = false,
    playQueue: (List<TrackEntity>, Int) -> Unit) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val error by viewModel.libraryError.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }
    var detail by remember { mutableStateOf<LibraryDetail?>(null) }
    val p = LocalGlassPalette.current

    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 104.dp)) {
        when (val selected = detail) {
            is LibraryDetail.Album -> DetailScreen(selected.album.title,
                selected.album.albumArtist.ifBlank { "Album" }, selected.album.artworkPath,
                viewModel.albumTracks(selected.album.id), { detail = null }, playQueue)
            is LibraryDetail.Artist -> DetailScreen(selected.artist.name,
                "${selected.artist.albumCount} albums", selected.artist.artworkPath,
                viewModel.artistTracks(selected.artist.name), { detail = null }, playQueue)
            is LibraryDetail.Genre -> DetailScreen(selected.genre.name, "Genre", null,
                viewModel.genreTracks(selected.genre.name), { detail = null }, playQueue)
            null -> Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SearchBar(query, viewModel::setQuery, searchOnly)
                    if (!searchOnly && query.isBlank()) {
                        Spacer(Modifier.height(12.dp)); LibraryTabs(tab) { tab = it }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (error != null) ErrorState(error!!)
                else if (searchOnly || query.isNotBlank()) {
                    if (query.isBlank()) EmptyState("Search your songs, artists, albums, genres or filenames.")
                    else if (results.isEmpty()) EmptyState("No songs match “$query”.")
                    else TrackList(results, Modifier.fillMaxSize(), currentTrackId, playing, playQueue)
                } else when (tab) {
                    LibraryTab.Songs -> LoadingOrEmpty(songs, "No songs found.") {
                        TrackList(it, Modifier.fillMaxSize(), currentTrackId, playing, playQueue)
                    }
                    LibraryTab.Albums -> LoadingOrEmpty(albums, "No albums found.") { list ->
                        LazyVerticalGrid(GridCells.Adaptive(152.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(list, key = { _, item -> item.id }) { index, album ->
                                FadeFirst(index) { AlbumCard(album) { detail = LibraryDetail.Album(album) } }
                            }
                        }
                    }
                    LibraryTab.Artists -> LoadingOrEmpty(artists, "No artists found.") { list ->
                        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                            itemsIndexed(list, key = { _, item -> item.name.lowercase() }) { index, artist ->
                                FadeFirst(index) { ArtistRow(artist) { detail = LibraryDetail.Artist(artist) } }
                            }
                        }
                    }
                    LibraryTab.Genres -> LoadingOrEmpty(genres, "No genres found.") { list ->
                        LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(list, key = { _, item -> item.name.lowercase() }) { index, genre ->
                                FadeFirst(index) { GenreCard(genre) { detail = LibraryDetail.Genre(genre) } }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, p.base))))
    }
}

@Composable private fun SearchBar(value: String, update: (String) -> Unit, expanded: Boolean) {
    val p = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth().animateContentSize(), radius = 24.dp) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MagnifierGlyph(Modifier.size(24.dp), p.secondary)
            Spacer(Modifier.width(10.dp))
            BasicTextField(value, update, singleLine = true, textStyle = TextStyle(color = p.text,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize), modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Search library"
                }, decorationBox = { inner -> Box { if (value.isEmpty()) Text("Search your library", color = p.secondary); inner() } })
            if (value.isNotEmpty()) Box(Modifier.size(48.dp).clickable(onClickLabel = "Clear search") { update("") },
                contentAlignment = Alignment.Center) { ClearGlyph(Modifier.size(20.dp), p.secondary) }
        }
    }
}

@Composable private fun LibraryTabs(selected: LibraryTab, select: (LibraryTab) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), radius = 24.dp) {
        Row(Modifier.padding(4.dp)) { LibraryTab.entries.forEach { tab ->
            val active = tab == selected
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                .background(if (active) LocalGlassPalette.current.accent.copy(alpha = .28f) else Color.Transparent)
                .clickable(role = Role.Tab) { select(tab) }, contentAlignment = Alignment.Center) {
                Text(tab.name, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) LocalGlassPalette.current.text else LocalGlassPalette.current.secondary)
            }
        } }
    }
}

@Composable private fun TrackList(tracks: List<TrackEntity>, modifier: Modifier, currentTrackId: Long?, playing: Boolean,
    playQueue: (List<TrackEntity>, Int) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 228.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track, track.id == currentTrackId, playing) { playQueue(tracks, index) }
        }
    }
}

@Composable private fun TrackRow(track: TrackEntity, current: Boolean = false, playing: Boolean = false, play: () -> Unit) {
    val p = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(20.dp))
        .background(if (current) p.accent.copy(alpha = .10f) else Color.Transparent)
        .clickable(role = Role.Button, onClickLabel = "Play ${track.title}", onClick = play)
        .padding(horizontal = if (current) 8.dp else 0.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val context = LocalContext.current
        val artworkPath = track.artworkHash?.let { File(context.cacheDir, "artwork/$it.art").absolutePath }
        Artwork(artworkPath, track.title, Modifier.size(60.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) { EqualizerGlyph(playing); Spacer(Modifier.width(8.dp)) }
                Text(track.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (current) p.accent else p.text, fontWeight = FontWeight.SemiBold)
            }
            Text(track.artist.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = p.text.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
            QualityPill(track)
        }
    }
}

@Composable private fun QualityPill(track: TrackEntity) {
    val parts = buildList {
        track.format?.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
        track.bitDepth?.takeIf { it > 0 }?.let { add("$it") }
        track.sampleRate?.takeIf { it > 0 }?.let { add("${it / 1000}${if (it % 1000 == 0) "" else ".${(it % 1000) / 100}"}") }
    }
    val format = track.format.orEmpty().uppercase()
    val notable = format in setOf("FLAC", "OPUS", "WAV", "ALAC") || (track.bitDepth ?: 0) >= 24 || (track.sampleRate ?: 0) > 48_000
    if (notable && parts.isNotEmpty()) Text(parts.joinToString("  "), color = LocalGlassPalette.current.accent.copy(alpha = .86f),
        style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

@Composable private fun AlbumCard(album: AlbumSummary, click: () -> Unit) {
    val p = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${album.title}", onClick = click)) {
        Artwork(album.artworkPath, album.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(10.dp)); Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.text, fontWeight = FontWeight.SemiBold)
        Text(album.albumArtist.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ArtistRow(artist: ArtistSummary, click: () -> Unit) {
    val p = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artist.artworkPath, artist.name, Modifier.size(60.dp).clip(CircleShape)); Spacer(Modifier.width(16.dp))
        Column { Text(artist.name, color = p.text, fontWeight = FontWeight.SemiBold); Text("${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"} · ${artist.trackCount} ${if (artist.trackCount == 1) "song" else "songs"}", color = p.secondary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun GenreCard(genre: GenreSummary, click: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click), radius = 20.dp) {
        Column(Modifier.padding(18.dp)) { Text(genre.name, color = LocalGlassPalette.current.text, fontWeight = FontWeight.SemiBold); Text("${genre.trackCount} songs", color = LocalGlassPalette.current.secondary) }
    }
}

@Composable private fun DetailScreen(title: String, subtitle: String, artworkPath: String?, tracks: Flow<List<TrackEntity>>,
    back: () -> Unit, playQueue: (List<TrackEntity>, Int) -> Unit) {
    val list by tracks.collectAsStateWithLifecycle(emptyList())
    val p = LocalGlassPalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        item(key = "header") {
            TextButton(onClick = back) { Text("‹  Library") }
            Artwork(artworkPath, title, Modifier.fillMaxWidth().height(220.dp))
            Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, color = p.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.secondary); Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(list, key = { _, track -> track.id }) { index, track -> FadeFirst(index) { TrackRow(track) { playQueue(list, index) } } }
    }
}

@Composable private fun Artwork(path: String?, label: String, modifier: Modifier) {
    val p = LocalGlassPalette.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) { value = loadArtworkThumbnail(path) }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color.hsl((label.hashCode().toUInt().toLong() % 360).toFloat(), .42f, if (p.dark) .30f else .72f).copy(alpha = .72f)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: MusicNoteGlyph(Modifier.size(28.dp), p.text.copy(alpha = .88f))
    }
}

@Composable private fun MagnifierGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    drawCircle(color, radius = size.minDimension * .29f, center = Offset(size.width * .42f, size.height * .42f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    drawLine(color, Offset(size.width * .64f, size.height * .64f), Offset(size.width * .86f, size.height * .86f), 2.dp.toPx())
}
@Composable private fun ClearGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    drawLine(color, Offset(size.width*.22f,size.height*.22f), Offset(size.width*.78f,size.height*.78f), 2.dp.toPx())
    drawLine(color, Offset(size.width*.78f,size.height*.22f), Offset(size.width*.22f,size.height*.78f), 2.dp.toPx())
}
@Composable private fun MusicNoteGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    drawLine(color, Offset(size.width*.58f,size.height*.18f), Offset(size.width*.58f,size.height*.70f), 3.dp.toPx())
    drawLine(color, Offset(size.width*.58f,size.height*.18f), Offset(size.width*.84f,size.height*.12f), 3.dp.toPx())
    drawCircle(color, size.width*.16f, Offset(size.width*.42f,size.height*.76f))
}
@Composable private fun EqualizerGlyph(active: Boolean) {
    val p = LocalGlassPalette.current
    val phase = if (active && !LocalReduceMotion.current) rememberInfiniteTransition(label="eq").animateFloat(0f,1f,
        infiniteRepeatable(tween(520), RepeatMode.Reverse), label="eqPhase") else remember { mutableStateOf(.45f) }
    Canvas(Modifier.size(16.dp)) { repeat(3) { i ->
        val f = ((phase.value + i*.27f) % 1f); val h = size.height * (.28f + .62f*f)
        drawRoundRect(p.accent, Offset(i*size.width*.34f,size.height-h), Size(size.width*.18f,h),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    } }
}

@Composable private fun <T> LoadingOrEmpty(value: List<T>?, empty: String, content: @Composable (List<T>) -> Unit) {
    when { value == null -> SkeletonList(); value.isEmpty() -> EmptyState(empty); else -> content(value) }
}
@Composable private fun SkeletonList() { Column(Modifier.padding(horizontal = 24.dp)) { repeat(6) { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(56.dp).background(LocalGlassPalette.current.opaque, RoundedCornerShape(16.dp))) } } }
@Composable private fun EmptyState(message: String) { Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(message, color = LocalGlassPalette.current.secondary, style = MaterialTheme.typography.bodyLarge) } }
@Composable private fun ErrorState(message: String) { Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { GlassCard { Text("Library unavailable", fontWeight = FontWeight.SemiBold); Text(message, color = LocalGlassPalette.current.secondary) } } }
@Composable private fun FadeFirst(index: Int, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    var visible by remember { mutableStateOf(index >= 8 || reduceMotion) }
    LaunchedEffect(index) { if (!visible) { delay(index * 35L); visible = true } }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "row")
    Box(Modifier.graphicsLayer { this.alpha = alpha }) { content() }
}

