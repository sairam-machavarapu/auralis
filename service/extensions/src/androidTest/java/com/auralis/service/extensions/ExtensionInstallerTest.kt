package com.auralis.service.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ExtensionInstallerTest {
    private lateinit var context: Context
    private lateinit var installer: ExtensionInstaller

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("installed_extensions", 0).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
        installer = ExtensionInstaller(context, "1.0.0")
    }

    @After fun cleanup() { File(context.filesDir, "extensions").deleteRecursively() }

    @Test fun hashMismatchBlocksInstall() {
        val file = packageFile("1.0.0")
        assertThrows(InstallFailure.HashMismatch::class.java) { installer.prepareFile(registry("1.0.0", "0".repeat(64)), file) }
    }

    @Test fun incompatibleVersionIsRejected() {
        val file = packageFile("2.0.0", minimum = "9.0.0")
        assertThrows(InstallFailure.Incompatible::class.java) {
            installer.prepareFile(registry("2.0.0", sha(file)), file)
        }
    }

    @Test fun updateKeepsRollbackAndUninstallLeavesLibraryFiles() = runBlocking {
        val libraryFile = File(context.filesDir, "user-library-song.mp3").apply { writeText("music") }
        val first = packageFile("1.0.0")
        installer.install(installer.prepareFile(registry("1.0.0", sha(first)), first))
        val second = packageFile("1.1.0", listOf("metadata", "artwork"))
        val update = installer.prepareFile(registry("1.1.0", sha(second)), second)
        assertEquals(listOf("artwork"), update.newPermissions)
        installer.install(update)
        assertTrue(File(context.filesDir, "extensions/example-provider/rollback/manifest.json").isFile)
        assertEquals("1.1.0", installer.installed().single().version)
        assertEquals("1.0.0", installer.rollback("example-provider").version)
        assertEquals("1.0.0", installer.installed().single().version)
        ExtensionPermissionStore(context).set("example-provider", "metadata", listOf("metadata"), true)
        installer.uninstall("example-provider")
        assertTrue(libraryFile.isFile)
        assertTrue(installer.installed().isEmpty())
        assertTrue(ExtensionPermissionStore(context).granted("example-provider", listOf("metadata")).isEmpty())
        libraryFile.delete()
        Unit
    }

    private fun packageFile(version: String, permissions: List<String> = listOf("metadata"), minimum: String = "1.0.0"): File {
        val file = File(context.cacheDir, "test-$version-${System.nanoTime()}.lmx")
        val permissionJson = permissions.joinToString(",") { "\"$it\"" }
        val manifest = """{"schema_version":1,"id":"example-provider","display_name":"Example Provider","version":"$version","description":"Provider description","author":"Author","homepage":"https://example.com","license":"MIT","icon":"icon.png","entrypoint":"extension.wasm","permissions":[$permissionJson],"network_hosts":[],"min_app_version":"$minimum"}"""
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("manifest.json" to manifest.toByteArray(), "icon.png" to byteArrayOf(1, 2), "extension.wasm" to byteArrayOf(0, 97, 115, 109)).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }
    private fun registry(version: String, hash: String) = RegistryExtension("example-provider", "Example Provider", version,
        "Provider description", "https://example.com/example.lmx", hash, "https://example.com/icon.png", "provider", listOf("metadata"), "1.0.0")
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
