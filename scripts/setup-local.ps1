param(
    [switch]$AcceptAndroidLicenses,
    [switch]$RevocationBestEffort
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $projectRoot '.tools'
$downloads = Join-Path $tools 'downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null

function Get-Download([string]$Url, [string]$Destination) {
    $curlArgs = @('-fL', '--retry', '3', '--connect-timeout', '20', '--max-time', '900')
    if ($RevocationBestEffort) { $curlArgs += '--ssl-revoke-best-effort' }
    & curl.exe @curlArgs -o "$Destination.partial" $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    Move-Item -LiteralPath "$Destination.partial" -Destination $Destination -Force
}

function Test-Archive([string]$Path, [string]$Checksum, [string]$Algorithm) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Checksum) {
        throw "Checksum mismatch: $Path"
    }
}

$jdk = Get-ChildItem -LiteralPath $tools -Directory -Filter 'jdk-17*' |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) {
    $metadata = Join-Path $downloads 'jdk.json'
    Get-Download 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows' $metadata
    $package = (Get-Content -Raw $metadata | ConvertFrom-Json)[0].binary.package
    $archive = Join-Path $downloads $package.name
    if (-not (Test-Path $archive)) { Get-Download $package.link $archive }
    Test-Archive $archive $package.checksum 'SHA256'
    Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
    $jdk = Get-ChildItem -LiteralPath $tools -Directory -Filter 'jdk-17*' |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
}

$sdk = Join-Path $tools 'android-sdk'
if ((Test-Path (Join-Path $sdk 'platforms/android-34/android.jar')) -and
    (Test-Path (Join-Path $sdk 'build-tools/34.0.0/aapt2.exe')) -and
    (Test-Path (Join-Path $sdk 'platform-tools/adb.exe'))) {
    Write-Host 'JDK 17 and Android SDK 34 already installed. Run ./scripts/build-local.ps1.'
    return
}
$sdkManager = Join-Path $sdk 'cmdline-tools/latest/bin/sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
    $metadata = Join-Path $downloads 'repository.xml'
    Get-Download 'https://dl.google.com/android/repository/repository2-1.xml' $metadata
    [xml]$repository = Get-Content -Raw $metadata
    $package = $repository.'sdk-repository'.remotePackage |
        Where-Object { $_.path -eq 'cmdline-tools;latest' }
    $complete = ($package.archives.archive | Where-Object { $_.'host-os' -eq 'windows' }).complete
    $archive = Join-Path $downloads $complete.url
    if (-not (Test-Path $archive)) { Get-Download ("https://dl.google.com/android/repository/" + $complete.url) $archive }
    Test-Archive $archive $complete.checksum 'SHA1'
    $staging = Join-Path $downloads 'android-commandline'
    Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools/latest') | Out-Null
    Copy-Item -Path (Join-Path $staging 'cmdline-tools/*') -Destination (Join-Path $sdk 'cmdline-tools/latest') -Recurse -Force
}

$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk.FullName
    if ($AcceptAndroidLicenses) {
        1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdk" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK license acceptance failed.' }
    }
    & $sdkManager "--sdk_root=$sdk" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed. Review licenses with sdkmanager --licenses.' }
} finally {
    $env:JAVA_HOME = $previousJava
}

Write-Host 'Toolchain installed. Run ./scripts/build-local.ps1 to download verified Gradle and validate the project.'
