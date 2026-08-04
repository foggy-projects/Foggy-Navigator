[CmdletBinding()]
param(
    [ValidateSet("Apply", "Status", "Remove")]
    [string]$Action = "Status",
    [string]$Distro = "tms-dev",
    [ValidateRange(1, 65535)]
    [int]$ListenPort = 5175,
    [ValidateRange(1, 65535)]
    [int]$ConnectPort = 5175
)

# This helper intentionally exposes only the static Workbench ingress. Backend,
# Runtime, Access, Host, and Worker listeners remain inside WSL on loopback.
$ErrorActionPreference = "Stop"
$FirewallRuleName = "Foggy Workbench FAP Canary 5175"
$ListenAddress = "0.0.0.0"
$Netsh = Join-Path $env:SystemRoot "System32\netsh.exe"
$Wsl = Join-Path $env:SystemRoot "System32\wsl.exe"

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Request-Elevation {
    $powerShell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    $arguments = @(
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $PSCommandPath,
        "-Action",
        $Action,
        "-Distro",
        $Distro,
        "-ListenPort",
        "$ListenPort",
        "-ConnectPort",
        "$ConnectPort"
    )
    Start-Process -FilePath $powerShell -Verb RunAs -ArgumentList $arguments | Out-Null
    [ordered]@{
        schema = "navigator.workbench-fap-wsl-lan.v1"
        action = $Action.ToUpperInvariant()
        elevationRequested = $true
    } | ConvertTo-Json -Compress
    exit 0
}

function Resolve-WslAddress {
    $rawAddresses = (& $Wsl -d $Distro hostname -I | Out-String).Trim()
    $address = @($rawAddresses -split "\s+" | Where-Object { $_ -match "^\d{1,3}(\.\d{1,3}){3}$" })[0]
    if (-not $address) {
        throw "WORKBENCH_WSL_IPV4_NOT_FOUND: $Distro"
    }
    return $address
}

function Get-StatusObject {
    param([string]$WslAddress)

    $proxyOutput = (& $Netsh interface portproxy show v4tov4 | Out-String)
    $proxyPattern = "(?m)^\s*0\.0\.0\.0\s+$ListenPort\s+$([regex]::Escape($WslAddress))\s+$ConnectPort\s*$"
    $firewallRule = Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue
    $firewallPort = $firewallRule | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue
    $firewallAddress = $firewallRule | Get-NetFirewallAddressFilter -ErrorAction SilentlyContinue
    $firewallConfigured = @($firewallRule).Count -eq 1 -and
        "$($firewallRule.Enabled)" -eq "True" -and
        "$($firewallRule.Direction)" -eq "Inbound" -and
        "$($firewallRule.Action)" -eq "Allow" -and
        "$($firewallRule.Profile)" -eq "Private" -and
        "$($firewallPort.Protocol)" -eq "TCP" -and
        "$($firewallPort.LocalPort)" -eq "$ListenPort" -and
        @($firewallAddress.RemoteAddress) -contains "LocalSubnet"
    $lanConfig = Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.NetProfile.NetworkCategory -eq "Private" } |
        Select-Object -First 1

    return [ordered]@{
        schema = "navigator.workbench-fap-wsl-lan.v1"
        action = $Action.ToUpperInvariant()
        distro = $Distro
        wslAddress = $WslAddress
        listenAddress = $ListenAddress
        listenPort = $ListenPort
        connectPort = $ConnectPort
        portProxyConfigured = [regex]::IsMatch($proxyOutput, $proxyPattern)
        firewallConfigured = [bool]$firewallConfigured
        firewallProfile = if ($firewallRule) { "$($firewallRule.Profile)" } else { $null }
        firewallRemoteScope = if ($firewallRule) {
            "$((@($firewallAddress.RemoteAddress)) -join ',')"
        } else {
            $null
        }
        lanAddress = if ($lanConfig) { "$($lanConfig.IPv4Address.IPAddress)" } else { $null }
        lanUrl = if ($lanConfig) { "http://$($lanConfig.IPv4Address.IPAddress):$ListenPort" } else { $null }
    }
}

$wslAddress = Resolve-WslAddress

if ($Action -in @("Apply", "Remove") -and -not (Test-Administrator)) {
    Request-Elevation
}

if ($Action -eq "Apply") {
    if (-not (Test-NetConnection -ComputerName $wslAddress -Port $ConnectPort -InformationLevel Quiet)) {
        throw "WORKBENCH_WSL_FRONTEND_NOT_REACHABLE: ${wslAddress}:$ConnectPort"
    }

    & $Netsh interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$ListenPort 2>$null | Out-Null
    & $Netsh interface portproxy add v4tov4 listenaddress=$ListenAddress listenport=$ListenPort connectaddress=$wslAddress connectport=$ConnectPort | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "WORKBENCH_PORTPROXY_APPLY_FAILED: $LASTEXITCODE"
    }

    Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
    New-NetFirewallRule `
        -DisplayName $FirewallRuleName `
        -Description "Private-LAN ingress to the personal FAP Workbench canary in WSL." `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort $ListenPort `
        -Profile Private `
        -RemoteAddress LocalSubnet | Out-Null
}
elseif ($Action -eq "Remove") {
    & $Netsh interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$ListenPort 2>$null | Out-Null
    Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
}

$status = Get-StatusObject -WslAddress $wslAddress
if ($Action -eq "Apply" -and (-not $status.portProxyConfigured -or -not $status.firewallConfigured)) {
    throw "WORKBENCH_WSL_LAN_VERIFICATION_FAILED"
}
$status | ConvertTo-Json -Depth 4 -Compress
