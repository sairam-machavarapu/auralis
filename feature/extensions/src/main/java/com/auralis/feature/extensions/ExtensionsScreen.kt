package com.auralis.feature.extensions

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auralis.core.ui.GlassSurface
import com.auralis.core.ui.LocalGlassPalette
import com.auralis.service.extensions.RegistryExtension
import com.auralis.service.extensions.RegistryManager
import com.auralis.service.extensions.RepositoryRecord
import com.auralis.service.extensions.ExtensionInstaller
import com.auralis.service.extensions.InstallCandidate
import com.auralis.service.extensions.InstalledExtension
import com.auralis.service.extensions.VersionOrder
import com.auralis.service.extensions.ExtensionPermissionStore
import com.auralis.service.extensions.ExtensionSandboxHost
import com.auralis.service.extensions.ExtensionRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

enum class ExtensionTab(val label: String) { Available("Available"), Installed("Installed"), Updates("Updates"), Repositories("Repositories") }

data class ExtensionsState(
    val selected: ExtensionTab = ExtensionTab.Available,
    val repositories: List<RepositoryRecord> = emptyList(),
    val loadingUrl: String? = null,
    val message: String? = null,
    val addOpen: Boolean = false,
    val showTour: Boolean = true,
    val installed: List<InstalledExtension> = emptyList(),
    val candidate: InstallCandidate? = null,
    val details: RegistryExtension? = null,
    val busyExtensionId: String? = null,
    val completedExtensionId: String? = null,
    val installedDetails: InstalledExtension? = null,
    val runtimeStatuses: Map<String, ExtensionRuntimeStatus> = emptyMap(),
) {
    val available: List<RegistryExtension> get() = repositories.filter { it.enabled }.flatMap { it.registry?.extensions.orEmpty() }.distinctBy { it.id }
    val updates: List<RegistryExtension> get() = available.filter { remote -> installed.firstOrNull { it.id == remote.id }?.let { VersionOrder.compare(remote.version, it.version) > 0 } == true }
}

class ExtensionsViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = RegistryManager(application)
    private val appVersion = application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"
    private val installer = ExtensionInstaller(application, appVersion)
    private val permissionStore = ExtensionPermissionStore(application)
    private val sandbox = ExtensionSandboxHost(application, permissionStore)
    private val prefs = application.getSharedPreferences("extension_repositories", 0)
    private val mutable = MutableStateFlow(ExtensionsState(repositories = manager.repositories(), installed = installer.installed(), showTour = !prefs.getBoolean("tour_complete", false)))
    val state = mutable.asStateFlow()

    fun select(tab: ExtensionTab) = mutable.update { it.copy(selected = tab, message = null) }
    fun showAdd(show: Boolean) = mutable.update { it.copy(addOpen = show, message = null) }
    fun dismissMessage() = mutable.update { it.copy(message = null) }
    fun showDetails(item: RegistryExtension) = mutable.update { it.copy(details = item) }
    fun dismissDetails() = mutable.update { it.copy(details = null) }
    fun showInstalledDetails(item: InstalledExtension?) = mutable.update { it.copy(installedDetails = item) }
    fun finishTour() { prefs.edit().putBoolean("tour_complete", true).apply(); mutable.update { it.copy(showTour = false) } }
    fun add(url: String) = launch(url) { manager.add(url); mutable.update { it.copy(addOpen = false) } }
    fun refresh(url: String) = launch(url) { manager.refresh(url) }
    fun remove(url: String) { manager.remove(url); reload() }
    fun enable(url: String, enabled: Boolean) { manager.setEnabled(url, enabled); reload() }
    fun requestInstall(item: RegistryExtension) {
        if (mutable.value.busyExtensionId != null) return
        viewModelScope.launch {
            mutable.update { it.copy(busyExtensionId = item.id, message = null, completedExtensionId = null) }
            runCatching { installer.downloadAndPrepare(item) }
                .onSuccess { candidate -> mutable.update { it.copy(candidate = candidate) } }
                .onFailure { error -> mutable.update { it.copy(message = error.message ?: "Extension could not be prepared.") } }
            mutable.update { it.copy(busyExtensionId = null) }
        }
    }
    fun cancelInstall() { mutable.value.candidate?.let(installer::discard); mutable.update { it.copy(candidate = null) } }
    fun approveInstall() {
        val candidate = mutable.value.candidate ?: return
        viewModelScope.launch {
            mutable.update { it.copy(candidate = null, busyExtensionId = candidate.manifest.id, message = null) }
            runCatching { installer.install(candidate) }
                .onSuccess { installed ->
                    installed.permissions.forEach { permissionStore.set(installed.id, it, installed.permissions, true) }
                    runCatching { sandbox.start(installed) }
                    mutable.update { state -> state.copy(installed = installer.installed(), completedExtensionId = installed.id,
                        runtimeStatuses = state.runtimeStatuses + (installed.id to sandbox.status(installed.id))) }
                }
                .onFailure { error -> mutable.update { it.copy(message = error.message ?: "Extension installation failed.") } }
            mutable.update { it.copy(busyExtensionId = null) }
        }
    }
    fun setExtensionEnabled(id: String, enabled: Boolean) {
        installer.setEnabled(id, enabled)
        val installed = installer.installed().firstOrNull { it.id == id }
        if (!enabled) sandbox.stop(id) else if (installed != null) runCatching { sandbox.start(installed) }
        reload()
    }
    fun setPermission(id: String, permission: String, enabled: Boolean) {
        val installed = installer.installed().firstOrNull { it.id == id } ?: return
        permissionStore.set(id, permission, installed.permissions, enabled)
        mutable.update { it.copy(installedDetails = installed) }
    }
    fun retry(id: String) {
        val installed = installer.installed().firstOrNull { it.id == id } ?: return
        runCatching { sandbox.retry(installed) }
        mutable.update { it.copy(runtimeStatuses = it.runtimeStatuses + (id to sandbox.status(id))) }
    }
    fun uninstall(id: String) { sandbox.stop(id); permissionStore.clear(id); installer.uninstall(id); reload() }
    private fun launch(url: String, action: suspend () -> Unit) {
        if (mutable.value.loadingUrl != null) return
        viewModelScope.launch {
            mutable.update { it.copy(loadingUrl = url, message = null) }
            runCatching { action() }.onFailure { error -> mutable.update { it.copy(message = error.message ?: "Repository operation failed.") } }
            reload()
        }
    }
    private fun reload() = mutable.update { it.copy(repositories = manager.repositories(), installed = installer.installed(), loadingUrl = null) }
}

@Composable
fun ExtensionsScreen(modifier: Modifier = Modifier, model: ExtensionsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    Column(modifier.fillMaxSize().padding(top = 132.dp)) {
        ExtensionTabs(state, model::select)
        when (state.selected) {
            ExtensionTab.Available -> AvailablePanel(state, model::finishTour, model::requestInstall, model::showDetails) { model.select(ExtensionTab.Repositories) }
            ExtensionTab.Repositories -> RepositoriesPanel(state, model)
            ExtensionTab.Installed -> InstalledPanel(state, model::setExtensionEnabled, model::uninstall, model::showInstalledDetails, model::retry)
            ExtensionTab.Updates -> ExtensionList(state.updates, state, model::requestInstall, model::showDetails, "Everything is current", "New versions will appear here after a repository sync.")
        }
    }
    if (state.addOpen) AddRepositorySheet(state.loadingUrl != null, state.message, model::dismissMessage,
        onDismiss = { model.showAdd(false) }, onAdd = model::add)
    state.candidate?.let { PermissionReviewSheet(it, model::cancelInstall, model::approveInstall) }
    state.details?.let { ExtensionDetailsSheet(it, model::dismissDetails) }
    state.installedDetails?.let { installed -> InstalledExtensionDetailsSheet(installed,
        permissionStore = ExtensionPermissionStore(LocalContext.current), onPermission = model::setPermission,
        onDismiss = { model.showInstalledDetails(null) }) }
}

