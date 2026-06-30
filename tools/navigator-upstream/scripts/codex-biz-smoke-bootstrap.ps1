param(
    [string]$SystemProfile = ".\.navigator\codex-biz-smoke.env",
    [string]$TenantProfile = ".\.navigator\tenants\codex-biz-smoke-local.env",
    [string]$TenantId = "navi-codex-biz-smoke-local",
    [string]$UpstreamRef = "codex-biz-smoke-local",
    [string]$UpstreamUserId = "codex-biz-smoke-user",
    [string]$AgentCode = "codex-biz-smoke-agent",
    [string]$ModelName = "gpt-5.5",
    [string]$ModelBaseUrl = "https://api.openai.com/v1",
    [string]$LlmApiKeyEnv = "NAVI_LLM_API_KEY",
    [string]$ProjectPath = "",
    [string]$WorkerHostId = "codex-biz-smoke-local",
    [string]$WorkerHostUrl = "http://127.0.0.1",
    [int]$WorkerPort = 3070,
    [string]$WorkerTokenEnv = "NAVI_CODEX_BIZ_SMOKE_WORKER_TOKEN",
    [switch]$ForceRuntimeKey,
    [switch]$ForceControlKey,
    [switch]$ForceModelCreate,
    [switch]$ForceDirectoryInit,
    [switch]$RunAsk,
    [string]$PrivateAccountId = "codex-biz-smoke-local-user",
    [string]$SmokeMessage = "Run a short Codex Biz Worker smoke. Reply with the current working directory and do not modify files."
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptRoot "..\..\..")).Path
$Navi = Join-Path $ProjectRoot "tools\navigator-upstream\navi.ps1"
$WorkDir = Join-Path $ProjectRoot "temp\codex-biz-smoke"
$WorkerHostFile = Join-Path $WorkDir "worker-host.generated.json"
$DirectoryFile = Join-Path $WorkDir "client-directory.generated.json"
$AgentFile = Join-Path $WorkDir "agent.generated.json"
$ControlScopes = "MODEL_CONFIG_MANAGE,WORKING_DIRECTORY_MANAGE,AGENT_BUNDLE_SYNC,AGENT_MODEL_BINDING_MANAGE,AGENT_WORKSPACE_BINDING_MANAGE,AGENT_WORKER_BINDING_MANAGE,UPSTREAM_USER_GRANT"

