package com.auralis.service.extensions

import android.content.Context
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ExtensionSearchOptions(val limit: Int = 25, val offset: Int = 0)
data class ExtensionSearchResult(val json: String)
data class ExtensionMetadataResult(val json: String)
data class ExtensionArtworkResult(val json: String)

enum class ExtensionCapability(val wireName: String) {
    Network("network"), Metadata("metadata"), Artwork("artwork");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wireName == value } }
}

sealed class ExtensionRuntimeFailure(message: String) : Exception(message) {
    class Permission(permission: String) : ExtensionRuntimeFailure("Permission denied: $permission")
    class Host(host: String) : ExtensionRuntimeFailure("Network access to $host is not allowed.")
    class Timeout : ExtensionRuntimeFailure("The extension exceeded its time limit.")
    class CpuLimit : ExtensionRuntimeFailure("The extension exceeded its CPU limit.")
    class MemoryLimit : ExtensionRuntimeFailure("The extension requested too much memory.")
    class Stopped(reason: String) : ExtensionRuntimeFailure(reason)
    class InvalidResponse : ExtensionRuntimeFailure("The extension returned an invalid response.")
}

data class ExtensionRuntimeStatus(val stopped: Boolean = false, val reason: String? = null, val stoppedAt: Long? = null)

/** Stored grants are deny-by-default and may only enable capabilities declared by the manifest. */
class ExtensionPermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("extension_permission_grants", Context.MODE_PRIVATE)
    fun granted(id: String, declared: List<String>): Set<String> = declared.filterTo(mutableSetOf()) {
        prefs.getBoolean("$id:$it", false)
    }
    fun set(id: String, permission: String, declared: List<String>, enabled: Boolean) {
        require(permission in declared) { "Extension did not declare $permission." }
        prefs.edit().putBoolean("$id:$permission", enabled).apply()
    }
    fun clear(id: String) { prefs.all.keys.filter { it.startsWith("$id:") }.let { keys ->
        prefs.edit().also { edit -> keys.forEach(edit::remove) }.apply()
    } }
}

/** HTTPS-only client with an exact/subdomain host allowlist and bounded responses. */
class ExtensionNetworkClient {
    suspend fun get(url: String, granted: Set<String>, allowedHosts: List<String>): ByteArray = withContext(Dispatchers.IO) {
        if ("network" !in granted) throw ExtensionRuntimeFailure.Permission("network")
        val uri = runCatching { URI(url) }.getOrNull() ?: throw ExtensionRuntimeFailure.Host("invalid URL")
        val host = uri.host?.let { IDN.toASCII(it).lowercase() } ?: throw ExtensionRuntimeFailure.Host("invalid host")
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || allowedHosts.none { allowed ->
                host == allowed.lowercase() || host.endsWith(".${allowed.lowercase()}")
            }) throw ExtensionRuntimeFailure.Host(host)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            if (connection.responseCode !in 200..299) throw ExtensionRuntimeFailure.Stopped("The provider returned HTTP ${connection.responseCode}.")
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw ExtensionRuntimeFailure.Stopped("The provider response is too large.")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) { val read = input.read(buffer); if (read < 0) break
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw ExtensionRuntimeFailure.Stopped("The provider response is too large.")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    companion object { const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
}

internal interface SandboxedModule : AutoCloseable { fun call(export: String, json: String): String }

internal class ChicorySandboxedModule(file: File) : SandboxedModule {
    private val instructionCount = AtomicLong()
    private val module = Parser.parse(file)
    private val instance: Instance
    init {
        val memory = module.memorySection().orElse(null)?.let { section ->
            if (section.memoryCount() > 1) throw ExtensionRuntimeFailure.MemoryLimit()
            if (section.memoryCount() == 1) section.getMemory(0).limits() else null
        }
        // A missing module maximum is represented as the WebAssembly global maximum.
        // Reject only an initial allocation above our cap; the host override clamps all growth.
        if (memory != null && memory.initialPages() > MAX_MEMORY_PAGES)
            throw ExtensionRuntimeFailure.MemoryLimit()
        instance = Instance.builder(module)
            .withMemoryLimits(MemoryLimits(1, MAX_MEMORY_PAGES))
            .withUnsafeExecutionListener { _, _ ->
                if (instructionCount.incrementAndGet() > MAX_INSTRUCTIONS) throw ExtensionRuntimeFailure.CpuLimit()
            }.build()
    }
    override fun call(export: String, json: String): String {
        instructionCount.set(0)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_INPUT_BYTES) { "Request is too large." }
        val alloc = instance.export("alloc") ?: throw ExtensionRuntimeFailure.Stopped("Missing alloc export.")
        val pointer = alloc.apply(bytes.size.toLong()).firstOrNull()?.toInt() ?: throw ExtensionRuntimeFailure.InvalidResponse()
        instance.memory().write(pointer, bytes)
        try {
            val packed = instance.export(export)?.apply(pointer.toLong(), bytes.size.toLong())?.firstOrNull()
                ?: throw ExtensionRuntimeFailure.Stopped("Missing $export export.")
            val resultPointer = (packed ushr 32).toInt()
            val resultLength = (packed and 0xffffffffL).toInt()
            if (resultLength !in 0..MAX_OUTPUT_BYTES) throw ExtensionRuntimeFailure.InvalidResponse()
            return instance.memory().readString(resultPointer, resultLength).also { JSONObject(it) }
        } finally { runCatching { instance.export("dealloc")?.apply(pointer.toLong(), bytes.size.toLong()) } }
    }
    override fun close() = Unit
    companion object {
        const val MAX_MEMORY_PAGES = 256 // 16 MiB
        const val MAX_INSTRUCTIONS = 5_000_000L
        const val MAX_INPUT_BYTES = 256 * 1024
        const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }
}

