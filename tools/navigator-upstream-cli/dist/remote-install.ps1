# Navigator Upstream CLI remote bootstrap installer for Windows.
# Hosted remotely and run from an upstream project root:
#   irm <base-url>/install.ps1 | iex

$ErrorActionPreference = "Stop"

$ReleaseBaseUrl = "__RELEASE_BASE_URL__"

if ($ReleaseBaseUrl -eq "__RELEASE_BASE_URL__" -or -not $ReleaseBaseUrl) {
    throw "This installer has not been configured with RELEASE_BASE_URL."
}

Write-Host "=== Navigator Upstream CLI Remote Installer ===" -ForegroundColor Cyan
Write-Host "Release: $ReleaseBaseUrl" -ForegroundColor Cyan

$latest = Invoke-RestMethod -Uri "$ReleaseBaseUrl/latest.json" -TimeoutSec 20
$version = [string]$latest.version
if (-not $version) {
    throw "Could not parse version from latest.json"
}
$filePath = $latest.files.windows
if (-not $filePath) {
    throw "latest.json does not contain files.windows"
}

$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "navigator-upstream-cli-install"
if (Test-Path $tmpDir) {
    Remove-Item -LiteralPath $tmpDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$archive = Join-Path $tmpDir (Split-Path $filePath -Leaf)
$downloadUrl = "$ReleaseBaseUrl/$filePath"
Write-Host "Downloading $downloadUrl" -ForegroundColor Cyan
Invoke-WebRequest -Uri $downloadUrl -OutFile $archive

$expectedSha = $latest.sha256.windows
if ($expectedSha) {
    $actualSha = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
    if ($actualSha -ne ([string]$expectedSha).ToLowerInvariant()) {
        throw "SHA256 mismatch for downloaded archive"
    }
}

Expand-Archive -Path $archive -DestinationPath $tmpDir -Force
$installScript = Get-ChildItem -Path $tmpDir -Recurse -Filter "install.ps1" | Select-Object -First 1
if (-not $installScript) {
    throw "No install.ps1 found in archive"
}

& powershell -ExecutionPolicy Bypass -File $installScript.FullName `
    -ProjectRoot (Get-Location).Path `
    -ReleaseBaseUrl $ReleaseBaseUrl

Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
