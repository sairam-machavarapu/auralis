package com.auralis.service.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Validates the stable ABI-1 response envelope before extension data reaches app features. */
internal object ExtensionProtocol {
    fun validate(export: String, text: String): String {
        val root = runCatching { JSONObject(text) }.getOrElse { throw ExtensionRuntimeFailure.InvalidResponse() }
        when (export) {
            "search" -> {
                exact(root, setOf("items"), setOf("next_offset"))
                array(root, "items", 100)
                if (root.has("next_offset")) require(root.getInt("next_offset") >= 0)
            }
            "get_metadata", "get_album", "get_artist", "get_playlist" -> {
                require(root.optString("id").isNotBlank())
            }
            "get_artwork" -> {
                exact(root, setOf("url"))
                https(root.getString("url"))
            }
            "get_lyrics" -> {
                exact(root, emptySet(), setOf("plain", "synced", "language"))
                require(root.has("plain") || root.has("synced"))
                listOf("plain", "synced", "language").filter(root::has).forEach {
                    require(root.getString(it).length <= MAX_TEXT_LENGTH)
                }
            }
            "get_home_feed" -> {
                exact(root, setOf("sections"), setOf("next_offset"))
                array(root, "sections", 50)
                if (root.has("next_offset")) require(root.getInt("next_offset") >= 0)
            }
            "resolve_download" -> {
                exact(root, setOf("url", "mime_type"), setOf("file_name", "content_length"))
                https(root.getString("url"))
                require(root.getString("mime_type").startsWith("audio/") && root.getString("mime_type").length <= 100)
                if (root.has("file_name")) require(root.getString("file_name").length in 1..180)
                if (root.has("content_length")) require(root.getLong("content_length") > 0)
            }
            else -> throw ExtensionRuntimeFailure.InvalidResponse()
        }
        return text
    }

    private fun exact(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional })
    }

    private fun array(value: JSONObject, key: String, maximum: Int): JSONArray =
        value.getJSONArray(key).also { require(it.length() <= maximum) }

    private fun https(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
    }

    private const val MAX_TEXT_LENGTH = 512 * 1024
}
