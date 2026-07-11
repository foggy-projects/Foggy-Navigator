param(
    [switch]$Upload,
    [switch]$SkipVerify,
    [switch]$AllowSameVersion,
    [string]$OutputDir = 'release/output'
)

$ErrorActionPreference = 'Stop'
$WorkerDir = Split-Path -Parent $PSScriptRoot
$Arguments = @((Join-Path $WorkerDir 'scripts\package-release.mjs'), '--output-dir', $OutputDir)
if ($SkipVerify) { $Arguments += '--skip-verify' }
if ($Upload) { $Arguments += '--upload' }
if ($AllowSameVersion) { $Arguments += '--allow-same-version' }

& node @Arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
