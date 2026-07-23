# Navigator Upstream CLI package script.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1 -Upload

param(
    [switch]$Upload,
    [switch]$AllowSameVersion
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Get-Sha256 {
    param([string]$Path)
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([System.BitConverter]::ToString($hasher.ComputeHash($stream))).Replace("-", "").ToLowerInvariant()
        }
        finally {
            $hasher.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Convert-ToWslPath {
    param([string]$Path)
    if ($Path -match '^([A-Za-z]):\\(.*)$') {
        $drive = $Matches[1].ToLowerInvariant()
        $rest = $Matches[2] -replace '\\', '/'
        return "/mnt/$drive/$rest"
    }
    return $null
}

function Get-GitMetadata {
    param([string]$RepoRoot)

    try {
        $commitLines = @(& git -C $RepoRoot rev-parse HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $commitLines.Count -gt 0) {
            $branchLines = @(& git -C $RepoRoot rev-parse --abbrev-ref HEAD 2>$null)
            $statusLines = @(& git -C $RepoRoot status --porcelain 2>$null)
            return @{
                commit = ([string]$commitLines[0]).Trim()
                branch = if ($branchLines.Count -gt 0) { ([string]$branchLines[0]).Trim() } else { "" }
                dirty = [bool]($statusLines -join "")
            }
        }
    }
    catch {
        # Try WSL below. This is needed for worktrees whose .git file contains a Linux path.
    }

    $wslRepoRoot = Convert-ToWslPath -Path $RepoRoot
    if (-not $wslRepoRoot -or -not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        return $null
    }

    $distroLines = @(& wsl.exe --list --quiet 2>$null)
    foreach ($rawDistro in $distroLines) {
        $distro = ([string]$rawDistro).Replace([string][char]0, "").Trim()
        if (-not $distro) { continue }

        try {
            $commitLines = @(& wsl.exe -d $distro -- git -C $wslRepoRoot rev-parse HEAD 2>$null)
            if ($LASTEXITCODE -ne 0 -or $commitLines.Count -eq 0) { continue }

            $branchLines = @(& wsl.exe -d $distro -- git -C $wslRepoRoot rev-parse --abbrev-ref HEAD 2>$null)
            $statusLines = @(& wsl.exe -d $distro -- git -C $wslRepoRoot status --porcelain 2>$null)
        }
        catch {
            continue
        }
        return @{
            commit = ([string]$commitLines[0]).Trim()
            branch = if ($branchLines.Count -gt 0) { ([string]$branchLines[0]).Trim() } else { "" }
            dirty = [bool]($statusLines -join "")
        }
    }

    return $null
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolDir = Split-Path -Parent $ScriptDir
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ToolDir)
$SdkDir = Join-Path $RepoRoot "navigator-open-sdk"
$PomPath = Join-Path $SdkDir "pom.xml"

[xml]$pom = Get-Content $PomPath
$version = $pom.project.version
if (-not $version) {
    throw "Could not read navigator-open-sdk version from $PomPath"
}

Write-Host "=== Navigator Upstream CLI Packager ===" -ForegroundColor Cyan
Write-Host "Version: $version"
Write-Host "Repo:    $RepoRoot"

$buildTimeUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$gitCommit = ""
$gitBranch = ""
$gitDirty = $false
$gitMetadata = Get-GitMetadata -RepoRoot $RepoRoot
if ($gitMetadata) {
    $gitCommit = [string]$gitMetadata.commit
    $gitBranch = [string]$gitMetadata.branch
    $gitDirty = [bool]$gitMetadata.dirty
}
elseif ($Upload) {
    throw "Could not resolve git metadata. Refusing to publish an untraceable release."
}
else {
    Write-Host "Could not resolve git metadata; local package will not be traceable." -ForegroundColor Yellow
}
if ($Upload -and $gitDirty) {
    throw "Refusing to publish from a dirty git worktree."
}
$features = @(
    "config-check",
    "auth-login",
    "runtime-token",
    "owner-smoke",
    "agent-readiness",
    "ask",
    "safe-ask",
    "runtime-request-audit",
    "safe-ask-client-request-correlation",
    "runtime-audit-no-task-id",
    "messages",
    "sessions",
    "skill-artifact-read",
    "skill-sync",
    "skill-clear",
    "agent-sync",
    "function-import",
    "function-grant",
    "function-grant-status",
    "function-visible",
    "upstream-route",
    "model-grant",
    "model-owned-config",
    "model-subscription-config",
    "codex-app-server-model-config",
    "gpt-5.6-model-catalog",
    "model-connection-test",
    "model-system-audit",
    "model-variant",
    "runtime-budget-preset",
    "account-context",
    "deterministic-e2e",
    "admin-key-bootstrap",
    "client-app-bootstrap",
    "client-app-runtime-credential",
    "system-admin-clientapp-scope",
    "upstream-worker-orchestration",
    "upstream-directory-orchestration",
    "upstream-worker-pool-orchestration",
    "task-diagnostics",
    "session-directory-diagnostics",
    "task-evidence",
    "message-event-contract",
    "physical-worker-diagnostics",
    "worker-host-suite",
    "navi-routed-codex-config",
    "codex-biz-worker-route",
    "codex-biz-runtime-options",
    "ask-allowed-tools",
    "ask-allowed-functions",
    "runtime-profile-posix-0600",
    "ask-directory-actionable-error"
)

Push-Location $RepoRoot
try {
    mvn -q -pl navigator-open-sdk -DskipTests package dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=target/dependency"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed"
    }
}
finally {
    Pop-Location
}

$stagingRoot = Join-Path $ScriptDir "staging"
$stageDir = Join-Path $stagingRoot "navigator-upstream"
if (Test-Path $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $stageDir "lib") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stageDir "bin") | Out-Null