class ExtensionSandboxHost private constructor(private val context: Context, private val permissions: ExtensionPermissionStore,
    private val moduleFactory: (File) -> SandboxedModule) {
    constructor(context: Context, permissions: ExtensionPermissionStore = ExtensionPermissionStore(context)) :
        this(context, permissions, ::ChicorySandboxedModule)
    internal constructor(context: Context, permissions: ExtensionPermissionStore, moduleFactory: (File) -> SandboxedModule,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit) : this(context, permissions, moduleFactory)
    private data class Runtime(val manifest: ExtensionManifest, val module: SandboxedModule,
        val executor: java.util.concurrent.ExecutorService, val dispatcher: ExecutorCoroutineDispatcher)
    private val runtimes = ConcurrentHashMap<String, Runtime>()
    private val statuses = ConcurrentHashMap<String, ExtensionRuntimeStatus>()
    private val supervisor = SupervisorJob()

    fun status(id: String) = statuses[id] ?: ExtensionRuntimeStatus()
    fun start(installed: InstalledExtension) {
        stop(installed.id)
        try {
            val root = File(installed.installPath)
            val manifest = RegistrySchemas.parseManifest(File(root, "manifest.json").readText())
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(null, runnable, "auralis-wasm-${installed.id}", 2L * 1024 * 1024) }
            val module = moduleFactory(File(root, manifest.entrypoint))
            runtimes[installed.id] = Runtime(manifest, module, executor, executor.asCoroutineDispatcher())
            statuses.remove(installed.id)
        } catch (error: Throwable) {
            statuses[installed.id] = ExtensionRuntimeStatus(true, error.message ?: "The extension could not start.", System.currentTimeMillis())
            throw error
        }
    }
    fun stop(id: String) { runtimes.remove(id)?.let { runtime -> runtime.module.close(); runtime.dispatcher.close(); runtime.executor.shutdownNow() } }
    fun retry(installed: InstalledExtension) = start(installed)

    suspend fun search(id: String, query: String, options: ExtensionSearchOptions = ExtensionSearchOptions()) =
        ExtensionSearchResult(invoke(id, "search", "metadata", JSONObject().put("query", query.take(500))
            .put("options", JSONObject().put("limit", options.limit.coerceIn(1, 100)).put("offset", options.offset.coerceAtLeast(0))).toString()))
    suspend fun metadata(id: String, trackId: String) = ExtensionMetadataResult(invoke(id, "get_metadata", "metadata", JSONObject().put("id", trackId.take(500)).toString()))
    suspend fun artwork(id: String, artworkId: String) = ExtensionArtworkResult(invoke(id, "get_artwork", "artwork", JSONObject().put("id", artworkId.take(500)).toString()))

    private suspend fun invoke(id: String, export: String, permission: String, payload: String): String {
        val runtime = runtimes[id] ?: throw ExtensionRuntimeFailure.Stopped(status(id).reason ?: "Extension is not running.")
        if (permission !in permissions.granted(id, runtime.manifest.permissions)) throw ExtensionRuntimeFailure.Permission(permission)
        return try { withContext(Dispatchers.IO + supervisor) {
            val future = runtime.executor.submit<String> { runtime.module.call(export, payload) }
            try { future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { future.cancel(true); throw ExtensionRuntimeFailure.Timeout() }
        } }
        catch (error: Throwable) {
            val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
            val failure = cause
            stop(id); statuses[id] = ExtensionRuntimeStatus(true, failure.message ?: "The extension stopped unexpectedly.", System.currentTimeMillis())
            throw failure
        }
    }
    companion object { const val CALL_TIMEOUT_MS = 2_000L }
}
