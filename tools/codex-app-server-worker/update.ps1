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
$LifecycleMarkerSource = Join-Path $ScriptRoot 'scripts\lifecycle-marker.mjs'
function Assert-SafeInstallTarget([string]$Target) {
    $RootPath = [IO.Path]::GetPathRoot($Target)
    $TrimChars = [char[]]@('\', '/')
    if ($Target.TrimEnd($TrimChars) -eq $RootPath.TrimEnd($TrimChars)) {
        throw 'Install directory must not be a filesystem root'
    }
    if (-not (Test-Path -LiteralPath $Target)) { return }
    $Item = Get-Item -LiteralPath $Target -Force
    if (-not $Item.PSIsContainer -or ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Install directory must be a real directory, not a file or reparse point'
    }
    if ($null -eq (Get-ChildItem -LiteralPath $Target -Force | Select-Object -First 1)) { return }
    $IdentityPackage = Join-Path $Target 'package.json'
    $IdentityVersion = Join-Path $Target 'VERSION'
    if (-not (Test-Path -LiteralPath $IdentityPackage -PathType Leaf) -or
        -not (Test-Path -LiteralPath $IdentityVersion -PathType Leaf)) {
        throw 'Refusing non-empty install directory without codex-app-server-worker identity'
    }
    foreach ($IdentityPath in @($IdentityPackage, $IdentityVersion)) {
        if ((Get-Item -LiteralPath $IdentityPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Product identity file must not be a reparse point'
        }
    }
    try { $Identity = Get-Content -Raw -LiteralPath $IdentityPackage | ConvertFrom-Json } catch {
        throw 'Refusing non-empty install directory with invalid product identity'
    }
    if ($Identity.name -ne 'codex-app-server-worker') {
        throw 'Refusing non-empty install directory owned by another product'
    }
    $InstalledVersion = (Get-Content -Raw -LiteralPath $IdentityVersion).Trim()
    if ($InstalledVersion -eq '0.1.0') {
        throw 'In-place update of codex-app-server-worker 0.1.0 is not supported; install into a new empty directory or use an external OS-level zero-residue migration'
    }
}
function Read-DotEnvValue([string]$Name) {
    $EnvFile = Join-Path $InstallDir '.env'
    if (-not (Test-Path -LiteralPath $EnvFile)) { return $null }
    $Reader = Join-Path $Candidate 'scripts\read-dotenv-value.mjs'
    $Value = & node $Reader $EnvFile $Name
    if ($LASTEXITCODE -ne 0) { throw "Failed to read $Name from $EnvFile" }
    if ($null -eq $Value) { return $null }
    return [string]$Value
}
function Select-ConfigValue([string]$ProcessValue, [string]$DotEnvValue, [string]$DefaultValue) {
    if (-not [string]::IsNullOrWhiteSpace($ProcessValue)) { return $ProcessValue.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($DotEnvValue)) { return $DotEnvValue.Trim() }
    return $DefaultValue
}
if (-not (Test-Path -LiteralPath $LifecycleMarkerSource -PathType Leaf)) {
    throw "Required lifecycle marker helper is missing: $LifecycleMarkerSource"
}
$UpdateTransactionFile = Join-Path $InstallDir 'update.in-progress'
Assert-SafeInstallTarget $InstallDir
New-Item -ItemType Directory -Force -Path $InstallParent | Out-Null
$TransactionNonce = [Guid]::NewGuid().ToString('N')
$LifecycleLockFile = Join-Path $InstallDir 'lifecycle.lock'
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
$CandidateStartAttempted = $false
$PreserveStageRoot = $false
$TransactionOwned = $false
$TransactionCanClose = $false
$LifecycleLockOwned = $false
$ControlMarkerHelper = Join-Path $InstallParent ".caw-marker-$TransactionNonce.mjs"
$BackedUpPaths = @()
$InstalledCandidatePaths = @()

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

function Test-Running([string]$Root, [string]$SelectedRunDir) {
    $PidFile = Join-Path $SelectedRunDir 'worker.pid'
    if (-not (Test-Path $PidFile)) { return $false }
    $RawPid = (Get-Content -Raw $PidFile).Trim()
    return $RawPid -match '^\d+$' -and $null -ne (Get-Process -Id ([int]$RawPid) -ErrorAction SilentlyContinue)
}
function Invoke-ProcessTree([string]$Helper, [string[]]$Arguments) {
    & node $Helper @Arguments *> $null
    return $LASTEXITCODE
}
function Invoke-LifecycleMarkerFile([string]$Helper, [string[]]$Arguments) {
    $PreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & node $Helper @Arguments *> $null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $PreviousPreference
    }
}
function Invoke-LifecycleMarker([string[]]$Arguments) {
    $Status = Invoke-LifecycleMarkerFile $ControlMarkerHelper $Arguments
    return $Status
}
function Set-TransactionPhase([string]$Phase, [string]$BackedUp = '', [string]$Installed = '') {
    $Arguments = @('update', '--path', $UpdateTransactionFile, '--nonce', $TransactionNonce, '--phase', $Phase)
    if ($BackedUp) { $Arguments += @('--append-backed-up', $BackedUp) }
    if ($Installed) { $Arguments += @('--append-installed', $Installed) }
    $Status = Invoke-LifecycleMarker $Arguments
    if ($Status -ne 0) { throw "Failed to persist update transaction phase '$Phase'" }
}
function Write-FailedStopLatch([string]$Path, [string]$Reason) {
    $Status = Invoke-LifecycleMarker @('write-once', '--path', $Path, '--reason', $Reason)
    if ($Status -ne 0) { throw "Failed to persist failure latch at $Path" }
}
function Find-StateEntry([string]$StateDirectory, [string]$ExpectedName) {
    if (-not (Test-Path -LiteralPath $StateDirectory -PathType Container)) { return $null }
    foreach ($Entry in [IO.Directory]::EnumerateFileSystemEntries($StateDirectory)) {
        if ([IO.Path]::GetFileName($Entry) -ieq $ExpectedName) { return $Entry }
    }
    return $null
}
function Test-RuntimeProcessTreeEvidence([string]$StateDirectory) {
    $Entry = Find-StateEntry $StateDirectory 'runtime-process-trees'
    if ($null -eq $Entry) { return $false }
    $Item = Get-Item -LiteralPath $Entry -Force -ErrorAction SilentlyContinue
    if ($null -eq $Item) { return $true }
    return (($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
        (-not $Item.PSIsContainer) -or
        ($null -ne (Get-ChildItem -LiteralPath $Entry -Force | Select-Object -First 1))
}
function Remove-EvidenceFile([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
    }
    if (Test-Path -LiteralPath $Path) { throw "Lifecycle evidence remains at $Path" }
}

function Restore-PreviousInstall {
    if ($CandidateStartAttempted) {
        $script:PreserveStageRoot = $true
        throw "Candidate startup was attempted; automatic rollback is suppressed so candidate files and backup evidence remain at $StageRoot"
    }
    Set-TransactionPhase 'rollback'
    $RollbackErrors = @()
    foreach ($Name in $InstalledCandidatePaths) {
        $Target = Join-Path $InstallDir $Name
        if (Test-Path -LiteralPath $Target) {
            try { Remove-Item -LiteralPath $Target -Recurse -Force } catch { $RollbackErrors += "remove ${Name}: $($_.Exception.Message)" }
        }
    }
    foreach ($Name in $BackedUpPaths) {
        $Backup = Join-Path $BackupRoot $Name
        $Target = Join-Path $InstallDir $Name
        if (-not (Test-Path -LiteralPath $Backup)) {
            $RollbackErrors += "backup missing: $Name"
        } elseif (Test-Path -LiteralPath $Target) {
            $RollbackErrors += "restore target occupied: $Name"
        } else {
            try { Move-Item -LiteralPath $Backup -Destination $Target } catch { $RollbackErrors += "restore ${Name}: $($_.Exception.Message)" }
        }
    }
    if ($RollbackErrors.Count -gt 0) {
        $script:PreserveStageRoot = $true
        try { Set-TransactionPhase 'rollback_failed' } catch {}
        throw "Rollback failed; installation and backup were preserved for operator recovery at $StageRoot. $($RollbackErrors -join '; ')"
    }
    if ($WasRunning -and -not (Test-Path -LiteralPath $FailedStopFile)) {
        $OldStart = Join-Path $InstallDir 'start.ps1'
        if (-not (Test-Path -LiteralPath $OldStart -PathType Leaf)) {
            $script:PreserveStageRoot = $true
            throw "Previous package was restored without a start.ps1; recovery evidence remains at $StageRoot"
        }
        & powershell -ExecutionPolicy Bypass -File $OldStart -NoBuild `
            -UpdateTransactionNonce $TransactionNonce -LifecycleLockNonce $TransactionNonce
        if ($LASTEXITCODE -ne 0) {
            $script:PreserveStageRoot = $true
            throw "Previous package was restored but failed to restart; recovery evidence remains at $StageRoot"
        }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $LockStatus = Invoke-LifecycleMarkerFile $LifecycleMarkerSource @(
        'lock-acquire', '--path', $LifecycleLockFile, '--nonce', $TransactionNonce, '--operation', 'update'
    )
    if ($LockStatus -ne 0) {
        throw "Lifecycle operation is locked at $LifecycleLockFile; complete manual recovery before retrying update"
    }
    $LifecycleLockOwned = $true
    Copy-Item -LiteralPath $LifecycleMarkerSource -Destination $ControlMarkerHelper
    if (Test-Path -LiteralPath $UpdateTransactionFile) {
        throw "An unresolved update transaction is present at $UpdateTransactionFile; operator recovery is required"
    }
    $CreateTransactionStatus = Invoke-LifecycleMarker @(
        'create', '--path', $UpdateTransactionFile, '--nonce', $TransactionNonce, '--stage-root', $StageRoot
    )
    if ($CreateTransactionStatus -ne 0) { throw 'Could not acquire the exclusive update transaction marker' }
    $TransactionOwned = $true
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
    foreach ($Required in @(
        'dist', 'src', 'tests', 'contracts', 'scripts', 'package-lock.json', 'tsconfig.json', 'VERSION',
        'start.ps1', 'start.sh', 'stop.ps1', 'stop.sh', 'update.ps1', 'update.sh', 'install.ps1', 'install.sh',
        'scripts\lifecycle-marker.mjs', 'scripts\process-tree.mjs', 'scripts\read-dotenv-value.mjs'
    )) {
        if (-not (Test-Path (Join-Path $Candidate $Required))) { throw "Release is missing $Required" }
    }
    foreach ($Forbidden in @('.env', 'logs', 'node_modules', 'CODEX_HOME', 'auth.json')) {
        if (Test-Path (Join-Path $Candidate $Forbidden)) { throw "Release contains forbidden runtime path: $Forbidden" }
    }
    $CandidateVersionFile = (Get-Content -Raw -LiteralPath (Join-Path $Candidate 'VERSION')).Trim()
    if ($CandidateVersionFile -cne [string]$PackageJson.version) {
        throw 'Release VERSION must exactly match package.json version'
    }

    Write-Output "Validating codex-app-server-worker $($PackageJson.version) before drain..."
    Invoke-Npm @('ci') $Candidate
    Invoke-Npm @('test') $Candidate
    Invoke-Npm @('run', 'verify:schema') $Candidate
    Invoke-Npm @('run', 'typecheck') $Candidate
    Invoke-Npm @('run', 'build') $Candidate
    Set-TransactionPhase 'validated'
    $EnvRunDir = Read-DotEnvValue 'CODEX_APP_SERVER_RUN_DIR'
    $EnvLogDir = Read-DotEnvValue 'CODEX_APP_SERVER_LOG_DIR'
    $EnvStateDir = Read-DotEnvValue 'CODEX_APP_SERVER_STATE_DIR'
    $EnvHost = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_HOST'
    $EnvPort = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_PORT'
    $RunDir = Select-ConfigValue $env:CODEX_APP_SERVER_RUN_DIR $EnvRunDir (Join-Path $InstallDir 'logs\run')
    $LogDir = Select-ConfigValue $env:CODEX_APP_SERVER_LOG_DIR $EnvLogDir (Join-Path $InstallDir 'logs')
    $StateDir = Select-ConfigValue $env:CODEX_APP_SERVER_STATE_DIR $EnvStateDir (Join-Path $InstallDir 'logs\state')
    $DisplayHost = Select-ConfigValue $env:CODEX_APP_SERVER_WORKER_HOST $EnvHost '127.0.0.1'
    $DisplayPort = Select-ConfigValue $env:CODEX_APP_SERVER_WORKER_PORT $EnvPort '3062'

    $env:CODEX_APP_SERVER_RUN_DIR = $RunDir
    $env:CODEX_APP_SERVER_LOG_DIR = $LogDir
    $env:CODEX_APP_SERVER_STATE_DIR = $StateDir
    $env:CODEX_APP_SERVER_WORKER_HOST = $DisplayHost
    $env:CODEX_APP_SERVER_WORKER_PORT = $DisplayPort

    $LifecycleFailureFile = Join-Path $StateDir 'lifecycle.failed'
    $RuntimeProcessTreeDir = Join-Path $StateDir 'runtime-process-trees'
    $FailedStopFile = Join-Path $RunDir 'stop.failed'
    if (Test-Path -LiteralPath $FailedStopFile) {
        throw "Previous failed stop is latched at $FailedStopFile; refusing package replacement"
    }

    $WasRunning = Test-Running $InstallDir $RunDir
    $PidFile = Join-Path $RunDir 'worker.pid'
    $LifecycleSnapshot = Join-Path $RunDir 'worker.process-tree.json'
    $UpdateSnapshot = Join-Path $RunDir 'update.process-tree.json'
    $ProcessTreeHelper = Join-Path $Candidate 'scripts\process-tree.mjs'
    $InstalledEntry = Join-Path $InstallDir 'dist\index.js'
    if (Test-Path -LiteralPath $UpdateSnapshot) {
        Write-FailedStopLatch $FailedStopFile 'previous_update_identity_evidence_present'
        throw "Previous update identity evidence remains at $UpdateSnapshot; refusing to overwrite or bypass it"
    }
    if (-not $WasRunning -and ((Test-Path -LiteralPath $PidFile) -or (Test-Path -LiteralPath $LifecycleSnapshot))) {
        Write-FailedStopLatch $FailedStopFile 'update_found_unresolved_worker_identity'
        throw 'Worker identity evidence exists without a verifiably running Worker; refusing package replacement'
    }
    if ($WasRunning) {
        $RunningPid = (Get-Content -Raw $PidFile).Trim()
        if (Test-Path -LiteralPath $LifecycleSnapshot -PathType Leaf) {
            $SnapshotStatus = Invoke-ProcessTree $ProcessTreeHelper @(
                'status', '--pid', $RunningPid, '--entry', $InstalledEntry, '--output', $LifecycleSnapshot
            )
        } else {
            Write-FailedStopLatch $FailedStopFile 'update_worker_identity_snapshot_missing'
            throw 'Running Worker has no persisted lifecycle identity snapshot; in-place capture is forbidden'
        }
        $IdentityProven = $SnapshotStatus -eq 10
        if (-not $IdentityProven) {
            Write-FailedStopLatch $FailedStopFile 'update_worker_identity_not_proven'
            throw 'Running Worker identity could not be proven from the persisted snapshot; refusing package replacement'
        }
    }

    if ($null -ne (Find-StateEntry $StateDir 'lifecycle.failed')) {
        throw "Unresolved runtime lifecycle evidence exists at $LifecycleFailureFile; refusing package replacement"
    }
    $HasRuntimeProcessTreeEvidence = Test-RuntimeProcessTreeEvidence $StateDir
    if ($HasRuntimeProcessTreeEvidence -and -not $WasRunning) {
        throw "Unresolved runtime process-tree evidence exists at $RuntimeProcessTreeDir without a running Worker; refusing package replacement"
    }

    if ($DryRun) {
        Set-TransactionPhase 'committed'
        $TransactionCanClose = $true
        Write-Output 'Dry run complete; current installation was not modified.'
        exit 0
    }

    if ($WasRunning) {
        Set-TransactionPhase 'draining'
        $StopScript = Join-Path $InstallDir 'stop.ps1'
        if (-not (Test-Path $StopScript)) { throw 'Running installation has no stop.ps1; refusing unsafe replacement' }
        Copy-Item -LiteralPath $LifecycleSnapshot -Destination $UpdateSnapshot
        $SnapshotStatus = Invoke-ProcessTree $ProcessTreeHelper @(
            'status', '--pid', $RunningPid, '--entry', $InstalledEntry, '--output', $UpdateSnapshot
        )
        if ($SnapshotStatus -ne 10) {
            Write-FailedStopLatch $FailedStopFile 'update_worker_identity_not_proven'
            throw 'Copied Worker identity evidence could not be revalidated before drain'
        }
        & powershell -ExecutionPolicy Bypass -File $StopScript `
            -UpdateTransactionNonce $TransactionNonce -LifecycleLockNonce $TransactionNonce
        $StopStatus = $LASTEXITCODE
        $VerifyStatus = Invoke-ProcessTree $ProcessTreeHelper @('verify', '--pid', $RunningPid, '--entry', $InstalledEntry, '--output', $UpdateSnapshot)
        if ($StopStatus -ne 0 -or $VerifyStatus -ne 0) {
            $KillStatus = Invoke-ProcessTree $ProcessTreeHelper @('kill', '--pid', $RunningPid, '--entry', $InstalledEntry, '--output', $UpdateSnapshot)
            $FinalVerifyStatus = Invoke-ProcessTree $ProcessTreeHelper @('verify', '--pid', $RunningPid, '--entry', $InstalledEntry, '--output', $UpdateSnapshot)
            $Reason = if ($KillStatus -eq 0 -and $FinalVerifyStatus -eq 0) { 'update_drain_not_proven' } else { 'update_process_residue' }
            Write-FailedStopLatch $FailedStopFile $Reason
            throw 'Worker drain failed or left verified descendants; current installation was not replaced'
        }
        if ($null -ne (Find-StateEntry $StateDir 'lifecycle.failed')) {
            Write-FailedStopLatch $FailedStopFile 'update_runtime_lifecycle_failure'
            throw "Worker drain produced runtime lifecycle failure evidence at $LifecycleFailureFile; current installation was not replaced"
        }
        if (Test-RuntimeProcessTreeEvidence $StateDir) {
            Write-FailedStopLatch $FailedStopFile 'update_runtime_process_tree_residue'
            throw "Worker drain left runtime process-tree evidence at $RuntimeProcessTreeDir; current installation was not replaced"
        }
        try {
            Remove-EvidenceFile $UpdateSnapshot
        } catch {
            Write-FailedStopLatch $FailedStopFile 'update_identity_evidence_cleanup_failed'
            throw "Worker drain completed but update identity evidence cleanup failed: $($_.Exception.Message)"
        }
        Set-TransactionPhase 'drained'
    }

    $SwapStarted = $true
    Set-TransactionPhase 'backing_up'
    foreach ($Name in $ManagedPaths) {
        $Current = Join-Path $InstallDir $Name
        if (Test-Path $Current) {
            Move-Item -LiteralPath $Current -Destination (Join-Path $BackupRoot $Name)
            $BackedUpPaths += $Name
            Set-TransactionPhase 'backing_up' $Name
        }
    }
    Set-TransactionPhase 'installing'
    foreach ($Name in $ManagedPaths) {
        $Source = Join-Path $Candidate $Name
        if (Test-Path $Source) {
            Move-Item -LiteralPath $Source -Destination (Join-Path $InstallDir $Name)
            $InstalledCandidatePaths += $Name
            Set-TransactionPhase 'installing' '' $Name
        }
    }

    if ($WasRunning -and -not $NoRestart) {
        $CandidateStartAttempted = $true
        Set-TransactionPhase 'candidate_start'
        try {
            & powershell -ExecutionPolicy Bypass -File (Join-Path $InstallDir 'start.ps1') -NoBuild `
                -UpdateTransactionNonce $TransactionNonce -LifecycleLockNonce $TransactionNonce
            $CandidateStartStatus = $LASTEXITCODE
        } catch {
            try { Write-FailedStopLatch $FailedStopFile 'rollback_after_candidate_start_failure' } catch {
                [Console]::Error.WriteLine("Failed to persist candidate startup failure latch: $($_.Exception.Message)")
            }
            try { Set-TransactionPhase 'candidate_failed' } catch {}
            throw
        }
        if ($CandidateStartStatus -ne 0) {
            try { Write-FailedStopLatch $FailedStopFile 'rollback_after_candidate_start_failure' } catch {
                [Console]::Error.WriteLine("Failed to persist candidate startup failure latch: $($_.Exception.Message)")
            }
            try { Set-TransactionPhase 'candidate_failed' } catch {}
            throw 'Updated Worker failed readiness after restart'
        }
    }
    Set-TransactionPhase 'committed'
    $SwapStarted = $false
    $TransactionCanClose = $true
    Write-Output "codex-app-server-worker updated to $($PackageJson.version); runtime configuration and state were preserved."
}
catch {
    $Failure = $_
    if ($SwapStarted) {
        try {
            Restore-PreviousInstall
            $TransactionCanClose = $true
        } catch {
            $PreserveStageRoot = $true
            throw "$($Failure.Exception.Message) $($_.Exception.Message)"
        }
    } else {
        if ($TransactionOwned) { try { Set-TransactionPhase 'failed_pre_swap' } catch {} }
        $TransactionCanClose = $true
    }
    throw $Failure
}
finally {
    try {
        if ($PreserveStageRoot) {
            [Console]::Error.WriteLine("Update staging was preserved for operator recovery at $StageRoot")
        } else {
            if (Test-Path $StageRoot) {
                try { Remove-Item -LiteralPath $StageRoot -Recurse -Force } catch {
                    $PreserveStageRoot = $true
                    throw "Failed to clean update staging; transaction marker and remaining evidence are preserved at $StageRoot. $($_.Exception.Message)"
                }
            }
            if ($TransactionOwned -and $TransactionCanClose) {
                $RemoveMarkerStatus = Invoke-LifecycleMarker @('remove', '--path', $UpdateTransactionFile, '--nonce', $TransactionNonce)
                if ($RemoveMarkerStatus -ne 0) {
                    $PreserveStageRoot = $true
                    throw "Failed to remove the owned update transaction marker at $UpdateTransactionFile"
                }
                $TransactionOwned = $false
            }
        }
    } finally {
        if ($LifecycleLockOwned) {
            $LockHelper = if (Test-Path -LiteralPath $ControlMarkerHelper -PathType Leaf) {
                $ControlMarkerHelper
            } else {
                $LifecycleMarkerSource
            }
            $ReleaseLockStatus = Invoke-LifecycleMarkerFile $LockHelper @(
                'lock-release', '--path', $LifecycleLockFile, '--nonce', $TransactionNonce
            )
            if ($ReleaseLockStatus -ne 0) {
                throw "Failed to release the owned lifecycle lock at $LifecycleLockFile"
            }
            $LifecycleLockOwned = $false
        }
        if (-not $TransactionOwned -and (Test-Path -LiteralPath $ControlMarkerHelper)) {
            Remove-Item -LiteralPath $ControlMarkerHelper -Force
        }
    }
}
