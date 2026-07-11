$ErrorActionPreference = 'Stop'

$ReleaseBaseUrl = "__RELEASE_BASE_URL__"
$Product = 'codex-app-server-worker'
$InstallDir = if ($env:CODEX_APP_SERVER_WORKER_HOME) {
    [IO.Path]::GetFullPath($env:CODEX_APP_SERVER_WORKER_HOME)
} else {
    [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.codex-app-server-worker'))
}

function Compare-SemVer {
    param([string]$Left, [string]$Right)
    $Pattern = '^(\d+)\.(\d+)\.(\d+)(?:-([^+]+))?(?:\+.*)?$'
    $LeftMatch = [regex]::Match($Left, $Pattern)
    $RightMatch = [regex]::Match($Right, $Pattern)
    if (-not $LeftMatch.Success -or -not $RightMatch.Success) { throw 'Installed or release version is not valid SemVer' }
    for ($Index = 1; $Index -le 3; $Index++) {
        $LeftPart = [int64]$LeftMatch.Groups[$Index].Value
        $RightPart = [int64]$RightMatch.Groups[$Index].Value
        if ($LeftPart -ne $RightPart) { return $(if ($LeftPart -lt $RightPart) { -1 } else { 1 }) }
    }
    $LeftPre = $LeftMatch.Groups[4].Value
    $RightPre = $RightMatch.Groups[4].Value
    if ($LeftPre -eq $RightPre) { return 0 }
    if (-not $LeftPre) { return 1 }
    if (-not $RightPre) { return -1 }
    $LeftIds = $LeftPre.Split('.')
    $RightIds = $RightPre.Split('.')
    for ($Index = 0; $Index -lt [Math]::Max($LeftIds.Count, $RightIds.Count); $Index++) {
        if ($Index -ge $LeftIds.Count) { return -1 }
        if ($Index -ge $RightIds.Count) { return 1 }
        if ($LeftIds[$Index] -eq $RightIds[$Index]) { continue }
        $LeftNumeric = $LeftIds[$Index] -match '^\d+$'
        $RightNumeric = $RightIds[$Index] -match '^\d+$'
        if ($LeftNumeric -and $RightNumeric) {
            $LeftNumber = $LeftIds[$Index].TrimStart('0'); if (-not $LeftNumber) { $LeftNumber = '0' }
            $RightNumber = $RightIds[$Index].TrimStart('0'); if (-not $RightNumber) { $RightNumber = '0' }
            if ($LeftNumber.Length -ne $RightNumber.Length) { return $(if ($LeftNumber.Length -lt $RightNumber.Length) { -1 } else { 1 }) }
            return $(if ([string]::CompareOrdinal($LeftNumber, $RightNumber) -lt 0) { -1 } else { 1 })
        }
        if ($LeftNumeric -ne $RightNumeric) { return $(if ($LeftNumeric) { -1 } else { 1 }) }
        return $(if ([string]::CompareOrdinal($LeftIds[$Index], $RightIds[$Index]) -lt 0) { -1 } else { 1 })
    }
    return 0
}

function Read-DotEnvValue {
    param([string]$Key)
    $EnvFile = Join-Path $InstallDir '.env'
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { return '' }
    $EscapedKey = [regex]::Escape($Key)
    $Match = @(Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match "^\s*$EscapedKey\s*=" } | Select-Object -Last 1)
    if ($Match.Count -eq 0) { return '' }
    $Value = ($Match[0] -replace "^\s*$EscapedKey\s*=", '').Trim()
    if ($Value.Length -ge 2 -and (($Value[0] -eq '"' -and $Value[-1] -eq '"') -or ($Value[0] -eq "'" -and $Value[-1] -eq "'"))) {
        return $Value.Substring(1, $Value.Length - 2)
    }
    return ([regex]::Replace($Value, '\s+#.*$', '')).Trim()
}

function Resolve-OperationPath {
    param([string]$Key, [string]$Fallback)
    $Value = [Environment]::GetEnvironmentVariable($Key)
    if (-not $Value) { $Value = Read-DotEnvValue $Key }
    if (-not $Value) { return [IO.Path]::GetFullPath($Fallback) }
    if (-not [IO.Path]::IsPathRooted($Value)) { throw "$Key must be an absolute path before install state can be verified" }
    return [IO.Path]::GetFullPath($Value)
}

if ($ReleaseBaseUrl -eq "__RELEASE_BASE_URL__" -or -not $ReleaseBaseUrl) {
    throw 'Release URL was not injected into install.ps1'
}
if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'Node.js and npm are required'
}

