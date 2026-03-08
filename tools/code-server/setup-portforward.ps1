# WSL Port Forwarding & Firewall Setup Script
# 统一管理所有需要从 Windows 宿主机/局域网访问 WSL 服务的端口转发
#
# 需要管理员权限运行
# Usage: powershell -ExecutionPolicy Bypass -File setup-portforward.ps1
#
# WSL2 使用虚拟网络，端口不会自动暴露到宿主机。
# 本脚本自动检测 WSL IP，配置 portproxy + 防火墙入站规则。

$ErrorActionPreference = "Stop"

# ============================================================
# Load HTTPS port from .env (if configured)
# ============================================================
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $ScriptDir ".env"
$HttpsPort = 0

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^CODE_SERVER_HTTPS_PORT=(\d+)") { $HttpsPort = [int]$Matches[1] }
    }
}

# ============================================================
# 端口配置（在这里统一管理所有需要转发的端口）
# ============================================================
$Ports = @(
    @{ Port = 8443; Name = "code-server-windows"; Desc = "Code Server (Windows mode)" }
    @{ Port = 8444; Name = "code-server-wsl";     Desc = "Code Server HTTP (WSL mode)" }
)

# Dynamically add HTTPS port if configured
if ($HttpsPort -gt 0) {
    $Ports += @{ Port = $HttpsPort; Name = "code-server-wsl-https"; Desc = "Code Server HTTPS (WSL mode)" }
}

Write-Host "=== WSL Port Forwarding & Firewall Setup ===" -ForegroundColor Cyan
Write-Host "Ports: $($Ports | ForEach-Object { $_.Port }) " -ForegroundColor Cyan
Write-Host ""

# ---- 检查管理员权限 ----
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "WARNING: This script requires administrator privileges" -ForegroundColor Yellow
    Write-Host "Please right-click PowerShell and select 'Run as administrator'" -ForegroundColor Yellow
    exit 1
}

# ---- 获取 WSL IP 地址 ----
Write-Host "Getting WSL IP address..." -ForegroundColor Yellow
$WSLIP = wsl -d Ubuntu-24.04 -- bash -c "hostname -I" | ForEach-Object { $_.Split(' ')[0] }

if (-not $WSLIP) {
    Write-Host "ERROR: Failed to get WSL IP address. Is WSL running?" -ForegroundColor Red
    exit 1
}

Write-Host "WSL IP: $WSLIP" -ForegroundColor Green
Write-Host ""

# ============================================================
# 配置每个端口的 portproxy + 防火墙规则
# ============================================================
$allSuccess = $true

foreach ($entry in $Ports) {
    $Port = $entry.Port
    $RuleName = "WSL $($entry.Name) $Port"

    Write-Host "--- Port $Port ($($entry.Desc)) ---" -ForegroundColor Cyan

    # 1. 删除旧的 portproxy 规则
    netsh interface portproxy delete v4tov4 listenport=$Port listenaddress=0.0.0.0 2>$null | Out-Null

    # 2. 添加新的 portproxy 规则
    Write-Host "  Adding portproxy: 0.0.0.0:$Port -> ${WSLIP}:$Port" -ForegroundColor Yellow
    netsh interface portproxy add v4tov4 listenport=$Port listenaddress=0.0.0.0 connectport=$Port connectaddress=$WSLIP | Out-Null

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ERROR: Failed to add portproxy rule for port $Port" -ForegroundColor Red
        $allSuccess = $false
        continue
    }
    Write-Host "  Portproxy rule added" -ForegroundColor Green

    # 3. 防火墙入站规则（先删旧的再创建，避免重复）
    netsh advfirewall firewall delete rule name="$RuleName" 2>$null | Out-Null
    netsh advfirewall firewall add rule name="$RuleName" dir=in action=allow protocol=TCP localport=$Port | Out-Null

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  WARNING: Failed to add firewall rule" -ForegroundColor Yellow
    } else {
        Write-Host "  Firewall rule added" -ForegroundColor Green
    }

    Write-Host ""
}

# ============================================================
# 显示当前规则 & 验证
# ============================================================
Write-Host "=== Current portproxy rules ===" -ForegroundColor Cyan
netsh interface portproxy show v4tov4
Write-Host ""

# 验证连接
Write-Host "=== Verifying connections ===" -ForegroundColor Cyan
foreach ($entry in $Ports) {
    $Port = $entry.Port
    $test = Test-NetConnection -ComputerName $WSLIP -Port $Port -WarningAction SilentlyContinue -InformationLevel Quiet 2>$null

    if ($test) {
        Write-Host "  Port ${Port}: WSL direct (${WSLIP}:$Port) ... OK" -ForegroundColor Green

        $localTest = Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -WarningAction SilentlyContinue -InformationLevel Quiet 2>$null
        if ($localTest) {
            Write-Host "  Port ${Port}: localhost:$Port ............. OK" -ForegroundColor Green
        } else {
            Write-Host "  Port ${Port}: localhost:$Port ............. FAILED (portproxy issue?)" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  Port ${Port}: WSL direct (${WSLIP}:$Port) ... NOT LISTENING (service not running?)" -ForegroundColor Yellow
    }
}

# ============================================================
# 总结
# ============================================================
Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Access URLs:" -ForegroundColor Green
foreach ($entry in $Ports) {
    $protocol = if ($entry.Name -like "*https*") { "https" } else { "http" }
    Write-Host "  $($entry.Desc):" -ForegroundColor White
    Write-Host "    Local:  ${protocol}://localhost:$($entry.Port)" -ForegroundColor Green
    Write-Host "    LAN:    ${protocol}://<Windows_IP>:$($entry.Port)" -ForegroundColor Green
}
Write-Host ""
Write-Host "Password: foggy123 (see config.yaml)" -ForegroundColor Yellow
Write-Host ""
Write-Host "NOTE: WSL IP may change after reboot. Re-run this script to update." -ForegroundColor Gray
Write-Host ""
Write-Host "To remove all rules:" -ForegroundColor Gray
foreach ($entry in $Ports) {
    Write-Host "  netsh interface portproxy delete v4tov4 listenport=$($entry.Port) listenaddress=0.0.0.0" -ForegroundColor Gray
    Write-Host "  netsh advfirewall firewall delete rule name=`"WSL $($entry.Name) $($entry.Port)`"" -ForegroundColor Gray
}
