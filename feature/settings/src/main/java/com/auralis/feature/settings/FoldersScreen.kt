package com.auralis.feature.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.core.database.*
import com.auralis.core.ui.*

@Composable
fun FoldersScreen(viewModel: FoldersViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val p = LocalGlassPalette.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.addObserver(observer)
        viewModel.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.add(uri)
    }
    val choose: (Uri?) -> Unit = { initial ->
        viewModel.clearError()
        try { picker.launch(initial) } catch (_: ActivityNotFoundException) { viewModel.pickerUnavailable() }
    }
    var removingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reducing = LocalReduceMotion.current
    val entrance = remember { Animatable(if (reducing) 1f else 0f) }
    LaunchedEffect(reducing) { if (reducing) entrance.snapTo(1f) else entrance.animateTo(1f, tween(220)) }
    LazyColumn(modifier.fillMaxSize().graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 12.dp.toPx() },
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 132.dp, bottom = 260.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (state.folders.isNullOrEmpty()) "Your music.\nAt home here." else "Music folders",
                    style = MaterialTheme.typography.headlineLarge, color = p.text, fontWeight = FontWeight.SemiBold)
                Text(if (state.folders.isNullOrEmpty()) "Choose a folder on your device to start your local library."
                    else "Choose which folders belong in your library.", style = MaterialTheme.typography.bodyLarge, color = p.secondary)
            }
        }
        if (state.error != null) item(key = "error") {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(state.error!!, color = p.text)
                TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
            }
        }
        if (state.folders == null && state.error == null) item(key = "loading") { Text("Opening your library…", color = p.secondary) }
        if (state.folders != null) item(key = "choose") {
            GlassButton(if (busy) "Saving…" else "Choose music folder", { choose(null) }, enabled = !busy)
        }
        items(state.folders.orEmpty(), key = { it.record.id }) { folder ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(folder.record.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(when (folder.access) {
                    FolderAccess.PermissionLost -> "Access was removed. Choose this folder again to reconnect it."
                    FolderAccess.Unavailable -> "This folder is unavailable. Reconnect its storage, then try again."
                    FolderAccess.Ready -> if (folder.record.enabled) "Enabled for your library" else "Paused — excluded from future scans"
                }, style = MaterialTheme.typography.bodyMedium, color = p.secondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Include folder", modifier = Modifier.weight(1f), color = p.text)
                    Switch(checked = folder.record.enabled, onCheckedChange = { viewModel.enable(folder, it) },
                        enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Include ${folder.record.displayName}" })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (folder.access != FolderAccess.Ready) GlassButton("Reconnect", { choose(Uri.parse(folder.record.uri)) }, enabled = !busy)
                    TextButton(onClick = { removingId = folder.record.id }, enabled = !busy) { Text("Remove") }
                }
            }
        }
    }
    state.folders?.firstOrNull { it.record.id == removingId }?.let { folder ->
        GlassDialog(onDismissRequest = { removingId = null }) {
            Text("Remove ${folder.record.displayName}?", style = MaterialTheme.typography.titleLarge)
            Text("Auralis will forget this folder. Your audio files stay on your device.", color = p.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { removingId = null }) { Text("Keep folder") }
                GlassButton("Remove folder", { removingId = null; viewModel.remove(folder) }, enabled = !busy)
            }
        }
    }
}
