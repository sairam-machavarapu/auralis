package com.auralis.feature.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.core.database.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FoldersState(val folders: List<MusicFolder>? = null, val error: String? = null)

class FoldersViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FolderRepository(application, AuralisDatabase.get(application).folders())
    private val error = MutableStateFlow<String?>(null)
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val state = combine(repository.folders, error) { folders, message -> FoldersState(folders, message) }
        .catch { emit(FoldersState(error = "Your folders could not be loaded. Close and reopen Auralis to try again.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersState())

    fun refresh() = repository.refreshAccess()
    fun clearError() { error.value = null }
    fun pickerUnavailable() { error.value = "The folder picker is unavailable. Enable the system Files app and try again." }
    fun add(uri: Uri) = perform { repository.add(uri) }
    fun enable(folder: MusicFolder, enabled: Boolean) = perform { repository.setEnabled(folder.record.id, enabled) }
    fun remove(folder: MusicFolder) = perform { repository.remove(folder.record) }

    private fun perform(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        error.value = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { error.value = "Folder access was not saved. Choose the folder again and allow access." }
            catch (_: Exception) { error.value = "That folder could not be updated. Check that it is available and try again." }
            finally { mutableBusy.value = false }
        }
    }
}
