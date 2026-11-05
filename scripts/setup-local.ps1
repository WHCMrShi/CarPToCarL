<#
.SYNOPSIS
    准备 CarPToCarL 的 Windows 本地构建工具链：JDK 17、Gradle 8.10.2、Android SDK 34。

.DESCRIPTION
    三种来源，按顺序尝试：
      1. 已存在的 .tools 组件（校验后直接使用）。
      2. -SharedToolsPath 指定的工具链目录（默认自动探测同级 ../CarPToCarL/.tools），
         以 Windows 目录 junction 的方式链接进来，不复制大文件。
      3. 从镜像下载：Temurin JDK 17、Gradle 8.10.2、Android command-line tools，
         全部按固定 SHA-256 校验。

    下载来源（本机 dl.google.com / services.gradle.org / api.adoptium.net 均不可达）：
      - JDK:    TUNA Adoptium 镜像，失败回退 GitHub Release
      - Gradle: mirrors.cloud.tencent.com
      - SDK:    mirrors.cloud.tencent.com/AndroidSDK（通过 SDK_TEST_BASE_URL 让 sdkmanager 使用镜像）

    安装完成后会写入两处**本机配置**（均被 .gitignore 排除，不入库）：
      - local.properties           Android SDK 路径
      - .tools/init-mirror.gradle  Maven 依赖镜像（阿里云），供 build-local.ps1 自动加载

.PARAMETER SharedToolsPath
    复用已有工具链目录。默认自动探测 <仓库根>/../CarPToCarL/.tools。

.PARAMETER Offline
    只使用已有/共享工具链，不访问网络。

.PARAMETER AcceptAndroidLicenses
    以 'y' 自动接受 Android SDK 许可（首次安装 SDK 必需）。

.PARAMETER NoSharedTools
    忽略自动探测到的共享工具链，强制在本目录安装/校验。

.EXAMPLE
    ./scripts/setup-local.ps1 -AcceptAndroidLicenses
    ./scripts/build-local.ps1 -Offline
#>
param(
    [string]$SharedToolsPath = '',
    [switch]$Offline,
    [switch]$AcceptAndroidLicenses,
    [switch]$NoSharedTools
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot '.tools'
$downloads = Join-Path $toolsDir 'downloads'
$jdkTarget = Join-Path $toolsDir 'jdk17'
$gradleTarget = Join-Path $toolsDir 'gradle-8.10.2'
$sdkTarget = Join-Path $toolsDir 'android-sdk'

$jdkSha256 = 'E53A79C3C3D86865BD7E787903884331068E71321714FFD44F145785AFFC7CB0'
$jdkArchiveName = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$gradleSha256 = '31C55713E40233A8303827CEB42CA48A47267A0AD4BAB9177123121E71524C26'
$cmdlineSha256 = '4d6931209eebb1bfb7c7e8b240a6a3cb3ab24479ea294f3539429574b1eec862'
$mirrorBase = 'https://mirrors.cloud.tencent.com'

function Test-Jdk([string]$Path) {
    return (Test-Path -LiteralPath (Join-Path $Path 'bin/java.exe'))
}

function Test-Gradle([string]$Path) {
    return (Test-Path -LiteralPath (Join-Path $Path 'bin/gradle.bat'))
}

function Test-Sdk([string]$Path) {
    return ((Test-Path -LiteralPath (Join-Path $Path 'platforms/android-34/android.jar')) -and
        (Test-Path -LiteralPath (Join-Path $Path 'build-tools/34.0.0/aapt2.exe')) -and
        (Test-Path -LiteralPath (Join-Path $Path 'platform-tools/adb.exe')))
}

function New-ToolLink([string]$LinkPath, [string]$TargetPath) {
    if (Test-Path -LiteralPath $LinkPath) { return }
    New-Item -ItemType Junction -Path $LinkPath -Target $TargetPath | Out-Null
    Write-Host "链接 $LinkPath -> $TargetPath"
}

function Get-RemoteFile {
    param(
        [string[]]$Urls,
        [string]$Destination,
        [string]$Sha256
    )
    if ((Test-Path -LiteralPath $Destination) -and
        ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256)) {
        Write-Host "已存在且校验通过：$Destination"
        return
    }
    if ($Offline) {
        throw "缺少 $Destination（Offline 模式不下载）。请先联网运行本脚本。"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    foreach ($url in $Urls) {
        $partial = "$Destination.partial"
        try {
            Write-Host "下载 $url"
            & curl.exe -fsSL --retry 3 --connect-timeout 20 --max-time 1800 -o $partial $url
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $partial)) {
                throw "curl 退出码 $LASTEXITCODE"
            }
            $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash
            if ($actual -ne $Sha256) {
                Remove-Item -LiteralPath $partial -Force
                throw "SHA-256 不匹配：期望 $Sha256，实际 $actual"
            }
            Move-Item -LiteralPath $partial -Destination $Destination -Force
            return
        } catch {
            Write-Warning "下载失败：$url`n$($_.Exception.Message)"
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        }
    }
    throw "所有下载源均失败：$Destination"
}

