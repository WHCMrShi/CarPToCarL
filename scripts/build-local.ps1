<#
.SYNOPSIS
    用 .tools 中的固定工具链构建/校验 CarPToCarL。

.DESCRIPTION
    只为本条命令设置 JAVA_HOME / ANDROID_HOME / ANDROID_USER_HOME / GRADLE_USER_HOME，
    结束后恢复。默认执行单元测试、lint 与 Debug APK 编译。

    默认**不包含** spotlessCheck：本基线（bcef70f）有 66 个文件存在历史格式问题，
    在未获准改动代码时它必然失败。需要时显式加 -IncludeFormatCheck。

.PARAMETER Tasks
    Gradle 任务列表。默认 @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')。

.PARAMETER GradleArguments
    追加给 Gradle 的参数，例如 '--stacktrace'。

.PARAMETER Offline
    加 --offline，只用已有缓存。

.PARAMETER IncludeFormatCheck
    在任务列表最前面加上 spotlessCheck（当前基线预期失败，仅供参考）。

.PARAMETER NoDaemon
    加 --no-daemon。自动化/捕获输出的场景建议启用：新建的 Gradle daemon 会继承
    调用方的 stdout 管道，导致父进程在构建完成后仍然等不到 EOF。

.EXAMPLE
    ./scripts/build-local.ps1
    ./scripts/build-local.ps1 -Tasks ':app:assembleDebug'
    ./scripts/build-local.ps1 -IncludeFormatCheck
    ./scripts/build-local.ps1 -Offline -GradleArguments '--stacktrace'
    ./scripts/build-local.ps1 -NoDaemon
#>
param(
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'),
    [string[]]$GradleArguments = @(),
    [switch]$Offline,
    [switch]$IncludeFormatCheck,
    [switch]$NoDaemon
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot '.tools'

if (-not (Test-Path -LiteralPath $toolsDir)) {
    throw "未找到 .tools。先运行 ./scripts/setup-local.ps1"
}

# JDK：优先 jdk17；兼容 jdk-17*，但含 '+' 的目录会触发 Kotlin 编译器 ICE。
$jdkCandidates = Get-ChildItem -LiteralPath $toolsDir -Directory |
    Where-Object { $_.Name -eq 'jdk17' -or $_.Name -like 'jdk-17*' } |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Sort-Object Name -Descending
$jdk = $jdkCandidates | Where-Object { $_.Name -eq 'jdk17' } | Select-Object -First 1
if (-not $jdk) { $jdk = $jdkCandidates | Select-Object -First 1 }
if (-not $jdk) {
    throw "未找到 JDK 17（.tools/jdk17）。先运行 ./scripts/setup-local.ps1"
}
if ($jdk.Name -like '*+*') {
    throw "JDK 目录名不能包含 '+'（当前：$($jdk.Name)），否则 Kotlin 编译会 ICE。请重命名为 .tools/jdk17。"
}

$sdk = Join-Path $toolsDir 'android-sdk'
$missing = @()
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-34/android.jar'))) { $missing += 'platforms;android-34' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools/34.0.0/aapt2.exe'))) { $missing += 'build-tools;34.0.0' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platform-tools/adb.exe'))) { $missing += 'platform-tools' }
if ($missing.Count -gt 0) {
    throw "Android SDK 缺少：$($missing -join ', ')。先运行 ./scripts/setup-local.ps1 -AcceptAndroidLicenses"
}

$gradle = Join-Path $toolsDir 'gradle-8.10.2/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    $gradle = Join-Path $projectRoot 'gradlew.bat'
}

# 依赖缓存：优先显式 GRADLE_USER_HOME，其次 .tools/gradle-user-home，最后复用用户目录。
$gradleHome = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleHome)) {
    $localGradleHome = Join-Path $toolsDir 'gradle-user-home'
    if (Test-Path -LiteralPath $localGradleHome) {
        $gradleHome = $localGradleHome
    } else {
        $gradleHome = Join-Path $env:USERPROFILE '.gradle'
    }
}

$invokeTasks = @($Tasks)
if ($IncludeFormatCheck) { $invokeTasks = @('spotlessCheck') + $invokeTasks }

$arguments = @('-p', $projectRoot) + $invokeTasks + @('--console=plain') + $GradleArguments
$initScript = Join-Path $toolsDir 'init-mirror.gradle'
if (Test-Path -LiteralPath $initScript) { $arguments = @('-I', $initScript) + $arguments }
if ($Offline) { $arguments += '--offline' }
if ($NoDaemon) { $arguments += '--no-daemon' }

$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$previousAndroidUser = $env:ANDROID_USER_HOME
$previousGradle = $env:GRADLE_USER_HOME
$previousRevision = $env:CARP_BUILD_REVISION
try {
    $revision = & git -C $projectRoot rev-parse --short=12 HEAD
    if ($LASTEXITCODE -ne 0) { throw '无法确定构建代码的 Git 提交' }
    $changes = @(& git -C $projectRoot status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw '无法确定构建工作区状态' }
    $env:CARP_BUILD_REVISION = "$revision" + $(if ($changes.Count -gt 0) { '-dirty' } else { '' })
    $env:JAVA_HOME = $jdk.FullName
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_USER_HOME = Join-Path $toolsDir 'android-user-home'
    $env:GRADLE_USER_HOME = $gradleHome
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

    Write-Host "JDK    : $($jdk.FullName)"
    Write-Host "SDK    : $sdk"
    Write-Host "Gradle : $gradle"
    Write-Host "缓存   : $gradleHome"
    Write-Host "任务   : $($invokeTasks -join ' ')"
    Write-Host "代码   : $env:CARP_BUILD_REVISION"
    Write-Host ''

    Push-Location $projectRoot
    try {
        & $gradle @arguments
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($exitCode -ne 0) { throw "Gradle 失败，退出码 $exitCode" }

    $apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (Test-Path -LiteralPath $apk) {
        $size = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 1)
        Write-Host ''
        Write-Host "APK: $apk（$size MB）"
    }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    $env:ANDROID_USER_HOME = $previousAndroidUser
    $env:GRADLE_USER_HOME = $previousGradle
    $env:CARP_BUILD_REVISION = $previousRevision
}
