plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.auralis.core.database"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api("androidx.room:room-runtime:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    ksp("androidx.room:room-compiler:2.8.5")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
androidComponents {
    onVariants { variant -> variant.androidTest?.sources?.assets?.addStaticSourceDirectory("schemas") }
}
