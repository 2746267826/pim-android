[CmdletBinding()]
param(
    [ValidateSet("PrepareHost", "RunDeviceTest", "PullEvidence", "StopHost")]
    [string]$Action = "PrepareHost",
    [string]$ApiRoot = "",
    [string]$QaRoot = "",
    [string]$DeliverablesRoot = "",
    [string]$Serial = "emulator-5556",
    [string]$ExpectedAvd = "Codex_Native_Notes_20261025",
    [string]$AppApk = "",
    [string]$TestApk = "",
    [ValidateSet("phone", "narrow", "tablet")]
    [string]$DeviceProfile = "phone",
    [string]$TestClass = "com.pim.app.notes.NativeNoteAsrTest",
    [switch]$ClearAppData,
    [switch]$ProceedWithOwnedDevice
)

$ErrorActionPreference = "Stop"
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $androidRoot ".."))
if ([string]::IsNullOrWhiteSpace($ApiRoot)) {
    $ApiRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot "pim-api"))
} else {
    $ApiRoot = [IO.Path]::GetFullPath($ApiRoot)
}
if ([string]::IsNullOrWhiteSpace($QaRoot)) {
    $QaRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot ".codex-tmp\asr-client-implementation\qa-runtime"))
} else {
    $QaRoot = [IO.Path]::GetFullPath($QaRoot)
}
if ([string]::IsNullOrWhiteSpace($DeliverablesRoot)) {
    $DeliverablesRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot "deliverables\native-note-asr-qa"))
} else {
    $DeliverablesRoot = [IO.Path]::GetFullPath($DeliverablesRoot)
}

$sourceHost = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "AndroidNoteAsrApiHost.cs"))
$apiTestsNotes = [IO.Path]::GetFullPath((Join-Path $ApiRoot "tests\Pim.UnitTests\Notes"))
$installedHost = [IO.Path]::GetFullPath((Join-Path $apiTestsNotes "TemporaryAndroidNoteAsrHostTests.cs"))
$statePath = [IO.Path]::GetFullPath((Join-Path $QaRoot "host-state.json"))
$readyPath = [IO.Path]::GetFullPath((Join-Path $QaRoot "ready.json"))
$stopPath = [IO.Path]::GetFullPath((Join-Path $QaRoot "stop"))
$stdoutPath = [IO.Path]::GetFullPath((Join-Path $QaRoot "host.stdout.log"))
$stderrPath = [IO.Path]::GetFullPath((Join-Path $QaRoot "host.stderr.log"))

function Assert-Under([string]$Path, [string]$Parent) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd("\") + "\"
    if (-not $fullPath.StartsWith($fullParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside expected root: $fullPath"
    }
}

function Read-Ready {
    if (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        throw "Missing isolated ASR QA ready file: $readyPath"
    }
    return Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
}

function Assert-OwnedDevice {
    if (-not $ProceedWithOwnedDevice) {
        throw "Device operation requires -ProceedWithOwnedDevice after the coordinator has approved the dedicated AVD window."
    }
    $adb = Get-Command adb -ErrorAction Stop
    $devices = (& $adb.Source devices) -join "`n"
    if ($devices -notmatch [regex]::Escape($Serial)) {
        throw "Dedicated serial is not connected: $Serial"
    }
    $nameLines = & $adb.Source -s $Serial emu avd name 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read AVD name for $Serial"
    }
    $actualAvd = (($nameLines | ForEach-Object { "$_".Trim() } | Where-Object { $_ }) | Select-Object -First 1)
    if ($actualAvd -ne $ExpectedAvd) {
        throw "Refusing device '$Serial': actual AVD '$actualAvd' is not dedicated AVD '$ExpectedAvd'"
    }
    return $adb.Source
}

function Set-OwnedDeviceProfile([string]$Adb) {
    $window = switch ($DeviceProfile) {
        "phone" { @{ Size = "1080x2424"; Density = "420"; FontScale = "1.0"; Rotation = "0" } }
        "narrow" { @{ Size = "640x1280"; Density = "320"; FontScale = "1.5"; Rotation = "0" } }
        "tablet" { @{ Size = "2048x1536"; Density = "320"; FontScale = "1.5"; Rotation = "1" } }
    }
    $commands = @(
        @("shell", "wm", "size", $window.Size),
        @("shell", "wm", "density", $window.Density),
        @("shell", "settings", "put", "system", "font_scale", $window.FontScale),
        @("shell", "settings", "put", "system", "accelerometer_rotation", "0"),
        @("shell", "settings", "put", "system", "user_rotation", $window.Rotation)
    )
    $output = [Collections.Generic.List[string]]::new()
    foreach ($command in $commands) {
        $line = @(& $Adb -s $Serial @command 2>&1 | ForEach-Object { "$_" })
        if ($LASTEXITCODE -ne 0) {
            throw "Could not apply $DeviceProfile window profile: adb $($command -join ' ')"
        }
        $output.Add("adb $($command -join ' ') => $($line -join ' ')".Trim()) | Out-Null
    }
    $actual = [ordered]@{
        size = ((& $Adb -s $Serial shell wm size 2>&1) -join " ").Trim()
        density = ((& $Adb -s $Serial shell wm density 2>&1) -join " ").Trim()
        fontScale = ((& $Adb -s $Serial shell settings get system font_scale 2>&1) -join " ").Trim()
        rotation = ((& $Adb -s $Serial shell settings get system user_rotation 2>&1) -join " ").Trim()
    }
    if ($actual.size -notmatch [regex]::Escape($window.Size) -or
        $actual.density -notmatch [regex]::Escape($window.Density) -or
        $actual.fontScale -ne $window.FontScale -or
        $actual.rotation -ne $window.Rotation) {
        throw "Window profile readback mismatch: $($actual | ConvertTo-Json -Compress)"
    }
    foreach ($key in $actual.Keys) {
        $output.Add("readback $key=$($actual[$key])") | Out-Null
    }
    return $output
}

