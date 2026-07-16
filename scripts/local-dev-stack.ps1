param(
    [ValidateSet("start", "stop", "restart", "status")]
    [string]$Action = "restart",
    [switch]$SkipBuild,
    [switch]$NoBackend,
    [switch]$NoClaude,
    [switch]$NoCodex,
    [switch]$NoGemini,
    [switch]$NoWinBiz,
    [switch]$NoWslBiz,
    [switch]$SyncWslBizSource
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
$PowerShellExe = (Get-Command powershell.exe -ErrorAction Stop).Source

$BackendPort = 8112
$WslBizPort = 3161

function Get-DotEnvValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$DefaultValue
    )

    if (-not (Test-Path $Path)) {
        return $DefaultValue
    }

    $line = Get-Content $Path | Where-Object { $_ -match "^\s*$Key=(.+)$" } | Select-Object -First 1
    if (-not $line) {
        return $DefaultValue
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

function Invoke-RepoScript {
    param(
        [string]$Label,
        [string]$RelativePath,
        [string[]]$Arguments = @()
    )

    $scriptPath = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path $scriptPath)) {
        throw "$Label script not found: $scriptPath"
    }

    Write-Host ""
    Write-Host "==> $Label" -ForegroundColor Cyan
    & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Invoke-WorkerStopScript {
    param(
        [string]$Label,
        [string]$RelativePath
    )

    try {
        Invoke-RepoScript -Label $Label -RelativePath $RelativePath
    }
    catch {
        throw "$Label did not prove safe Worker quiescence; local stack will not continue or start a replacement Worker. $($_.Exception.Message)"
    }
}

function Get-PortListeners {
    param([int]$Port)

    return Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
}

function Stop-Port {
    param(
        [string]$Label,
        [int]$Port
    )

    $pids = Get-PortListeners -Port $Port
    if (-not $pids) {
        Write-Host "$Label is not listening on port $Port." -ForegroundColor Gray
        return
    }

    foreach ($procId in $pids) {
        $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
        $name = if ($process) { $process.ProcessName } else { "unknown" }
        Write-Host "Stopping $Label on port $Port (PID=$procId, $name)..." -ForegroundColor Yellow
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
}

function Test-HttpHealth {
    param([string]$Url)

    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3 | Out-Null
        return $true
    }
    catch {
        return $false
    }
}

function Write-PortStatus {
    param(
        [string]$Label,
        [int]$Port,
        [string]$HealthUrl
    )

    $pids = Get-PortListeners -Port $Port
    if ($pids) {
        $health = if ($HealthUrl -and (Test-HttpHealth -Url $HealthUrl)) { "UP" } else { "LISTENING" }
        Write-Host ("{0,-18} port {1,-5} {2,-10} PID {3}" -f $Label, $Port, $health, ($pids -join ",")) -ForegroundColor Green
    }
    else {
        Write-Host ("{0,-18} port {1,-5} DOWN" -f $Label, $Port) -ForegroundColor Gray
    }
}

