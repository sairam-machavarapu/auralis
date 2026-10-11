package com.auralis.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.core.database.AuralisDatabase
import com.auralis.core.database.TrackEntity
import com.auralis.service.scanner.ScanScheduler
import com.auralis.service.scanner.ScanState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryScanState(val songs: Int = 0, val folderCount: Int = 0, val loading: Boolean = true,
    val scan: ScanState = ScanState(), val recentlyAdded: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackEntity> = emptyList())

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AuralisDatabase.get(application)
    private val scheduler = ScanScheduler(application)
    val state = combine(db.tracks().observeAvailableCount(), db.folders().observeAll(), scheduler.state,
        db.tracks().observeRecentlyAdded(), db.tracks().observeMostPlayed()) { count, folders, scan, recent, most ->
        LibraryScanState(count, folders.size, false, scan, recent, most)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryScanState())

    init {
        viewModelScope.launch {
            var first = true
            db.folders().observeAll().map { rows -> rows.filter { it.enabled }.map { it.id to it.uri } }.distinctUntilChanged().collect { folders ->
                if (folders.isNotEmpty()) scheduler.request(afterCurrent = !first)
                first = false
            }
        }
    }
    fun rescan() = scheduler.request()
}
