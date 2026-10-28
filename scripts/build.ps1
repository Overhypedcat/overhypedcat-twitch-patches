# Builds the patches and produces a patched Twitch APK in dist\.
# Installs missing tools on first run. Put the original Twitch .apkm (or .apk) from APKMirror in apk\.
# Native tools write progress to stderr; failures are checked through exit codes instead.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$keystorePassword = 'twitchmorphed'

function Step($message) { Write-Host "`n== $message" -ForegroundColor Cyan }
function Fail($message) { Write-Host "`n$message" -ForegroundColor Red; exit 1 }

function Find-Jdk21 {
    $candidates = @($env:JAVA_HOME) + (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-2*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path "$dir\release")) {
            $release = Get-Content "$dir\release" -Raw
            if ($release -match 'JAVA_VERSION="(\d+)') { if ([int]$Matches[1] -ge 21) { return $dir.TrimEnd('\') } }
        }
    }
    return $null
}

Step 'Java 21'
$jdk = Find-Jdk21
if (-not $jdk) {
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements --silent | Out-Null
    $jdk = Find-Jdk21
    if (-not $jdk) { Fail 'Java 21 could not be installed. Install Temurin 21 from https://adoptium.net and run again.' }
}
$env:JAVA_HOME = $jdk
Write-Host $jdk

Step 'Android SDK'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$sdk\platforms\android-36")) {
    $cli = "$sdk\cmdline-tools\latest\bin\android.exe"
    if (-not (Test-Path $cli)) {
        $repository = Invoke-RestMethod 'https://dl.google.com/android/repository/repository2-3.xml'
        $zip = ([regex]::Matches($repository.OuterXml, 'commandlinetools-win-(\d+)_latest\.zip') |
                Sort-Object { [long]$_.Groups[1].Value } | Select-Object -Last 1).Value
        $download = Join-Path $env:TEMP $zip
        Invoke-WebRequest "https://dl.google.com/android/repository/$zip" -OutFile $download
        $unpacked = Join-Path $env:TEMP 'twitch-morphed-cmdline-tools'
        Remove-Item $unpacked -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive $download $unpacked
        New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
        Move-Item "$unpacked\cmdline-tools" "$sdk\cmdline-tools\latest"
        Remove-Item $download, $unpacked -Recurse -Force
    }
    & $cli sdk install --sdk=$sdk platform-tools platforms/android-36 build-tools/36.0.0
    if (-not (Test-Path "$sdk\platforms\android-36")) { Fail 'Android SDK setup failed.' }
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
}
$env:ANDROID_HOME = $sdk
Write-Host $sdk

Step 'GitHub access (Morphe libraries are on GitHub Packages)'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    winget install --id GitHub.cli -e --accept-package-agreements --accept-source-agreements --silent | Out-Null
    $env:Path += ';C:\Program Files\GitHub CLI'
}
$status = gh auth status 2>&1 | Out-String
if ($status -notmatch 'Logged in') { gh auth login -h github.com -s read:packages -w }
elseif ($status -notmatch 'read:packages') { gh auth refresh -h github.com -s read:packages }
$env:GITHUB_ACTOR = (gh api user --jq .login)
$env:GITHUB_TOKEN = (gh auth token)

Step 'Building patches and running unit tests'
& .\gradlew.bat --no-daemon :extensions:twitch:test buildAndroid
if ($LASTEXITCODE -ne 0) { Fail 'Build failed.' }
$patches = Get-ChildItem 'patches\build\libs\patches-*.mpp' | Sort-Object LastWriteTime | Select-Object -Last 1

Step 'Morphe desktop'
New-Item -ItemType Directory -Force tools | Out-Null
$morphe = Get-ChildItem 'tools\morphe-desktop-*-all.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $morphe) {
    $release = Invoke-RestMethod 'https://api.github.com/repos/MorpheApp/morphe-desktop/releases/latest'
    $asset = $release.assets | Where-Object name -like 'morphe-desktop-*-all.jar' | Select-Object -First 1
    Invoke-WebRequest $asset.browser_download_url -OutFile "tools\$($asset.name)"
    $morphe = Get-Item "tools\$($asset.name)"
}
Write-Host $morphe.Name

Step 'Signing key'
New-Item -ItemType Directory -Force signing | Out-Null
$keystore = 'signing\twitch-morphed.keystore'
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $keystore -storetype PKCS12 -alias morphe -keyalg RSA -keysize 4096 `
        -validity 36500 -storepass $keystorePassword -keypass $keystorePassword -dname 'CN=Twitch Morphed'
}

Step 'Patching Twitch'
$original = Get-ChildItem 'apk\*' -Include *.apkm, *.apk, *.xapk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $original) { Fail 'Put the original Twitch .apkm from APKMirror in the apk folder and run again.' }
New-Item -ItemType Directory -Force dist | Out-Null
$output = 'dist\twitch-morphed.apk'
& "$jdk\bin\java.exe" -jar $morphe.FullName patch -p $patches.FullName --keystore=$keystore `
    --keystore-password=$keystorePassword --keystore-entry-alias=morphe --keystore-entry-password=$keystorePassword `
    --striplibs=arm64-v8a -o $output $original.FullName
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $output)) { Fail 'Patching failed.' }
Write-Host "Patched APK: $root\$output" -ForegroundColor Green

Step 'Installing'
$adb = "$sdk\platform-tools\adb.exe"
# Start the adb server on its own, otherwise it inherits this script's output and keeps it open.
# WaitForExit waits for adb itself only; Start-Process -Wait would also wait for the server it leaves running.
(Start-Process $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru).WaitForExit()
$device = & $adb devices | Select-String '\tdevice$' | Select-Object -First 1
if (-not $device) {
    Write-Host 'No phone connected over USB debugging. Copy the patched APK to the phone and open it to install.'
    exit 0
}
$result = & $adb install -r $output 2>&1 | Out-String
Write-Host $result
if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Host 'The Play Store Twitch is signed with a different key. Uninstall it on the phone once, then run again.' -ForegroundColor Yellow
}
