param(
    [string]$Profile = ".\.navigator\codex-biz-smoke.env",
    [string]$RequestCode = "",
    [string]$AuthorizedTenantIds = "navi-codex-biz-smoke-local",
    [string]$Namespace = "navi-codex-biz-smoke",
    [string]$OperatorApiKeyEnv = "NAVI_OPERATOR_API_KEY",
    [string]$Scopes = "CLIENT_APP_MANAGE,CLIENT_APP_RUNTIME_KEY_ISSUE,CLIENT_APP_CONTROL_KEY_ISSUE,WORKER_MANAGE,WORKING_DIRECTORY_MANAGE,WORKER_POOL_MANAGE,MODEL_CONFIG_MANAGE,BUSINESS_OBJECT_MANAGE,AGENT_MANAGE,AGENT_MODEL_BINDING_MANAGE,AGENT_WORKSPACE_BINDING_MANAGE,AGENT_WORKER_BINDING_MANAGE,AGENT_BUNDLE_SYNC"
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptRoot "..\..\..")).Path
$Navi = Join-Path $ProjectRoot "tools\navigator-upstream\navi.ps1"

function Get-ProfileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = [string]$line
        if ($text.Trim().Length -eq 0 -or $text.TrimStart().StartsWith("#")) {
            continue
        }
        $idx = $text.IndexOf("=")
        if ($idx -le 0) {
            continue
        }
        $key = $text.Substring(0, $idx).Trim()
        if ($key -eq $Name) {
            return $text.Substring($idx + 1).Trim().Trim('"').Trim("'")
        }
    }
    return ""
}

Push-Location $ProjectRoot
try {
    if (-not [Environment]::GetEnvironmentVariable($OperatorApiKeyEnv)) {
        throw "Set environment variable $OperatorApiKeyEnv before running this script."
    }
    if (-not $RequestCode) {
        $RequestCode = Get-ProfileValue -Path $Profile -Name "NAVI_ADMIN_KEY_REQUEST_CODE"
    }
    if (-not $RequestCode) {
        throw "Request code is required. Pass -RequestCode or store NAVI_ADMIN_KEY_REQUEST_CODE in $Profile."
    }

    & $Navi upstream admin-key approve `
        --request-code $RequestCode `
        --authorized-tenant-ids $AuthorizedTenantIds `
        --namespace $Namespace `
        --scopes $Scopes `
        --operator-api-key-env $OperatorApiKeyEnv `
        --profile $Profile

    if ($LASTEXITCODE -ne 0) {
        throw "admin-key approve failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
