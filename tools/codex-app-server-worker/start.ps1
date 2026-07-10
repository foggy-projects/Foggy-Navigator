param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = if ($env:CODEX_APP_SERVER_RUN_DIR) { $env:CODEX_APP_SERVER_RUN_DIR } else { Join-Path $Root 'logs\run' }
$LogDir = if ($env:CODEX_APP_SERVER_LOG_DIR) { $env:CODEX_APP_SERVER_LOG_DIR } else { Join-Path $Root 'logs' }
$PidFile = Join-Path $RunDir 'worker.pid'
$StopFile = Join-Path $RunDir 'stop.request'
$Entry = Join-Path $Root 'dist\index.js'

New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null
if (Test-Path $PidFile) {
    $ExistingPid = (Get-Content -Raw $PidFile).Trim()
    if ($ExistingPid -match '^\d+$' -and (Get-Process -Id ([int]$ExistingPid) -ErrorAction SilentlyContinue)) {
        throw "codex-app-server-worker is already running (PID $ExistingPid)"
    }
    Remove-Item -LiteralPath $PidFile -Force
}

if (-not $NoBuild) {
    & npm run build --prefix $Root
    if ($LASTEXITCODE -ne 0) { throw 'Worker build failed' }
}
if (-not (Test-Path $Entry)) { throw "Missing build output: $Entry" }
$env:CODEX_APP_SERVER_RUN_DIR = $RunDir
if (-not $env:CODEX_APP_SERVER_STATE_DIR) {
    $env:CODEX_APP_SERVER_STATE_DIR = Join-Path $Root 'logs\state'
}
Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue

$Stdout = Join-Path $LogDir 'worker.stdout.log'
$Stderr = Join-Path $LogDir 'worker.stderr.log'
$Process = Start-Process -FilePath node `
    -ArgumentList @($Entry) `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -RedirectStandardOutput $Stdout `
    -RedirectStandardError $Stderr `
    -PassThru
Start-Sleep -Milliseconds 500
if ($Process.HasExited) {
    $Tail = if (Test-Path $Stderr) { (Get-Content $Stderr -Tail 20) -join [Environment]::NewLine } else { '' }
    throw "Worker exited during startup. $Tail"
}
Set-Content -LiteralPath $PidFile -Value $Process.Id -NoNewline
function Read-DotEnvValue([string]$Name) {
    $EnvFile = Join-Path $Root '.env'
    if (-not (Test-Path $EnvFile)) { return $null }
    $Line = Get-Content $EnvFile | Where-Object { $_ -match "^\s*$([Regex]::Escape($Name))\s*=\s*([^#\s]+)\s*$" } | Select-Object -Last 1
    if ($Line -and $Line -match '=\s*([^#\s]+)\s*$') { return $Matches[1].Trim('"', "'") }
    return $null
}
$EnvPort = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_PORT'
$EnvHost = Read-DotEnvValue 'CODEX_APP_SERVER_WORKER_HOST'
$DisplayPort = if ($env:CODEX_APP_SERVER_WORKER_PORT -match '^\d+$') { $env:CODEX_APP_SERVER_WORKER_PORT } elseif ($EnvPort -match '^\d+$') { $EnvPort } else { '3062' }
$DisplayHost = if ($env:CODEX_APP_SERVER_WORKER_HOST) { $env:CODEX_APP_SERVER_WORKER_HOST } elseif ($EnvHost) { $EnvHost } else { '127.0.0.1' }
$HealthHost = if ($DisplayHost -in @('0.0.0.0', '::', '[::]')) { '127.0.0.1' } else { $DisplayHost }
$HealthUrl = "http://${HealthHost}:${DisplayPort}/health"
$Ready = $false
for ($Index = 0; $Index -lt 120; $Index++) {
    $Process.Refresh()
    if ($Process.HasExited) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        $Tail = if (Test-Path $Stderr) { (Get-Content $Stderr -Tail 20) -join [Environment]::NewLine } else { '' }
        throw "Worker exited before readiness. $Tail"
    }
    try {
        $Health = Invoke-RestMethod -Uri $HealthUrl -Method Get -TimeoutSec 2
        if ($Health.ready -eq $true) { $Ready = $true; break }
    } catch {
        # Startup polling intentionally retries connection and degraded readiness failures.
    }
    Start-Sleep -Milliseconds 500
}
if (-not $Ready) {
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    throw "Worker did not become ready: $HealthUrl"
}
Write-Output "codex-app-server-worker started (PID $($Process.Id), URL http://${DisplayHost}:${DisplayPort})"
