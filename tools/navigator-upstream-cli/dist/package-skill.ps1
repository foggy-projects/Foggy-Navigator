# Navigator Upstream CLI skill package/upload script.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package-skill.ps1
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package-skill.ps1 -Upload
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package-skill.ps1 -Version 1.0.19 -Upload

param(
    [string]$Version = "",
    [switch]$Upload
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $Content = $Content -replace "`r`n", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Read-ReleaseConfig {
    param([string]$DotEnv)

    if (-not (Test-Path $DotEnv)) {
        throw ".env not found. Copy tools/navigator-upstream-cli/.env.example to .env and configure RELEASE_OBS_BUCKET / RELEASE_BASE_URL."
    }

    $config = @{
        Bucket = ""
        BaseUrl = ""
    }
    Get-Content $DotEnv | ForEach-Object {
        if ($_ -match "^RELEASE_OBS_BUCKET=(.+)") { $config.Bucket = $Matches[1].Trim() }
        if ($_ -match "^RELEASE_BASE_URL=(.+)") { $config.BaseUrl = $Matches[1].Trim() }
    }
    if (-not $config.Bucket -or -not $config.BaseUrl) {
        throw "RELEASE_OBS_BUCKET and RELEASE_BASE_URL are required in $DotEnv"
    }
    return $config
}

function Get-ObsUtilPath {
    $obsUtil = (Get-Command obsutil -ErrorAction SilentlyContinue).Source
    if ($obsUtil) {
        return $obsUtil
    }

    foreach ($candidate in @(
        "D:\work\obsutil_windows_amd64_5.7.9\obsutil.exe",
        "C:\Windows\obsutil.exe",
        "$env:USERPROFILE\obsutil\obsutil.exe"
    )) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "obsutil not found in PATH"
}

function Get-RelativeSlashPath {
    param([string]$Root, [string]$Path)

    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    if (-not $pathFull.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside root. root=$rootFull path=$pathFull"
    }
    return $pathFull.Substring($rootFull.Length + 1).Replace("\", "/")
}

function Get-WebContentString {
    param([string]$Uri)

    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers @{ "Cache-Control" = "no-cache" } -TimeoutSec 30
    if ($response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$response.Content)
    }
    return [string]$response.Content
}

function Test-RemoteSkill {
    param(
        [string]$BaseUrl,
        [string]$ExpectedVersion,
        [string]$SkillName,
        [string]$ArchiveName
    )

    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $skillUrl = "$BaseUrl/skills/latest/$SkillName/SKILL.md?ts=$ts"
    $skillContent = Get-WebContentString -Uri $skillUrl
    if ($skillContent -notmatch "Navigator Upstream CLI" -or $skillContent -notmatch "worker-setup-handoff\.md") {
        throw "remote SKILL.md smoke failed: missing expected Navigator worker setup guidance"
    }

    $manifest = Invoke-RestMethod -Uri "$BaseUrl/skills/latest.json?ts=$ts" -Headers @{ "Cache-Control" = "no-cache" } -TimeoutSec 30
    if ([string]$manifest.version -ne $ExpectedVersion -or -not $manifest.sha256) {
        throw "remote skill latest.json smoke failed"
    }

    $archiveHead = Invoke-WebRequest `
        -UseBasicParsing `
        -Method Head `
        -Uri "$BaseUrl/skills/$ExpectedVersion/$ArchiveName" `
        -Headers @{ "Cache-Control" = "no-cache" } `
        -TimeoutSec 30
    if ($archiveHead.StatusCode -lt 200 -or $archiveHead.StatusCode -ge 300) {
        throw "remote skill archive HEAD failed: $($archiveHead.StatusCode)"
    }

    Write-Host "Remote skill smoke passed." -ForegroundColor Green
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolDir = Split-Path -Parent $ScriptDir
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ToolDir)
$DotEnv = Join-Path $ToolDir ".env"
$config = Read-ReleaseConfig -DotEnv $DotEnv

if (-not $Version) {
    $pomPath = Join-Path $RepoRoot "navigator-open-sdk\pom.xml"
    [xml]$pom = Get-Content $pomPath
    $Version = $pom.project.version
    if (-not $Version) {
        throw "Could not read navigator-open-sdk version from $pomPath"
    }
}

$skillName = "navigator-upstream-cli"
$sourceDir = Join-Path $RepoRoot "docs\skills\$skillName"
if (-not (Test-Path (Join-Path $sourceDir "SKILL.md"))) {
    throw "Skill source not found: $sourceDir"
}

Write-Host "=== Navigator Upstream CLI Skill Packager ===" -ForegroundColor Cyan
Write-Host "Version:  $Version"
Write-Host "Source:   $sourceDir"
Write-Host "Base URL: $($config.BaseUrl)"

$outputDir = Join-Path $ScriptDir "output\skills"
$stageRoot = Join-Path $outputDir "staging"
$stageSkill = Join-Path $stageRoot $skillName
if (Test-Path $stageRoot) {
    Remove-Item -LiteralPath $stageRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stageSkill | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceDir "SKILL.md") -Destination $stageSkill -Force
Copy-Item -LiteralPath (Join-Path $sourceDir "agents") -Destination $stageSkill -Recurse -Force
Copy-Item -LiteralPath (Join-Path $sourceDir "references") -Destination $stageSkill -Recurse -Force

$archiveName = "$skillName-skill-$Version.zip"
$archivePath = Join-Path $outputDir $archiveName
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
if (Test-Path $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}
Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $archivePath -Force
$sha = (Get-FileHash -Algorithm SHA256 -Path $archivePath).Hash.ToLowerInvariant()

$files = Get-ChildItem -LiteralPath $stageSkill -Recurse -File |
    ForEach-Object { Get-RelativeSlashPath -Root $stageSkill -Path $_.FullName } |
    Sort-Object

$manifest = [ordered]@{
    name = $skillName
    version = $Version
    released = (Get-Date -Format "yyyy-MM-dd")
    skillPath = "skills/latest/$skillName/SKILL.md"
    skillUrl = "$($config.BaseUrl)/skills/latest/$skillName/SKILL.md"
    versionedSkillPath = "skills/$Version/$skillName/SKILL.md"
    versionedSkillUrl = "$($config.BaseUrl)/skills/$Version/$skillName/SKILL.md"
    archivePath = "skills/$Version/$archiveName"
    archiveUrl = "$($config.BaseUrl)/skills/$Version/$archiveName"
    sha256 = $sha
    files = $files
} | ConvertTo-Json -Depth 5

$manifestPath = Join-Path $outputDir "latest.json"
Write-Utf8NoBom -Path $manifestPath -Content $manifest

Write-Host "Archive: $archivePath" -ForegroundColor Green
Write-Host "SHA256:  $sha" -ForegroundColor Green

if ($Upload) {
    $obsUtil = Get-ObsUtilPath
    $obsBucket = $config.Bucket
    $baseUrl = $config.BaseUrl

    Write-Host "Uploading skill archive and files to $obsBucket" -ForegroundColor Cyan
    & $obsUtil cp $archivePath "$obsBucket/skills/$Version/$archiveName" -f
    if ($LASTEXITCODE -ne 0) { throw "Failed to upload skill archive" }

    & $obsUtil cp $manifestPath "$obsBucket/skills/latest.json" -f
    if ($LASTEXITCODE -ne 0) { throw "Failed to upload skill latest.json" }

    foreach ($file in Get-ChildItem -LiteralPath $stageSkill -Recurse -File) {
        $rel = Get-RelativeSlashPath -Root $stageSkill -Path $file.FullName
        & $obsUtil cp $file.FullName "$obsBucket/skills/$Version/$skillName/$rel" -f
        if ($LASTEXITCODE -ne 0) { throw "Failed to upload versioned skill file: $rel" }
        & $obsUtil cp $file.FullName "$obsBucket/skills/latest/$skillName/$rel" -f
        if ($LASTEXITCODE -ne 0) { throw "Failed to upload latest skill file: $rel" }
    }

    Test-RemoteSkill -BaseUrl $baseUrl -ExpectedVersion $Version -SkillName $skillName -ArchiveName $archiveName

    Write-Host ""
    Write-Host "Skill upload complete." -ForegroundColor Green
    Write-Host "Skill link:" -ForegroundColor Cyan
    Write-Host "  $baseUrl/skills/latest/$skillName/SKILL.md"
    Write-Host "Archive:" -ForegroundColor Cyan
    Write-Host "  $baseUrl/skills/$Version/$archiveName"
}
