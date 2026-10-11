package com.auralis.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.auralis.core.ui.*
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.hazeSource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import com.auralis.feature.settings.FoldersScreen
import com.auralis.feature.settings.FoldersViewModel
import com.auralis.feature.home.ScanScreen
import com.auralis.feature.home.ScanViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.feature.library.LibraryScreen
import com.auralis.feature.library.LibraryViewModel
import com.auralis.feature.player.PlaybackMiniPlayer
import com.auralis.feature.player.NowPlayingScreen
import com.auralis.feature.player.PlaybackViewModel
import com.auralis.feature.playlists.PlaylistsScreen
import com.auralis.feature.playlists.PlaylistsViewModel
import com.auralis.feature.extensions.ExtensionsScreen
import com.auralis.feature.downloads.DownloadsScreen
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.graphics.Color
import androidx.core.app.ActivityCompat

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 60)
        }
        val gallery = BuildConfig.DEBUG && intent.getBooleanExtra("gallery", false)
        val previewTheme = if (BuildConfig.DEBUG) when (intent.getStringExtra("theme")) {
            "light" -> false
            "dark" -> true
            else -> null
        } else null
        setContent { AuralisApp(gallery, previewTheme) }
    }

}

private enum class Destination(val label: Int, val icon: Int) {
    Home(R.string.home, R.drawable.ic_home), Library(R.string.library, R.drawable.ic_library),
    Search(R.string.search, R.drawable.ic_search), Playlists(R.string.playlists, R.drawable.ic_playlists),
    Settings(R.string.settings, R.drawable.ic_settings)
}

