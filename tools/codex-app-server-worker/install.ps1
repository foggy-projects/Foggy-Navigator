param([string]$InstallDir = '')

$ErrorActionPreference = 'Stop'
$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $InstallDir) {
    $InstallDir = if ($env:CODEX_APP_SERVER_WORKER_HOME) { $env:CODEX_APP_SERVER_WORKER_HOME } else { Join-Path $env:USERPROFILE '.codex-app-server-worker' }
}
& powershell -ExecutionPolicy Bypass -File (Join-Path $SourceDir 'update.ps1') -Package $SourceDir -InstallDir $InstallDir -NoRestart
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$EnvFile = Join-Path $InstallDir '.env'
if (-not (Test-Path $EnvFile)) {
    Copy-Item (Join-Path $InstallDir '.env.example') $EnvFile
    Write-Output "Created $EnvFile; configure required secrets and isolation paths before start."
}
Write-Output "codex-app-server-worker installed at $([IO.Path]::GetFullPath($InstallDir))"
