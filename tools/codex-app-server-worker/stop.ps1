param(
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
function Remove-EvidenceFile([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
    }
    if (Test-Path -LiteralPath $Path) { throw "Lifecycle evidence remains at $Path" }
}
function Write-FailedStopLatch([string]$Reason) {
    $Status = Invoke-LifecycleMarker @('write-once', '--path', $FailedStopFile, '--reason', $Reason)
    if ($Status -ne 0) { throw "Failed to persist failure latch at $FailedStopFile" }
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

$env:CODEX_APP_SERVER_RUN_DIR = $RunDir
$env:CODEX_APP_SERVER_LOG_DIR = $LogDir
$env:CODEX_APP_SERVER_STATE_DIR = $StateDir
$env:CODEX_APP_SERVER_WORKER_HOST = $DisplayHost
$env:CODEX_APP_SERVER_WORKER_PORT = $DisplayPort

$PidFile = Join-Path $RunDir 'worker.pid'
$StopFile = Join-Path $RunDir 'stop.request'
$FailedStopFile = Join-Path $RunDir 'stop.failed'
$ShutdownSuccessFile = Join-Path $RunDir 'shutdown.success'
$ShutdownFailureFile = Join-Path $RunDir 'shutdown.failure'
$SnapshotFile = Join-Path $RunDir 'worker.process-tree.json'
$ProcessTreeHelper = Join-Path $Root 'scripts\process-tree.mjs'
$LifecycleMarkerHelper = Join-Path $Root 'scripts\lifecycle-marker.mjs'
$LifecycleLockFile = Join-Path $Root 'lifecycle.lock'
$UpdateTransactionFile = Join-Path $Root 'update.in-progress'
$Entry = Join-Path $Root 'dist\index.js'
$ShutdownTimeoutMs = 30000
if ($env:CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS -match '^\d+$') {
    $ShutdownTimeoutMs = [int]$env:CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS
} else {
    $EnvShutdownTimeoutMs = Read-DotEnvValue 'CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS'
    if ($EnvShutdownTimeoutMs -match '^\d+$') { $ShutdownTimeoutMs = [int]$EnvShutdownTimeoutMs }
}
$ShutdownDeadline = [DateTimeOffset]::UtcNow.AddMilliseconds($ShutdownTimeoutMs + 5000)

if (-not (Test-Path -LiteralPath $ProcessTreeHelper -PathType Leaf) -or
    -not (Test-Path -LiteralPath $LifecycleMarkerHelper -PathType Leaf)) {
    [Console]::Error.WriteLine('Required lifecycle helpers are missing')
    exit 5
}
$OwnLifecycleLock = [string]::IsNullOrWhiteSpace($LifecycleLockNonce)
if ($OwnLifecycleLock -and -not [string]::IsNullOrWhiteSpace($UpdateTransactionNonce)) {
    [Console]::Error.WriteLine('Internal update transaction ownership also requires the lifecycle lock nonce')
    exit 5
}
if (-not $OwnLifecycleLock -and
    ([string]::IsNullOrWhiteSpace($UpdateTransactionNonce) -or $UpdateTransactionNonce -cne $LifecycleLockNonce)) {
    [Console]::Error.WriteLine('Internal lifecycle lock ownership requires the matching update transaction nonce')
    exit 5
}
if ($OwnLifecycleLock) {
    $LifecycleLockNonce = New-LifecycleNonce
    $LockStatus = Invoke-LifecycleMarker @(
        'lock-acquire', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce, '--operation', 'stop'
    )
    if ($LockStatus -ne 0) {
        [Console]::Error.WriteLine("Lifecycle operation is locked at $LifecycleLockFile; complete manual recovery before retrying stop")
        exit 5
    }
} elseif ((Invoke-LifecycleMarker @(
    'lock-verify-owner', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce, '--operation', 'update'
)) -ne 0) {
    [Console]::Error.WriteLine("Lifecycle lock owner verification failed at $LifecycleLockFile")
    exit 5
}

try {
if (Test-Path -LiteralPath $UpdateTransactionFile) {
    if ([string]::IsNullOrWhiteSpace($UpdateTransactionNonce) -or
        (Invoke-LifecycleMarker @('verify-owner', '--path', $UpdateTransactionFile, '--nonce', $UpdateTransactionNonce)) -ne 0) {
        [Console]::Error.WriteLine("An unresolved update transaction is present at $UpdateTransactionFile; only its owner updater may stop the Worker")
        exit 5
    }
} elseif (-not [string]::IsNullOrWhiteSpace($UpdateTransactionNonce)) {
    [Console]::Error.WriteLine("The owner update transaction is missing at $UpdateTransactionFile")
    exit 5
}
if (Test-Path -LiteralPath $FailedStopFile) {
    [Console]::Error.WriteLine("Previous failed stop is latched at $FailedStopFile; verify descendants and remove the latch explicitly")
    exit 4
}
if (-not (Test-Path $PidFile)) {
    if (Test-Path -LiteralPath $SnapshotFile) {
        Write-FailedStopLatch 'worker_pid_missing_with_identity_evidence'
        [Console]::Error.WriteLine("Worker PID file is missing while identity evidence remains at $SnapshotFile; operator review is required")
        exit 4
    }
    Write-Output 'codex-app-server-worker is not running (PID file missing)'
    exit 0
}
$RawPid = (Get-Content -Raw $PidFile).Trim()
if ($RawPid -notmatch '^\d+$') {
    Write-FailedStopLatch 'worker_pid_file_invalid'
    throw 'Invalid Worker PID file'
}
$PidValue = [int]$RawPid
if (-not (Test-Path -LiteralPath $SnapshotFile -PathType Leaf)) {
    Write-FailedStopLatch 'worker_identity_snapshot_missing'
    [Console]::Error.WriteLine("Worker identity snapshot is missing; PID $PidValue does not belong to a safely stoppable codex-app-server-worker")
    exit 4
}
$SnapshotStatus = Invoke-ProcessTree @('status', '--pid', [string]$PidValue, '--entry', $Entry, '--output', $SnapshotFile)
if ($SnapshotStatus -notin @(0, 10)) {
    Write-FailedStopLatch 'worker_identity_snapshot_invalid'
    [Console]::Error.WriteLine("PID $PidValue does not belong to the persisted codex-app-server-worker identity")
    exit 4
}

$RequestId = [Guid]::NewGuid().ToString('N')
try {
    Remove-EvidenceFile $ShutdownSuccessFile
    Remove-EvidenceFile $ShutdownFailureFile
} catch {
    try { Write-FailedStopLatch 'shutdown_cleanup_failed' } catch {
        [Console]::Error.WriteLine("Failed to persist shutdown cleanup latch: $($_.Exception.Message)")
    }
    [Console]::Error.WriteLine("Could not clear stale shutdown outcomes: $($_.Exception.Message)")
    exit 3
}
$TemporaryRequest = "$StopFile.$PID.tmp"
Set-Content -LiteralPath $TemporaryRequest -Value $RequestId -NoNewline
Move-Item -LiteralPath $TemporaryRequest -Destination $StopFile -Force
$ProtocolFailure = $false
while ([DateTimeOffset]::UtcNow -lt $ShutdownDeadline) {
    $TreeStatus = Invoke-ProcessTree @('poll', '--pid', [string]$PidValue, '--entry', $Entry, '--output', $SnapshotFile)
    if ($TreeStatus -eq 0) { break }
    if ($TreeStatus -ne 10) { $ProtocolFailure = $true; break }
    Start-Sleep -Milliseconds 100
}
$SuccessMatches = $false
if (Test-Path -LiteralPath $ShutdownSuccessFile) {
    $SuccessMatches = (Get-Content -Raw -LiteralPath $ShutdownSuccessFile).Trim() -ceq $RequestId
}
$VerifyStatus = if ($ProtocolFailure) { 11 } else { Invoke-ProcessTree @('verify', '--pid', [string]$PidValue, '--entry', $Entry, '--output', $SnapshotFile) }
if ($SuccessMatches -and $VerifyStatus -eq 0) {
    try {
        foreach ($EvidencePath in @($StopFile,$PidFile,$ShutdownSuccessFile,$ShutdownFailureFile)) {
            Remove-EvidenceFile $EvidencePath
        }
        Remove-EvidenceFile $SnapshotFile
    } catch {
        try { Write-FailedStopLatch 'shutdown_cleanup_failed' } catch {
            [Console]::Error.WriteLine("Failed to persist shutdown cleanup latch: $($_.Exception.Message)")
        }
        [Console]::Error.WriteLine("Worker exited cleanly but lifecycle evidence cleanup failed: $($_.Exception.Message)")
        exit 3
    }
    Write-Output 'codex-app-server-worker stopped'
    exit 0
}

$Reason = if ($SuccessMatches) { 'shutdown_success_with_process_residue' } else { 'shutdown_not_proven' }
try { Write-FailedStopLatch $Reason } catch {
    [Console]::Error.WriteLine("Failed to persist shutdown failure latch at $FailedStopFile: $($_.Exception.Message)")
    exit 3
}
[Console]::Error.WriteLine("Worker shutdown was not proven graceful; no Worker process was terminated. Lifecycle evidence remains at $SnapshotFile and $FailedStopFile pending an explicit signed termination operation or operator recovery")
exit 4
} finally {
    if ($OwnLifecycleLock) {
        $ReleaseStatus = Invoke-LifecycleMarker @(
            'lock-release', '--path', $LifecycleLockFile, '--nonce', $LifecycleLockNonce
        )
        if ($ReleaseStatus -ne 0) {
            [Console]::Error.WriteLine("Failed to release the owned lifecycle lock at $LifecycleLockFile")
            exit 5
        }
    }
}
