param(
    [string]$SystemProfile = ".\.navigator\navigator-provisioning-selftest.env",
    [string]$TenantAProfile = ".\.navigator\tenants\navi-provisioning-selftest-a.env",
    [string]$TenantBProfile = ".\.navigator\tenants\navi-provisioning-selftest-b.env",
    [string]$TenantAId = "navi-provisioning-selftest-a",
    [string]$TenantBId = "navi-provisioning-selftest-b",
    [string]$UpstreamRefA = "navigator-provisioning-selftest-a",
    [string]$UpstreamRefB = "navigator-provisioning-selftest-b",
    [string]$UpstreamUserA = "navigator-provisioning-selftest-user-a",
    [string]$AgentCodeA = "navigator-provisioning-selftest-agent-a",
    [string]$WorkerHostId = "navigator-provisioning-selftest-local",
    [string]$WorkerHostUrl = "http://127.0.0.1",
    [int]$ClaudePort = 3131,
    [int]$CodexPort = 3151,
    [int]$BizPort = 3161,
    [string]$WorkerTokenEnv = "NAVI_SELFTEST_WORKER_TOKEN",
    [string]$BizIdentityTokenEnv = "",
    [string]$ModelName = "navigator-selftest-model",
    [string]$ModelBaseUrl = "http://127.0.0.1:8200/v1",
    [string]$LlmApiKeyEnv = "NAVI_SELFTEST_LLM_API_KEY",
    [string]$WorkspaceRoot = "",
    [switch]$PrepareOnly,
    [switch]$SkipModelCreate,
    [switch]$RunIsolationChecks,
    [switch]$RunLiveAsk,
    [switch]$GenerateEphemeralWorkerToken,
    [switch]$ForceRuntimeKey,
    [switch]$ForceControlKey,
    [switch]$ForceModelCreate,
    [switch]$ForceDirectoryInit
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptRoot "..\..\..")).Path
$Navi = Join-Path $ProjectRoot "tools\navigator-upstream\navi.ps1"
$WorkDir = Join-Path $ProjectRoot "temp\navigator-provisioning-selftest"
$VerifyProfile = Join-Path $WorkDir "verify.env"
$WorkerHostFile = Join-Path $WorkDir "worker-host.generated.json"
$DirectoryAFile = Join-Path $WorkDir "client-directory-a.generated.json"
$AgentAFile = Join-Path $WorkDir "agent-a.generated.json"
$ControlScopes = "MODEL_CONFIG_MANAGE,MODEL_CONFIG_GRANT_MANAGE,WORKING_DIRECTORY_MANAGE,AGENT_BUNDLE_SYNC,AGENT_MODEL_BINDING_MANAGE,AGENT_WORKSPACE_BINDING_MANAGE,AGENT_WORKER_BINDING_MANAGE,UPSTREAM_USER_GRANT"

function Assert-NotAccountsPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        $full = [System.IO.Path]::GetFullPath($Path)
    }
    else {
        $full = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $Path))
    }
    $accountsRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "accounts"))
    if ($full.StartsWith($accountsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to read or write accounts path: $Path"
    }
}

function Get-ProfileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )
    Assert-NotAccountsPath -Path $Path
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

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    Assert-NotAccountsPath -Path $Path
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $json = $Value | ConvertTo-Json -Depth 16
    Write-TextFile -Path $Path -Text $json
}

function Invoke-Navi {
    param(
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string[]]$NaviArgs
    )
    Assert-NotAccountsPath -Path $Profile
    & $Navi upstream @NaviArgs --profile $Profile
    if ($LASTEXITCODE -ne 0) {
        throw "navi upstream $($NaviArgs[0]) failed with exit code $LASTEXITCODE"
    }
}

function Invoke-NaviExpectFailure {
    param(
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string[]]$NaviArgs,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    Assert-NotAccountsPath -Path $Profile
    $output = & $Navi upstream @NaviArgs --profile $Profile 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw $FailureMessage
    }
    Write-Host "Expected failure observed for $($NaviArgs[0]) $($NaviArgs[1])."
}

