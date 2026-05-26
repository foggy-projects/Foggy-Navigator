# Navigator E2E CLI wrapper for Windows.
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$CliArgs
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallDir = $ScriptDir
if ((Split-Path -Leaf $ScriptDir) -eq "bin") {
    $InstallDir = Split-Path -Parent $ScriptDir
}

function Get-ProjectRoot {
    if ($env:NAVI_UPSTREAM_PROJECT_ROOT) {
        return (Resolve-Path $env:NAVI_UPSTREAM_PROJECT_ROOT).Path
    }
    $toolsDir = Split-Path -Parent $InstallDir
    if ((Split-Path -Leaf $toolsDir) -eq "tools") {
        return (Split-Path -Parent $toolsDir)
    }
    return (Get-Location).Path
}

function Has-Option {
    param([string[]]$Values, [string]$Name)
    foreach ($value in $Values) {
        if ($value -eq $Name -or $value.StartsWith("$Name=")) {
            return $true
        }
    }
    return $false
}

$libDir = Join-Path $InstallDir "lib"
if (-not (Test-Path $libDir)) {
    Write-Host "lib directory not found: $libDir" -ForegroundColor Red
    exit 1
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "java not found in PATH. Install JDK 17+ first." -ForegroundColor Red
    exit 1
}

$projectRoot = Get-ProjectRoot
$profile = Join-Path $projectRoot ".navigator\upstream.env"
$effectiveArgs = @($CliArgs)
if (-not (Has-Option -Values $effectiveArgs -Name "--profile")) {
    $effectiveArgs += @("--profile", $profile)
}

$classpath = (Get-ChildItem -Path $libDir -Filter "*.jar" -File | ForEach-Object { $_.FullName }) -join [System.IO.Path]::PathSeparator
if (-not $classpath) {
    Write-Host "No jar files found in $libDir" -ForegroundColor Red
    exit 1
}

Push-Location $projectRoot
try {
    & java -cp $classpath com.foggy.navigator.sdk.cli.E2eCli @effectiveArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
