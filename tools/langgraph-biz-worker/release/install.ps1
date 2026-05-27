$ErrorActionPreference = "Stop"

$InstallDir = if ($env:LANGGRAPH_BIZ_WORKER_HOME) {
    $env:LANGGRAPH_BIZ_WORKER_HOME
} else {
    Join-Path $HOME ".langgraph-biz-worker"
}
$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Installing LangGraph BizWorker to $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

Get-ChildItem -Path $SourceDir -Force | Where-Object {
    $_.Name -notin @(".env", ".venv")
} | ForEach-Object {
    $target = Join-Path $InstallDir $_.Name
    if (Test-Path $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
}

Push-Location $InstallDir
try {
    if (-not (Test-Path ".env")) {
        Copy-Item ".env.example" ".env"
    }

    $envContent = Get-Content ".env" -Raw
    if ($envContent -notmatch "(?m)^BIZ_WORKER_ENABLE_COMMAND=") {
        Add-Content ".env" "`nBIZ_WORKER_ENABLE_COMMAND=true"
    }

    $python = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $python) {
        $python = (Get-Command py -ErrorAction SilentlyContinue).Source
    }
    if (-not $python) {
        throw "Python was not found. Install Python >=3.10 and rerun the installer."
    }

    if ((Split-Path $python -Leaf) -eq "py.exe") {
        & $python -3 -m venv .venv
    } else {
        & $python -m venv .venv
    }
    if ($LASTEXITCODE -ne 0) { throw "Failed to create virtualenv." }

    $venvPython = Join-Path $InstallDir ".venv\Scripts\python.exe"
    & $venvPython -m pip install --upgrade pip
    if ($LASTEXITCODE -ne 0) { throw "Failed to upgrade pip." }
    & $venvPython -m pip install -e .
    if ($LASTEXITCODE -ne 0) { throw "Failed to install LangGraph BizWorker." }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "LangGraph BizWorker installed." -ForegroundColor Green
Write-Host "Edit $InstallDir\.env with LLM settings, then start it with:" -ForegroundColor Cyan
Write-Host "  cd $InstallDir"
Write-Host "  .\.venv\Scripts\python.exe -m uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port 3065"
Write-Host ""
Write-Host "Note: the real command tool is Linux-only in the current BizWorker release. Use WSL or Linux for command-enabled trials." -ForegroundColor Yellow
