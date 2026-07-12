param(
    [switch]$AllowSameVersion,
    [switch]$AllowDirty,
    [switch]$AllowUnpushed,
    [string]$OutputDir = 'release/output'
)

$ErrorActionPreference = 'Stop'
$WorkerDir = Split-Path -Parent $PSScriptRoot
$Arguments = @((Join-Path $WorkerDir 'scripts\publish-obs.mjs'), '--output-dir', $OutputDir)
if ($AllowSameVersion) { $Arguments += '--allow-same-version' }
if ($AllowDirty) { $Arguments += '--allow-dirty' }
if ($AllowUnpushed) { $Arguments += '--allow-unpushed' }
& node @Arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
