param(
    [Parameter(Mandatory = $true)][string]$Package,
    [string]$InstallDir = '',
    [switch]$NoRestart,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $InstallDir) { $InstallDir = $ScriptRoot }
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$InstallParent = Split-Path -Parent $InstallDir
New-Item -ItemType Directory -Force -Path $InstallParent | Out-Null
$StageRoot = Join-Path $InstallParent ".caw-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$ExtractRoot = Join-Path $StageRoot 'c'
$BackupRoot = Join-Path $StageRoot 'backup'
$ManagedPaths = @(
    'dist', 'src', 'tests', 'contracts', 'scripts', 'node_modules',
    '.env.example', 'README.md', 'VERSION', 'package.json', 'package-lock.json', 'tsconfig.json',
    'start.ps1', 'start.sh', 'stop.ps1', 'stop.sh', 'update.ps1', 'update.sh', 'install.ps1', 'install.sh'
)
$SwapStarted = $false
$WasRunning = $false

function Invoke-Npm([string[]]$Arguments, [string]$WorkingDirectory) {
    $TimeoutSec = if ($env:CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC -match '^[1-9]\d*$') {
        [int]$env:CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC
    } else { 300 }
    Get-Command npm -ErrorAction Stop | Out-Null
    $StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $StartInfo.FileName = 'cmd.exe'
    $StartInfo.Arguments = "/d /s /c `"npm $($Arguments -join ' ')`""
    $StartInfo.WorkingDirectory = $WorkingDirectory
    $StartInfo.UseShellExecute = $false
    $Process = [System.Diagnostics.Process]::Start($StartInfo)
    if (-not $Process.WaitForExit($TimeoutSec * 1000)) {
        & taskkill.exe /PID $Process.Id /T /F 2>$null | Out-Null
        throw "npm $($Arguments -join ' ') timed out after $TimeoutSec seconds"
    }
    $Process.WaitForExit()
    if ($Process.ExitCode -ne 0) { throw "npm $($Arguments -join ' ') failed with exit code $($Process.ExitCode)" }
}

function Find-Candidate([string]$Root) {
    if (Test-Path (Join-Path $Root 'package.json')) { return $Root }
    $Candidates = @(Get-ChildItem -LiteralPath $Root -Directory -Force | Where-Object {
        Test-Path (Join-Path $_.FullName 'package.json')
    })
    if ($Candidates.Count -ne 1) { throw 'Release must contain exactly one codex-app-server-worker root' }
    return $Candidates[0].FullName
}

function Test-Running([string]$Root) {
    $RunDir = if ($env:CODEX_APP_SERVER_RUN_DIR) { $env:CODEX_APP_SERVER_RUN_DIR } else { Join-Path $Root 'logs\run' }
    $PidFile = Join-Path $RunDir 'worker.pid'
    if (-not (Test-Path $PidFile)) { return $false }
    $RawPid = (Get-Content -Raw $PidFile).Trim()
    return $RawPid -match '^\d+$' -and $null -ne (Get-Process -Id ([int]$RawPid) -ErrorAction SilentlyContinue)
}

function Restore-PreviousInstall {
    $ErrorActionPreference = 'Continue'
    $NewStop = Join-Path $InstallDir 'stop.ps1'
    if (Test-Path $NewStop) { & powershell -ExecutionPolicy Bypass -File $NewStop 2>$null }
    foreach ($Name in $ManagedPaths) {
        $Target = Join-Path $InstallDir $Name
        if (Test-Path $Target) { Remove-Item -LiteralPath $Target -Recurse -Force }
    }
    foreach ($Name in $ManagedPaths) {
        $Backup = Join-Path $BackupRoot $Name
        if (Test-Path $Backup) { Move-Item -LiteralPath $Backup -Destination (Join-Path $InstallDir $Name) -Force }
    }
    if ($WasRunning) {
        $OldStart = Join-Path $InstallDir 'start.ps1'
        if (Test-Path $OldStart) { & powershell -ExecutionPolicy Bypass -File $OldStart -NoBuild }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $ExtractRoot, $BackupRoot | Out-Null
    $PackagePath = [IO.Path]::GetFullPath($Package)
    if (Test-Path -LiteralPath $PackagePath -PathType Container) {
        Get-ChildItem -LiteralPath $PackagePath -Force | Copy-Item -Destination $ExtractRoot -Recurse -Force
    }
    elseif (Test-Path -LiteralPath $PackagePath -PathType Leaf) {
        if ([IO.Path]::GetExtension($PackagePath) -ne '.zip') { throw 'Only deterministic .zip releases are supported' }
        Expand-Archive -LiteralPath $PackagePath -DestinationPath $ExtractRoot
    }
    else { throw "Release package not found: $PackagePath" }

    $Candidate = Find-Candidate $ExtractRoot
    $PackageJson = Get-Content (Join-Path $Candidate 'package.json') -Raw | ConvertFrom-Json
    if ($PackageJson.name -ne 'codex-app-server-worker') { throw 'Unexpected package identity' }
    foreach ($Required in @('dist', 'src', 'tests', 'contracts', 'scripts', 'package-lock.json', 'tsconfig.json', 'VERSION')) {
        if (-not (Test-Path (Join-Path $Candidate $Required))) { throw "Release is missing $Required" }
    }
    foreach ($Forbidden in @('.env', 'logs', 'node_modules', 'CODEX_HOME', 'auth.json')) {
        if (Test-Path (Join-Path $Candidate $Forbidden)) { throw "Release contains forbidden runtime path: $Forbidden" }
    }

    Write-Output "Validating codex-app-server-worker $($PackageJson.version) before drain..."
    Invoke-Npm @('ci') $Candidate
    Invoke-Npm @('test') $Candidate
    Invoke-Npm @('run', 'verify:schema') $Candidate
    Invoke-Npm @('run', 'typecheck') $Candidate
    Invoke-Npm @('run', 'build') $Candidate
    if ($DryRun) {
        Write-Output 'Dry run complete; current installation was not modified.'
        exit 0
    }

    $WasRunning = Test-Running $InstallDir
    if ($WasRunning) {
        $StopScript = Join-Path $InstallDir 'stop.ps1'
        if (-not (Test-Path $StopScript)) { throw 'Running installation has no stop.ps1; refusing unsafe replacement' }
        & powershell -ExecutionPolicy Bypass -File $StopScript
        if ($LASTEXITCODE -ne 0) { throw 'Worker drain failed; current installation was not replaced' }
    }

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $SwapStarted = $true
    foreach ($Name in $ManagedPaths) {
        $Current = Join-Path $InstallDir $Name
        if (Test-Path $Current) { Move-Item -LiteralPath $Current -Destination (Join-Path $BackupRoot $Name) -Force }
    }
    foreach ($Name in $ManagedPaths) {
        $Source = Join-Path $Candidate $Name
        if (Test-Path $Source) { Move-Item -LiteralPath $Source -Destination (Join-Path $InstallDir $Name) -Force }
    }

    if ($WasRunning -and -not $NoRestart) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $InstallDir 'start.ps1') -NoBuild
        if ($LASTEXITCODE -ne 0) { throw 'Updated Worker failed readiness after restart' }
    }
    $SwapStarted = $false
    Write-Output "codex-app-server-worker updated to $($PackageJson.version); runtime configuration and state were preserved."
}
catch {
    $Failure = $_
    if ($SwapStarted) { Restore-PreviousInstall }
    throw $Failure
}
finally {
    if (Test-Path $StageRoot) { Remove-Item -LiteralPath $StageRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
