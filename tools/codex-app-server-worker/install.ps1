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
    try {
        Copy-Item (Join-Path $InstallDir '.env.example') $EnvFile
        $DefaultRoots = @(
            [IO.DriveInfo]::GetDrives() | ForEach-Object {
                try {
                    if ($_.IsReady) {
                        $Root = $_.RootDirectory.FullName
                        if ($Root -match '^[A-Za-z]:\\$') {
                            $Root
                        }
                    }
                } catch {
                    # A drive may disappear between enumeration and the readiness check.
                }
            } | Sort-Object -Unique
        )
        $DefaultAllowedCwds = $DefaultRoots -join ','
        & node (Join-Path $InstallDir 'scripts\configure-install-env.mjs') $EnvFile $DefaultAllowedCwds
        if ($LASTEXITCODE -ne 0) { throw 'Failed to configure fresh-install environment' }
        $CodexHome = [IO.Path]::GetFullPath((Join-Path $InstallDir 'codex-home'))
        if ($DefaultAllowedCwds) {
            Write-Output "Created $EnvFile with all ready drive roots: $DefaultAllowedCwds"
            Write-Warning 'The cwd allowlist is an admission check, not a filesystem sandbox. Fresh installs allow every directory on every drive that was ready during installation.'
        } else {
            Write-Warning "Created $EnvFile without workspace roots because no ready drive roots were found. Configure CODEX_APP_SERVER_ALLOWED_CWDS before start."
        }
        Write-Output "Generated a persistent state key and isolated CODEX_HOME at $CodexHome"
        Write-Output 'Worker token and OPENAI_API_KEY remain empty; Navigator ModelConfig may supply model credentials.'
    } catch {
        Remove-Item -LiteralPath $EnvFile -Force -ErrorAction SilentlyContinue
        throw
    }
}
Write-Output "codex-app-server-worker installed at $([IO.Path]::GetFullPath($InstallDir))"
