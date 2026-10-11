package com.auralis.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun AuralisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassMode: GlassMode = GlassMode.Lite,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) darkColorScheme(
        primary = Color(0xFF3DDC97), onPrimary = Color(0xFF073B29), background = Color(0xFF07090D),
        surface = Color(0xFF141B24), onBackground = Color(0xFFF5F7FA)
    ) else lightColorScheme(
        primary = Color(0xFF006C48), onPrimary = Color.White, background = Color(0xFFEEF2F7),
        surface = Color(0xFFE1EAE9), onBackground = Color(0xFF0E141B)
    )
    CompositionLocalProvider(
        LocalGlassPalette provides GlassPalette(darkTheme),
        LocalGlassMode provides glassMode,
        LocalReduceMotion provides (reduceMotion || rememberSystemReduceMotion())
    ) { MaterialTheme(colorScheme = colors, content = content) }
}

@Composable
fun DestinationPlaceholder(title: String) {
    val dark = LocalGlassPalette.current.dark
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            if (dark) Color(0xFF17332E) else Color(0xFFD5EAE4),
            MaterialTheme.colorScheme.background
        ))),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium)
    }
}
