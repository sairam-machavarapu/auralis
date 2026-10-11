package com.auralis.feature.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.service.extensions.ExtensionPermissionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AuthorizedProviderAdapterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "day17-provider"
    private lateinit var root: File

    @Before fun prepare() {
        root = File(context.filesDir, "extensions/$id").apply { mkdirs() }
        File(root, "manifest.json").writeText("""{"schema_version":1,"id":"$id","display_name":"Day 17 Provider","version":"1.0.0","description":"Test provider","author":"Auralis","homepage":"https://example.com","license":"MIT","icon":"icon.png","entrypoint":"extension.wasm","permissions":["network","download"],"network_hosts":["audio.example.com"],"min_app_version":"1.0.0"}""")
        val installed = JSONObject().put("id", id).put("displayName", "Day 17 Provider").put("version", "1.0.0")
            .put("description", "Test provider").put("enabled", true).put("permissions", JSONArray(listOf("network", "download")))
            .put("installedAt", 1).put("updatedAt", 1).put("installPath", root.absolutePath)
        context.getSharedPreferences("installed_extensions", 0).edit().putString("installed", JSONArray().put(installed).toString()).commit()
        ExtensionPermissionStore(context).set(id, "download", listOf("network", "download"), true)
    }

    @After fun clean() {
        ExtensionPermissionStore(context).clear(id)
        context.getSharedPreferences("installed_extensions", 0).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun authorizedDeclaredHostIsAccepted() {
        AuthorizedProviderAdapter(context).verify(id, "https://cdn.audio.example.com/song.flac")
    }

    @Test fun unlistedHostAndUnknownExtensionAreRejected() {
        assertThrows(SecurityException::class.java) {
            AuthorizedProviderAdapter(context).verify(id, "https://untrusted.example.net/song.flac")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedProviderAdapter(context).verify("missing-provider", "https://audio.example.com/song.flac")
        }
    }

    @Test fun downloadStateTransitionsAndBackoffAreBounded() {
        assertTrue(DownloadRules.canPause(DownloadStatus.Downloading))
        assertFalse(DownloadRules.canPause(DownloadStatus.Completed))
        assertTrue(DownloadRules.canResume(DownloadStatus.Paused))
        assertFalse(DownloadRules.canResume(DownloadStatus.Completed))
        assertTrue(DownloadRules.canCancel(DownloadStatus.Queued))
        assertFalse(DownloadRules.canCancel(DownloadStatus.Completed))
        assertEquals(10L, DownloadRules.retryDelaySeconds(0))
        assertEquals(300L, DownloadRules.retryDelaySeconds(20))
        assertTrue(DownloadRules.hasSpace(200, 100, 64))
        assertFalse(DownloadRules.hasSpace(63, 10, 64))
        assertTrue(DownloadRules.transferComplete(100, 100))
        assertFalse(DownloadRules.transferComplete(99, 100))
        assertFalse(DownloadRules.transferComplete(0, null))
    }

    @Test fun duplicateTargetsReceiveStableNumberedNames() {
        val existing = setOf("Song.flac", "Song (2).flac", "Song (3).flac")
        assertEquals("Song (4).flac", ImportRules.availableName("Song", "flac", existing))
        assertEquals("Other.flac", ImportRules.availableName("Other", "flac", existing))
    }
}