function Invoke-WslBizStop {
    if ($NoWslBiz) {
        return
    }

    Write-Host ""
    Write-Host "==> Stop WSL LangGraph Biz Worker" -ForegroundColor Cyan
    $script = @"
set -euo pipefail
port=$WslBizPort
pid=`$(ss -ltnp 2>/dev/null | grep -E "[:.]`$port[[:space:]]" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1 || true)
if [ -n "`$pid" ]; then
  echo "Stopping WSL biz worker on port `$port (pid=`$pid)"
  kill "`$pid" || true
  sleep 1
  if kill -0 "`$pid" 2>/dev/null; then
    kill -9 "`$pid" || true
  fi
else
  echo "No WSL biz worker listening on port `$port"
fi
"@
    $tempScript = New-TemporaryFile
    try {
        [System.IO.File]::WriteAllText(
            $tempScript.FullName,
            ($script -replace "`r?`n", "`n"),
            [System.Text.Encoding]::ASCII
        )
        $tempScriptWsl = (& wsl -e wslpath -a $tempScript.FullName).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $tempScriptWsl) {
            throw "Failed to resolve temporary WSL script path"
        }

        & wsl -e bash $tempScriptWsl
        if ($LASTEXITCODE -ne 0) {
            throw "Stop WSL LangGraph Biz Worker failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Remove-Item -LiteralPath $tempScript -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-WslBizStart {
    if ($NoWslBiz) {
        return
    }

    $args = @("-Port", "$WslBizPort")
    if ($SyncWslBizSource) {
        $args += "-SyncSource"
    }
    Invoke-RepoScript -Label "Restart WSL LangGraph Biz Worker" -RelativePath "tools\langgraph-biz-worker\restart-wsl-3161.ps1" -Arguments $args
}

function Write-WslBizStatus {
    if ($NoWslBiz) {
        return
    }

    $ok = $false
    try {
        & wsl -e bash -lc "curl -fsS http://127.0.0.1:$WslBizPort/health >/dev/null 2>/dev/null"
        $ok = ($LASTEXITCODE -eq 0)
    }
    catch {
        $ok = $false
    }

    if ($ok) {
        Write-Host ("{0,-18} port {1,-5} UP" -f "wsl-biz-worker", $WslBizPort) -ForegroundColor Green
    }
    else {
        Write-Host ("{0,-18} port {1,-5} DOWN" -f "wsl-biz-worker", $WslBizPort) -ForegroundColor Gray
    }
}

$ClaudePort = [int](Get-DotEnvValue -Path (Join-Path $RepoRoot "tools\claude-agent-worker\.env") -Key "AGENT_WORKER_PORT" -DefaultValue "3031")
$CodexPort = [int](Get-DotEnvValue -Path (Join-Path $RepoRoot "tools\codex-agent-worker\.env") -Key "CODEX_WORKER_PORT" -DefaultValue "3051")
$GeminiPort = [int](Get-DotEnvValue -Path (Join-Path $RepoRoot "tools\gemini-agent-worker\.env") -Key "GEMINI_WORKER_PORT" -DefaultValue "3071")
$WinBizPortDefault = Get-DotEnvValue -Path (Join-Path $RepoRoot "tools\langgraph-biz-worker\.env") -Key "BIZ_WORKER_PORT" -DefaultValue "3061"
$WinBizPort = [int](Get-DotEnvValue -Path (Join-Path $RepoRoot "tools\langgraph-biz-worker\.env.local") -Key "BIZ_WORKER_PORT" -DefaultValue $WinBizPortDefault)

Set-Location $RepoRoot

if ($Action -eq "status") {
    if (-not $NoBackend) { Write-PortStatus -Label "backend" -Port $BackendPort -HealthUrl "http://127.0.0.1:$BackendPort/actuator/health" }
    if (-not $NoClaude) { Write-PortStatus -Label "claude-worker" -Port $ClaudePort -HealthUrl "http://127.0.0.1:$ClaudePort/health" }
    if (-not $NoCodex) { Write-PortStatus -Label "codex-worker" -Port $CodexPort -HealthUrl "http://127.0.0.1:$CodexPort/health" }
    if (-not $NoGemini) { Write-PortStatus -Label "gemini-worker" -Port $GeminiPort -HealthUrl "http://127.0.0.1:$GeminiPort/health" }
    if (-not $NoWinBiz) { Write-PortStatus -Label "win-biz-worker" -Port $WinBizPort -HealthUrl "http://127.0.0.1:$WinBizPort/health" }
    Write-WslBizStatus
    exit 0
}

if ($Action -eq "stop" -or $Action -eq "restart") {
    if (-not $NoGemini) { Invoke-RepoScript -Label "Stop Gemini Worker" -RelativePath "tools\gemini-agent-worker\stop.ps1" }
    if (-not $NoCodex) { Invoke-WorkerStopScript -Label "Stop Codex Worker" -RelativePath "tools\codex-agent-worker\stop.ps1" }
    if (-not $NoClaude) { Invoke-WorkerStopScript -Label "Stop Claude Worker" -RelativePath "tools\claude-agent-worker\stop.ps1" }
    if (-not $NoWinBiz) { Invoke-RepoScript -Label "Stop Windows LangGraph Biz Worker" -RelativePath "tools\langgraph-biz-worker\stop.ps1" }
    if (-not $NoBackend) { Invoke-RepoScript -Label "Stop Java Backend" -RelativePath "scripts\stop-launcher.ps1" }
    Invoke-WslBizStop
}

if ($Action -eq "stop") {
    Write-Host ""
    Write-Host "Local stack stopped." -ForegroundColor Green
    exit 0
}

if ($Action -eq "start" -or $Action -eq "restart") {
    Invoke-WslBizStart
    if (-not $NoWinBiz) { Invoke-RepoScript -Label "Start Windows LangGraph Biz Worker" -RelativePath "tools\langgraph-biz-worker\start.ps1" }
    if (-not $NoClaude) { Invoke-RepoScript -Label "Start Claude Worker" -RelativePath "tools\claude-agent-worker\start.ps1" }
    if (-not $NoCodex) { Invoke-RepoScript -Label "Start Codex Worker" -RelativePath "tools\codex-agent-worker\start.ps1" }
    if (-not $NoGemini) { Invoke-RepoScript -Label "Start Gemini Worker" -RelativePath "tools\gemini-agent-worker\start.ps1" }

    if (-not $NoBackend) {
        $backendArgs = @()
        if ($SkipBuild) {
            $backendArgs += "-SkipBuild"
        }
        Invoke-RepoScript -Label "Start Java Backend" -RelativePath "scripts\start-launcher.ps1" -Arguments $backendArgs
    }
}

Write-Host ""
Write-Host "Local stack status:" -ForegroundColor Cyan
if (-not $NoBackend) { Write-PortStatus -Label "backend" -Port $BackendPort -HealthUrl "http://127.0.0.1:$BackendPort/actuator/health" }
if (-not $NoClaude) { Write-PortStatus -Label "claude-worker" -Port $ClaudePort -HealthUrl "http://127.0.0.1:$ClaudePort/health" }
if (-not $NoCodex) { Write-PortStatus -Label "codex-worker" -Port $CodexPort -HealthUrl "http://127.0.0.1:$CodexPort/health" }
if (-not $NoGemini) { Write-PortStatus -Label "gemini-worker" -Port $GeminiPort -HealthUrl "http://127.0.0.1:$GeminiPort/health" }
if (-not $NoWinBiz) { Write-PortStatus -Label "win-biz-worker" -Port $WinBizPort -HealthUrl "http://127.0.0.1:$WinBizPort/health" }
Write-WslBizStatus
