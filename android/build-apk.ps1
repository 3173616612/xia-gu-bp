$ErrorActionPreference = "Stop"

$androidRoot = $PSScriptRoot
$workspaceRoot = Split-Path $androidRoot -Parent
$portableJdk = Join-Path $workspaceRoot "work\toolchains\jdk17\jdk-17.0.19+10"
$portableSdk = Join-Path $workspaceRoot "work\android-sdk"
$portableGradle = Join-Path $workspaceRoot "work\toolchains\gradle\gradle-8.13\bin\gradle.bat"

if (Test-Path $portableJdk) {
    $env:JAVA_HOME = $portableJdk
} elseif (-not $env:JAVA_HOME) {
    throw "JDK 17 was not found. Set JAVA_HOME first."
}

if (Test-Path $portableSdk) {
    $env:ANDROID_SDK_ROOT = $portableSdk
} elseif (-not $env:ANDROID_SDK_ROOT) {
    throw "Android SDK was not found. Set ANDROID_SDK_ROOT first."
}

Push-Location $androidRoot
try {
    $gradleCommand = if (Test-Path $portableGradle) { $portableGradle } else { ".\gradlew.bat" }
    & $gradleCommand --no-daemon testDebugUnitTest assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Android build failed." }
} finally {
    Pop-Location
}

$sourceApk = Join-Path $androidRoot "app\build\outputs\apk\release\app-release.apk"
$outputDir = Join-Path $workspaceRoot "output"
$outputApk = Join-Path $outputDir "xia-gu-bp-avatar-overlay-v1.1.apk"
New-Item -ItemType Directory -Force $outputDir | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $outputApk -Force
Write-Host "APK: $outputApk"
