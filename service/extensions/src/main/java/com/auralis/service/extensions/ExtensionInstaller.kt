package com.auralis.service.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

class ExtensionInstaller(private val context: Context, private val appVersion: String) {
    private val preferences = context.getSharedPreferences("installed_extensions", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "extensions").apply { mkdirs() }
    private val downloads = File(context.cacheDir, "extension-packages").apply { mkdirs() }
    private val audit = ExtensionAuditLog(context)

    fun installed(): List<InstalledExtension> = runCatching {
        val array = JSONArray(preferences.getString("installed", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).toInstalled() }
    }.getOrDefault(emptyList())

    suspend fun downloadAndPrepare(item: RegistryExtension): InstallCandidate = withContext(Dispatchers.IO) {
        require(item.downloadUrl.startsWith("https://")) { "Package URL must use HTTPS." }
        val target = File(downloads, "${item.id}-${UUID.randomUUID()}.lmx")
        val connection = URL(item.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Auralis/1")
            if (connection.responseCode !in 200..299) throw InstallFailure.Network()
            if (connection.contentLengthLong > MAX_PACKAGE_BYTES) throw InstallFailure.PackageTooLarge()
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(16_384); var total = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; total += read
                    if (total > MAX_PACKAGE_BYTES) throw InstallFailure.PackageTooLarge()
                    output.write(buffer, 0, read) }
            } }
            prepareFile(item, target)
        } catch (error: InstallFailure) { target.delete(); throw error
        } catch (_: Exception) { target.delete(); throw InstallFailure.Network()
        } finally { connection.disconnect() }
    }

    fun prepareFile(item: RegistryExtension, packageFile: File): InstallCandidate {
        if (!sha256(packageFile).equals(item.sha256, true)) {
            audit.record("verify", item.id, item.version, "hash_mismatch")
            throw InstallFailure.HashMismatch()
        }
        val manifest = try {
            ZipFile(packageFile).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: throw InstallFailure.UnsupportedFormat()
                if (entry.size > MAX_MANIFEST_BYTES) throw InstallFailure.InvalidPackage("manifest.json is too large")
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                RegistrySchemas.parseManifest(text)
            }
        } catch (error: InstallFailure) { throw error
        } catch (error: RegistryFailure) { throw InstallFailure.InvalidPackage(error.message ?: "manifest validation failed")
        } catch (_: Exception) { throw InstallFailure.InvalidPackage("the archive cannot be read") }
        if (manifest.id != item.id || manifest.version != item.version) throw InstallFailure.InvalidPackage("manifest identity does not match the registry")
        if (VersionOrder.compare(appVersion, manifest.minAppVersion) < 0) {
            audit.record("verify", item.id, item.version, "incompatible")
            throw InstallFailure.Incompatible(manifest.minAppVersion)
        }
        val previous = installed().firstOrNull { it.id == item.id }
        return InstallCandidate(item, manifest, packageFile.absolutePath, previous != null,
            manifest.permissions.filterNot { it in previous?.permissions.orEmpty() })
    }

    suspend fun install(candidate: InstallCandidate): InstalledExtension = withContext(Dispatchers.IO) {
        val packageFile = File(candidate.packagePath)
        // Recheck immediately before install so a swapped temporary file cannot bypass integrity verification.
        if (!sha256(packageFile).equals(candidate.registry.sha256, true)) throw InstallFailure.HashMismatch()
        val extensionRoot = File(root, candidate.manifest.id)
        val current = File(extensionRoot, "current")
        val rollback = File(extensionRoot, "rollback")
        val staging = File(extensionRoot, "staging-${UUID.randomUUID()}")
        extensionRoot.mkdirs(); staging.mkdirs()
        try {
            extract(packageFile, staging)
            rollback.deleteRecursively()
            if (current.exists() && !current.renameTo(rollback)) throw InstallFailure.InvalidPackage("could not create rollback copy")
            if (!staging.renameTo(current)) {
                if (rollback.exists()) rollback.renameTo(current)
                throw InstallFailure.InvalidPackage("could not activate package")
            }
            val now = System.currentTimeMillis()
            val old = installed().firstOrNull { it.id == candidate.manifest.id }
            val record = InstalledExtension(candidate.manifest.id, candidate.manifest.displayName, candidate.manifest.version,
                candidate.manifest.description, true, candidate.manifest.permissions, old?.installedAt ?: now, now, current.absolutePath)
            save(installed().filterNot { it.id == record.id } + record)
            audit.record(if (candidate.isUpdate) "update" else "install", record.id, record.version, "success")
            packageFile.delete()
            record
        } catch (error: Exception) {
            audit.record(if (candidate.isUpdate) "update" else "install", candidate.manifest.id,
                candidate.manifest.version, "failed")
            staging.deleteRecursively(); throw error
        }
    }

    fun rollback(id: String): InstalledExtension {
        val old = installed().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Extension is not installed.")
        val extensionRoot = File(root, id)
        val current = File(extensionRoot, "current")
        val rollback = File(extensionRoot, "rollback")
        if (!rollback.isDirectory) throw IllegalStateException("No rollback version is available.")
        val swap = File(extensionRoot, "rollback-swap-${UUID.randomUUID()}")
        if (!current.renameTo(swap)) throw IllegalStateException("The current version could not be prepared for rollback.")
        if (!rollback.renameTo(current)) {
            swap.renameTo(current)
            throw IllegalStateException("The rollback version could not be restored.")
        }
        if (!swap.renameTo(rollback)) {
            current.renameTo(rollback)
            swap.renameTo(current)
            throw IllegalStateException("The rollback could not be completed.")
        }
        return try {
            val manifest = RegistrySchemas.parseManifest(File(current, "manifest.json").readText())
            val restored = old.copy(displayName = manifest.displayName, version = manifest.version,
                description = manifest.description, permissions = manifest.permissions, updatedAt = System.currentTimeMillis(),
                installPath = current.absolutePath)
            save(installed().filterNot { it.id == id } + restored)
            audit.record("rollback", id, restored.version, "success")
            restored
        } catch (error: Exception) {
            audit.record("rollback", id, old.version, "failed")
            throw error
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = save(installed().map { if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it })
    fun uninstall(id: String) {
        // Only the extension's app-private directory is removed. User library files are never touched.
        File(root, id).deleteRecursively()
        ExtensionPermissionStore(context).clear(id)
        save(installed().filterNot { it.id == id })
        audit.record("uninstall", id, null, "success")
    }
    fun discard(candidate: InstallCandidate) { File(candidate.packagePath).delete() }

    private fun extract(packageFile: File, target: File) {
        ZipFile(packageFile).use { zip ->
            var expanded = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val output = File(target, entry.name)
                val rootPath = target.canonicalPath + File.separator
                if (!output.canonicalPath.startsWith(rootPath)) throw InstallFailure.InvalidPackage("unsafe archive path")
                if (entry.isDirectory) { output.mkdirs(); continue }
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> output.outputStream().use { destination ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        expanded += read
                        if (expanded > MAX_EXPANDED_BYTES) throw InstallFailure.InvalidPackage("expanded package is too large")
                        destination.write(buffer, 0, read)
                    }
                } }
            }
        }
        val manifest = RegistrySchemas.parseManifest(File(target, "manifest.json").readText())
        if (!File(target, manifest.icon).isFile || !File(target, manifest.entrypoint).isFile) {
            throw InstallFailure.InvalidPackage("the declared icon or entrypoint is missing")
        }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer = ByteArray(16_384); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun save(items: List<InstalledExtension>) {
        val array = JSONArray(); items.forEach { item -> array.put(JSONObject().put("id", item.id).put("displayName", item.displayName)
            .put("version", item.version).put("description", item.description).put("enabled", item.enabled)
            .put("permissions", JSONArray(item.permissions)).put("installedAt", item.installedAt).put("updatedAt", item.updatedAt)
            .put("installPath", item.installPath)) }
        preferences.edit().putString("installed", array.toString()).apply()
    }
    private fun JSONObject.toInstalled() = InstalledExtension(getString("id"), getString("displayName"), getString("version"),
        getString("description"), getBoolean("enabled"), getJSONArray("permissions").let { array -> List(array.length()) { array.getString(it) } },
        getLong("installedAt"), getLong("updatedAt"), getString("installPath"))

    companion object { const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024; const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024; const val MAX_MANIFEST_BYTES = 32L * 1024 }
}

private class ExtensionAuditLog(context: Context) {
    private val file = android.util.AtomicFile(File(context.filesDir, "extension-audit.jsonl"))
    @Synchronized fun record(event: String, id: String, version: String?, result: String) {
        val previous = runCatching { file.openRead().bufferedReader().readText() }.getOrDefault("")
            .takeLast(MAX_BYTES / 2)
        val row = JSONObject().put("time", System.currentTimeMillis()).put("event", event.take(24))
            .put("extension", id.take(64)).put("result", result.take(32)).apply { version?.let { put("version", it.take(48)) } }
            .toString() + "\n"
        val output = file.startWrite()
        try { output.write((previous + row).takeLast(MAX_BYTES).toByteArray()); file.finishWrite(output) }
        catch (_: Exception) { file.failWrite(output) }
    }
    companion object { const val MAX_BYTES = 64 * 1024 }
}