function Ensure-WorkerSecretEnv {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Purpose
    )
    if (-not $Name) {
        return
    }
    if ([Environment]::GetEnvironmentVariable($Name)) {
        return
    }
    if (-not $GenerateEphemeralWorkerToken) {
        throw "Environment variable $Name is required for $Purpose. Set it in the current shell, or pass -GenerateEphemeralWorkerToken for a disposable local registration."
    }
    $generated = ([Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N"))
    [Environment]::SetEnvironmentVariable($Name, $generated, "Process")
    Write-Host "Generated process-local value for $Name."
}

function Ensure-ClientApp {
    param(
        [Parameter(Mandatory = $true)][string]$TenantId,
        [Parameter(Mandatory = $true)][string]$UpstreamRef,
        [Parameter(Mandatory = $true)][string]$TenantProfile,
        [Parameter(Mandatory = $true)][string]$Name
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $TenantProfile) | Out-Null
    Invoke-Navi -Profile $SystemProfile -NaviArgs @(
        "client-app", "ensure",
        "--target-tenant-id", $TenantId,
        "--upstream-ref", $UpstreamRef,
        "--name", $Name,
        "--tenant-profile", $TenantProfile,
        "--write-profile"
    ) | Out-Host

    $clientAppId = Get-ProfileValue -Path $TenantProfile -Name "NAVI_CLIENT_APP_ID"
    if (-not $clientAppId) {
        throw "NAVI_CLIENT_APP_ID was not written to $TenantProfile"
    }
    return $clientAppId
}

function Ensure-ClientCredentials {
    param(
        [Parameter(Mandatory = $true)][string]$TenantProfile,
        [Parameter(Mandatory = $true)][string]$ClientAppId
    )
    if ($ForceRuntimeKey -or -not (Get-ProfileValue -Path $TenantProfile -Name "NAVI_CLIENT_APP_KEY")) {
        Invoke-Navi -Profile $SystemProfile -NaviArgs @(
            "client-app", "issue-runtime-key",
            "--client-app-id", $ClientAppId,
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
            "--client-app-id", $ClientAppId,
            "--scopes", $ControlScopes,
            "--tenant-profile", $TenantProfile,
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant profile already has control key; skipping issue-control-key."
    }
}

function New-WorkerHostManifest {
    $workers = [ordered]@{
        claudeCode = [ordered]@{
            enabled = $true
            port = $ClaudePort
            name = "$WorkerHostId Claude Code anchor"
            authTokenEnv = $WorkerTokenEnv
        }
        codex = [ordered]@{
            enabled = $true
            port = $CodexPort
            name = "$WorkerHostId Codex worker"
            authTokenEnv = $WorkerTokenEnv
            model = $ModelName
        }
        biz = [ordered]@{
            enabled = $true
            port = $BizPort
            workerId = "$WorkerHostId-biz"
            name = "$WorkerHostId Biz worker"
            version = "selftest"
        }
    }
    if ($BizIdentityTokenEnv) {
        $workers.biz.identityTokenEnv = $BizIdentityTokenEnv
    }
    return [ordered]@{
        workerHostId = $WorkerHostId
        hostUrl = $WorkerHostUrl
        port = $ClaudePort
        workers = $workers
    }
}

Push-Location $ProjectRoot
try {
    if (-not $WorkspaceRoot) {
        $WorkspaceRoot = $ProjectRoot
    }
    foreach ($path in @($SystemProfile, $TenantAProfile, $TenantBProfile, $WorkspaceRoot)) {
        Assert-NotAccountsPath -Path $path
    }
    if (-not (Test-Path -LiteralPath $Navi)) {
        throw "Navigator upstream CLI not found: $Navi"
    }
    if (-not (Test-Path -LiteralPath $WorkspaceRoot)) {
        throw "Workspace root not found: $WorkspaceRoot"
    }

    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    Write-JsonFile -Path $WorkerHostFile -Value (New-WorkerHostManifest)
    Write-TextFile -Path $VerifyProfile -Text "NAVI_BASE_URL=http://127.0.0.1:18080`nNAVI_UPSTREAM_SYSTEM_ID=navigator-provisioning-selftest`n"

    $directoryA = [ordered]@{
        workerId = ""
        path = $WorkspaceRoot
        projectName = "navigator-provisioning-selftest-a"
        workspaceScope = "CLIENT_APP_SHARED"
        resolverType = "MANAGED"
        rootRef = $WorkspaceRoot
        readOnly = $false
        allowedPathPrefixes = @($WorkspaceRoot)
        files = [ordered]@{
            "temp/navigator-provisioning-selftest/.directory-smoke.md" = "Navigator provisioning selftest workspace marker."
        }
        enabled = $true
    }
    Write-JsonFile -Path $DirectoryAFile -Value $directoryA

    $agentA = [ordered]@{
        agentCode = $AgentCodeA
        agentId = $AgentCodeA
        skillId = "$AgentCodeA-skill"
        name = "Navigator Provisioning Selftest Agent A"
        description = "Local Navigator provisioning selftest agent. Does not access real TMS."
        status = "ENABLED"
        workerId = "$WorkerHostId-biz"
        defaultModel = $ModelName
        contextVisibility = "isolated"
        markdownBody = "Use only local selftest fixtures. Do not access real TMS, accounts, secrets, cookies, or passwords."
        resources = @()
        functions = @()
        materialize = $true
    }
    Write-JsonFile -Path $AgentAFile -Value $agentA

    Invoke-Navi -Profile $VerifyProfile -NaviArgs @("worker-host", "verify", "--file", $WorkerHostFile)

    if ($PrepareOnly) {
        Write-Host "Prepare-only completed."
        Write-Host "Generated worker-host manifest: $WorkerHostFile"
        Write-Host "Generated directory manifest: $DirectoryAFile"
        Write-Host "Generated agent manifest: $AgentAFile"
        return
    }

    if (-not (Test-Path -LiteralPath $SystemProfile)) {
        throw "System profile not found: $SystemProfile"
    }
    if (-not (Get-ProfileValue -Path $SystemProfile -Name "NAVI_ADMIN_API_KEY")) {
        Invoke-Navi -Profile $SystemProfile -NaviArgs @("admin-key", "claim", "--write-profile")
    }
    else {
        Write-Host "System profile already has admin key; skipping claim."
    }

    $clientAppAId = Ensure-ClientApp -TenantId $TenantAId -UpstreamRef $UpstreamRefA -TenantProfile $TenantAProfile -Name "Navigator Provisioning Selftest A"
    Ensure-ClientCredentials -TenantProfile $TenantAProfile -ClientAppId $clientAppAId
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("runtime-token", "--write-profile")

    Ensure-WorkerSecretEnv -Name $WorkerTokenEnv -Purpose "claudeCode/codex worker-host apply"
    if ($BizIdentityTokenEnv) {
        Ensure-WorkerSecretEnv -Name $BizIdentityTokenEnv -Purpose "biz worker identity registration"
    }
    Invoke-Navi -Profile $SystemProfile -NaviArgs @(
        "worker-host", "apply",
        "--file", $WorkerHostFile,
        "--target-tenant-id", $TenantAId,
        "--write-profile"
    )

    $anchorWorkerId = Get-ProfileValue -Path $SystemProfile -Name "NAVI_WORKER_ID"
    if (-not $anchorWorkerId) {
        throw "NAVI_WORKER_ID was not written to $SystemProfile"
    }
    $bizWorkerId = Get-ProfileValue -Path $SystemProfile -Name "NAVI_BIZ_WORKER_ID"
    if (-not $bizWorkerId) {
        $bizWorkerId = "$WorkerHostId-biz"
    }

    if ($ForceModelCreate -or -not (Get-ProfileValue -Path $TenantAProfile -Name "NAVI_MODEL_CONFIG_ID")) {
        if ($SkipModelCreate) {
            throw "Tenant A profile has no NAVI_MODEL_CONFIG_ID and -SkipModelCreate was specified."
        }
        if (-not [Environment]::GetEnvironmentVariable($LlmApiKeyEnv)) {
            throw "$LlmApiKeyEnv is required for live model create. Set the environment variable or prefill NAVI_MODEL_CONFIG_ID in the tenant profile."
        }
        Invoke-Navi -Profile $TenantAProfile -NaviArgs @(
            "model", "create",
            "--name", "Navigator Provisioning Selftest LANGGRAPH_BIZ",
            "--model-base-url", $ModelBaseUrl,
            "--model-name", $ModelName,
            "--provider", "openai-compatible",
            "--api-key-env", $LlmApiKeyEnv,
            "--worker-backend", "LANGGRAPH_BIZ",
            "--available-models", $ModelName,
            "--set-default",
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant A profile already has NAVI_MODEL_CONFIG_ID; skipping model create."
    }

    $modelConfigId = Get-ProfileValue -Path $TenantAProfile -Name "NAVI_MODEL_CONFIG_ID"
    if (-not $modelConfigId) {
        throw "NAVI_MODEL_CONFIG_ID was not written to $TenantAProfile"
    }

    if ($ForceDirectoryInit -or -not (Get-ProfileValue -Path $TenantAProfile -Name "NAVI_DIRECTORY_ID")) {
        $directoryA.workerId = $anchorWorkerId
        Write-JsonFile -Path $DirectoryAFile -Value $directoryA
        Invoke-Navi -Profile $TenantAProfile -NaviArgs @(
            "directory", "client-init",
            "--file", $DirectoryAFile,
            "--write-profile"
        )
    }
    else {
        Write-Host "Tenant A profile already has NAVI_DIRECTORY_ID; skipping directory client-init."
    }

    $directoryId = Get-ProfileValue -Path $TenantAProfile -Name "NAVI_DIRECTORY_ID"
    if (-not $directoryId) {
        throw "NAVI_DIRECTORY_ID was not written to $TenantAProfile"
    }

    $agentA.workerId = $bizWorkerId
    $agentA.defaultModelConfigId = $modelConfigId
    Write-JsonFile -Path $AgentAFile -Value $agentA
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("agent", "sync", "--manifest", $AgentAFile)
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("agent", "set-default-model", "--agent-code", $AgentCodeA, "--model-config-id", $modelConfigId)
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("agent", "set-default-workspace", "--agent-code", $AgentCodeA, "--directory-id", $directoryId)

    $workerPoolId = Get-ProfileValue -Path $SystemProfile -Name "NAVI_WORKER_POOL_ID"
    if ($workerPoolId) {
        Invoke-Navi -Profile $TenantAProfile -NaviArgs @("agent", "set-default-worker", "--agent-code", $AgentCodeA, "--worker-pool-id", $workerPoolId)
    }

    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("ensure-grant", "--upstream-user-id", $UpstreamUserA)
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("verify-agent-readiness", "--agent-code", $AgentCodeA, "--upstream-user-id", $UpstreamUserA)
    Invoke-Navi -Profile $TenantAProfile -NaviArgs @("owner-smoke", "--agent-code", $AgentCodeA, "--upstream-user-id", $UpstreamUserA)

    if ($RunIsolationChecks) {
        $clientAppBId = Ensure-ClientApp -TenantId $TenantBId -UpstreamRef $UpstreamRefB -TenantProfile $TenantBProfile -Name "Navigator Provisioning Selftest B"
        Invoke-NaviExpectFailure -Profile $TenantAProfile -NaviArgs @(
            "model", "grants",
            "--client-app-id", $clientAppBId
        ) -FailureMessage "Isolation check failed: Tenant A control key listed Tenant B model grants."
    }

    if ($RunLiveAsk) {
        Invoke-Navi -Profile $TenantAProfile -NaviArgs @(
            "ask",
            "--agent-code", $AgentCodeA,
            "--upstream-user-id", $UpstreamUserA,
            "--directory-id", $directoryId,
            "--provider-type", "biz-worker",
            "--sandbox-mode", "workspace-write",
            "--approval-policy", "never",
            "--network-access-enabled", "false",
            "--web-search-mode", "disabled",
            "--max-turns", "1",
            "--message", "Run a local Navigator provisioning selftest. Report the effective directory id only. Do not access real TMS or accounts."
        )
    }

    Write-Host "Navigator provisioning selftest completed."
    Write-Host "Tenant A profile: $TenantAProfile"
}
finally {
    Pop-Location
}
