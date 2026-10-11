package com.auralis.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class AppRelease(
    val version: String,
    val name: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

internal sealed interface AppUpdateResult {
    data class Available(val release: AppRelease) : AppUpdateResult
    data object Current : AppUpdateResult
    data class Failed(val message: String) : AppUpdateResult
}

internal object AppUpdateChecker {
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/sairam-machavarapu/auralis/releases/latest"

    suspend fun check(currentVersion: String): AppUpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Auralis/${BuildConfig.VERSION_NAME}")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                    HttpURLConnection.HTTP_NOT_FOUND -> AppUpdateResult.Current
                    else -> AppUpdateResult.Failed("Update service returned ${connection.responseCode}.")
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { AppUpdateResult.Failed("Couldn’t check for updates. Check your connection and try again.") }
    }

    private fun parseRelease(json: String, currentVersion: String): AppUpdateResult {
        val root = JSONObject(json)
        val tag = root.getString("tag_name")
        val version = tag.removePrefix("v")
        if (compareVersions(version, currentVersion) <= 0) return AppUpdateResult.Current
        val assets = root.getJSONArray("assets")
        val apk = (0 until assets.length()).asSequence()
            .map(assets::getJSONObject)
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val releaseUrl = root.getString("html_url")
        return AppUpdateResult.Available(AppRelease(
            version = version,
            name = root.optString("name").ifBlank { "Auralis $version" },
            downloadUrl = apk?.getString("browser_download_url") ?: releaseUrl,
            releaseUrl = releaseUrl,
        ))
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
