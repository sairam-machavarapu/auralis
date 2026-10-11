import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val signingKeys = listOf("release.storeFile", "release.storePassword", "release.keyAlias", "release.keyPassword")
val canSignRelease = signingKeys.all { !local.getProperty(it).isNullOrBlank() }
android {
    namespace = "com.auralis.music"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.auralis.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "2.0.0"
    }
    signingConfigs {
        if (canSignRelease) create("release") {
            storeFile = rootProject.file(local.getProperty("release.storeFile"))
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { jvmToolchain(17) }
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    doFirst { check(canSignRelease) { "Add release signing properties to local.properties before building a release APK." } }
}
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":service:scanner"))
    implementation(project(":service:playback"))
    implementation(project(":feature:home"))
    implementation(project(":feature:player"))
    implementation(project(":feature:library"))
    implementation(project(":feature:search"))
    implementation(project(":feature:playlists"))
    implementation(project(":feature:extensions"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
}