function Invoke-Navi {
    param(
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string[]]$NaviArgs
    )
    & $Navi upstream @NaviArgs --profile $Profile
    if ($LASTEXITCODE -ne 0) {
        throw "navi upstream $($NaviArgs -join ' ') failed with exit code $LASTEXITCODE"
    }
}

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

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $json = $Value | ConvertTo-Json -Depth 16
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Ensure-WorkerTokenEnv {
    param(
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ([Environment]::GetEnvironmentVariable($Name)) {
        return
    }

    $workerToken = [Environment]::GetEnvironmentVariable("CODEX_WORKER_TOKEN")
    if (-not $workerToken) {
        $workerEnvPath = Join-Path $ProjectRoot "tools\codex-agent-worker\.env"
        $workerToken = Get-ProfileValue -Path $workerEnvPath -Name "CODEX_WORKER_TOKEN"
    }
    if (-not $workerToken) {
        $workerToken = ([Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N"))
    }
    [Environment]::SetEnvironmentVariable($Name, $workerToken, "Process")
}

Push-Location $ProjectRoot
try {
    if (-not $ProjectPath) {
        $ProjectPath = $ProjectRoot
    }
    if (-not (Test-Path -LiteralPath $Navi)) {
        throw "Navigator upstream CLI not found: $Navi"
    }
    if (-not (Test-Path -LiteralPath $SystemProfile)) {
        throw "System profile not found: $SystemProfile"
    }
    if (-not (Test-Path -LiteralPath $ProjectPath)) {
        throw "Project path not found: $ProjectPath"
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $TenantProfile) | Out-Null
    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

    $workerBaseUrl = "${WorkerHostUrl}:$WorkerPort"
    $health = Invoke-RestMethod -Uri "$workerBaseUrl/health" -TimeoutSec 5
    if (-not $health.codex_biz_home_root_configured -or -not $health.codex_biz_scoped_home_ready) {
        throw "Codex Worker $workerBaseUrl is not ready for codex-biz-worker scoped homes."
    }

    if (-not (Get-ProfileValue -Path $SystemProfile -Name "NAVI_ADMIN_API_KEY")) {
        Invoke-Navi -Profile $SystemProfile -NaviArgs @("admin-key", "claim", "--write-profile")
    }
    else {
        Write-Host "System profile already has NAVI_ADMIN_API_KEY; skipping claim."
    }

    Invoke-Navi -Profile $SystemProfile -NaviArgs @(
        "client-app", "ensure",
        "--target-tenant-id", $TenantId,
        "--upstream-ref", $UpstreamRef,
        "--name", "Codex Biz Smoke Local",
        "--tenant-profile", $TenantProfile,
        "--write-profile"
    )

    $clientAppId = Get-ProfileValue -Path $TenantProfile -Name "NAVI_CLIENT_APP_ID"
    if (-not $clientAppId) {
        throw "NAVI_CLIENT_APP_ID was not written to $TenantProfile"
    }

    if ($ForceRuntimeKey -or -not (Get-ProfileValue -Path $TenantProfile -Name "NAVI_CLIENT_APP_KEY")) {
        Invoke-Navi -Profile $SystemProfile -NaviArgs @(
            "client-app", "issue-runtime-key",
            "--client-app-id", $clientAppId,
            "--tenant-profile", $TenantProfile,
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant profile already has runtime key; skipping issue-runtime-key."
    }

    if ($ForceControlKey -or -not (Get-ProfileValue -Path $TenantProfile -Name "NAVI_CONTROL_API_KEY")) {
        Invoke-Navi -Profile $SystemProfile -NaviArgs @(
            "client-app", "issue-control-key",
            "--client-app-id", $clientAppId,
            "--scopes", $ControlScopes,
            "--tenant-profile", $TenantProfile,
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant profile already has control key; skipping issue-control-key."
    }

    Invoke-Navi -Profile $TenantProfile -NaviArgs @("runtime-token", "--write-profile")

    Ensure-WorkerTokenEnv -Name $WorkerTokenEnv
    $workerHost = [ordered]@{
        workerHostId = $WorkerHostId
        hostUrl = $WorkerHostUrl
        port = $WorkerPort
        workers = [ordered]@{
            claudeCode = [ordered]@{
                enabled = $true
                port = $WorkerPort
                name = "codex-biz-smoke-anchor"
                authTokenEnv = $WorkerTokenEnv
            }
            codex = [ordered]@{
                enabled = $true
                port = $WorkerPort
                name = "codex-biz-smoke-codex"
                authTokenEnv = $WorkerTokenEnv
                model = $ModelName
            }
        }
    }
    Write-JsonFile -Path $WorkerHostFile -Value $workerHost
    Invoke-Navi -Profile $SystemProfile -NaviArgs @(
        "worker-host", "apply",
        "--file", $WorkerHostFile,
        "--target-tenant-id", $TenantId,
        "--write-profile"
    )

    $workerId = Get-ProfileValue -Path $SystemProfile -Name "NAVI_WORKER_ID"
    if (-not $workerId) {
        throw "NAVI_WORKER_ID was not written to $SystemProfile"
    }

    if ($ForceModelCreate -or -not (Get-ProfileValue -Path $TenantProfile -Name "NAVI_MODEL_CONFIG_ID")) {
        if (-not [Environment]::GetEnvironmentVariable($LlmApiKeyEnv)) {
            throw "$LlmApiKeyEnv is required by navigator-upstream model create. Set it in this shell before running."
        }
        Invoke-Navi -Profile $TenantProfile -NaviArgs @(
            "model", "create",
            "--name", "Codex Biz Smoke OPENAI_CODEX",
            "--model-base-url", $ModelBaseUrl,
            "--model-name", $ModelName,
            "--provider", "openai",
            "--api-key-env", $LlmApiKeyEnv,
            "--worker-backend", "OPENAI_CODEX",
            "--available-models", $ModelName,
            "--set-default",
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant profile already has NAVI_MODEL_CONFIG_ID; skipping model create."
    }

    $modelConfigId = Get-ProfileValue -Path $TenantProfile -Name "NAVI_MODEL_CONFIG_ID"
    if (-not $modelConfigId) {
        throw "NAVI_MODEL_CONFIG_ID was not written to $TenantProfile"
    }

    if ($ForceDirectoryInit -or -not (Get-ProfileValue -Path $TenantProfile -Name "NAVI_DIRECTORY_ID")) {
        $directory = [ordered]@{
            workerId = $workerId
            path = $ProjectPath
            projectName = "codex-biz-smoke-local"
            workspaceScope = "CLIENT_APP_SHARED"
            resolverType = "MANAGED"
            rootRef = $ProjectPath
            readOnly = $false
            allowedPathPrefixes = @($ProjectPath)
            files = [ordered]@{
                "temp/codex-biz-smoke/.directory-smoke.md" = "Codex Biz smoke workspace marker."
            }
            enabled = $true
        }
        Write-JsonFile -Path $DirectoryFile -Value $directory
        Invoke-Navi -Profile $TenantProfile -NaviArgs @(
            "directory", "client-init",
            "--file", $DirectoryFile,
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant profile already has NAVI_DIRECTORY_ID; skipping directory client-init."
    }

    $directoryId = Get-ProfileValue -Path $TenantProfile -Name "NAVI_DIRECTORY_ID"
    if (-not $directoryId) {
        throw "NAVI_DIRECTORY_ID was not written to $TenantProfile"
    }

    $agent = [ordered]@{
        agentCode = $AgentCode
        agentId = $AgentCode
        skillId = "$AgentCode-skill"
        name = "Codex Biz Smoke Agent"
        description = "Isolated local smoke agent for codex-biz-worker migration validation."
        status = "ENABLED"
        workerId = $workerId
        defaultModelConfigId = $modelConfigId
        defaultModel = $ModelName
        contextVisibility = "isolated"
        markdownBody = "Use Codex Biz Worker only for local migration smoke. Keep replies short and do not modify files unless explicitly asked."
        resources = @()
        functions = @()
        materialize = $true
    }
    Write-JsonFile -Path $AgentFile -Value $agent

    Invoke-Navi -Profile $TenantProfile -NaviArgs @("agent", "sync", "--manifest", $AgentFile)
    Invoke-Navi -Profile $TenantProfile -NaviArgs @("agent", "set-default-model", "--agent-code", $AgentCode, "--model-config-id", $modelConfigId)
    Invoke-Navi -Profile $TenantProfile -NaviArgs @("agent", "set-default-workspace", "--agent-code", $AgentCode, "--directory-id", $directoryId)
    Invoke-Navi -Profile $TenantProfile -NaviArgs @("ensure-grant", "--upstream-user-id", $UpstreamUserId)
    Invoke-Navi -Profile $TenantProfile -NaviArgs @("owner-smoke", "--agent-code", $AgentCode, "--upstream-user-id", $UpstreamUserId)

    if ($RunAsk) {
        Invoke-Navi -Profile $TenantProfile -NaviArgs @(
            "ask",
            "--agent-code", $AgentCode,
            "--upstream-user-id", $UpstreamUserId,
            "--directory-id", $directoryId,
            "--provider-type", "codex-biz-worker",
            "--private-account-id", $PrivateAccountId,
            "--sandbox-mode", "workspace-write",
            "--approval-policy", "never",
            "--network-access-enabled", "false",
            "--web-search-mode", "disabled",
            "--max-turns", "1",
            "--message", $SmokeMessage
        )
    }

    Write-Host "Codex Biz smoke bootstrap completed."
    Write-Host "System profile: $SystemProfile"
    Write-Host "Tenant profile: $TenantProfile"
}
finally {
    Pop-Location
}
