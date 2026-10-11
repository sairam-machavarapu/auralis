plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}

// OneDrive can turn generated Gradle files into cloud placeholders mid-build.
// CI and local builds may opt into an external generated-output directory.
System.getenv("AURALIS_BUILD_ROOT")?.takeIf { it.isNotBlank() }?.let { root ->
    allprojects {
        val projectFolder = path.trim(':').replace(':', '_').ifBlank { "root" }
        layout.buildDirectory.set(file("$root/$projectFolder"))
    }
}
