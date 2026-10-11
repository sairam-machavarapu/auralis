package com.auralis.service.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant

object RegistrySchemas {
    private val registryKeys = setOf("schema_version", "updated_at", "extensions")
    private val itemKeys = setOf("id", "display_name", "version", "description", "download_url", "sha256", "icon_url", "category", "tags", "min_app_version")
    private val manifestKeys = setOf("schema_version", "id", "display_name", "version", "description", "author", "homepage", "license", "icon", "entrypoint", "permissions", "network_hosts", "min_app_version")
    private val spotiflacRootKeys = setOf("version", "updated_at", "extensions")
    private val spotiflacRequiredKeys = setOf("id", "name", "display_name", "version", "description", "download_url", "sha256", "category", "tags", "downloads", "updated_at", "min_app_version")
    private val spotiflacOptionalKeys = setOf("icon_url")
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    private val versionPattern = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")
    private val shaPattern = Regex("[0-9a-fA-F]{64}")
    private val permissions = setOf("network", "metadata", "artwork", "lyrics", "catalog", "download")

    fun parseRegistry(text: String): RegistryDocument = wrap {
        val root = JSONObject(text)
        exactKeys(root, registryKeys, "registry")
        require(root.getInt("schema_version") == 1) { "unsupported schema version" }
        val updatedAt = root.string("updated_at", 64)
        runCatching { Instant.parse(updatedAt) }.getOrElse { throw IllegalArgumentException("updated_at must use ISO-8601 UTC") }
        val array = root.getJSONArray("extensions")
        require(array.length() <= 2_000) { "too many extensions" }
        val seen = mutableSetOf<String>()
        RegistryDocument(1, updatedAt, List(array.length()) { parseItem(array.getJSONObject(it)).also { item ->
            require(seen.add(item.id)) { "duplicate extension id '${item.id}'" }
        } })
    }

    /**
     * Keeps Auralis' canonical schema strict while allowing the established SpotiFLAC
     * registry envelope to be browsed. Its packages use a different runtime and are
     * marked explicitly so the store never attempts to install them as Auralis WASM.
     */
    fun parseCompatibleRegistry(text: String): RegistryDocument = try {
        parseRegistry(text)
    } catch (canonical: RegistryFailure.InvalidData) {
        runCatching { parseSpotiFlacRegistry(text) }.getOrElse {
            runCatching { parseExternalRegistry(text) }.getOrElse { external ->
                throw RegistryFailure.InvalidData(external.message ?: canonical.message ?: "unsupported registry format")
            }
        }
    }