Write-Output 'Fetching Codex App Server Worker release metadata...'
$Latest = Invoke-RestMethod -Uri "$ReleaseBaseUrl/latest.json?ts=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" `
    -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 30
if ($Latest.schemaVersion -ne 1 -or $Latest.product -ne $Product) {
    throw 'latest.json has an unexpected product or schema'
}
$Version = [string]$Latest.version
$FilePath = [string]$Latest.files.windows
$ExpectedHash = ([string]$Latest.sha256.windows).ToLowerInvariant()
$ExpectedBytes = [long]$Latest.bytes.windows
if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$') {
    throw 'latest.json contains an invalid version'
}
$ExpectedPath = "$Version/$Product-$Version.zip"
if ($FilePath -ne $ExpectedPath -or $FilePath -match '[\\?#]' -or $FilePath.StartsWith('/')) {
    throw 'latest.json contains an unsafe or unexpected Windows artifact path'
}
if ($ExpectedHash -notmatch '^[0-9a-f]{64}$' -or $ExpectedBytes -le 0) {
    throw 'latest.json contains invalid archive integrity metadata'
}

$InstalledPackage = Join-Path $InstallDir 'package.json'
$ExistingIdentity = $null
$RepairInstall = $false
if (Test-Path -LiteralPath $InstalledPackage) {
    $ExistingIdentity = Get-Content -LiteralPath $InstalledPackage -Raw | ConvertFrom-Json
    if ($ExistingIdentity.name -ne $Product) { throw "Install directory belongs to another product: $InstallDir" }
    $InstalledVersion = [string]$ExistingIdentity.version
    $VersionComparison = Compare-SemVer -Left $InstalledVersion -Right $Version
    if ($VersionComparison -eq 0) {
        $VersionFile = Join-Path $InstallDir 'VERSION'
        if (-not (Test-Path -LiteralPath $VersionFile -PathType Leaf)) {
            throw 'Incomplete installation has no VERSION identity; refusing automatic repair'
        }
        $RecordedVersion = (Get-Content -LiteralPath $VersionFile -Raw).Trim()
        if ($RecordedVersion -ne $InstalledVersion) {
            throw 'package.json and VERSION identities disagree; refusing automatic repair'
        }
        $RunDir = Resolve-OperationPath 'CODEX_APP_SERVER_RUN_DIR' (Join-Path $InstallDir 'logs\run')
        $StateDir = Resolve-OperationPath 'CODEX_APP_SERVER_STATE_DIR' (Join-Path $InstallDir 'logs\state')
        $FailureEvidence = @(
            (Join-Path $InstallDir 'update.in-progress'),
            (Join-Path $InstallDir 'lifecycle.lock'),
            (Join-Path $RunDir 'stop.failed'),
            (Join-Path $StateDir 'lifecycle.failed')
        ) | Where-Object { Test-Path -LiteralPath $_ }
        if ($FailureEvidence.Count -gt 0) {
            throw 'Unresolved update or lifecycle failure evidence exists; follow the README manual recovery procedure before rerunning the installer'
        }
        $RequiredFiles = @(
            'VERSION', '.env', '.env.example', 'package-lock.json', 'dist\index.js',
            'start.ps1', 'stop.ps1', 'update.ps1', 'install.ps1',
            'scripts\configure-install-env.mjs', 'scripts\read-dotenv-value.mjs',
            'node_modules\@openai\codex\package.json'
        )
        $MissingFiles = @($RequiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $InstallDir $_) -PathType Leaf) })
        if ($MissingFiles.Count -eq 0) {
            Write-Output "Codex App Server Worker $Version is already installed and complete; nothing to do."
            return
        }
        $RepairInstall = $true
        Write-Output "Codex App Server Worker $Version is incomplete; repairing the installation from the published archive."
    }
    if ($VersionComparison -gt 0) {
        throw "Installed version $InstalledVersion is newer than published latest $Version; refusing downgrade"
    }
}

$TempDir = Join-Path ([IO.Path]::GetTempPath()) ("codex-app-server-worker-install-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
try {
    $Archive = Join-Path $TempDir "$Product-$Version.zip"
    Write-Output "Downloading Codex App Server Worker $Version..."
    Invoke-WebRequest -Uri "$ReleaseBaseUrl/$FilePath" -OutFile $Archive -TimeoutSec 300
    if ((Get-Item -LiteralPath $Archive).Length -ne $ExpectedBytes) {
        throw 'Downloaded archive size does not match latest.json'
    }
    $ActualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ActualHash -ne $ExpectedHash) {
        throw 'Downloaded archive SHA-256 does not match latest.json'
    }

    if ($ExistingIdentity -and -not $RepairInstall) {
        $Updater = Join-Path $InstallDir 'update.ps1'
        if (-not (Test-Path -LiteralPath $Updater -PathType Leaf)) {
            throw "Existing installation has no update.ps1: $InstallDir"
        }
        Write-Output "Updating existing installation at $InstallDir..."
        & powershell -NoProfile -ExecutionPolicy Bypass -File $Updater -Package $Archive -InstallDir $InstallDir
        if ($LASTEXITCODE -ne 0) { throw "Existing updater failed with exit code $LASTEXITCODE" }
    } else {
        $ExtractDir = Join-Path $TempDir 'extract'
        Expand-Archive -LiteralPath $Archive -DestinationPath $ExtractDir -Force
        $Candidates = @(Get-ChildItem -LiteralPath $ExtractDir -Directory | Where-Object {
            Test-Path -LiteralPath (Join-Path $_.FullName 'install.ps1') -PathType Leaf
        })
        if ($Candidates.Count -ne 1) { throw 'Release must contain exactly one installable root' }
        $CandidatePackage = Get-Content -LiteralPath (Join-Path $Candidates[0].FullName 'package.json') -Raw | ConvertFrom-Json
        if ($CandidatePackage.name -ne $Product -or $CandidatePackage.version -ne $Version) {
            throw 'Downloaded release identity does not match latest.json'
        }
        Write-Output "Installing into $InstallDir..."
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Candidates[0].FullName 'install.ps1') -InstallDir $InstallDir
        if ($LASTEXITCODE -ne 0) { throw "Bundled installer failed with exit code $LASTEXITCODE" }
    }
}
finally {
    Remove-Item -LiteralPath $TempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'Install/update complete. A fresh installation remains stopped until .env is configured and start.ps1 is run.'
