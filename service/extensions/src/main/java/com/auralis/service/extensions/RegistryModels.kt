package com.auralis.service.extensions

data class RegistryDocument(val schemaVersion: Int, val updatedAt: String, val extensions: List<RegistryExtension>)

data class RegistryExtension(
    val id: String, val displayName: String, val version: String, val description: String,
    val downloadUrl: String, val sha256: String, val iconUrl: String, val category: String,
    val tags: List<String>, val minAppVersion: String
)

data class ExtensionManifest(
    val schemaVersion: Int, val id: String, val displayName: String, val version: String,
    val description: String, val author: String, val homepage: String, val license: String,
    val icon: String, val entrypoint: String, val permissions: List<String>,
    val networkHosts: List<String>, val minAppVersion: String
)

data class RepositoryRecord(
    val url: String, val name: String, val trusted: Boolean = false, val enabled: Boolean = true,
    val lastSyncAt: Long? = null, val lastError: String? = null, val registry: RegistryDocument? = null
)

data class InstalledExtension(
    val id: String, val displayName: String, val version: String, val description: String,
    val enabled: Boolean, val permissions: List<String>, val installedAt: Long,
    val updatedAt: Long, val installPath: String
)

data class InstallCandidate(
    val registry: RegistryExtension, val manifest: ExtensionManifest, val packagePath: String,
    val isUpdate: Boolean, val newPermissions: List<String>
)

sealed class RegistryFailure(message: String) : Exception(message) {
    class InsecureUrl : RegistryFailure("Only secure HTTPS repository URLs are supported.")
    class InvalidUrl : RegistryFailure("Enter a valid repository URL.")
    class Timeout : RegistryFailure("The repository took too long to respond. Try again.")
    class TooLarge : RegistryFailure("The repository response is larger than 1 MB.")
    class Http(code: Int) : RegistryFailure("The repository returned HTTP $code.")
    class InvalidData(reason: String) : RegistryFailure("This repository is not valid: $reason")
    class Network : RegistryFailure("A secure connection to the repository could not be made.")
}

sealed class InstallFailure(message: String) : Exception(message) {
    class HashMismatch : InstallFailure("Security check failed: the package SHA-256 does not match the registry.")
    class PackageTooLarge : InstallFailure("The extension package is larger than 32 MB.")
    class InvalidPackage(reason: String) : InstallFailure("This extension package is invalid: $reason")
    class UnsupportedFormat : InstallFailure("Package verified, but it does not contain an Auralis manifest and cannot be installed.")
    class Incompatible(required: String) : InstallFailure("This extension needs Auralis $required or newer.")
    class Network : InstallFailure("The extension package could not be downloaded securely.")
}

object VersionOrder {
    fun compare(left: String, right: String): Int {
        val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return when {
            '-' !in left && '-' in right -> 1
            '-' in left && '-' !in right -> -1
            else -> left.compareTo(right)
        }
    }
}
