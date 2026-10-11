# Auralis

Auralis is a local-first Android music player built with Kotlin and Jetpack Compose. It provides folder-based library access, Media3 playback, search, playlists, synchronized lyrics, a glass visual system, and an optional sandboxed extension platform.

## Requirements

- Android Studio with Android SDK Platform 37
- JDK 17
- Android 8.0 (API 26) or newer

Create an ignored `local.properties` file containing your Android SDK path:

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

## Build

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

The optional PowerShell helper uses the portable toolchain under the ignored `tools/` directory when present:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1
```

## Tests

Connect an Android device or emulator, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The instrumentation suites cover Room migrations, library scanning, extension schemas and installation, sandbox isolation, downloads, authorized imports, and playback-facing state.

## Release signing

Release builds require these entries in ignored `local.properties`:

```properties
release.storeFile=C:/secure/location/auralis-release.jks
release.storePassword=LOCAL_SECRET
release.keyAlias=auralis
release.keyPassword=LOCAL_SECRET
```

Build and verify the APK:

```powershell
.\gradlew.bat :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Keep the signing key and credentials outside this repository. Every update must use the same signing identity.

## Project structure

- `app` — application entry point, navigation, dependency injection and Android resources
- `core/database` — Room entities, migrations, queries and library repositories
- `core/model` — shared domain models
- `core/ui` — theme, glass surfaces and shared Compose components
- `feature/*` — Home, Library, Player, Playlists, Search, Downloads, Extensions and Settings
- `service/playback` — Media3 playback and media session
- `service/scanner` — SAF library scanning and metadata extraction
- `service/extensions` — registry validation, package installation and WebAssembly sandbox
- `docs` — design, security and release documentation

## Extension security

Auralis accepts HTTPS registries using its strict schema. Packages are verified with SHA-256 before installation. Extensions run as WebAssembly with deny-by-default permissions, declared network hosts, bounded memory and execution time, and no direct filesystem or native-code access.

The official registry and sample extension are maintained separately in the `auralis-extensions` repository.

## Privacy

The local player works offline. Auralis contains no analytics. Network access is used only for repositories and capabilities the user explicitly enables for an extension.

## License

Copyright © 2026 Auralis contributors. Licensing terms will be finalized before public distribution.