if ($Action -eq "PrepareHost") {
    if (-not (Test-Path -LiteralPath $sourceHost -PathType Leaf)) {
        throw "Missing host source: $sourceHost"
    }
    if ((Test-Path -LiteralPath $readyPath) -or (Test-Path -LiteralPath $installedHost)) {
        throw "QA host may already be prepared. Choose a new -QaRoot and clean up the previous temporary API source before retrying."
    }
    Assert-Under $installedHost $apiTestsNotes
    New-Item -ItemType Directory -Path $QaRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $apiTestsNotes -Force | Out-Null
    Copy-Item -LiteralPath $sourceHost -Destination $installedHost
    $sourceHash = (Get-FileHash -LiteralPath $sourceHost -Algorithm SHA256).Hash
    $installedHash = (Get-FileHash -LiteralPath $installedHost -Algorithm SHA256).Hash
    if ($sourceHash -ne $installedHash) {
        throw "Temporary API host copy hash mismatch"
    }

    $oldRoot = $env:PIM_ANDROID_NOTE_ASR_QA_ROOT
    try {
        $env:PIM_ANDROID_NOTE_ASR_QA_ROOT = $QaRoot
        $process = Start-Process -FilePath "dotnet" `
            -ArgumentList @("test", "tests/Pim.UnitTests/Pim.UnitTests.csproj", "--filter", "FullyQualifiedName~TemporaryAndroidNoteAsrHostTests", "-m:1", "/nr:false") `
            -WorkingDirectory $ApiRoot `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -WindowStyle Hidden `
            -PassThru
    } finally {
        $env:PIM_ANDROID_NOTE_ASR_QA_ROOT = $oldRoot
    }

    $state = [ordered]@{
        processId = $process.Id
        apiRoot = $ApiRoot
        qaRoot = $QaRoot
        installedHost = $installedHost
        installedHostSha256 = $installedHash
        sourceHostSha256 = $sourceHash
        startedAt = [DateTimeOffset]::UtcNow
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8

    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(4)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $readyPath -PathType Leaf) {
            $ready = Read-Ready
            Write-Output "ASR QA host ready: $($ready.apiBase)"
            Write-Output "Accounts: $($ready.accounts -join ', ')"
            Write-Output "Synthetic segments: $($ready.fixture.expectedSegments)"
            exit 0
        }
        if ($process.HasExited) {
            $tail = @()
            if (Test-Path -LiteralPath $stdoutPath) { $tail += Get-Content -LiteralPath $stdoutPath -Tail 80 }
            if (Test-Path -LiteralPath $stderrPath) { $tail += Get-Content -LiteralPath $stderrPath -Tail 80 }
            throw "ASR QA host exited before readiness.`n$($tail -join "`n")"
        }
        Start-Sleep -Seconds 1
    }
    throw "ASR QA host did not become ready. See $stdoutPath and $stderrPath"
}

if ($Action -eq "StopHost") {
    if (Test-Path -LiteralPath $QaRoot -PathType Container) {
        New-Item -ItemType File -Path $stopPath -Force | Out-Null
    }
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            $process.WaitForExit(15000) | Out-Null
            if (-not $process.HasExited) {
                Stop-Process -Id $state.processId -Force
                $process.WaitForExit()
            }
        }
        $installed = [IO.Path]::GetFullPath($state.installedHost)
        Assert-Under $installed $apiTestsNotes
        if (Test-Path -LiteralPath $installed -PathType Leaf) {
            $currentHash = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
            if ($currentHash -ne $state.installedHostSha256) {
                throw "Temporary host changed after preparation; refusing to delete $installed"
            }
            Remove-Item -LiteralPath $installed -Force
            Write-Output "Removed only the QA-generated temporary API host: $installed"
        }
    }
    exit 0
}

if ($Action -eq "PullEvidence") {
    $adb = Assert-OwnedDevice
    New-Item -ItemType Directory -Path $DeliverablesRoot -Force | Out-Null
    $remote = "/sdcard/Android/data/com.pim.app/files/native-note-asr-qa"
    & $adb -s $Serial pull $remote $DeliverablesRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Could not pull ASR screenshots from $remote"
    }
    exit 0
}

if ($Action -eq "RunDeviceTest") {
    if ([string]::IsNullOrWhiteSpace($AppApk) -or [string]::IsNullOrWhiteSpace($TestApk)) {
        throw "RunDeviceTest requires -AppApk and -TestApk; this driver never invokes Gradle."
    }
    $AppApk = [IO.Path]::GetFullPath($AppApk)
    $TestApk = [IO.Path]::GetFullPath($TestApk)
    if (-not (Test-Path -LiteralPath $AppApk -PathType Leaf)) { throw "Missing app APK: $AppApk" }
    if (-not (Test-Path -LiteralPath $TestApk -PathType Leaf)) { throw "Missing test APK: $TestApk" }
    $ready = Read-Ready
    $uri = [Uri]$ready.apiBase
    if ($uri.Host -ne "127.0.0.1") {
        throw "QA API must listen on loopback, found: $($uri.Host)"
    }
    $adb = Assert-OwnedDevice
    New-Item -ItemType Directory -Path $DeliverablesRoot -Force | Out-Null
    $safeTest = ($TestClass -replace "[^A-Za-z0-9_.-]", "_")
    if ($safeTest.Length -gt 64) { $safeTest = $safeTest.Substring(0, 64) }
    $runLog = [IO.Path]::GetFullPath((Join-Path $QaRoot (
        "device-run-$DeviceProfile-$safeTest-$([DateTime]::Now.ToString('yyyyMMdd-HHmmss')).log"
    )))
    $logLines = [Collections.Generic.List[string]]::new()
    $logLines.Add("serial=$Serial avd=$ExpectedAvd")
    $logLines.Add("deviceProfile=$DeviceProfile")
    $logLines.Add("testClass=$TestClass")
    $logLines.Add("clearAppData=$($ClearAppData.IsPresent)")
    $logLines.Add("apiBase=$($ready.apiBase)")
    $logLines.Add("appApk=$AppApk")
    $logLines.Add("appApkSha256=$((Get-FileHash -LiteralPath $AppApk -Algorithm SHA256).Hash)")
    $logLines.Add("testApk=$TestApk")
    $logLines.Add("testApkSha256=$((Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash)")
    $port = $uri.Port
    try {
        foreach ($line in @(Set-OwnedDeviceProfile $adb)) {
            $logLines.Add([string]$line)
        }
        $installApp = @(& $adb -s $Serial install -r $AppApk 2>&1 | ForEach-Object { "$_" })
        foreach ($line in $installApp) { $logLines.Add([string]$line) }
        if ($LASTEXITCODE -ne 0) { throw "App APK install failed; see $runLog" }
        $installTest = @(& $adb -s $Serial install -r $TestApk 2>&1 | ForEach-Object { "$_" })
        foreach ($line in $installTest) { $logLines.Add([string]$line) }
        if ($LASTEXITCODE -ne 0) { throw "Test APK install failed; see $runLog" }
        if ($ClearAppData) {
            $clear = @(& $adb -s $Serial shell pm clear "com.pim.app" 2>&1 | ForEach-Object { "$_" })
            foreach ($line in $clear) { $logLines.Add([string]$line) }
            if ($LASTEXITCODE -ne 0 -or ($clear -join " ") -notmatch "Success") {
                throw "Could not clear owned test app data; see $runLog"
            }
        }
        $reverse = @(& $adb -s $Serial reverse "tcp:$port" "tcp:$port" 2>&1 | ForEach-Object { "$_" })
        foreach ($line in $reverse) { $logLines.Add([string]$line) }
        if ($LASTEXITCODE -ne 0) { throw "adb reverse failed; see $runLog" }
        $instrumentation = @(
            "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", $TestClass,
            "-e", "nativeNoteAsrQaApi", $ready.apiBase,
            "-e", "nativeNoteAsrQaProfile", $DeviceProfile,
            "com.pim.app.test/androidx.test.runner.AndroidJUnitRunner"
        )
        $output = @(& $adb @instrumentation 2>&1 | ForEach-Object { "$_" })
        foreach ($line in $output) { $logLines.Add([string]$line) }
        $joined = $output -join "`n"
        if ($joined -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" -or $joined -notmatch "OK \(\d+ tests?\)") {
            throw "Instrumentation did not report a passing NativeNoteAsrTest result. See $runLog"
        }
        $remoteEvidence = "/sdcard/Android/data/com.pim.app/files/native-note-asr-qa"
        & $adb -s $Serial shell test -d $remoteEvidence
        if ($LASTEXITCODE -eq 0) {
            & $adb -s $Serial pull $remoteEvidence $DeliverablesRoot | Out-Null
        } else {
            $logLines.Add("no screenshot directory for this test class")
        }
        Write-Output "Device ASR test passed on $ExpectedAvd/$Serial"
        Write-Output "Evidence log: $runLog"
    } finally {
        & $adb -s $Serial reverse --remove "tcp:$port" 2>&1 | Out-Null
        $logLines | Set-Content -LiteralPath $runLog -Encoding UTF8
    }
    exit 0
}
