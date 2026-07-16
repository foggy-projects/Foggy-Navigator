# Claude Agent Worker stop script (Windows).
#
# The legacy Worker has no signal-safe drain API. This script therefore stops
# only an authenticated, workspace-owned, quiescent Worker and never forces a
# process termination.

$ErrorActionPreference = "Stop"

$WorkerDir = (Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$EnvFile = Join-Path $WorkerDir ".env"
$LogDir = Join-Path $WorkerDir "logs"
$PidFile = Join-Path $LogDir "worker.pid"
$EvidenceDir = Join-Path $LogDir "stop-evidence"
$Port = 3031
$SnapshotActive = "unavailable"
$SnapshotTotal = "unavailable"
$SavedWorkerPid = $null
$PidFileInvalid = $false
$ListenerProcessIds = @()

function Get-DotEnvValue {
    param(
        [string]$Path,
        [string]$Key
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }

    $escapedKey = [regex]::Escape($Key)
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^\s*$escapedKey=" } | Select-Object -First 1
    if (-not $line) {
        return ""
    }

    $value = ($line -split "=", 2)[1].Trim()
    if ($value.Length -ge 2) {
        $first = $value.Substring(0, 1)
        $last = $value.Substring($value.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }
    return $value
}

$configuredPort = Get-DotEnvValue -Path $EnvFile -Key "AGENT_WORKER_PORT"
if ($configuredPort) {
    [int]$parsedPort = 0
    if (-not [int]::TryParse($configuredPort, [ref]$parsedPort) -or $parsedPort -lt 1 -or $parsedPort -gt 65535) {
        Write-Host "Invalid AGENT_WORKER_PORT: $configuredPort" -ForegroundColor Red
        exit 2
    }
    $Port = $parsedPort
}

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$EvidenceFile = Join-Path $EvidenceDir ("stop-{0}-{1}.log" -f (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"), $PID)

function Write-StopEvidence {
    param(
        [string]$Result,
        [string]$Action,
        [string]$Detail
    )

    $listenerSummary = if ($ListenerProcessIds.Count -gt 0) { $ListenerProcessIds -join "," } else { "" }
    $savedPidSummary = if ($null -ne $SavedWorkerPid) { [string]$SavedWorkerPid } else { "none" }
    @(
        "timestamp_utc=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "worker=claude-agent-worker",
        "port=$Port",
        "listener_pids=$listenerSummary",
        "pid_file_pid=$savedPidSummary",
        "snapshot_active_task_count=$SnapshotActive",
        "snapshot_managed_process_count=$SnapshotTotal",
        "action=$Action",
        "result=$Result",
        "detail=$Detail"
    ) | Set-Content -LiteralPath $EvidenceFile -Encoding utf8
    Write-Host "Stop evidence: $EvidenceFile" -ForegroundColor DarkGray
}

function Get-ListenerProcessIds {
    if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        throw "Get-NetTCPConnection is required to inspect the listener safely."
    }

    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    return @($connections | Select-Object -ExpandProperty OwningProcess -Unique | Sort-Object)
}

function Test-ProcessExists {
    param([int]$ProcessId)
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Get-WorkerProcess {
    param([int]$ProcessId)
    try {
        return Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    }
    catch {
        return $null
    }
}

function Test-OwnedClaudeWorker {
    param([int]$ProcessId)

    $process = Get-WorkerProcess -ProcessId $ProcessId
    if ($null -eq $process) {
        return $false
    }

    $commandLine = [string]$process.CommandLine
    $executablePath = [string]$process.ExecutablePath
    $hasWorkspacePath = $commandLine.IndexOf($WorkerDir, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $executablePath.IndexOf($WorkerDir, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    $hasEntrypoint = $commandLine -match "(?i)agent_worker\.main:app"
    $hasUvicorn = $commandLine -match "(?i)uvicorn"

    return $hasWorkspacePath -and $hasEntrypoint -and $hasUvicorn
}

function Get-WorkerSnapshot {
    param([string]$Token)

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }

    try {
        $snapshot = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/api/v1/processes" -Headers $headers -TimeoutSec 3
        $activeProperty = $snapshot.PSObject.Properties["active_task_count"]
        $totalProperty = $snapshot.PSObject.Properties["total"]
        if ($null -eq $activeProperty -or $null -eq $totalProperty) {
            return $null
        }

        [int]$active = $activeProperty.Value
        [int]$total = $totalProperty.Value
        if ($active -lt 0 -or $total -lt 0) {
            return $null
        }

        return [pscustomobject]@{
            ActiveTaskCount = $active
            ManagedProcessCount = $total
        }
    }
    catch {
        return $null
    }
}

function Request-NonForcedStop {
    foreach ($processId in $ListenerProcessIds) {
        if (Test-ProcessExists -ProcessId $processId) {
            Write-Host "Requesting non-forced Claude Worker stop (PID=$processId)..." -ForegroundColor Yellow
            try {
                Stop-Process -Id $processId -ErrorAction Stop
            }
            catch {
                return $false
            }
        }
    }
    return $true
}

function Wait-ForExit {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        $alive = @($ListenerProcessIds | Where-Object { Test-ProcessExists -ProcessId $_ })
        if ($alive.Count -eq 0) {
            return $true
        }
        if ((Get-Date) -ge $deadline) {
            return $false
        }
        Start-Sleep -Seconds 1
    }
}

try {
    $ListenerProcessIds = @(Get-ListenerProcessIds)
}
catch {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "listener_inspection_unavailable"
    Write-Host "Refusing to stop: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

if (Test-Path -LiteralPath $PidFile) {
    $savedPidText = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    if ($savedPidText) {
        [int]$parsedSavedPid = 0
        if ([int]::TryParse($savedPidText, [ref]$parsedSavedPid) -and $parsedSavedPid -gt 0) {
            $SavedWorkerPid = $parsedSavedPid
        }
        else {
            $PidFileInvalid = $true
        }
    }
}

if ($PidFileInvalid) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "pid_file_unparseable"
    Write-Host "Refusing to stop: PID file is not trustworthy: $PidFile" -ForegroundColor Red
    exit 2
}

if ($null -ne $SavedWorkerPid -and (Test-ProcessExists -ProcessId $SavedWorkerPid) -and ($ListenerProcessIds -notcontains $SavedWorkerPid)) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "live_pid_file_process_not_listening"
    Write-Host "Refusing to stop: live PID-file process is not the expected listener." -ForegroundColor Red
    exit 2
}

if ($ListenerProcessIds.Count -eq 0) {
    if ($null -ne $SavedWorkerPid -and -not (Test-ProcessExists -ProcessId $SavedWorkerPid)) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    }
    Write-StopEvidence -Result "NO_LISTENER" -Action "none" -Detail "no_worker_listener_found"
    Write-Host "No Claude Worker listener found on port $Port." -ForegroundColor Gray
    exit 0
}

foreach ($processId in $ListenerProcessIds) {
    if (-not (Test-OwnedClaudeWorker -ProcessId $processId)) {
        Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "worker_ownership_unverified"
        Write-Host "Refusing to stop PID $processId: command line and workspace ownership are not proven." -ForegroundColor Red
        exit 2
    }
}

$workerToken = Get-DotEnvValue -Path $EnvFile -Key "AGENT_WORKER_WORKER_TOKEN"
$snapshot = Get-WorkerSnapshot -Token $workerToken
if ($null -eq $snapshot) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "process_snapshot_unavailable_or_invalid"
    Write-Host "Refusing to stop: unable to prove the Claude Worker task snapshot is quiescent." -ForegroundColor Red
    exit 2
}

$SnapshotActive = $snapshot.ActiveTaskCount
$SnapshotTotal = $snapshot.ManagedProcessCount
if ($SnapshotActive -ne 0 -or $SnapshotTotal -ne 0) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "none" -Detail "preflight_not_quiescent"
    Write-Host "Claude Worker has active or unverified managed work; automatic restart is blocked. See $EvidenceFile" -ForegroundColor Red
    exit 2
}

Write-StopEvidence -Result "QUIESCENT_STOP_REQUESTED" -Action "non_forced_stop_requested" -Detail "preflight_quiescent"
if (-not (Request-NonForcedStop)) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "non_forced_stop_requested" -Detail "non_forced_stop_request_failed"
    Write-Host "Non-forced stop request failed; preserving Worker for operator review." -ForegroundColor Red
    exit 2
}

if (-not (Wait-ForExit)) {
    Write-StopEvidence -Result "WORKER_DRAIN_UNCONFIRMED" -Action "non_forced_stop_requested" -Detail "worker_exit_not_observed_within_30_seconds"
    Write-Host "Claude Worker did not exit within 30 seconds; no forced termination was attempted." -ForegroundColor Red
    exit 2
}

if ($null -ne $SavedWorkerPid -and -not (Test-ProcessExists -ProcessId $SavedWorkerPid)) {
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}
Write-StopEvidence -Result "QUIESCENT_STOPPED" -Action "non_forced_stop_requested" -Detail "worker_exit_observed"
Write-Host "Claude Worker stopped after a quiescent snapshot." -ForegroundColor Green
