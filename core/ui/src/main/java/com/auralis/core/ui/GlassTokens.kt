package com.auralis.core.ui

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class GlassMode { Full, Lite, Off }

@Immutable
data class GlassPalette(val dark: Boolean) {
    val base = Color(if (dark) 0xFF07090D else 0xFFEEF2F7)
    val opaque = Color(if (dark) 0xFF141B24 else 0xFFF4F7F9)
    val text = Color(if (dark) 0xFFF5F7FA else 0xFF0E141B)
    val secondary = text.copy(alpha = if (dark) .74f else .72f)
    val accent = Color(if (dark) 0xFF3DDC97 else 0xFF006C48)
    val fillAlpha = if (dark) .10f else .60f
    val borderStart = Color.White.copy(alpha = if (dark) .35f else .90f)
    val borderEnd = Color.White.copy(alpha = if (dark) .05f else .20f)
}

object GlassTokens {
    val smallRadius = 12.dp
    val cardRadius = 20.dp
    val sheetRadius = 28.dp
    val spacing = listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp)
    val cardBlur = 12.dp
    val barBlur = 24.dp
    val sheetBlur = 40.dp
    const val fastMs = 120
    const val baseMs = 220
    const val slowMs = 400
    fun pressSpring() = spring<Float>(dampingRatio = .8f, stiffness = Spring.StiffnessMedium)
    fun sheetSpring() = spring<Float>(dampingRatio = .7f, stiffness = Spring.StiffnessLow)
}

val LocalGlassPalette = staticCompositionLocalOf { GlassPalette(true) }
val LocalGlassMode = staticCompositionLocalOf { GlassMode.Lite }
val LocalReduceMotion = staticCompositionLocalOf { false }

fun defaultGlassMode(context: Context): GlassMode {
    val lowRam = context.getSystemService(ActivityManager::class.java).isLowRamDevice
    val saver = context.getSystemService(PowerManager::class.java).isPowerSaveMode
    return if (Build.VERSION.SDK_INT >= 31 && !lowRam && !saver) GlassMode.Full else GlassMode.Lite
}

@Composable
fun rememberSystemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
