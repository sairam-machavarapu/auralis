package com.auralis.core.ui

import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassGallery(
    dark: Boolean, onDarkChange: (Boolean) -> Unit,
    mode: GlassMode, onModeChange: (GlassMode) -> Unit,
    reduceMotion: Boolean, onReduceMotionChange: (Boolean) -> Unit,
    scrollState: ScrollState, modifier: Modifier = Modifier
) {
    val p = LocalGlassPalette.current
    var slider by rememberSaveable { mutableFloatStateOf(.6f) }
    var favorite by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var pressed by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(scrollState)
        .padding(start = 20.dp, end = 20.dp, top = 128.dp, bottom = 220.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A little light.\nA little depth.", style = MaterialTheme.typography.headlineLarge, color = p.text, fontWeight = FontWeight.SemiBold)
            Text("Explore the surfaces that hold your music.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Light", !dark, { onDarkChange(false) })
                GlassChip("Dark", dark, { onDarkChange(true) })
            }
            Text("Glass effects", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassMode.entries.forEach { choice -> GlassChip(choice.name, mode == choice, { onModeChange(choice) }) }
            }
            if (mode == GlassMode.Full && Build.VERSION.SDK_INT < 31)
                Text("This device uses the Lite fallback.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Reduce motion", modifier = Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium)
                Switch(reduceMotion, onReduceMotionChange)
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            PlaceholderArtwork(Modifier.fillMaxWidth().height(140.dp))
            Text("Room to listen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Soft edges, clear words, and colour that follows the music.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Buttons & chips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            GlassButton(if (pressed) "Pressed. Beautifully." else "Try the press", { pressed = !pressed })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Favourites", favorite, { favorite = !favorite })
                GlassChip("Lossless", !favorite, { favorite = !favorite })
            }
        }
        GlassSlider("Slider preview · ${(slider * 100).toInt()}%", slider, { slider = it }, Modifier.fillMaxWidth())
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Sheets & dialogs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Focus on one thing at a time.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Open sheet", { sheet = true })
                GlassButton("Open dialog", { dialog = true })
            }
        }
        Text("The floating player and navigation below use the same surfaces.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
    }
    if (sheet) GlassSheet({ sheet = false }) {
        Text("A softer pause", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("A glass sheet keeps the next action close, without losing the view behind it.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
        GlassButton("Done", { sheet = false })
    }
    if (dialog) GlassDialog({ dialog = false }) {
        Text("Perfectly clear", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("One message. One action. Enough room to breathe.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
        GlassButton("Got it", { dialog = false })
    }
}
