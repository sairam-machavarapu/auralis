package com.auralis.feature.downloads

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auralis.core.database.AuralisDatabase
import com.auralis.core.database.DownloadEntity
import com.auralis.core.database.TrackEntity
import com.auralis.core.ui.GlassSurface
import com.auralis.core.ui.LocalGlassPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AuralisDownloadManager(application)
    val jobs = manager.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun pause(id: Long) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { manager.cancel(id) }
    fun remove(id: Long) = viewModelScope.launch { manager.remove(id) }
    fun play(job: DownloadEntity, onReady: (TrackEntity) -> Unit) = viewModelScope.launch {
        job.targetUri?.let { AuralisDatabase.get(getApplication()).tracks().findContentUri(it) }?.let(onReady)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier, onPlay: (TrackEntity) -> Unit = {}, model: DownloadsViewModel = viewModel()) {
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxSize().padding(top = 112.dp)) {
        if (jobs.isEmpty()) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            GlassSurface(Modifier.fillMaxWidth(), radius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("↓", color = palette.accent, style = MaterialTheme.typography.displaySmall)
                    Text("Nothing downloading", color = palette.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Downloads requested from authorized extensions will appear here.", color = palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Your downloads", color = palette.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            items(jobs, key = { it.id }) { job ->
                val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { target ->
                    if (target != SwipeToDismissBoxValue.Settled && job.status !in listOf(DownloadStatus.Completed, DownloadStatus.Canceled)) {
                        model.cancel(job.id)
                        true
                    } else false
                })
                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                        val result = snackbar.showSnackbar("${job.title} canceled", "Undo", duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) model.resume(job.id)
                        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                    }
                }
                SwipeToDismissBox(state = dismissState, backgroundContent = {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = .22f))
                        .padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) { Text("Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
                }) { DownloadCard(job, model::pause, model::resume, model::remove) { model.play(job, onPlay) } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun DownloadCard(job: DownloadEntity, pause: (Long) -> Unit, resume: (Long) -> Unit,
    remove: (Long) -> Unit, play: () -> Unit) {
    val palette = LocalGlassPalette.current
    val progress by animateFloatAsState(job.progress.coerceIn(0f, 1f), label = "downloadProgress")
    val checkScale by animateFloatAsState(if (job.status == DownloadStatus.Completed) 1f else .72f, label = "completedCheck")
    GlassSurface(Modifier.fillMaxWidth(), radius = 22.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(palette.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Text(if (job.status == DownloadStatus.Completed) "✓" else "↓", color = palette.accent,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.scale(checkScale))
                }
                Column(Modifier.weight(1f)) {
                    Text(job.title, color = palette.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(job.extensionId ?: runCatching { java.net.URI(job.sourceId).host }.getOrNull().orEmpty(), color = palette.secondary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (job.status) {
                    DownloadStatus.Downloading -> TextButton(onClick = { pause(job.id) }, Modifier.heightIn(min = 48.dp)) { Text("Pause") }
                    DownloadStatus.Paused, DownloadStatus.Failed, DownloadStatus.Canceled -> TextButton(onClick = { resume(job.id) }, Modifier.heightIn(min = 48.dp)) { Text("Resume") }
                    DownloadStatus.Completed -> Row {
                        TextButton(onClick = play, Modifier.heightIn(min = 48.dp)) { Text("Play") }
                        TextButton(onClick = { remove(job.id) }, Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                    }
                    else -> Text("Waiting", color = palette.secondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(palette.text.copy(alpha = .10f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(palette.accent))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(statusLabel(job), color = if (job.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error else palette.secondary,
                    style = MaterialTheme.typography.bodySmall)
                Text(if (job.bytesTotal != null) "${formatBytes(job.bytesDone)} / ${formatBytes(job.bytesTotal!!)}" else formatBytes(job.bytesDone),
                    color = palette.secondary, style = MaterialTheme.typography.bodySmall)
            }
            job.error?.let { Text(it, color = if (job.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error else palette.secondary,
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusLabel(job: DownloadEntity) = when (job.status) {
    DownloadStatus.Queued -> if (job.wifiOnly) "Waiting for Wi-Fi" else "Waiting"
    DownloadStatus.Downloading -> "Downloading"
    DownloadStatus.Paused -> "Paused"
    DownloadStatus.Completed -> "Added to library"
    DownloadStatus.Failed -> "Needs attention"
    DownloadStatus.Canceled -> "Canceled"
    else -> job.status
}
