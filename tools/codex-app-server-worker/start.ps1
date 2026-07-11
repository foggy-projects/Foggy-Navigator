param(
    [switch]$NoBuild,
    [string]$UpdateTransactionNonce = '',
    [string]$LifecycleLockNonce = ''
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
function Read-DotEnvValue([string]$Name) {
    $EnvFile = Join-Path $Root '.env'
    if (-not (Test-Path -LiteralPath $EnvFile)) { return $null }
    $Reader = Join-Path $Root 'scripts\read-dotenv-value.mjs'
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
function Invoke-ProcessTree([string[]]$Arguments) {
    & node $ProcessTreeHelper @Arguments *> $null
    return $LASTEXITCODE
}
function Invoke-LifecycleMarker([string[]]$Arguments) {
    $PreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & node $LifecycleMarkerHelper @Arguments *> $null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $PreviousPreference
    }
}
function New-LifecycleNonce {
    $Bytes = New-Object byte[] 16
    $Generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $Generator.GetBytes($Bytes) } finally { $Generator.Dispose() }
    return [BitConverter]::ToString($Bytes).Replace('-', '').ToLowerInvariant()
}
function Write-FailedStartLatch([string]$Reason) {
    $Status = Invoke-LifecycleMarker @('write-once', '--path', $FailedStopFile, '--reason', $Reason)
    if ($Status -ne 0) { throw "Failed to persist failure latch at $FailedStopFile" }
}
function Write-StateLifecycleFailure([string]$Reason) {
    $Status = Invoke-LifecycleMarker @('write-once', '--path', $LifecycleFailureFile, '--reason', $Reason)
    if ($Status -ne 0) { throw "Failed to persist lifecycle failure marker at $LifecycleFailureFile" }
}
function Fail-WorkerStartup([string]$Message) {
    $PollStatus = Invoke-ProcessTree @('poll', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    $KillStatus = Invoke-ProcessTree @('kill', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    $VerifyStatus = Invoke-ProcessTree @('verify', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    try { Write-FailedStartLatch 'startup_not_ready' } catch {
        [Console]::Error.WriteLine("Failed to persist startup failure latch: $($_.Exception.Message)")
    }
    if ($KillStatus -ne 0 -or $VerifyStatus -ne 0) {
        try { Write-StateLifecycleFailure 'WORKER_START_CLEANUP_NOT_PROVEN' } catch {
            [Console]::Error.WriteLine("Failed to persist state lifecycle evidence: $($_.Exception.Message)")
        }
    }
    Remove-Item -LiteralPath $PidFile,$StopFile,$ShutdownSuccessFile,$ShutdownFailureFile -Force -ErrorAction SilentlyContinue
    $Tail = if (Test-Path $Stderr) { (Get-Content $Stderr -Tail 20) -join [Environment]::NewLine } else { '' }
    throw "$Message Process-tree cleanup status: poll=$PollStatus kill=$KillStatus verify=$VerifyStatus. Evidence: $LifecycleSnapshotFile $Tail"
}

$EnvRunDir = Read-DotEnvValue 'CODEX_APP_SERVER_RUN_DIR'
$EnvLogDir = Read-DotEnvValue 'CODEX_APP_SERVER_LOG_DIR'
$EnvStateDir = Read-DotEnvValue 'CODEX_APP_SERVER_STATE_DIR'
$EnvHost = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_HOST'
$EnvPort = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_PORT'
$RunDir = Select-ConfigValue $env:CODEX_APP_SERVER_RUN_DIR $EnvRunDir (Join-Path $Root 'logs\run')
$LogDir = Select-ConfigValue $env:CODEX_APP_SERVER_LOG_DIR $EnvLogDir (Join-Path $Root 'logs')
$StateDir = Select-ConfigValue $env:CODEX_APP_SERVER_STATE_DIR $EnvStateDir (Join-Path $Root 'logs\state')
$DisplayHost = Select-ConfigValue $env:CODEX_APP_SERVER_WORKER_HOST $EnvHost '127.0.0.1'
$DisplayPort = Select-ConfigValue $env:CODEX_APP_SERVER_WORKER_PORT $EnvPort '3062'
$PidFile = Join-Path $RunDir 'worker.pid'
$StopFile = Join-Path $RunDir 'stop.request'
$FailedStopFile = Join-Path $RunDir 'stop.failed'
$ShutdownSuccessFile = Join-Path $RunDir 'shutdown.success'
$ShutdownFailureFile = Join-Path $RunDir 'shutdown.failure'
$LifecycleSnapshotFile = Join-Path $RunDir 'worker.process-tree.json'
$RuntimeProcessTreeDir = Join-Path $StateDir 'runtime-process-trees'
$LifecycleFailureFile = Join-Path $StateDir 'lifecycle.failed'
$ProcessTreeHelper = Join-Path $Root 'scripts\process-tree.mjs'
$LifecycleMarkerHelper = Join-Path $Root 'scripts\lifecycle-marker.mjs'
$LifecycleLockFile = Join-Path $Root 'lifecycle.lock'
$UpdateTransactionFile = Join-Path $Root 'update.in-progress'
$Entry = Join-Path $Root 'dist\index.js'

$env:CODEX_APP_SERVER_RUN_DIR = $RunDir
$env:CODEX_APP_SERVER_LOG_DIR = $LogDir
$env:CODEX_APP_SERVER_STATE_DIR = $StateDir
$env:CODEX_APP_SERVER_WORKER_HOST = $DisplayHost
$env:CODEX_APP_SERVER_WORKER_PORT = $DisplayPort

if (-not (Test-Path -LiteralPath $ProcessTreeHelper -PathType Leaf) -or
    -not (Test-Path -LiteralPath $LifecycleMarkerHelper -PathType Leaf)) {
    throw 'Required lifecycle helpers are missing'
}
$OwnLifecycleLock = [string]::IsNullOrWhiteSpace($LifecycleLockNonce)
$LifecycleLockCanRelease = $true
if ($OwnLifecycleLock -and -not [string]::IsNullOrWhiteSpace($UpdateTransactionNonce)) {
    throw 'Internal update transaction ownership also requires the lifecycle lock nonce'
}
if (-not $OwnLifecycleLock -and
    ([string]::IsNullOrWhiteSpace($UpdateTransactionNonce) -or $UpdateTransactionNonce -cne $LifecycleLockNonce)) {
    throw 'Internal lifecycle lock ownership requires the matching update transaction nonce'
}
if ($OwnLifecycleLock) {
    $LifecycleLockNonce = New-LifecycleNonce
    $LockStatus = Invoke-LifecycleMarker @(
        'lock-acquire', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce, '--operation', 'start'
    )
    if ($LockStatus -ne 0) {
        throw "Lifecycle operation is locked at $LifecycleLockFile; complete manual recovery before retrying start"
    }
} elseif ((Invoke-LifecycleMarker @(
    'lock-verify-owner', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce, '--operation', 'update'
)) -ne 0) {
    throw "Lifecycle lock owner verification failed at $LifecycleLockFile"
}

try {
New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null
if (Test-Path -LiteralPath $UpdateTransactionFile) {
    if ([string]::IsNullOrWhiteSpace($UpdateTransactionNonce) -or
        (Invoke-LifecycleMarker @('verify-owner', '--path', $UpdateTransactionFile, '--nonce', $UpdateTransactionNonce)) -ne 0) {
        throw "An unresolved update transaction is present at $UpdateTransactionFile; only its owner updater may start the Worker"
    }
} elseif (-not [string]::IsNullOrWhiteSpace($UpdateTransactionNonce)) {
    throw "The owner update transaction is missing at $UpdateTransactionFile"
}
if (Test-Path -LiteralPath $FailedStopFile) {
    throw "Previous failed stop is latched at $FailedStopFile; verify no Worker descendants remain, then remove the latch explicitly"
}
if ((Test-Path -LiteralPath $LifecycleSnapshotFile) -and (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
    $ExistingPid = (Get-Content -Raw -LiteralPath $PidFile).Trim()
    $ExistingIdentityStatus = if ($ExistingPid -match '^\d+$') {
        Invoke-ProcessTree @('status', '--pid', $ExistingPid, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    } else { 64 }
    if ($ExistingIdentityStatus -eq 10) {
        throw "codex-app-server-worker is already running (PID $ExistingPid)"
    }
}
$HasRuntimeProcessTreeEvidence = (Test-Path -LiteralPath $RuntimeProcessTreeDir) -and
    ((-not (Test-Path -LiteralPath $RuntimeProcessTreeDir -PathType Container)) -or
     $null -ne (Get-ChildItem -LiteralPath $RuntimeProcessTreeDir -Force | Select-Object -First 1))
if ($HasRuntimeProcessTreeEvidence) {
    try { Write-FailedStartLatch 'runtime_process_tree_evidence_present' } catch {
        [Console]::Error.WriteLine("Failed to persist runtime process-tree latch: $($_.Exception.Message)")
    }
    throw "Unresolved app-server process-tree evidence exists at $RuntimeProcessTreeDir; startup is blocked pending operator review"
}
if (Test-Path -LiteralPath $LifecycleFailureFile) {
    try { Write-FailedStartLatch 'runtime_lifecycle_failure_present' } catch {
        [Console]::Error.WriteLine("Failed to persist runtime lifecycle latch: $($_.Exception.Message)")
    }
    throw "Unresolved runtime lifecycle evidence exists at $LifecycleFailureFile; startup is blocked pending operator review"
}
if (Test-Path -LiteralPath $LifecycleSnapshotFile) {
    try { Write-FailedStartLatch 'existing_worker_identity_evidence' } catch {
        [Console]::Error.WriteLine("Failed to persist existing identity latch: $($_.Exception.Message)")
    }
    throw "Existing Worker identity evidence is present at $LifecycleSnapshotFile; review it before starting"
}
if (Test-Path $PidFile) {
    try { Write-FailedStartLatch 'worker_pid_without_identity_snapshot' } catch {
        [Console]::Error.WriteLine("Failed to persist stale PID latch: $($_.Exception.Message)")
    }
    throw "Stale Worker PID evidence is present at $PidFile without an identity snapshot; review it before starting"
}

if (-not $NoBuild) {
    & npm run build --prefix $Root
    if ($LASTEXITCODE -ne 0) { throw 'Worker build failed' }
}
if (-not (Test-Path $Entry)) { throw "Missing build output: $Entry" }
Remove-Item -LiteralPath $StopFile,$ShutdownSuccessFile,$ShutdownFailureFile -Force -ErrorAction SilentlyContinue

$Stdout = Join-Path $LogDir 'worker.stdout.log'
$Stderr = Join-Path $LogDir 'worker.stderr.log'
$Process = Start-Process -FilePath node `
    -ArgumentList @("`"$Entry`"") `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -RedirectStandardOutput $Stdout `
    -RedirectStandardError $Stderr `
    -PassThru
$SnapshotStatus = Invoke-ProcessTree @('snapshot', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
if ($SnapshotStatus -ne 0) {
    $TaskkillStatus = 0
    if (-not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F *> $null
        $TaskkillStatus = $LASTEXITCODE
    }
    Start-Sleep -Milliseconds 200
    $RootStillAlive = $null -ne (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue)
    $FailureEvidencePersisted = $false
    try {
        Write-FailedStartLatch 'startup_identity_not_proven'
        $FailureEvidencePersisted = $true
    } catch {
        [Console]::Error.WriteLine("Failed to persist startup failure latch: $($_.Exception.Message)")
    }
    try {
        Write-StateLifecycleFailure 'WORKER_START_IDENTITY_NOT_PROVEN'
        $FailureEvidencePersisted = $true
    } catch {
        [Console]::Error.WriteLine("Failed to persist state lifecycle evidence: $($_.Exception.Message)")
    }
    if (-not $FailureEvidencePersisted) { $LifecycleLockCanRelease = $false }
    throw "Worker startup identity could not be proven; taskkill status=$TaskkillStatus root_alive=$RootStillAlive and exact cleanup is unproven. State evidence: $LifecycleFailureFile"
}
try {
    Start-Sleep -Milliseconds 500
    $Process.Refresh()
    if ($Process.HasExited) { throw 'Worker exited during startup.' }
    $TemporaryPidFile = "$PidFile.$PID.tmp"
    Set-Content -LiteralPath $TemporaryPidFile -Value $Process.Id -NoNewline
    Move-Item -LiteralPath $TemporaryPidFile -Destination $PidFile
    $HealthHost = if ($DisplayHost -in @('0.0.0.0', '::', '[::]')) { '127.0.0.1' } else { $DisplayHost }
    $HealthUrl = "http://${HealthHost}:${DisplayPort}/health"
    $Ready = $false
    for ($Index = 0; $Index -lt 120; $Index++) {
        $Process.Refresh()
        $PollStatus = Invoke-ProcessTree @('poll', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
        if ($Process.HasExited -or $PollStatus -eq 0) { throw 'Worker exited before readiness.' }
        if ($PollStatus -ne 10) { throw 'Worker process identity could not be revalidated during startup.' }
        try {
            $Health = Invoke-RestMethod -Uri $HealthUrl -Method Get -TimeoutSec 2
            if ($Health.ready -eq $true) { $Ready = $true; break }
        } catch {
            # Startup polling intentionally retries connection and degraded readiness failures.
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $Ready) { throw "Worker did not become ready: $HealthUrl" }
    $FinalIdentityStatus = Invoke-ProcessTree @('poll', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    if ($FinalIdentityStatus -ne 10) { throw 'Worker identity could not be persisted after readiness.' }
    $FinalRootStatus = Invoke-ProcessTree @('poll-root', '--pid', [string]$Process.Id, '--entry', $Entry, '--output', $LifecycleSnapshotFile)
    if ($FinalRootStatus -ne 10) { throw 'Worker root identity exited after readiness.' }
} catch {
    if (Test-Path -LiteralPath $LifecycleSnapshotFile) { Fail-WorkerStartup $_.Exception.Message }
    throw
}
$StartSuccessMessage = "codex-app-server-worker started (PID $($Process.Id), URL http://${DisplayHost}:${DisplayPort})"
} finally {
    if ($OwnLifecycleLock -and $LifecycleLockCanRelease) {
        $ReleaseStatus = Invoke-LifecycleMarker @(
            'lock-release', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce
        )
        if ($ReleaseStatus -ne 0) {
            throw "Failed to release the owned lifecycle lock at $LifecycleLockFile"
        }
    }
}
Write-Output $StartSuccessMessage
