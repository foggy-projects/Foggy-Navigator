$ErrorActionPreference = "Stop"

$ReleaseBaseUrl = "__RELEASE_BASE_URL__"

if ($ReleaseBaseUrl -eq "__RELEASE_BASE_URL__" -or -not $ReleaseBaseUrl) {
    throw "release URL was not injected into install.ps1"
}

Write-Host "Fetching LangGraph BizWorker release metadata..." -ForegroundColor Cyan
$latest = Invoke-RestMethod -Uri "$ReleaseBaseUrl/latest.json" -TimeoutSec 30
$version = $latest.version
if (-not $version) {
    throw "Could not parse version from latest.json"
}

$filePath = $latest.files.windows
if (-not $filePath) {
    throw "No Windows release found in latest.json"
}

$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("langgraph-biz-worker-install-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

try {
    $archive = Join-Path $tmpDir (Split-Path $filePath -Leaf)
    Write-Host "Downloading LangGraph BizWorker $version (windows)..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "$ReleaseBaseUrl/$filePath" -OutFile $archive

    Expand-Archive -Path $archive -DestinationPath $tmpDir -Force
    $installScript = Get-ChildItem -Path $tmpDir -Recurse -Filter install.ps1 | Select-Object -First 1
    if (-not $installScript) {
        throw "install.ps1 not found in archive"
    }

    & powershell -ExecutionPolicy Bypass -File $installScript.FullName
}
finally {
    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
