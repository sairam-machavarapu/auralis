# Library scanner

`ScanWorker` runs a unique WorkManager job on `Dispatchers.IO`, with a data-sync
foreground notification. `AndroidDocumentSource` queries DocumentsContract one
directory at a time. `AndroidMetadataReader` reads tags and audio stream properties
through MediaMetadataRetriever/MediaExtractor. Unsupported or unreadable audio is
reported per file. Unknown technical properties remain null.

The scanner writes batches of at most 50 tracks, preserving existing IDs,
favorites, history counters and added timestamps. It also populates album, artist
and genre records. File size and modification time skip unchanged files; missing
cached covers force a metadata refresh. Embedded covers up to 16 MB are cached by
SHA-256 inside app cache storage. No provider write or delete API is called.

Only a fully enumerated folder reconciles missing files. Partial provider failures
and cancellation preserve unvisited rows. Missing or unreadable known files are
marked unavailable, never deleted. Each transaction checks that its folder still
exists and is enabled. Stable document IDs are retained across tree URI changes;
content-fingerprint matching for providers that change document IDs is not yet
implemented.

WorkManager progress is exposed as Flow and throttled to 250 ms. Up to 100 issue
details are saved atomically in internal storage; the total error count is kept
even when the display limit is reached. Home and Library show a scan chip, a
details sheet with Rescan and expandable issues, and reduced-motion-aware loading
placeholders. App startup and enabled-folder changes schedule scans; periodic
background scans and ContentObserver monitoring are future work.

## Verification

```powershell
.\scripts\build.ps1 -GradleTasks :service:scanner:connectedDebugAndroidTest
.\scripts\build.ps1 -GradleTasks :core:database:connectedDebugAndroidTest
```

The debug source set includes read-only virtual documents backed by generated
WAV/MP3 assets. The DocumentsProvider is protected by MANAGE_DOCUMENTS, and app
benchmark entry points require the shell's DUMP permission. None ship in release.
Tests exercise 1,000 real metadata reads, an unreadable entry, nested and
unreadable directories, rescans, missing files, cancellation, tags, cover hashing,
and preservation of user fields.

To benchmark the actual app after installing the debug APK, first prepare the
disabled fixture folder and let the ordinary library finish scanning:

```text
adb shell am start -n com.auralis.music/.ScannerBenchmarkActivity
adb shell dumpsys gfxinfo com.auralis.music reset
adb shell am broadcast -n com.auralis.music/.ScannerBenchmarkReceiver
adb shell dumpsys gfxinfo com.auralis.music framestats
adb shell am start -n com.auralis.music/.ScannerBenchmarkActivity --ez cleanup true
```

Enable the fixture only after the Home screen is visible, so startup work is
excluded from frame measurements. Cleanup removes only fixture database rows,
then returns to the user's saved folders. Generated fixtures use silence and
synthetic metadata and artwork.

## Day 4 device results (2026-10-10)

RMX2170, Android 12: three scanner instrumented tests and four database tests
passed. The isolated 1,000-file metadata test took 41.4 seconds. In the actual
app, 1,000 virtual tracks plus 34 existing tracks were indexed, with the single
deliberately unreadable file reported nonfatally. The details sheet and expanded
issue message were verified on the device. The user's real folder scanned with
34 tracks and no issues. Light and dark scan screens were inspected.

Active scans temporarily use Lite glass when Full is selected; Full returns
after completion. Animated rings and shimmer have isolated graphics layers.
The final debug frame sample still recorded 477/2083 late frames (22.90%), with
29 ms median and 40 ms p95. Earlier samples were 71.18% with full content blur
and 20.18% with Lite before animation isolation; these are separate runs, not a
controlled performance guarantee. The no-jank acceptance requirement remains
open. Profile an optimized build and compare idle, unchanged-rescan and fresh
metadata scans before further tuning. Do not call Day 4 fully accepted yet.

Final APK assembled and lint passed with 0 errors / 9 existing warnings.
