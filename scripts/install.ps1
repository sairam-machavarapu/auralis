$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = "$projectRoot/tools/android-sdk/platform-tools/adb.exe"
$apk = "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path -LiteralPath $adb)) { throw 'Workspace Android SDK is missing.' }
if (!(Test-Path -LiteralPath $apk)) { throw 'Build the debug APK with scripts/build.ps1 first.' }
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed. Connect a phone and authorize USB debugging.' }
& $adb shell am start -W -n com.auralis.music/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
