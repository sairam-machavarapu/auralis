package com.auralis.feature.home

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.core.database.TrackEntity
import com.auralis.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun ScanScreen(state: LibraryScanState, library: Boolean, rescan: () -> Unit,
    playQueue: (List<TrackEntity>, Int) -> Unit = { _, _ -> }) {
    val p=LocalGlassPalette.current; var sheet by rememberSaveable { mutableStateOf(false) }; val scan=state.scan.progress
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(top=112.dp,bottom=228.dp),
        verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Column(Modifier.padding(horizontal=24.dp)) {
            Text(if(library)"Your library" else "Your music.\nAll together.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,color=p.text)
            Spacer(Modifier.height(12.dp))
            GlassSurface(Modifier.clickable(role=Role.Button,onClickLabel="Open scan details"){sheet=true},radius=100.dp) {
                Row(Modifier.heightIn(min=48.dp).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ScanRing(state.scan.active)
                    Text(if(state.scan.active)"${scan.checked} checked" else "${state.songs} songs · ${scan.stage}",color=p.text,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
                }
            }
        }
        if(state.songs==0) GlassCard(Modifier.padding(horizontal=24.dp).fillMaxWidth()) {
            Text("Room for your music",style=MaterialTheme.typography.titleLarge)
            Text("Add a music folder from Settings to start your library.",color=p.secondary)
        } else {
            HomeShelf("Recently added",state.recentlyAdded,playQueue); HomeShelf("Most played",state.mostPlayed,playQueue)
        }
    }
    if(sheet) GlassSheet(onDismissRequest={sheet=false}) { Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Library scan",style=MaterialTheme.typography.headlineSmall,color=p.text); Text(scan.stage,color=p.text); Text("${scan.checked} checked · ${scan.indexed} indexed · ${scan.unchanged} unchanged",color=p.secondary); GlassButton(if(state.scan.active)"Scanning…" else "Rescan",rescan,enabled=!state.scan.active)
    } }
}

@Composable private fun HomeShelf(title:String,tracks:List<TrackEntity>,play:(List<TrackEntity>,Int)->Unit) { val p=LocalGlassPalette.current
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,Modifier.padding(horizontal=24.dp),color=p.text,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        if(tracks.isEmpty()) Text("Play some music and it will appear here.",Modifier.padding(horizontal=24.dp),color=p.secondary)
        else LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { itemsIndexed(tracks,key={_,t->t.id}) { i,t ->
            Column(Modifier.width(136.dp).clickable(role=Role.Button,onClickLabel="Play ${t.title}"){play(tracks,i)}) { HomeArtwork(t,Modifier.fillMaxWidth().aspectRatio(1f)); Spacer(Modifier.height(8.dp)); Text(t.title,color=p.text,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold); Text(t.artist.ifBlank{"Unknown artist"},color=p.text.copy(alpha=.82f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall) }
        } }
    }
}

@Composable private fun HomeArtwork(t:TrackEntity,modifier:Modifier) { val p=LocalGlassPalette.current
    val context=LocalContext.current; val path=t.artworkHash?.let{File(context.cacheDir,"artwork/$it.art").absolutePath}
    val bitmap by produceState<android.graphics.Bitmap?>(null,path) { value=loadArtworkThumbnail(path) }
    val hue=(t.title.hashCode().toUInt().toLong()%360).toFloat(); Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.hsl(hue,.42f,.28f)),contentAlignment=Alignment.Center){ bitmap?.let{Image(it.asImageBitmap(),t.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}?:Text("♪",color=p.text,style=MaterialTheme.typography.headlineMedium) }
}

@Composable private fun ScanRing(active:Boolean) { val p=LocalGlassPalette.current; val moving=active&&!LocalReduceMotion.current
    val rotation=if(moving) rememberInfiniteTransition(label="scan").animateFloat(0f,360f,infiniteRepeatable(tween(2000,easing=LinearEasing)),label="ring") else remember{mutableStateOf(0f)}
    Canvas(Modifier.size(28.dp).semantics{contentDescription=if(active)"Scan in progress" else "Scan status"}) { drawCircle(p.accent.copy(alpha=.18f),style=Stroke(3.dp.toPx())); drawArc(p.accent,rotation.value-90,if(active)100f else 360f,false,style=Stroke(3.dp.toPx())) }
}
