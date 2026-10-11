param([string[]]$GradleTasks = @(':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath "$projectRoot/tools/java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (Test-Path -LiteralPath "$projectRoot/tools/android-sdk") {
    $env:ANDROID_HOME = "$projectRoot/tools/android-sdk"
}
$env:GRADLE_USER_HOME = if ($env:AURALIS_GRADLE_HOME) { $env:AURALIS_GRADLE_HOME } else { "$projectRoot/tools/gradle-cache" }
Push-Location $projectRoot
try {
    $portableGradle = "$projectRoot/tools/gradle/gradle-9.4.1/bin/gradle.bat"
    if (Test-Path -LiteralPath $portableGradle) {
        & $portableGradle @GradleTasks --console=plain
    } else {
        & .\gradlew.bat @GradleTasks --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
