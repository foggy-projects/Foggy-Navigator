param(
    [ValidateSet('auto', 'windows', 'linux', 'macos', 'all')]
    [string]$OS = 'all',
    [ValidateSet('none', 'patch', 'minor', 'major')]
    [string]$Bump = 'none',
    [string]$Version = '',
    [ValidateSet('auto', 'skip', 'basic', 'full')]
    [string]$Smoke = 'auto',
    [switch]$SkipVerify,
    [switch]$Upload,
    [switch]$AllowSameVersion,
    [switch]$AllowDirty,
    [switch]$AllowUnpushed
)

$ErrorActionPreference = 'Stop'
$WorkerDir = Split-Path -Parent $PSScriptRoot

if ($Version -and $Bump -ne 'none') { throw 'Use either -Version or -Bump, not both.' }
Push-Location $WorkerDir
try {
    if ($Version) { & npm version $Version --no-git-tag-version }
    elseif ($Bump -ne 'none') { & npm version $Bump --no-git-tag-version }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $Platform = if ($OS -eq 'auto') { 'current' } else { $OS }
    $Arguments = @('scripts/package-release.mjs', '--platform', $Platform, '--smoke', $Smoke)
    if ($SkipVerify) { $Arguments += '--skip-verify' }
    if ($Upload) { $Arguments += '--upload' }
    if ($AllowSameVersion) { $Arguments += '--allow-same-version' }
    if ($AllowDirty) { $Arguments += '--allow-dirty' }
    if ($AllowUnpushed) { $Arguments += '--allow-unpushed' }
    & node @Arguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}
