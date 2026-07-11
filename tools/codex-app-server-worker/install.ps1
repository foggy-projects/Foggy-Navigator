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
                        if ($Root -match '^[A-Za-z]:\\$' -and -not $Root.Equals('C:\', [StringComparison]::OrdinalIgnoreCase)) {
                            $Root
                        }
                    }
                } catch {
                    # A drive may disappear between enumeration and the readiness check.
                }
            } | Sort-Object -Unique
        )
        $DefaultAllowedCwds = $DefaultRoots -join ','
        if ($DefaultAllowedCwds) {
            & node (Join-Path $InstallDir 'scripts\configure-install-env.mjs') $EnvFile $DefaultAllowedCwds
            if ($LASTEXITCODE -ne 0) { throw 'Failed to configure default workspace roots' }
            Write-Output "Created $EnvFile with non-C workspace roots: $DefaultAllowedCwds"
            Write-Warning 'The cwd allowlist is an admission check, not a filesystem sandbox. danger-full-access tasks can access paths outside these roots, including C:\.'
        } else {
            Write-Warning "Created $EnvFile without workspace roots because no ready non-C drives were found. Configure CODEX_APP_SERVER_ALLOWED_CWDS before start."
        }
        Write-Output 'Configure required secrets and an isolated CODEX_HOME before start.'
    } catch {
        Remove-Item -LiteralPath $EnvFile -Force -ErrorAction SilentlyContinue
        throw
    }
}
Write-Output "codex-app-server-worker installed at $([IO.Path]::GetFullPath($InstallDir))"
