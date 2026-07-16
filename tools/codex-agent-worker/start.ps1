# Codex Agent Worker 启动脚本
# 用法: powershell -ExecutionPolicy Bypass -File start.ps1

$ErrorActionPreference = "Stop"
$ScriptDir = ""
if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    $ScriptDir = [string]$PSScriptRoot
}
if ([string]::IsNullOrWhiteSpace($ScriptDir) -and -not [string]::IsNullOrWhiteSpace($PSCommandPath)) {
    $ScriptDir = Split-Path -Parent $PSCommandPath
}
if ([string]::IsNullOrWhiteSpace($ScriptDir) -and -not [string]::IsNullOrWhiteSpace($MyInvocation.MyCommand.Path)) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if ([string]::IsNullOrWhiteSpace($ScriptDir) -and -not [string]::IsNullOrWhiteSpace($MyInvocation.MyCommand.Definition) -and (Test-Path -LiteralPath $MyInvocation.MyCommand.Definition -PathType Leaf)) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
}
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    $ScriptDir = (Get-Location).ProviderPath
}
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    throw "Unable to resolve script directory for start.ps1"
}
Set-Location -LiteralPath $ScriptDir
$ScriptDir = "$((Get-Location).ProviderPath)"

function Import-DotEnv {
    param([string]$Path)

    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) {
            continue
        }

        $key = $parts[0].Trim()
        if ($key -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
            continue
        }

        $value = $parts[1].Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        Set-Item -Path "Env:$key" -Value $value
    }
}

function Get-DotEnvValue {
    param(
        [string]$Path,
        [string]$Name
    )

    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) {
        return ""
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) {
            continue
        }

        $key = $parts[0].Trim()
        if ($key -ne $Name) {
            continue
        }

        $value = $parts[1].Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        return $value
    }

    return ""
}

# 读取脚本所在目录的 .env，并覆盖当前启动进程的环境，避免外部 CODEX_* 变量污染 worker。
# Windows PowerShell 5.1 -File may keep this value lazy; materialize before composing child paths.
$null = "$ScriptDir"
$ResolvedDotEnvPath = "$ScriptDir\.env"
$envPort = Get-DotEnvValue -Path $ResolvedDotEnvPath -Name "CODEX_WORKER_PORT"
$envAutoUpdateSdk = Get-DotEnvValue -Path $ResolvedDotEnvPath -Name "CODEX_WORKER_AUTO_UPDATE_SDK"
if ($envAutoUpdateSdk) {
    $env:CODEX_WORKER_AUTO_UPDATE_SDK = $envAutoUpdateSdk
}
Import-DotEnv -Path $ResolvedDotEnvPath
$PORT = if ($envPort) {
    $envPort.Trim()
} elseif ($env:CODEX_WORKER_PORT) {
    $env:CODEX_WORKER_PORT.Trim()
} else {
    "3051"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Codex Agent Worker" -ForegroundColor Cyan
Write-Host "  Config: $ResolvedDotEnvPath" -ForegroundColor Cyan
Write-Host "  Port: $PORT" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Existing Worker safety gate. Run stop.ps1 in a child PowerShell because its
# explicit exit codes are part of the no-replacement contract.
Write-Host "`n[1/4] Verifying any existing Worker can stop safely..." -ForegroundColor Yellow
$StopScript = Join-Path $ScriptDir "stop.ps1"
$PowerShellHost = (Get-Process -Id $PID -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $StopScript) -or [string]::IsNullOrWhiteSpace($PowerShellHost)) {
    throw "Unable to invoke the Codex Worker safe-stop gate."
}
& $PowerShellHost -NoProfile -ExecutionPolicy Bypass -File $StopScript
$safeStopExitCode = $LASTEXITCODE
if ($safeStopExitCode -ne 0) {
    Write-Host "  Refusing to start a replacement: the existing Worker did not prove safe quiescence." -ForegroundColor Red
    exit $safeStopExitCode
}

# 安装依赖
Write-Host "`n[2/4] Checking dependencies..." -ForegroundColor Yellow
if (-not (Test-Path "node_modules")) {
    Write-Host "  Running npm install..." -ForegroundColor Yellow
    npm install 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  npm install failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Dependencies installed." -ForegroundColor Green
} else {
    Write-Host "  node_modules exists, skipping install." -ForegroundColor Green
}

$EnsureSdkScript = Join-Path $ScriptDir "scripts\ensure-sdk.mjs"
if (-not (Test-Path -LiteralPath $EnsureSdkScript)) {
    Write-Host "  SDK preflight script not found: $EnsureSdkScript" -ForegroundColor Red
    exit 1
}
& node $EnsureSdkScript --worker-dir $ScriptDir
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Codex SDK preflight failed; worker will not start." -ForegroundColor Red
    exit $LASTEXITCODE
}

# 确保 logs 目录存在
$LogDir = "$ScriptDir\logs"
if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir | Out-Null
}

# 后台启动
Write-Host "`n[3/4] Starting Codex Worker..." -ForegroundColor Yellow
$logFile = "$LogDir\worker.log"
$errFile = "$LogDir\worker-error.log"

$process = Start-Process -FilePath "npx.cmd" -ArgumentList "tsx", "src/index.ts" `
    -WorkingDirectory $ScriptDir `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errFile `
    -PassThru -NoNewWindow

Write-Host "  PID: $($process.Id)" -ForegroundColor Green

# 等待就绪
Write-Host "`n[4/4] Waiting for worker to be ready..." -ForegroundColor Yellow
$maxWait = 60
$waited = 0
$ready = $false
$healthUrls = @(
    "http://127.0.0.1:$PORT/health"
    "http://localhost:$PORT/health"
)

function Test-WorkerHealth {
    param(
        [string[]]$Urls
    )

    foreach ($url in $Urls) {
        try {
            $health = Invoke-RestMethod -Uri $url -TimeoutSec 2 -ErrorAction Stop
            if ($health.status -eq "ok" -and $health.codex_sdk_available -eq $true -and $health.codex_sdk_compatible -eq $true) {
                return $true
            }
        }
        catch {
        }
    }

    return $false
}

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 1
    $waited++

    if (Test-WorkerHealth -Urls $healthUrls) {
        $ready = $true
        break
    }

    # 检查进程是否崩溃
    $process.Refresh()
    if ($process.HasExited) {
        Write-Host "`n  Worker process exited unexpectedly!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }

    Write-Host "  Waiting... ($waited/$maxWait)" -ForegroundColor Gray
}

if ($ready) {
    Start-Sleep -Seconds 3
    $process.Refresh()
    if ($process.HasExited) {
        Write-Host "`n  Worker exited after readiness!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }

    if (-not (Test-WorkerHealth -Urls $healthUrls)) {
        Write-Host "`n  Worker health failed after readiness!" -ForegroundColor Red
        if (Test-Path $errFile) {
            Write-Host "`n  Error log:" -ForegroundColor Red
            Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
        exit 1
    }
    Write-Host "`n========================================" -ForegroundColor Green
    Write-Host "  Codex Worker is READY!" -ForegroundColor Green
    Write-Host "  URL: http://localhost:$PORT" -ForegroundColor Green
    Write-Host "  Health: http://localhost:$PORT/health" -ForegroundColor Green
    Write-Host "  PID: $($process.Id)" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
} else {
    Write-Host "`n  Worker failed to start within ${maxWait}s!" -ForegroundColor Red
    if (Test-Path $errFile) {
        Write-Host "`n  Error log:" -ForegroundColor Red
        Get-Content $errFile | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    }
    exit 1
}
