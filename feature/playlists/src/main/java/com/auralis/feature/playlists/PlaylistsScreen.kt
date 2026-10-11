package com.auralis.feature.playlists

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.auralis.core.database.*
import com.auralis.core.ui.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(application: Application): AndroidViewModel(application) {
    private val dao=AuralisDatabase.get(application).playlists()
    val playlists=dao.observeAll().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    fun create(name:String)=viewModelScope.launch { val clean=name.trim(); if(clean.isNotEmpty()) { val now=System.currentTimeMillis(); dao.insert(PlaylistEntity(name=clean,createdAt=now,updatedAt=now)) } }
    fun rename(id:Long,name:String)=viewModelScope.launch { name.trim().takeIf{it.isNotEmpty()}?.let{dao.rename(id,it,System.currentTimeMillis())} }
    fun delete(id:Long)=viewModelScope.launch { dao.delete(id) }
}

@Composable fun PlaylistsScreen(viewModel: PlaylistsViewModel) {
    val rows by viewModel.playlists.collectAsStateWithLifecycle(); val p=LocalGlassPalette.current
    var editor by remember { mutableStateOf<PlaylistEntity?>(null) }; var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top=132.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick={creating=true}) { Text("+ New") }
        }
        if(rows.isEmpty()) Box(Modifier.fillMaxSize().padding(bottom=220.dp),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("Your playlists will live here.",color=p.secondary); GlassButton("Create playlist", { creating=true }) }
        } else LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,top=16.dp,bottom=228.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            items(rows,key={it.id}) { playlist -> GlassSurface(Modifier.fillMaxWidth().aspectRatio(1f).clickable{editor=playlist},radius=24.dp) {
                Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.Bottom) { Text("♫",color=p.accent,style=MaterialTheme.typography.headlineLarge); Spacer(Modifier.weight(1f)); Text(playlist.name,color=p.text,maxLines=2,fontWeight=FontWeight.Bold); Text("Playlist",color=p.secondary) }
            } }
        }
    }
    if(creating) PlaylistEditor("New playlist","",{creating=false},{viewModel.create(it);creating=false})
    editor?.let { item -> PlaylistEditor("Edit playlist",item.name,{editor=null},{viewModel.rename(item.id,it);editor=null},delete={viewModel.delete(item.id);editor=null}) }
}

@Composable private fun PlaylistEditor(title:String,initial:String,dismiss:()->Unit,save:(String)->Unit,delete:(()->Unit)?=null) {
    var name by remember(initial){mutableStateOf(TextFieldValue(initial))}
    GlassSheet(onDismissRequest=dismiss) { Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth().heightIn(min=56.dp),label={Text("Playlist name")},singleLine=true)
        GlassButton("Save",{save(name.text)},enabled=name.text.isNotBlank())
        delete?.let { TextButton(onClick=it,Modifier.fillMaxWidth()){Text("Delete playlist",color=MaterialTheme.colorScheme.error)} }
    } }
}