@Composable
private fun ExtensionTabs(state: ExtensionsState, select: (ExtensionTab) -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), radius = 24.dp, strong = true, showRim = false) {
        Column(Modifier.fillMaxWidth().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExtensionTab.entries.chunked(2).forEach { rowTabs ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowTabs.forEach { tab ->
                        val selected = state.selected == tab
                        val count = if (tab == ExtensionTab.Updates) state.updates.size else 0
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (selected) palette.accent.copy(alpha = .22f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(onClickLabel = "Open ${tab.label}") { select(tab) }, contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(tab.label, color = if (selected) palette.accent else palette.secondary,
                                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                if (count > 0) Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(palette.accent), contentAlignment = Alignment.Center) {
                                    Text("$count", color = palette.base, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailablePanel(state: ExtensionsState, finishTour: () -> Unit, install: (RegistryExtension) -> Unit,
    details: (RegistryExtension) -> Unit,
    openRepositories: () -> Unit) {
    val palette = LocalGlassPalette.current
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.message?.let { item { ErrorCard(it) } }
        if (state.showTour) item { GuidedTour(finishTour) }
        if (state.available.isEmpty()) item {
            GlassSurface(Modifier.fillMaxWidth(), radius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(palette.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Text("＋", color = palette.accent, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text("Add your first source", color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Connect a trusted HTTPS repository to browse extensions.", color = palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = openRepositories, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) {
                        Text("Open Repositories")
                    }
                }
            }
        }
        items(state.available, key = { it.id }) { item -> ExtensionCard(item, state, install, details) }
    }
}

@Composable
private fun ExtensionList(items: List<RegistryExtension>, state: ExtensionsState, install: (RegistryExtension) -> Unit,
    details: (RegistryExtension) -> Unit,
    emptyTitle: String, emptyBody: String) {
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (items.isEmpty()) item { EmptyCard(emptyTitle, emptyBody) }
        items(items, key = { it.id }) { ExtensionCard(it, state, install, details) }
    }
}

@Composable
private fun ExtensionCard(item: RegistryExtension, state: ExtensionsState, install: (RegistryExtension) -> Unit,
    details: (RegistryExtension) -> Unit) {
    val palette = LocalGlassPalette.current
    val installed = state.installed.firstOrNull { it.id == item.id }
    val update = installed != null && VersionOrder.compare(item.version, installed.version) > 0
    val spotiflacFormat = "_spotiflac" in item.tags
    val externalFormat = "_external" in item.tags
    val busy = state.busyExtensionId == item.id
    val complete = state.completedExtensionId == item.id
    GlassSurface(Modifier.fillMaxWidth(), radius = 22.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(palette.accent.copy(alpha = .18f)),
                contentAlignment = Alignment.Center) { Text("✦", color = palette.accent, style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.displayName, Modifier.weight(1f, fill = false), color = palette.text, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("v${item.version}", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                }
                Text(item.description, color = palette.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Text(when {
                    "_spotiflac" in item.tags -> "SPOTIFLAC FORMAT · BROWSE ONLY"
                    "_external" in item.tags -> "EXTERNAL FORMAT · BROWSE ONLY"
                    else -> item.category.uppercase()
                },
                    color = if (spotiflacFormat || externalFormat) palette.secondary else palette.accent, style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = { if (spotiflacFormat) details(item) else install(item) },
                enabled = !busy && (installed == null || update),
                contentPadding = PaddingValues(horizontal = 13.dp), modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    complete -> Text("✓")
                    spotiflacFormat -> Text("Details")
                    externalFormat -> Text("Check")
                    update -> Text("Update")
                    installed != null -> Text("Installed")
                    else -> Text("Install")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionDetailsSheet(item: RegistryExtension, dismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    val isSpotiFlac = "_spotiflac" in item.tags
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = palette.base) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.displayName, color = palette.text, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("Version ${item.version} · ${item.category}", color = palette.accent,
                style = MaterialTheme.typography.labelLarge)
            Text(item.description, color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
            GlassSurface(Modifier.fillMaxWidth(), radius = 18.dp, showRim = false) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Package format", color = palette.text, fontWeight = FontWeight.Bold)
                    Text(if (isSpotiFlac) "SpotiFLAC .sflx" else "Third-party package",
                        color = palette.secondary)
                    Text(if (isSpotiFlac)
                        "This package uses the SpotiFLAC runtime and cannot run inside Auralis yet. It is shown so the repository remains browsable."
                    else
                        "Use Check compatibility to download, verify, and inspect this package for an Auralis manifest.",
                        color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Source: ${runCatching { java.net.URI(item.downloadUrl).host }.getOrNull() ?: item.downloadUrl}",
                color = palette.secondary, style = MaterialTheme.typography.bodySmall)
            Button(onClick = dismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp)) { Text("Close") }
        }
    }
}

@Composable
private fun InstalledPanel(state: ExtensionsState, enable: (String, Boolean) -> Unit, uninstall: (String) -> Unit,
    details: (InstalledExtension) -> Unit, retry: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.installed.isEmpty()) item { EmptyCard("No installed extensions", "Choose an extension from Available and review its permissions before installing.") }
        items(state.installed, key = { it.id }) { item ->
            val stopped = state.runtimeStatuses[item.id]?.takeIf { it.stopped }
            GlassSurface(Modifier.fillMaxWidth(), radius = 22.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(palette.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                            Text("✦", color = palette.accent, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) { Text(item.displayName, color = palette.text, fontWeight = FontWeight.Bold)
                            Text("Version ${item.version}", color = palette.secondary, style = MaterialTheme.typography.bodySmall) }
                        Switch(item.enabled, { enable(item.id, it) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                    if (stopped != null) GlassSurface(Modifier.fillMaxWidth(), radius = 16.dp, showRim = false) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Extension stopped", color = palette.text, fontWeight = FontWeight.Bold)
                                Text(stopped.reason ?: "The extension stopped safely.", color = palette.secondary, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { retry(item.id) }) { Text("Retry") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { details(item) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
                        TextButton(onClick = { uninstall(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Uninstall") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledExtensionDetailsSheet(item: InstalledExtension, permissionStore: ExtensionPermissionStore,
    onPermission: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    var grants by remember(item.id) { mutableStateOf(permissionStore.granted(item.id, item.permissions)) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.base) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.displayName, color = palette.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Permissions are off until you grant them. Changes apply to the next extension call.", color = palette.secondary)
            if (item.permissions.isEmpty()) Text("This extension declares no permissions.", color = palette.secondary)
            item.permissions.forEach { permission ->
                GlassSurface(Modifier.fillMaxWidth(), radius = 18.dp, showRim = false) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(permission.replaceFirstChar { it.uppercase() }, color = palette.text, fontWeight = FontWeight.Bold)
                            Text(permissionDescription(permission), color = palette.secondary, style = MaterialTheme.typography.bodySmall) }
                        Switch(permission in grants, { enabled -> grants = if (enabled) grants + permission else grants - permission
                            onPermission(item.id, permission, enabled) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                }
            }
            Button(onClick = onDismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) { Text("Done") }
        }
    }
}

private fun permissionDescription(permission: String) = when (permission) {
    "network" -> "Connect only to HTTPS hosts listed in the extension manifest."
    "metadata" -> "Search and return song, album, and artist information."
    "artwork" -> "Find and return album or artist artwork references."
    "download" -> "Prepare an authorized download when you request one."
    else -> "Allow the $permission capability."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionReviewSheet(candidate: InstallCandidate, dismiss: () -> Unit, approve: () -> Unit) {
    val palette = LocalGlassPalette.current
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = palette.base) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (candidate.isUpdate) "Review update" else "Review permissions", color = palette.text,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${candidate.manifest.displayName} ${candidate.manifest.version}", color = palette.secondary)
            if (candidate.manifest.permissions.isEmpty()) Text("This extension requests no capabilities.", color = palette.secondary)
            candidate.manifest.permissions.forEach { permission -> PermissionRow(permission, permission in candidate.newPermissions) }
            if (candidate.isUpdate && candidate.newPermissions.isNotEmpty()) {
                Text("This update adds ${candidate.newPermissions.size} permission${if (candidate.newPermissions.size == 1) "" else "s"}. Your approval is required again.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = approve, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) {
                Text(if (candidate.isUpdate) "Approve update" else "Approve and install")
            }
            TextButton(onClick = dismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

@Composable
private fun PermissionRow(permission: String, added: Boolean) {
    val palette = LocalGlassPalette.current
    val description = when (permission) {
        "network" -> "Connect only to the hosts declared by this extension."
        "metadata" -> "Read and provide song, album, and artist information."
        "artwork" -> "Read and provide album and artist artwork."
        "download" -> "Create authorized downloads after you request them."
        else -> "Use the $permission capability."
    }
    GlassSurface(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(permission.replaceFirstChar { it.uppercase() } + if (added) "  • NEW" else "", color = if (added) palette.accent else palette.text, fontWeight = FontWeight.Bold)
            Text(description, color = palette.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GuidedTour(onDone: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), radius = 24.dp, strong = true) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Start with a trusted source", color = palette.text, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                    Text("Add, review, then install", color = palette.secondary, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
            }
            val steps = listOf("Open store", "Repositories", "Add HTTPS URL", "Browse", "Review access", "Install")
            steps.chunked(2).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { columnIndex, step ->
                        val number = rowIndex * 2 + columnIndex + 1
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(palette.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                                Text("$number", color = palette.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(step, Modifier.weight(1f), color = palette.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoriesPanel(state: ExtensionsState, model: ExtensionsViewModel) {
    val palette = LocalGlassPalette.current
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Repository sources", color = palette.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("HTTPS registries you control", color = palette.secondary, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { model.showAdd(true) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) { Text("＋ Add") }
        } }
        state.message?.let { message -> item { ErrorCard(message) } }
        if (state.repositories.isEmpty()) item { EmptyCard("No repositories", "Only add sources you know and trust.") }
        items(state.repositories, key = { it.url }) { repository ->
            GlassSurface(Modifier.fillMaxWidth(), radius = 22.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(repository.name, color = palette.text, fontWeight = FontWeight.Bold)
                                if (repository.trusted) Text("TRUSTED", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(repository.url, color = palette.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(repository.enabled, { model.enable(repository.url, it) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                    }
                    Text(repository.lastSyncAt?.let { "Synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" }
                        ?: "Not synced", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                    repository.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.refresh(repository.url) }, enabled = state.loadingUrl == null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.loadingUrl == repository.url) "Refreshing…" else "Refresh") }
                        TextButton(onClick = { model.remove(repository.url) }, enabled = state.loadingUrl == null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRepositorySheet(loading: Boolean, message: String?, clearMessage: () -> Unit, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalGlassPalette.current.base) {
        val palette = LocalGlassPalette.current
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add repository", color = palette.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Unknown repositories can publish untrusted code. Add only a source you recognize. Auralis validates the registry before saving it.", color = palette.secondary)
            OutlinedTextField(url, { url = it; clearMessage() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("HTTPS registry URL") }, placeholder = { Text("https://example.com/registry.json") },
                isError = message != null, supportingText = { message?.let { Text(it) } })
            Button(onClick = { onAdd(url) }, enabled = !loading && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Validate and add")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) = Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) { EmptyCard(title, body) }

@Composable
private fun EmptyCard(title: String, body: String) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(palette.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                Text("✦", color = palette.accent, style = MaterialTheme.typography.headlineSmall)
            }
            Text(title, color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    GlassSurface(Modifier.fillMaxWidth(), radius = 18.dp, showRim = false) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
