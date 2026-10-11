package com.auralis.service.extensions

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegistrySchemasTest {
    @Test fun validRegistryParses() {
        val parsed = RegistrySchemas.parseRegistry(registry(item()))
        assertEquals("example-provider", parsed.extensions.single().id)
    }

    @Test fun spotiflacRegistryCanBeBrowsedButIsMarkedIncompatible() {
        val json = """{"version":1,"updated_at":"2026-10-06T00:00:00Z","extensions":[{"id":"spotify-web","name":"spotify-web","display_name":"Spotify Web","version":"1.10.2","description":"Metadata provider","download_url":"https://example.com/spotify.sflx","sha256":"${"a".repeat(64)}","category":"integration","tags":["spotify"],"downloads":0,"updated_at":"2026-10-06T00:00:00Z","min_app_version":"4.3.0"}]}"""
        val parsed = RegistrySchemas.parseCompatibleRegistry(json)
        assertEquals("spotify-web", parsed.extensions.single().id)
        assertTrue(parsed.extensions.single().tags.contains("_spotiflac"))
    }

    @Test fun genericHttpsRegistryAliasesAreNormalized() {
        val json = """{"updatedAt":"2026-10-10T00:00:00Z","items":[{"slug":"sample-source","title":"Sample Source","package_version":"2.1.0","summary":"External provider","url":"https://example.com/sample.zip","checksum":"sha256:${"b".repeat(64)}","keywords":["search"]}]}"""
        val parsed = RegistrySchemas.parseCompatibleRegistry(json).extensions.single()
        assertEquals("sample-source", parsed.id)
        assertEquals("Sample Source", parsed.displayName)
        assertTrue(parsed.tags.contains("_external"))
    }

    @Test fun genericRegistryStillRequiresHttpsAndSha256() {
        val json = """{"items":[{"id":"unsafe-source","version":"1.0.0","url":"http://example.com/file.zip","hash":"bad"}]}"""
        assertThrows(RegistryFailure.InvalidData::class.java) { RegistrySchemas.parseCompatibleRegistry(json) }
    }

    @Test fun malformedJsonIsFriendlyError() {
        assertThrows(RegistryFailure.InvalidData::class.java) { RegistrySchemas.parseRegistry("{") }
    }

    @Test fun duplicateIdsAreRejected() {
        assertThrows(RegistryFailure.InvalidData::class.java) { RegistrySchemas.parseRegistry(registry("${item()},${item()}")) }
    }

    @Test fun unknownFieldsAreRejected() {
        assertThrows(RegistryFailure.InvalidData::class.java) {
            RegistrySchemas.parseRegistry(registry(item().dropLast(1) + ",\"extra\":true}"))
        }
    }

    @Test fun insecurePackageUrlIsRejected() {
        assertThrows(RegistryFailure.InvalidData::class.java) {
            RegistrySchemas.parseRegistry(registry(item().replace("https://example.com/example.lmx", "http://example.com/example.lmx")))
        }
    }

    @Test fun manifestRejectsUnknownPermission() {
        val manifest = """{"schema_version":1,"id":"example-provider","display_name":"Example Provider","version":"1.0.0","description":"Provider description","author":"Author","homepage":"https://example.com","license":"MIT","icon":"icon.png","entrypoint":"extension.wasm","permissions":["camera"],"network_hosts":[],"min_app_version":"1.0.0"}"""
        assertThrows(RegistryFailure.InvalidData::class.java) { RegistrySchemas.parseManifest(manifest) }
    }

    @Test fun semanticVersionsCompareNumerically() {
        assertEquals(1, VersionOrder.compare("1.10.0", "1.9.9"))
        assertEquals(-1, VersionOrder.compare("2.0.0-beta", "2.0.0"))
        assertEquals(0, VersionOrder.compare("1.0.0", "1.0.0"))
    }

    private fun registry(items: String) = """{"schema_version":1,"updated_at":"2026-10-09T00:00:00Z","extensions":[$items]}"""
    private fun item() = """{"id":"example-provider","display_name":"Example Provider","version":"1.0.0","description":"Provider description","download_url":"https://example.com/example.lmx","sha256":"${"a".repeat(64)}","icon_url":"https://example.com/icon.png","category":"provider","tags":["search","metadata"],"min_app_version":"1.0.0"}"""
}
