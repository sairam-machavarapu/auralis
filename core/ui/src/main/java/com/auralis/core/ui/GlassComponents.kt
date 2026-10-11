package com.auralis.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog

@Composable
private fun GlassControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    selected: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduced = LocalReduceMotion.current
    val scale = animateFloatAsState(if (pressed && !reduced) .96f else 1f,
        animationSpec = if (reduced) snap() else GlassTokens.pressSpring(), label = "glassPress")
    var point by remember { mutableStateOf<Offset?>(null) }
    val action = if (selected == null) Modifier.clickable(interactions, null, enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected = selected, enabled = enabled, role = Role.Tab,
            interactionSource = interactions, indication = null, onClick = onClick)
    GlassSurface(modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (enabled) 1f else .55f }
        .then(action).semantics { if (description != null) contentDescription = description }
        .pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    point = event.changes.firstOrNull { it.pressed }?.position
                }
            }
        }, radius = 100.dp, highlight = if (pressed) point else null
    ) {
        Row(Modifier.defaultMinSize(minHeight = 48.dp)
            .background(if (selected == true) LocalGlassPalette.current.accent.copy(alpha = .16f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassControl(onClick, modifier, enabled) { Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GlassIconButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: @Composable () -> Unit) {
    GlassControl(onClick, modifier, enabled, description) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } }
}

@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassControl(onClick, modifier, selected = selected) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun GlassSlider(label: String, value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    GlassSurface(modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Slider(value, onValueChange, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent,
                    inactiveTrackColor = p.text.copy(alpha = .15f)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, containerColor = Color.Transparent, dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f), shape = RoundedCornerShape(GlassTokens.sheetRadius)) {
        GlassSurface(Modifier.fillMaxWidth(), radius = GlassTokens.sheetRadius, blurRadius = GlassTokens.sheetBlur, strong = true) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                    .background(LocalGlassPalette.current.secondary, RoundedCornerShape(100.dp)))
                content()
            }
        }
    }
}

@Composable
fun GlassDialog(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest) {
        GlassSurface(Modifier.fillMaxWidth(), radius = GlassTokens.sheetRadius, blurRadius = GlassTokens.sheetBlur, strong = true) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

data class GlassNavItem(val key: String, val label: String, val icon: Painter)

@Composable
fun GlassBottomBar(items: List<GlassNavItem>, selectedKey: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    val showLabels = LocalDensity.current.fontScale <= 1.3f
    GlassSurface(modifier.fillMaxWidth(), radius = 100.dp, blurRadius = GlassTokens.barBlur, showRim = false) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val interactions = remember { MutableInteractionSource() }
                val pressed by interactions.collectIsPressedAsState()
                val reduced = LocalReduceMotion.current
                val scale = animateFloatAsState(if (pressed && !reduced) .96f else 1f,
                    if (reduced) snap() else GlassTokens.pressSpring(), label = "navPress")
                Column(Modifier.weight(1f).heightIn(min = if (showLabels) 58.dp else 46.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    .background(if (selected) p.accent.copy(alpha = .15f) else Color.Transparent, RoundedCornerShape(100.dp))
                    .selectable(selected, role = Role.Tab, interactionSource = interactions, indication = null, onClick = { onSelect(item.key) })
                    .semantics(mergeDescendants = true) { contentDescription = item.label }
                    .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(item.icon, null, Modifier.size(21.dp), tint = if (selected) p.accent else p.secondary)
                    if (showLabels) Text(item.label, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) p.text else p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassTopBar(title: String, scrollFraction: Float, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    GlassSurface(modifier.fillMaxWidth(), radius = GlassTokens.cardRadius, blurRadius = GlassTokens.barBlur,
        opacity = scrollFraction.coerceIn(0f, 1f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick,
                    onLongClickLabel = "Open component gallery") else Modifier),
                contentAlignment = Alignment.CenterStart) {
                if (titleContent != null) titleContent()
                else Text(title, color = LocalGlassPalette.current.text, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            actions()
        }
    }
}

@Composable
fun PlaceholderArtwork(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF80BFA5), Color(0xFF285F50), Color(0xFF102A25))), RoundedCornerShape(12.dp))
        .drawWithCache {
            onDrawBehind {
                repeat(4) { ring ->
                    drawCircle(Color.White.copy(alpha = .12f), size.minDimension * (.18f + ring * .12f),
                        center = Offset(size.width * .75f, size.height * .65f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
        })
}

@Composable
fun GlassMiniPlayer(playIcon: Painter, modifier: Modifier = Modifier, librarySongCount: Int = 0) {
    val p = LocalGlassPalette.current
    GlassSurface(modifier.fillMaxWidth(), radius = 100.dp, blurRadius = GlassTokens.barBlur) {
        Column {
            Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderArtwork(Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Nothing playing", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.text)
                    Text(if (librarySongCount > 0) "$librarySongCount songs in your library" else "Add music to begin", style = MaterialTheme.typography.bodySmall, color = p.secondary)
                }
                IconButton(onClick = {}, enabled = false, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(playIcon, contentDescription = "Play, unavailable", tint = p.secondary.copy(alpha = .5f))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.text.copy(alpha = .08f)))
        }
    }
}
