package com.auralis.service.extensions

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExtensionSandboxTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun permissionsAreDeniedUntilExplicitlyGranted() = runBlocking {
        val fixture = fixture()
        val store = ExtensionPermissionStore(context).also { it.clear(fixture.id) }
        val host = ExtensionSandboxHost(context, store, { SampleModule() })
        host.start(fixture)
        assertThrows(ExtensionRuntimeFailure.Permission::class.java) { runBlocking { host.search(fixture.id, "jazz") } }
        store.set(fixture.id, "metadata", fixture.permissions, true)
        assertTrue(host.search(fixture.id, "jazz").json.contains("jazz"))
        host.stop(fixture.id)
    }

    @Test fun realSampleWasmRunsThroughJsonAbi() = runBlocking {
        val fixture = fixture("real-wasm-sample")
        val store = ExtensionPermissionStore(context).also { it.clear(fixture.id); it.set(fixture.id, "metadata", fixture.permissions, true) }
        val host = ExtensionSandboxHost(context, store)
        host.start(fixture)
        assertEquals("{\"items\":[]}", host.search(fixture.id, "sample").json)
        host.stop(fixture.id)
    }

    @Test fun extendedCapabilitiesUseDedicatedPermissionsAndExports() = runBlocking {
        val declared = listOf("metadata", "artwork", "lyrics", "catalog", "download")
        val fixture = fixture("extended-provider", declared)
        val store = ExtensionPermissionStore(context).also { permissionStore ->
            permissionStore.clear(fixture.id)
            declared.forEach { permissionStore.set(fixture.id, it, declared, true) }
        }
        val host = ExtensionSandboxHost(context, store, { SampleModule() })
        host.start(fixture)
        assertTrue(host.lyrics(fixture.id, "track").json.contains("get_lyrics"))
        assertTrue(host.album(fixture.id, "album").json.contains("get_album"))
        assertTrue(host.artist(fixture.id, "artist").json.contains("get_artist"))
        assertTrue(host.playlist(fixture.id, "playlist").json.contains("get_playlist"))
        assertTrue(host.homeFeed(fixture.id).json.contains("get_home_feed"))
        assertTrue(host.resolveDownload(fixture.id, "track").json.contains("resolve_download"))
        host.stop(fixture.id)
    }

    @Test fun downloadResolutionIsDeniedWithoutDownloadGrant() = runBlocking {
        val fixture = fixture("download-denied", listOf("metadata", "download"))
        val store = ExtensionPermissionStore(context).also { it.clear(fixture.id) }
        val host = ExtensionSandboxHost(context, store, { SampleModule() })
        host.start(fixture)
        assertThrows(ExtensionRuntimeFailure.Permission::class.java) {
            runBlocking { host.resolveDownload(fixture.id, "track") }
        }
        host.stop(fixture.id)
    }

    @Test fun crashingExtensionIsStoppedWithoutEscapingHost() {
        val fixture = fixture("crash-provider")
        val store = ExtensionPermissionStore(context).also { it.clear(fixture.id); it.set(fixture.id, "metadata", fixture.permissions, true) }
        val host = ExtensionSandboxHost(context, store, { object : SandboxedModule {
            override fun call(export: String, json: String): String = error("deliberate crash")
            override fun close() = Unit
        } })
        host.start(fixture)
        assertThrows(IllegalStateException::class.java) { runBlocking { host.search(fixture.id, "x") } }
        assertTrue(host.status(fixture.id).stopped)
    }

    @Test fun loopingExtensionTimesOutAndIsStopped() {
        val fixture = fixture("loop-provider")
        val store = ExtensionPermissionStore(context).also { it.clear(fixture.id); it.set(fixture.id, "metadata", fixture.permissions, true) }
        val host = ExtensionSandboxHost(context, store, { object : SandboxedModule {
            override fun call(export: String, json: String): String { while (!Thread.currentThread().isInterrupted) Thread.yield(); return "{}" }
            override fun close() = Unit
        } })
        host.start(fixture)
        assertThrows(ExtensionRuntimeFailure.Timeout::class.java) { runBlocking { host.search(fixture.id, "x") } }
        assertTrue(host.status(fixture.id).stopped)
    }

    @Test fun malformedProviderResponseStopsOnlyItsExtension() {
        val fixture = fixture("malformed-provider")
        val store = ExtensionPermissionStore(context).also {
            it.clear(fixture.id); it.set(fixture.id, "metadata", fixture.permissions, true)
        }
        val host = ExtensionSandboxHost(context, store, { object : SandboxedModule {
            override fun call(export: String, json: String) = "{\"unexpected\":true}"
            override fun close() = Unit
        } })
        host.start(fixture)
        assertThrows(ExtensionRuntimeFailure.InvalidResponse::class.java) {
            runBlocking { host.search(fixture.id, "x") }
        }
        assertTrue(host.status(fixture.id).stopped)
    }

    private fun fixture(id: String = "sample-provider", declared: List<String> = listOf("metadata", "artwork")): InstalledExtension {
        val root = File(context.cacheDir, "sandbox-test-$id").apply { deleteRecursively(); mkdirs() }
        File(root, "extension.wasm").writeBytes(sampleWasm())
        File(root, "icon.png").writeBytes(byteArrayOf(1))
        val permissionJson = declared.joinToString(",") { "\"$it\"" }
        File(root, "manifest.json").writeText("""{"schema_version":1,"id":"$id","display_name":"Sample Provider","version":"1.0.0","description":"Day 15 sandbox sample","author":"Auralis","homepage":"https://example.com","license":"MIT","icon":"icon.png","entrypoint":"extension.wasm","permissions":[$permissionJson],"network_hosts":[],"min_app_version":"1.0.0"}""")
        return InstalledExtension(id, "Sample Provider", "1.0.0", "Day 15 sandbox sample", true,
            declared, 0, 0, root.absolutePath)
    }

    private class SampleModule : SandboxedModule {
        override fun call(export: String, json: String) = when (export) {
            "search" -> """{"items":[]}"""
            "get_metadata", "get_album", "get_artist", "get_playlist" -> """{"id":"sample"}"""
            "get_artwork" -> """{"url":"https://example.com/art.jpg"}"""
            "get_lyrics" -> """{"plain":"Sample lyrics"}"""
            "get_home_feed" -> """{"sections":[]}"""
            "resolve_download" -> """{"url":"https://example.com/audio.flac","mime_type":"audio/flac"}"""
            else -> error("Unexpected export")
        }
        override fun close() = Unit
    }

    /** Tiny valid WASM module: memory + alloc/dealloc + search returning `{"items":[]}`. */
    private fun sampleWasm() = byteArrayOf(
        0x00,0x61,0x73,0x6d,0x01,0x00,0x00,0x00,
        0x01,0x11,0x03,0x60,0x01,0x7f,0x01,0x7f,0x60,0x02,0x7f,0x7f,0x00,0x60,0x02,0x7f,0x7f,0x01,0x7e,
        0x03,0x04,0x03,0x00,0x01,0x02,
        0x05,0x03,0x01,0x00,0x01,
        0x07,0x25,0x04,
        0x06,0x6d,0x65,0x6d,0x6f,0x72,0x79,0x02,0x00,
        0x05,0x61,0x6c,0x6c,0x6f,0x63,0x00,0x00,
        0x07,0x64,0x65,0x61,0x6c,0x6c,0x6f,0x63,0x00,0x01,
        0x06,0x73,0x65,0x61,0x72,0x63,0x68,0x00,0x02,
        0x0a,0x0e,0x03,0x04,0x00,0x41,0x10,0x0b,0x02,0x00,0x0b,0x04,0x00,0x42,0x0c,0x0b,
        0x0b,0x12,0x01,0x00,0x41,0x00,0x0b,0x0c,0x7b,0x22,0x69,0x74,0x65,0x6d,0x73,0x22,0x3a,0x5b,0x5d,0x7d
    ).map { it.toByte() }.toByteArray()
}
