# Navigator Upstream CLI package script.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1
#   powershell -ExecutionPolicy Bypass -File tools\navigator-upstream-cli\dist\package.ps1 -Upload

param(
    [switch]$Upload
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
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

$outputDir = Join-Path $ScriptDir "output"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$archiveName = "navigator-upstream-cli-$version-windows.zip"
$archivePath = Join-Path $outputDir $archiveName
if (Test-Path $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}
Compress-Archive -Path "$stagingRoot\*" -DestinationPath $archivePath -Force
$sha = (Get-FileHash -Algorithm SHA256 -Path $archivePath).Hash.ToLowerInvariant()
Write-Utf8NoBom -Path (Join-Path $outputDir "$archiveName.sha256") -Content "$sha  $archiveName"

Remove-Item -LiteralPath $stagingRoot -Recurse -Force

Write-Host "Archive: $archivePath" -ForegroundColor Green
Write-Host "SHA256:  $sha" -ForegroundColor Green

if ($Upload) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "upload.ps1") -Version $version
}
