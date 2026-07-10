$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = if ($env:CODEX_APP_SERVER_RUN_DIR) { $env:CODEX_APP_SERVER_RUN_DIR } else { Join-Path $Root 'logs\run' }
$PidFile = Join-Path $RunDir 'worker.pid'
$StopFile = Join-Path $RunDir 'stop.request'
$Entry = (Join-Path $Root 'dist\index.js').ToLowerInvariant()
$ShutdownTimeoutMs = 30000
if ($env:CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS -match '^\d+$') {
    $ShutdownTimeoutMs = [int]$env:CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS
} else {
    $EnvFile = Join-Path $Root '.env'
    if (Test-Path $EnvFile) {
        $TimeoutLine = Get-Content $EnvFile | Where-Object { $_ -match '^\s*CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS\s*=\s*\d+\s*$' } | Select-Object -Last 1
        if ($TimeoutLine -and $TimeoutLine -match '=\s*(\d+)\s*$') { $ShutdownTimeoutMs = [int]$Matches[1] }
    }
}
$WaitIterations = [Math]::Ceiling(($ShutdownTimeoutMs + 5000) / 100)

if (-not (Test-Path $PidFile)) {
    Write-Output 'codex-app-server-worker is not running (PID file missing)'
    exit 0
}
$RawPid = (Get-Content -Raw $PidFile).Trim()
if ($RawPid -notmatch '^\d+$') { throw 'Invalid Worker PID file' }
$PidValue = [int]$RawPid
$Process = Get-CimInstance Win32_Process -Filter "ProcessId=$PidValue" -ErrorAction SilentlyContinue
if (-not $Process) {
    Remove-Item -LiteralPath $PidFile -Force
    Write-Output 'codex-app-server-worker is not running (stale PID removed)'
    exit 0
}
$CommandLine = ([string]$Process.CommandLine).ToLowerInvariant()
if (-not $CommandLine.Contains($Entry)) {
    throw "PID $PidValue does not belong to this codex-app-server-worker"
}

Set-Content -LiteralPath $StopFile -Value ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) -NoNewline
for ($Index = 0; $Index -lt $WaitIterations; $Index++) {
    if (-not (Get-Process -Id $PidValue -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 100
}
if (Get-Process -Id $PidValue -ErrorAction SilentlyContinue) {
  Stop-Process -Id $PidValue -Force
}
Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
Write-Output 'codex-app-server-worker stopped'
