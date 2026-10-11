package com.auralis.core.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*
import kotlin.random.Random

val LocalGlassHaze = staticCompositionLocalOf<HazeState> { error("GlassScene is required") }

@Composable
fun GlassScene(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = remember { HazeState() }
    val palette = LocalGlassPalette.current
    val source = if (LocalGlassMode.current == GlassMode.Full && Build.VERSION.SDK_INT >= 31)
        Modifier.hazeSource(state, zIndex = 0f) else Modifier
    CompositionLocalProvider(LocalGlassHaze provides state) {
        Box(modifier.background(palette.base)) {
            Box(Modifier.matchParentSize().then(source)) { AmbientBackdrop() }
            content()
        }
    }
}

@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val p = LocalGlassPalette.current
    Box(modifier.fillMaxSize().drawWithCache {
        val top = Color(if (p.dark) 0xFF15352F else 0xFFD8EDE6)
        val glow = Color(if (p.dark) 0xFF24423B else 0xFFBBDDD2)
        val wash = Brush.verticalGradient(listOf(top, p.base, p.base))
        val halo = Brush.radialGradient(listOf(glow, Color.Transparent),
            center = Offset(size.width * .92f, size.height * .22f), radius = size.width * .85f)
        onDrawBehind {
            drawRect(wash)
            drawRect(halo)
            drawRect(Brush.radialGradient(listOf(p.accent.copy(alpha = .055f), Color.Transparent),
                center = Offset(size.width * .08f, size.height * .78f), radius = size.width * .72f))
        }
    })
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = GlassTokens.cardRadius,
    blurRadius: Dp = GlassTokens.cardBlur,
    opacity: Float = 1f,
    strong: Boolean = false,
    showRim: Boolean = true,
    highlight: Offset? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val p = LocalGlassPalette.current
    val requested = LocalGlassMode.current
    val mode = if (requested == GlassMode.Full && Build.VERSION.SDK_INT < 31) GlassMode.Lite else requested
    val shape = RoundedCornerShape(radius)
    val hazeState = LocalGlassHaze.current
    val fill = (p.fillAlpha + if (strong) .06f else 0f).coerceAtMost(.75f)
    val style = remember(p, fill, blurRadius) {
        HazeStyle(backgroundColor = p.base, tint = HazeTint(Color.White.copy(alpha = fill)),
            blurRadius = blurRadius, noiseFactor = 0f)
    }
    val blur = if (mode == GlassMode.Full) Modifier.hazeEffect(hazeState, style) else Modifier
    Box(modifier) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = opacity.coerceIn(0f, 1f) }
            .shadow(12.dp, shape, ambientColor = p.accent.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .15f))
            .clip(shape).then(blur)
            .drawWithCache {
                val border = Brush.linearGradient(listOf(p.borderStart, p.borderEnd))
                val outline = shape.createOutline(size, layoutDirection, this)
                val lite = Brush.linearGradient(if (p.dark) listOf(Color(0xFF243C37), Color(0xFF182622))
                    else listOf(Color(0xFFF2F9F5), Color(0xFFE0EDE7)))
                val rim = Brush.horizontalGradient(listOf(Color.Transparent, p.borderStart, Color.Transparent))
                // Grain is only useful over a real blurred backdrop. Drawing hundreds of
                // particles on every Lite surface made list scrolling CPU-bound.
                val grain = if (mode == GlassMode.Full) {
                    val random = Random(27)
                    List((size.width * size.height / 1800f).toInt().coerceIn(24, 600)) {
                        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                    }
                } else emptyList()
                onDrawBehind {
                    when (mode) {
                        GlassMode.Off -> drawRect(p.opaque)
                        GlassMode.Lite -> drawRect(lite)
                        GlassMode.Full -> drawRect(Color.White.copy(alpha = .02f))
                    }
                    grain.forEach { drawCircle(Color.White.copy(alpha = .025f), .65f, it) }
                    highlight?.let { point ->
                        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent),
                            center = point, radius = size.width.coerceAtLeast(size.height)))
                    }
                    drawOutline(outline, border, style = Stroke(1.dp.toPx()))
                    if (showRim) drawLine(rim, Offset(radius.toPx().coerceAtMost(size.width / 2), 1.dp.toPx()),
                        Offset((size.width - radius.toPx()).coerceAtLeast(size.width / 2), 1.dp.toPx()), 1.dp.toPx())
                }
            })
        CompositionLocalProvider(LocalContentColor provides p.text) { content() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
