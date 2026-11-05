param(
    [string[]]$Tasks = @('spotlessCheck', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot '.tools'
$jdk = Get-ChildItem -LiteralPath $toolsDir -Directory -Filter 'jdk-17*' |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw 'Install a JDK 17 distribution under .tools first.' }
$sdk = Join-Path $toolsDir 'android-sdk'
if (-not (Test-Path (Join-Path $sdk 'platforms/android-34/android.jar'))) {
    throw 'Install Android SDK platform 34 under .tools/android-sdk first.'
}

$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$previousGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $jdk.FullName
    $env:ANDROID_HOME = $sdk
    $env:GRADLE_USER_HOME = Join-Path $toolsDir 'gradle-user-home'
    Push-Location $projectRoot
    try {
        $gradle = Join-Path $toolsDir 'gradle-8.10.2/bin/gradle.bat'
        if (-not (Test-Path $gradle)) { $gradle = Join-Path $projectRoot 'gradlew.bat' }
        & $gradle @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    $env:GRADLE_USER_HOME = $previousGradle
}