Copy-Item -LiteralPath (Join-Path $SdkDir "target\navigator-open-sdk-$version.jar") -Destination (Join-Path $stageDir "lib") -Force
Copy-Item -Path (Join-Path $SdkDir "target\dependency\*.jar") -Destination (Join-Path $stageDir "lib") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi.ps1") -Destination (Join-Path $stageDir "navi.ps1") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi.cmd") -Destination (Join-Path $stageDir "navi.cmd") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi-e2e.ps1") -Destination (Join-Path $stageDir "navi-e2e.ps1") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi-e2e.cmd") -Destination (Join-Path $stageDir "navi-e2e.cmd") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi.ps1") -Destination (Join-Path $stageDir "bin\navi.ps1") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi.cmd") -Destination (Join-Path $stageDir "bin\navi.cmd") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi-e2e.ps1") -Destination (Join-Path $stageDir "bin\navi-e2e.ps1") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "bin\navi-e2e.cmd") -Destination (Join-Path $stageDir "bin\navi-e2e.cmd") -Force
Copy-Item -LiteralPath (Join-Path $ScriptDir "install.ps1") -Destination $stageDir -Force
Write-Utf8NoBom -Path (Join-Path $stageDir "VERSION") -Content $version
$buildInfo = [ordered]@{
    version = $version
    buildTimeUtc = $buildTimeUtc
    gitCommit = $gitCommit
    gitBranch = $gitBranch
    gitDirty = $gitDirty
    features = $features
} | ConvertTo-Json -Depth 5
Write-Utf8NoBom -Path (Join-Path $stageDir "BUILD_INFO.json") -Content $buildInfo

$outputDir = Join-Path $ScriptDir "output"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
Write-Utf8NoBom -Path (Join-Path $outputDir "BUILD_INFO.json") -Content $buildInfo
$archiveName = "navigator-upstream-cli-$version-windows.zip"
$archivePath = Join-Path $outputDir $archiveName
if (Test-Path $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}
Compress-Archive -Path "$stagingRoot\*" -DestinationPath $archivePath -Force
$sha = Get-Sha256 -Path $archivePath
Write-Utf8NoBom -Path (Join-Path $outputDir "$archiveName.sha256") -Content "$sha  $archiveName"

$shortCommit = if ($gitCommit.Length -ge 12) { $gitCommit.Substring(0, 12) } else { $gitCommit }
$buildId = if ($shortCommit) { "$version+$shortCommit" } else { "$version+$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))" }
if ($gitDirty) {
    $buildId = "$buildId.dirty"
}
$releaseManifest = [ordered]@{
    version = $version
    released = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
    buildTimeUtc = $buildTimeUtc
    buildId = $buildId
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    features = $features
    files = @{
        windows = "$version/$archiveName"
    }
    sha256 = @{
        windows = $sha
    }
} | ConvertTo-Json -Depth 5
Write-Utf8NoBom -Path (Join-Path $outputDir "RELEASE_MANIFEST.json") -Content $releaseManifest

Remove-Item -LiteralPath $stagingRoot -Recurse -Force

Write-Host "Archive: $archivePath" -ForegroundColor Green
Write-Host "SHA256:  $sha" -ForegroundColor Green
Write-Host "Release manifest: $(Join-Path $outputDir 'RELEASE_MANIFEST.json')" -ForegroundColor Green

if ($Upload) {
    if ($AllowSameVersion) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "upload.ps1") -Version $version -AllowSameVersion
    }
    else {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "upload.ps1") -Version $version
    }
    if ($LASTEXITCODE -ne 0) {
        throw "CLI upload failed"
    }
    & powershell -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "package-skill.ps1") -Version $version -Upload
    if ($LASTEXITCODE -ne 0) {
        throw "Skill upload failed"
    }
}