function Expand-Zip {
    param([string]$Archive, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $Archive -C $Destination
        if ($LASTEXITCODE -ne 0) { throw "解包失败（tar）：$Archive" }
    } else {
        Expand-Archive -LiteralPath $Archive -DestinationPath $Destination -Force
    }
}

New-Item -ItemType Directory -Force -Path $downloads | Out-Null

# ---- 1. 复用共享工具链（大文件不复制） ----
$shared = ''
if (-not $NoSharedTools) {
    if (-not [string]::IsNullOrWhiteSpace($SharedToolsPath)) {
        $shared = [System.IO.Path]::GetFullPath($SharedToolsPath)
    } else {
        $candidate = Join-Path (Split-Path -Parent $projectRoot) 'CarPToCarL/.tools'
        if (Test-Path -LiteralPath $candidate) { $shared = [System.IO.Path]::GetFullPath($candidate) }
    }
    if ($shared -and -not (Test-Path -LiteralPath $shared)) {
        throw "共享工具链目录不存在：$shared"
    }
    if ($shared -and $shared -ne $toolsDir) {
        Write-Host "复用共享工具链：$shared"
        if (-not (Test-Jdk $jdkTarget) -and (Test-Jdk (Join-Path $shared 'jdk17'))) {
            New-ToolLink $jdkTarget (Join-Path $shared 'jdk17')
        }
        if (-not (Test-Gradle $gradleTarget) -and (Test-Gradle (Join-Path $shared 'gradle-8.10.2'))) {
            New-ToolLink $gradleTarget (Join-Path $shared 'gradle-8.10.2')
        }
        if (-not (Test-Sdk $sdkTarget) -and (Test-Sdk (Join-Path $shared 'android-sdk'))) {
            New-ToolLink $sdkTarget (Join-Path $shared 'android-sdk')
        }
    }
}

# ---- 2. JDK 17 ----
if (-not (Test-Jdk $jdkTarget)) {
    $archive = Join-Path $downloads $jdkArchiveName
    Get-RemoteFile -Urls @(
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/$jdkArchiveName",
        "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/$jdkArchiveName"
    ) -Destination $archive -Sha256 $jdkSha256
    $stage = Join-Path $downloads 'jdk-stage'
    Expand-Zip -Archive $archive -Destination $stage
    $extracted = Get-ChildItem -LiteralPath $stage -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
        Select-Object -First 1
    if (-not $extracted) { throw "JDK 压缩包结构异常：$archive" }
    if (Test-Path -LiteralPath $jdkTarget) { Remove-Item -LiteralPath $jdkTarget -Recurse -Force }
    Move-Item -LiteralPath $extracted.FullName -Destination $jdkTarget
    Remove-Item -LiteralPath $stage -Recurse -Force
    Write-Host "JDK 17 已安装：$jdkTarget"
}

# 目录名含 '+' 会让 Kotlin 编译器出现 FirClassLikeSymbol 的 ICE，必须使用 jdk17。
if ($jdkTarget -like '*+*') { throw "JDK 目录名不能包含 '+'：$jdkTarget" }

# ---- 3. Gradle 8.10.2 ----
if (-not (Test-Gradle $gradleTarget)) {
    $archive = Join-Path $downloads 'gradle-8.10.2-bin.zip'
    Get-RemoteFile -Urls @("$mirrorBase/gradle/gradle-8.10.2-bin.zip") -Destination $archive -Sha256 $gradleSha256
    $stage = Join-Path $downloads 'gradle-stage'
    Expand-Zip -Archive $archive -Destination $stage
    $extracted = Join-Path $stage 'gradle-8.10.2'
    if (-not (Test-Path -LiteralPath $extracted)) {
        $extracted = (Get-ChildItem -LiteralPath $stage -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/gradle.bat') } |
            Select-Object -First 1).FullName
    }
    if (-not $extracted -or -not (Test-Path -LiteralPath $extracted)) { throw "Gradle 压缩包结构异常：$archive" }
    if (Test-Path -LiteralPath $gradleTarget) { Remove-Item -LiteralPath $gradleTarget -Recurse -Force }
    Move-Item -LiteralPath $extracted -Destination $gradleTarget
    Remove-Item -LiteralPath $stage -Recurse -Force
    if (-not (Test-Gradle $gradleTarget)) { throw "Gradle 解包后未找到 gradle.bat：$gradleTarget" }
    Write-Host "Gradle 8.10.2 已安装：$gradleTarget"
}