    /** Normalizes common third-party registry aliases while retaining hard security limits. */
    private fun parseExternalRegistry(text: String): RegistryDocument = wrap {
        val root = JSONObject(text)
        require(root.length() <= 32) { "registry contains too many root fields" }
        val array = root.firstArray("extensions", "items", "packages")
            ?: throw IllegalArgumentException("registry must contain extensions, items, or packages")
        require(array.length() <= 2_000) { "too many extensions" }
        val updated = root.firstString("updated_at", "updatedAt", "last_updated")
            ?.also { runCatching { Instant.parse(it) }.getOrElse { throw IllegalArgumentException("invalid registry update time") } }
            ?: "1970-01-01T00:00:00Z"
        val seen = mutableSetOf<String>()
        val items = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.length() <= 48) { "extension contains too many fields" }
            val id = item.alias("id", "slug", "package_id").trim().also {
                require(idPattern.matches(it)) { "invalid extension id" }
                require(seen.add(it)) { "duplicate extension id '$it'" }
            }
            val version = item.alias("version", "package_version").trim().also {
                require(versionPattern.matches(it)) { "invalid version for '$id'" }
            }
            val download = item.alias("download_url", "downloadUrl", "package_url", "url").trim().also {
                requireHttps(it, "download URL for '$id'")
            }
            val hash = item.alias("sha256", "sha_256", "checksum", "hash").trim()
                .removePrefix("sha256:").also { require(shaPattern.matches(it)) { "invalid SHA-256 for '$id'" } }.lowercase()
            val display = item.firstString("display_name", "displayName", "title", "name")?.trim().takeUnless { it.isNullOrEmpty() } ?: id
            require(display.length <= 80) { "display name is too long" }
            val description = item.firstString("description", "summary")?.trim().orEmpty().take(240)
            val icon = item.firstString("icon_url", "iconUrl", "icon")?.trim()?.takeIf { runCatching { requireHttps(it, "icon URL") }.isSuccess } ?: download
            val category = item.firstString("category", "type")?.trim().takeUnless { it.isNullOrEmpty() }?.take(40) ?: "extension"
            val tags = item.firstArray("tags", "keywords")?.let { values -> List(values.length().coerceAtMost(22)) { values.getString(it).trim().take(120) } }
                .orEmpty().filter { it.isNotEmpty() }.distinct() + "_external"
            val minimum = item.firstString("min_app_version", "minAppVersion", "minimum_app_version")?.trim()?.takeIf(versionPattern::matches) ?: "0.0.0"
            RegistryExtension(id, display, version, description.ifEmpty { "Third-party extension" }, download, hash, icon, category, tags, minimum)
        }
        RegistryDocument(1, updated, items)
    }

    private fun parseSpotiFlacRegistry(text: String): RegistryDocument = wrap {
        val root = JSONObject(text)
        exactKeys(root, spotiflacRootKeys, "SpotiFLAC registry")
        require(root.getInt("version") == 1) { "unsupported SpotiFLAC registry version" }
        val updatedAt = root.string("updated_at", 64)
        runCatching { Instant.parse(updatedAt) }.getOrElse { throw IllegalArgumentException("updated_at must use ISO-8601 UTC") }
        val array = root.getJSONArray("extensions")
        require(array.length() <= 2_000) { "too many extensions" }
        val seen = mutableSetOf<String>()
        val extensions = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val keys = item.keys().asSequence().toSet()
            require(keys.containsAll(spotiflacRequiredKeys) && keys.all { it in spotiflacRequiredKeys || it in spotiflacOptionalKeys }) {
                "invalid SpotiFLAC extension fields"
            }
            val id = item.validId()
            require(seen.add(id)) { "duplicate extension id '$id'" }
            item.string("name", 80)
            item.getLong("downloads").also { require(it >= 0) { "downloads cannot be negative" } }
            item.string("updated_at", 64).also { runCatching { Instant.parse(it) }.getOrElse { throw IllegalArgumentException("invalid extension updated_at") } }
            val download = item.https("download_url")
            RegistryExtension(id, item.string("display_name", 80), item.version(), item.string("description", 240),
                download, item.string("sha256", 64).also { require(shaPattern.matches(it)) { "invalid sha256" } }.lowercase(),
                if (item.has("icon_url")) item.https("icon_url") else download,
                item.string("category", 40), item.stringList("tags", 23) + "_spotiflac",
                item.version("min_app_version"))
        }
        RegistryDocument(1, updatedAt, extensions)
    }

    fun parseManifest(text: String): ExtensionManifest = wrap {
        val root = JSONObject(text)
        exactKeys(root, manifestKeys, "manifest")
        require(root.getInt("schema_version") == 1) { "unsupported schema version" }
        val requested = root.stringList("permissions", 16)
        require(requested.all { it in permissions }) { "unknown permission" }
        val hosts = root.stringList("network_hosts", 32)
        require(hosts.all(::validHost)) { "invalid network host" }
        require("network" in requested || hosts.isEmpty()) { "network_hosts requires network permission" }
        ExtensionManifest(1, root.validId(), root.string("display_name", 80), root.version(),
            root.string("description", 240), root.string("author", 80), root.https("homepage"),
            root.string("license", 40), root.safeFile("icon"), root.safeFile("entrypoint"),
            requested, hosts, root.version("min_app_version"))
    }

    private fun parseItem(root: JSONObject): RegistryExtension {
        exactKeys(root, itemKeys, "extension")
        return RegistryExtension(root.validId(), root.string("display_name", 80), root.version(),
            root.string("description", 240), root.https("download_url"), root.string("sha256", 64).also {
                require(shaPattern.matches(it)) { "sha256 must contain 64 hexadecimal characters" }
            }.lowercase(), root.https("icon_url"), root.string("category", 40),
            root.stringList("tags", 24), root.version("min_app_version"))
    }

    private inline fun <T> wrap(block: () -> T): T = try { block() }
    catch (error: RegistryFailure) { throw error }
    catch (error: Exception) { throw RegistryFailure.InvalidData(error.message ?: "malformed JSON") }

    private fun exactKeys(value: JSONObject, expected: Set<String>, label: String) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) {
            listOfNotNull((actual - expected).takeIf { it.isNotEmpty() }?.let { "unknown $label fields: ${it.joinToString()}" },
                (expected - actual).takeIf { it.isNotEmpty() }?.let { "missing $label fields: ${it.joinToString()}" }).joinToString("; ")
        }
    }
    private fun JSONObject.string(key: String, max: Int) = getString(key).trim().also { require(it.isNotEmpty() && it.length <= max) { "$key must be 1-$max characters" } }
    private fun JSONObject.validId() = string("id", 64).also { require(idPattern.matches(it)) { "invalid id" } }
    private fun JSONObject.version(key: String = "version") = string(key, 48).also { require(versionPattern.matches(it)) { "$key must be a semantic version" } }
    private fun JSONObject.https(key: String) = string(key, 2_048).also {
        val uri = runCatching { URI(it) }.getOrNull()
        require(uri?.scheme.equals("https", true) && !uri?.host.isNullOrBlank() && uri?.userInfo == null) { "$key must be HTTPS" }
    }
    private fun JSONObject.safeFile(key: String) = string(key, 120).also { require(!it.startsWith('/') && ".." !in it && '\\' !in it) { "$key must be a safe package path" } }
    private fun JSONObject.stringList(key: String, max: Int): List<String> {
        val array: JSONArray = getJSONArray(key)
        require(array.length() <= max) { "$key has too many entries" }
        return List(array.length()) { array.getString(it).trim().also { value -> require(value.isNotEmpty() && value.length <= 120) { "invalid $key entry" } } }
            .also { require(it.distinct().size == it.size) { "$key contains duplicates" } }
    }
    private fun JSONObject.alias(vararg keys: String): String = firstString(*keys)
        ?: throw IllegalArgumentException("missing required field: ${keys.joinToString(" or ")}")
    private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        if (has(key) && !isNull(key) && opt(key) is String) getString(key) else null
    }
    private fun JSONObject.firstArray(vararg keys: String): JSONArray? = keys.firstNotNullOfOrNull { key -> optJSONArray(key) }
    private fun requireHttps(value: String, label: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.scheme.equals("https", true) && !uri?.host.isNullOrBlank() && uri?.userInfo == null) { "$label must use HTTPS" }
    }
    private fun validHost(host: String) = host.length <= 253 && Regex("(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(host)
}
