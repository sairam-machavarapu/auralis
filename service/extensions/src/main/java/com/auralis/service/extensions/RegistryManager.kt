package com.auralis.service.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class RegistryManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("extension_repositories", Context.MODE_PRIVATE)
    private val cacheDirectory = java.io.File(context.filesDir, "registry-cache").apply { mkdirs() }

    fun repositories(): List<RepositoryRecord> = runCatching {
        val saved = JSONArray(preferences.getString("repositories", "[]"))
        List(saved.length()) { index ->
            val item = saved.getJSONObject(index)
            val url = item.getString("url")
            RepositoryRecord(url, item.optString("name", URI(url).host), item.optBoolean("trusted"),
                item.optBoolean("enabled", true), item.optLong("lastSyncAt").takeIf { item.has("lastSyncAt") },
                item.optString("lastError").takeIf(String::isNotBlank), readCache(url))
        }
    }.getOrDefault(emptyList())

    suspend fun add(url: String): RepositoryRecord = withContext(Dispatchers.IO) {
        val normalized = validateUrl(url)
        if (repositories().any { it.url.equals(normalized, true) }) throw IllegalArgumentException("This repository is already added.")
        val registry = fetch(normalized)
        val record = RepositoryRecord(normalized, URI(normalized).host, lastSyncAt = System.currentTimeMillis(), registry = registry)
        writeCache(normalized, registry)
        save(repositories() + record)
        record
    }

    suspend fun refresh(url: String): RepositoryRecord = withContext(Dispatchers.IO) {
        val current = repositories().firstOrNull { it.url == url } ?: throw IllegalArgumentException("Repository not found.")
        try {
            val registry = fetch(url)
            writeCache(url, registry)
            current.copy(lastSyncAt = System.currentTimeMillis(), lastError = null, registry = registry).also(::replace)
        } catch (error: Exception) {
            current.copy(lastError = error.message ?: "Sync failed.").also(::replace)
            throw error
        }
    }

    fun remove(url: String) { save(repositories().filterNot { it.url == url }); cacheFile(url).delete() }
    fun setEnabled(url: String, enabled: Boolean) { repositories().firstOrNull { it.url == url }?.copy(enabled = enabled)?.also(::replace) }
    private fun replace(record: RepositoryRecord) = save(repositories().map { if (it.url == record.url) record else it })

    private fun save(records: List<RepositoryRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject().put("url", record.url).put("name", record.name)
            .put("trusted", record.trusted).put("enabled", record.enabled).apply {
                record.lastSyncAt?.let { put("lastSyncAt", it) }
                record.lastError?.let { put("lastError", it) }
            }) }
        preferences.edit().putString("repositories", array.toString()).apply()
    }

    private fun fetch(url: String): RegistryDocument {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Auralis/1")
            val code = connection.responseCode
            if (code !in 200..299) throw RegistryFailure.Http(code)
            if (connection.contentLengthLong > MAX_BYTES) throw RegistryFailure.TooLarge()
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw RegistryFailure.TooLarge()
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            RegistrySchemas.parseCompatibleRegistry(bytes.toString(Charsets.UTF_8))
        } catch (_: SocketTimeoutException) { throw RegistryFailure.Timeout()
        } catch (error: RegistryFailure) { throw error
        } catch (_: Exception) { throw RegistryFailure.Network()
        } finally { connection.disconnect() }
    }

    private fun validateUrl(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw RegistryFailure.InvalidUrl() }
        if (!uri.scheme.equals("https", true)) throw RegistryFailure.InsecureUrl()
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) throw RegistryFailure.InvalidUrl()
        return uri.normalize().toString()
    }

    private fun writeCache(url: String, value: RegistryDocument) = cacheFile(url).writeText(serialize(value))
    private fun readCache(url: String) = runCatching { RegistrySchemas.parseCompatibleRegistry(cacheFile(url).readText()) }.getOrNull()
    private fun cacheFile(url: String): java.io.File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(cacheDirectory, "$hash.json")
    }
    private fun serialize(value: RegistryDocument) = JSONObject().put("schema_version", value.schemaVersion)
        .put("updated_at", value.updatedAt).put("extensions", JSONArray().apply { value.extensions.forEach { item ->
            put(JSONObject().put("id", item.id).put("display_name", item.displayName).put("version", item.version)
                .put("description", item.description).put("download_url", item.downloadUrl).put("sha256", item.sha256)
                .put("icon_url", item.iconUrl).put("category", item.category).put("tags", JSONArray(item.tags))
                .put("min_app_version", item.minAppVersion))
        } }).toString()

    companion object { const val MAX_BYTES = 1_048_576 }
}