# ---- 4. Android SDK 34 ----
if (-not (Test-Sdk $sdkTarget)) {
    $sdkManager = Join-Path $sdkTarget 'cmdline-tools/latest/bin/sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        $archive = Join-Path $downloads 'commandlinetools-win-11076708_latest.zip'
        Get-RemoteFile -Urls @("$mirrorBase/AndroidSDK/commandlinetools-win-11076708_latest.zip") `
            -Destination $archive -Sha256 $cmdlineSha256
        $stage = Join-Path $downloads 'cmdline-tools-stage'
        Expand-Zip -Archive $archive -Destination $stage
        $source = Join-Path $stage 'cmdline-tools'
        if (-not (Test-Path -LiteralPath (Join-Path $source 'bin/sdkmanager.bat'))) {
            throw "Android command-line tools 压缩包结构异常：$archive"
        }
        $latest = Join-Path $sdkTarget 'cmdline-tools/latest'
        New-Item -ItemType Directory -Force -Path $latest | Out-Null
        Copy-Item -Path (Join-Path $source '*') -Destination $latest -Recurse -Force
        Remove-Item -LiteralPath $stage -Recurse -Force
    }

    $previousJava = $env:JAVA_HOME
    $previousBaseUrl = $env:SDK_TEST_BASE_URL
    try {
        $env:JAVA_HOME = $jdkTarget
        # 让 sdkmanager 从腾讯云镜像读取 repository2-1.xml 与安装包（dl.google.com 不可达）。
        $env:SDK_TEST_BASE_URL = "$mirrorBase/AndroidSDK/"
        if ($AcceptAndroidLicenses) {
            1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkTarget" --licenses 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Android SDK 许可接受失败。" }
        }
        & $sdkManager "--sdk_root=$sdkTarget" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools' 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Android SDK 安装失败。可加 -AcceptAndroidLicenses 重新运行。"
        }
    } finally {
        $env:JAVA_HOME = $previousJava
        $env:SDK_TEST_BASE_URL = $previousBaseUrl
    }
    if (-not (Test-Sdk $sdkTarget)) { throw "Android SDK 安装后校验失败：$sdkTarget" }
    Write-Host "Android SDK 34 已安装：$sdkTarget"
}

# ---- 5. 本机配置（不入库） ----
$localProperties = Join-Path $projectRoot 'local.properties'
$sdkDir = $sdkTarget.Replace('\', '/')
$localPropertiesContent = "sdk.dir=$sdkDir`n"
if (-not (Test-Path -LiteralPath $localProperties) -or
    ((Get-Content -Raw -LiteralPath $localProperties) -ne $localPropertiesContent)) {
    [System.IO.File]::WriteAllText($localProperties, $localPropertiesContent, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已写入 local.properties"
}

$initScript = Join-Path $toolsDir 'init-mirror.gradle'
if (-not (Test-Path -LiteralPath $initScript)) {
    $initContent = @'
// Local Maven mirror injection (.tools/ is git-ignored and never distributed).
// google() / mavenCentral() time out from this machine, and Gradle does not fall
// back to the next repository after the first one fails, so drop the official
// repositories first and keep only the mirrors below.
def mirrors = [
    'https://maven.aliyun.com/repository/google',
    'https://maven.aliyun.com/repository/public',
    'https://maven.aliyun.com/repository/gradle-plugin',
]
def officialNames = ['Google', 'MavenRepo', 'mavenCentral', 'google', 'BintrayJCenter']

def purgeOfficial = { repositories ->
    def doomed = repositories.findAll { it.name in officialNames }
    doomed.each { repositories.remove(it) }
    doomed.size()
}

beforeSettings { settings ->
    settings.pluginManagement.repositories { handler ->
        mirrors.each { u -> handler.maven { url = u } }
    }
}

settingsEvaluated { settings ->
    def pm = settings.pluginManagement.repositories
    mirrors.each { u -> pm.maven { url = u } }
    purgeOfficial(pm)
    def dr = settings.dependencyResolutionManagement.repositories
    purgeOfficial(dr)
    mirrors.each { u -> dr.maven { url = u } }
}
'@
    [System.IO.File]::WriteAllText($initScript, $initContent, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已生成 .tools/init-mirror.gradle（阿里云 Maven 镜像）"
}

# ---- 6. 结果 ----
# java -version 写 stderr；在 EAP=Stop 的脚本里直接 2>&1 会被当成终止性错误。
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = [string]((& (Join-Path $jdkTarget 'bin/java.exe') -version 2>&1) | Select-Object -First 1)
$ErrorActionPreference = $previousEap
Write-Host ''
Write-Host '工具链就绪：'
Write-Host "  JDK    : $jdkTarget  [$javaVersion]"
Write-Host "  Gradle : $gradleTarget"
Write-Host "  SDK    : $sdkTarget"
Write-Host "  SDK 路径已写入 local.properties；依赖镜像见 .tools/init-mirror.gradle"
Write-Host ''
Write-Host '下一步：./scripts/build-local.ps1'