@Composable
private fun AuralisApp(startGallery: Boolean, previewTheme: Boolean?) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("auralis_appearance", android.content.Context.MODE_PRIVATE) }
    var themeOverride by rememberSaveable { mutableStateOf(previewTheme ?: when(preferences.getString("theme","system")){"light"->false;"dark"->true;else->null}) }
    var glassMode by rememberSaveable { mutableStateOf(runCatching { GlassMode.valueOf(preferences.getString("glass",null) ?: "") }.getOrElse { defaultGlassMode(context) }) }
    var reduceMotion by rememberSaveable { mutableStateOf(preferences.getBoolean("reduce_motion",false)) }
    var gallery by rememberSaveable { mutableStateOf(startGallery) }
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    var extensionsOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var currentRoute by rememberSaveable { mutableStateOf(Destination.Home.name) }
    var launchVisible by remember { mutableStateOf(true) }
    val launchAlpha = remember { Animatable(1f) }
    val foldersViewModel: FoldersViewModel = viewModel()
    val scanViewModel: ScanViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val playbackViewModel: PlaybackViewModel = viewModel()
    val playlistsViewModel: PlaylistsViewModel = viewModel()
    val libraryState by scanViewModel.state.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val nowPlayingProgress = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (launchVisible) {
            // Start the hold time after Compose has produced a frame. On slower launches,
            // counting from composition can finish the splash while Android still shows
            // the window background, so the logo would never become visible.
            withFrameNanos { }
            kotlinx.coroutines.delay(1_100)
            launchAlpha.animateTo(0f, tween(if (reduceMotion) 0 else 320))
            launchVisible = false
        }
    }
    var sheetAnimation by remember { mutableStateOf<Job?>(null) }
    var sheetDragging by remember { mutableStateOf(false) }
    fun settleSheet(target: Float) {
        sheetAnimation?.cancel()
        sheetAnimation = animationScope.launch {
            nowPlayingProgress.stop()
            nowPlayingProgress.animateTo(target.coerceIn(0f, 1f), tween(if (reduceMotion) 0 else 280))
        }
    }
    LaunchedEffect(playbackViewModel) { playbackViewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(nowPlayingProgress.value > 0f) {
        settleSheet(0f)
    }
    BackHandler(gallery || foldersOpen || extensionsOpen || downloadsOpen) { gallery = false; foldersOpen = false; extensionsOpen = false; downloadsOpen = false }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !sheetDragging) {
                settleSheet(if (nowPlayingProgress.value >= .5f) 1f else 0f)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playbackState.mediaId) {
        if (!sheetDragging && nowPlayingProgress.value !in listOf(0f, 1f)) {
            settleSheet(if (nowPlayingProgress.value >= .5f) 1f else 0f)
        }
    }
    val dark = themeOverride ?: isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        val window = (context as ComponentActivity).window
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.setBackgroundColor(if (dark) Color.rgb(7, 9, 13) else Color.rgb(244, 240, 234))
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val browsingLibrary = currentRoute == Destination.Library.name || currentRoute == Destination.Search.name
    val effectiveGlassMode = if ((libraryState.scan.active || browsingLibrary) && !gallery && glassMode == GlassMode.Full) GlassMode.Lite else glassMode
    AuralisTheme(dark, effectiveGlassMode, reduceMotion) {
        val controller = rememberNavController()
        val entry by controller.currentBackStackEntryAsState()
        val scroll = rememberScrollState()
        val scrollRange = with(LocalDensity.current) { 120.dp.toPx() }
        val topOpacity by remember(scroll, scrollRange, gallery) {
            derivedStateOf { if (gallery) (scroll.value / scrollRange).coerceIn(0f, 1f) else 1f }
        }
        val items = Destination.entries.map { GlassNavItem(it.name, stringResource(it.label), painterResource(it.icon)) }
        GlassScene(Modifier.fillMaxSize().clipToBounds()) {
            // The scan ring changes every frame. Static scanner glass samples the ambient
            // layer directly, avoiding recapture/blur of the entire animated screen.
            val contentBackdrop = if (gallery) Modifier.hazeSource(LocalGlassHaze.current, zIndex = 1f) else Modifier
            Box(Modifier.fillMaxSize().then(contentBackdrop)) {
                if (gallery) GlassGallery(dark, { themeOverride = it }, glassMode, { glassMode = it },
                    reduceMotion, { reduceMotion = it }, scroll)
                else if (foldersOpen) FoldersScreen(foldersViewModel, Modifier.statusBarsPadding())
                else if (extensionsOpen) ExtensionsScreen()
                else if (downloadsOpen) DownloadsScreen(onPlay = { playbackViewModel.playQueue(listOf(it), 0) })
                else NavHost(controller, startDestination = Destination.Home.name) {
                    Destination.entries.forEach { destination ->
                        composable(destination.name) {
                            when (destination) {
                                Destination.Home -> if (libraryState.loading) LoadingLibraryScreen()
                                    else ScanScreen(libraryState, false, scanViewModel::rescan, playbackViewModel::playQueue)
                                Destination.Library -> if (libraryState.loading) LoadingLibraryScreen()
                                    else LibraryScreen(libraryViewModel, currentTrackId = playbackState.mediaId.toLongOrNull(),
                                        playing = playbackState.playing, playQueue = playbackViewModel::playQueue)
                                Destination.Search -> LibraryScreen(libraryViewModel, searchOnly = true,
                                    currentTrackId = playbackState.mediaId.toLongOrNull(), playing = playbackState.playing,
                                    playQueue = playbackViewModel::playQueue)
                                Destination.Playlists -> PlaylistsScreen(playlistsViewModel)
                                Destination.Settings -> SettingsScreen(theme=themeOverride,
                                    setTheme={ value -> themeOverride=value; preferences.edit().putString("theme",when(value){false->"light";true->"dark";null->"system"}).apply() },
                                    glassMode=glassMode, setGlassMode={ glassMode=it; preferences.edit().putString("glass",it.name).apply() },
                                    reduceMotion=reduceMotion, setReduceMotion={ reduceMotion=it; preferences.edit().putBoolean("reduce_motion",it).apply() },
                                    openFolders={ foldersOpen=true }, openExtensions={ extensionsOpen=true }, openDownloads={ downloadsOpen=true })
                            }
                        }
                    }
                }
            }
            val barTitle = when {
                gallery -> "Glass studio"
                foldersOpen -> "Library folders"
                extensionsOpen -> "Extensions"
                downloadsOpen -> "Downloads"
                currentRoute == Destination.Library.name -> "Library"
                currentRoute == Destination.Search.name -> "Search"
                currentRoute == Destination.Playlists.name -> "Playlists"
                currentRoute == Destination.Settings.name -> "Settings"
                else -> "Auralis"
            }
            GlassTopBar(barTitle, topOpacity,
                Modifier.align(Alignment.TopCenter)
                    .background(if (dark) androidx.compose.ui.graphics.Color(0xFF0B1715) else androidx.compose.ui.graphics.Color(0xFFF4F0EA))
                    .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                onLongClick = if (BuildConfig.DEBUG) ({ gallery = true }) else null,
                titleContent = if (!gallery && !foldersOpen && !extensionsOpen && !downloadsOpen && currentRoute == Destination.Home.name) ({
                    Image(painterResource(R.drawable.auralis_logo), contentDescription = "Auralis",
                        modifier = Modifier.width(112.dp).height(52.dp), contentScale = ContentScale.Fit)
                }) else null) {
                if (gallery) TextButton(onClick = { gallery = false; themeOverride = null }) { Text("Done") }
                else if (foldersOpen || extensionsOpen || downloadsOpen) TextButton(onClick = {
                    foldersOpen = false
                    extensionsOpen = false
                    downloadsOpen = false
                }) { Text("‹  Back") }
            }
            if (!foldersOpen && !extensionsOpen && !downloadsOpen) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackMiniPlayer(playbackState, libraryState.songs, playbackViewModel::toggle,
                        playbackViewModel::previous, playbackViewModel::next, onExpand = { settleSheet(1f) })
                    GlassBottomBar(items, entry?.destination?.route, { route ->
                        gallery = false
                        foldersOpen = false
                        extensionsOpen = false
                        downloadsOpen = false
                        currentRoute = route
                        controller.navigate(route) {
                            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    })
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = 200.dp, start = 24.dp, end = 24.dp)) { data ->
                GlassSurface(Modifier.fillMaxWidth(), radius = 22.dp, strong = true) {
                    Text(data.visuals.message, Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        color = LocalGlassPalette.current.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (playbackState.hasMedia && nowPlayingProgress.value > .001f) {
                NowPlayingScreen(
                    playbackState,
                    onCollapseDragStart = {
                        sheetDragging = true
                        sheetAnimation?.cancel()
                    },
                    onCollapseEnd = { collapse, distance ->
                        sheetDragging = false
                        sheetAnimation?.cancel()
                        sheetAnimation = animationScope.launch {
                            nowPlayingProgress.stop()
                            nowPlayingProgress.snapTo((1f - distance).coerceIn(0f, 1f))
                            nowPlayingProgress.animateTo(if (collapse) 0f else 1f,
                                tween(if (reduceMotion) 0 else 280))
                        }
                    },
                    toggle = playbackViewModel::toggle,
                    previous = playbackViewModel::previous,
                    next = playbackViewModel::next,
                    seekTo = playbackViewModel::seekTo,
                    toggleShuffle = playbackViewModel::toggleShuffle,
                    cycleRepeat = playbackViewModel::cycleRepeat,
                    toggleFavorite = playbackViewModel::toggleFavorite,
                    moveQueue = playbackViewModel::moveQueue,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationY = size.height * (1f - nowPlayingProgress.value)
                        clip = true
                    },
                )
            }
            if (launchVisible) AuralisLaunchOverlay(launchAlpha.value)
        }
    }
}

