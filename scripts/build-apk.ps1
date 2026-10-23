param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $workspacePath
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $env:GRADLE_USER_HOME = Join-Path $workspacePath '.gradle-local'
    $criteria = Join-Path $workspacePath 'gradle\gradle-daemon-jvm.properties'
    $criteriaBackup = "$criteria.build-backup"
    if (Test-Path -LiteralPath $criteriaBackup) { throw "Existing Gradle criteria backup: $criteriaBackup" }
    $movedCriteria = $false
    try {
        if (Test-Path -LiteralPath $criteria) {
            Move-Item -LiteralPath $criteria -Destination $criteriaBackup
            $movedCriteria = $true
        }
        $gradleArgs = @(':app:testDebugUnitTest', ':app:assembleRelease', ':app:assembleDebugAndroidTest', '--console=plain')
        if ($Offline) { $gradleArgs += '--offline' }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    } finally {
        if ($movedCriteria) { Move-Item -LiteralPath $criteriaBackup -Destination $criteria }
    }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android build-tools not found' }
    $testKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (-not (Test-Path -LiteralPath $testKey)) { throw 'Android test signing key not found' }
    $dist = Join-Path $workspacePath 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $outputApk = Join-Path $dist 'WeType-Flick.apk'
    & (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $testKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $outputApk 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose $outputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    & (Join-Path $buildTools.FullName 'zipalign.exe') -c -P 16 4 $outputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed' }
    $apkFile = Get-Item -LiteralPath $outputApk
    if ($apkFile.Length -ge 10000000) { throw 'Signed APK must stay below 10 MB' }
    Write-Output "Output: $outputApk ($($apkFile.Length) bytes, Android test signature)"
} finally { Pop-Location }