@Composable
private fun AuralisLaunchOverlay(alpha: Float) {
    val p = LocalGlassPalette.current
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
        .background(if (p.dark) androidx.compose.ui.graphics.Color(0xFF07090D) else androidx.compose.ui.graphics.Color(0xFFF4F0EA)),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.auralis_logo), contentDescription = "Auralis",
                modifier = Modifier.size(280.dp), contentScale = ContentScale.Fit)
            Text("Your music, in motion", color=p.secondary, style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsScreen(theme: Boolean?, setTheme: (Boolean?) -> Unit, glassMode: GlassMode,
    setGlassMode: (GlassMode) -> Unit, reduceMotion: Boolean, setReduceMotion: (Boolean) -> Unit,
    openFolders: () -> Unit, openExtensions: () -> Unit, openDownloads: () -> Unit) {
    val context = LocalContext.current
    val p = LocalGlassPalette.current
    val batterySaver = remember { (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(start=24.dp,end=24.dp,top=132.dp,bottom=228.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("Make Auralis yours", color=p.text, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold) }
        item { SettingsSection("Theme", listOf("System","Light","Dark"),
            when(theme){null->0;false->1;true->2}) { setTheme(when(it){1->false;2->true;else->null}) } }
        item { SettingsSection("Glass effects", listOf("Full","Lite","Off"), glassMode.ordinal) { setGlassMode(GlassMode.entries[it]) }
            if (batterySaver) Text("Battery Saver is on. Lite glass is recommended for smoother playback.",
                Modifier.padding(top=8.dp), color=p.secondary, style=MaterialTheme.typography.bodySmall) }
        item { GlassSurface(Modifier.fillMaxWidth(), radius=22.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Reduce motion",color=p.text,fontWeight=FontWeight.SemiBold); Text("Keep clear state changes without movement",color=p.secondary,style=MaterialTheme.typography.bodySmall) }
                Switch(reduceMotion,setReduceMotion,Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp))
            }
        } }
        item { GlassSurface(Modifier.fillMaxWidth().clickable(onClickLabel="Open library folders",onClick=openFolders),radius=22.dp) {
            Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text("Library folders",color=p.text,fontWeight=FontWeight.SemiBold); Text("›",color=p.accent) }
        } }
        item { GlassSurface(Modifier.fillMaxWidth().clickable(onClickLabel="Open extensions",onClick=openExtensions),radius=22.dp) {
            Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text("Extensions",color=p.text,fontWeight=FontWeight.SemiBold); Text("›",color=p.accent) }
        } }
        item { GlassSurface(Modifier.fillMaxWidth().clickable(onClickLabel="Open downloads",onClick=openDownloads),radius=22.dp) {
            Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text("Downloads",color=p.text,fontWeight=FontWeight.SemiBold); Text("›",color=p.accent) }
        } }
        item { GlassSurface(Modifier.fillMaxWidth(),radius=22.dp) { Column(Modifier.padding(18.dp)) {
            Text("Playback",color=p.text,fontWeight=FontWeight.SemiBold); Text("Gapless playback and audio focus follow Android media controls.",color=p.secondary,style=MaterialTheme.typography.bodySmall)
        } } }
        item { GlassSurface(Modifier.fillMaxWidth(),radius=22.dp) { Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("About Auralis",color=p.text,fontWeight=FontWeight.SemiBold)
            Text("Version ${BuildConfig.VERSION_NAME}",color=p.secondary,style=MaterialTheme.typography.bodySmall)
            Text("Your local music stays on this device. Auralis sends no analytics.",color=p.secondary,style=MaterialTheme.typography.bodySmall)
        } } }
    }
}

@Composable
private fun LoadingLibraryScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
        CircularProgressIndicator(color=LocalGlassPalette.current.accent, strokeWidth=3.dp, modifier=Modifier.size(36.dp))
    }
}

@Composable
private fun SettingsSection(title:String, options:List<String>, selected:Int, select:(Int)->Unit) {
    val p=LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(),radius=22.dp) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,color=p.text,fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { options.forEachIndexed { index,label ->
            Box(Modifier.weight(1f).heightIn(min=48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(if(index==selected)p.accent.copy(alpha=.24f) else p.text.copy(alpha=.05f))
                .clickable { select(index) },contentAlignment=Alignment.Center) {
                Text(label,color=if(index==selected)p.accent else p.secondary,fontWeight=if(index==selected)FontWeight.Bold else FontWeight.Normal)
            }
        } }
    } }
}

@Composable
private fun DestinationScreen(title: String, home: Boolean) {
    val p = LocalGlassPalette.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 104.dp, bottom = 220.dp),
        verticalArrangement = Arrangement.Center) {
        if (home) {
            Text("Your music.\nYour space.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.height(16.dp))
            Text("A quieter place for the songs you love.", style = MaterialTheme.typography.bodyLarge, color = p.secondary)
            Spacer(Modifier.height(32.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Welcome to Auralis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Your local library will live here.", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
            }
        } else Text(title, style = MaterialTheme.typography.headlineLarge, color = p.text, fontWeight = FontWeight.SemiBold)
    }
}

