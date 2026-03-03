<#
.SYNOPSIS
    Foggy Navigator wgt 热更新一键发版

.DESCRIPTION
    流程:
    1. 输入新版本号 + 更新内容
    2. 更新 manifest.json 和 package.json
    3. 构建 wgt 包
    4. 上传到 uni-admin 升级中心并上线

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/release.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ManifestPath = Join-Path $ProjectRoot "src\manifest.json"
$PackagePath = Join-Path $ProjectRoot "package.json"
$ScriptDir = Join-Path $ProjectRoot "scripts"

# --- 读取当前版本 ---
$manifest = Get-Content $ManifestPath -Raw | ConvertFrom-Json
$currentVersion = $manifest.versionName
$currentVersionCode = [int]$manifest.versionCode

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Foggy Navigator - wgt 热更新发版" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "当前版本 (本地): v$currentVersion (code: $currentVersionCode)" -ForegroundColor Yellow

# --- 查询线上最新版本 ---
$apiScript = Join-Path $ScriptDir "uni-admin-api.js"
$serverVersion = $null
Write-Host "正在查询线上最新版本..." -ForegroundColor Gray
try {
    $serverJson = node $apiScript latest-version 2>$null | Select-Object -Last 1
    if ($serverJson) {
        $serverInfo = $serverJson | ConvertFrom-Json
        if ($serverInfo -and $serverInfo.version) {
            $serverVersion = $serverInfo.version
        }
    }
} catch {
    Write-Host "  查询线上版本失败: $_" -ForegroundColor Yellow
    Write-Host "  将使用本地版本递增" -ForegroundColor Yellow
}

if ($serverVersion) {
    Write-Host "线上最新版本:     v$serverVersion" -ForegroundColor Yellow
} else {
    Write-Host "线上无版本记录" -ForegroundColor Yellow
}

# --- 计算建议版本号 (取 max(本地, 服务器) 的 patch+1) ---
function Parse-SemVer($ver) {
    # Strip pre-release/build metadata (e.g. 1.2.3-beta+build)
    $ver = ($ver -split '-')[0]
    $ver = ($ver -split '\+')[0]
    $parts = $ver -split '\.'
    if ($parts.Count -lt 3) {
        throw "Invalid semver format: $ver (expected X.Y.Z)"
    }
    return @{
        Major = [int]$parts[0]
        Minor = [int]$parts[1]
        Patch = [int]$parts[2]
    }
}

function Compare-SemVer($a, $b) {
    # 返回较大版本的字符串
    $pa = Parse-SemVer $a
    $pb = Parse-SemVer $b
    if ($pa.Major -ne $pb.Major) { return $(if ($pa.Major -gt $pb.Major) { $a } else { $b }) }
    if ($pa.Minor -ne $pb.Minor) { return $(if ($pa.Minor -gt $pb.Minor) { $a } else { $b }) }
    if ($pa.Patch -ge $pb.Patch) { return $a } else { return $b }
}

$baseVersion = $currentVersion
if ($serverVersion) {
    $baseVersion = Compare-SemVer $currentVersion $serverVersion
}
$baseParts = Parse-SemVer $baseVersion
$suggestedVersion = "$($baseParts.Major).$($baseParts.Minor).$($baseParts.Patch + 1)"

Write-Host ""
Write-Host "建议版本号: v$suggestedVersion" -ForegroundColor Green

# --- 输入新版本号 ---
$newVersion = Read-Host "请输入新版本号 (回车使用建议版本 $suggestedVersion)"
if ([string]::IsNullOrWhiteSpace($newVersion)) {
    $newVersion = $suggestedVersion
    Write-Host "使用版本: v$newVersion" -ForegroundColor Gray
}

# 自动递增 versionCode
$newVersionCode = $currentVersionCode + 1
Write-Host "版本代码: $currentVersionCode -> $newVersionCode"

# --- 输入更新内容 ---
Write-Host ""
$releaseTitle = Read-Host "更新标题 (回车使用默认: v$newVersion)"
if ([string]::IsNullOrWhiteSpace($releaseTitle)) { $releaseTitle = "v$newVersion" }

$releaseNote = Read-Host "更新内容 (一行描述)"
if ([string]::IsNullOrWhiteSpace($releaseNote)) {
    $releaseNote = "Bug fixes and improvements"
}

# --- 是否静默更新 ---
$silentInput = Read-Host "静默更新? (y/N)"
$isSilent = $silentInput -eq 'y' -or $silentInput -eq 'Y'

Write-Host ""
Write-Host "--- 确认发版信息 ---" -ForegroundColor Green
Write-Host "版本:     v$newVersion (code: $newVersionCode)"
Write-Host "标题:     $releaseTitle"
Write-Host "内容:     $releaseNote"
Write-Host "静默更新: $isSilent"
Write-Host ""
$confirm = Read-Host "确认发版? (y/N)"
if ($confirm -ne 'y' -and $confirm -ne 'Y') {
    Write-Host "已取消" -ForegroundColor Yellow
    exit 0
}

# ==============================================
# Step 1: 更新版本号
# ==============================================
Write-Host ""
Write-Host "[1/3] 更新版本号..." -ForegroundColor Cyan

$manifestContent = Get-Content $ManifestPath -Raw
$manifestContent = $manifestContent -replace '"versionName"\s*:\s*"[^"]*"', "`"versionName`" : `"$newVersion`""
$manifestContent = $manifestContent -replace '"versionCode"\s*:\s*"[^"]*"', "`"versionCode`" : `"$newVersionCode`""
Set-Content -Path $ManifestPath -Value $manifestContent -NoNewline

$pkgContent = Get-Content $PackagePath -Raw
$pkgContent = $pkgContent -replace '"version"\s*:\s*"[^"]*"', "`"version`": `"$newVersion`""
Set-Content -Path $PackagePath -Value $pkgContent -NoNewline

Write-Host "  manifest.json -> v$newVersion (code: $newVersionCode)"
Write-Host "  package.json  -> v$newVersion"

# ==============================================
# Step 2: 构建 wgt
# ==============================================
Write-Host ""
Write-Host "[2/3] 构建 wgt 包..." -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    pnpm build:wgt
    if ($LASTEXITCODE -ne 0) { throw "wgt build failed" }
} finally {
    Pop-Location
}

$wgtFile = Join-Path $ProjectRoot "dist\foggy-navigator-$newVersion.wgt"
if (-not (Test-Path $wgtFile)) {
    Write-Host "ERROR: wgt file not found at $wgtFile" -ForegroundColor Red
    exit 1
}
$wgtSize = (Get-Item $wgtFile).Length
Write-Host "  wgt: $wgtFile ($([math]::Round($wgtSize / 1MB, 2)) MB)"

# ==============================================
# Step 3: 发布到 uni-admin
# ==============================================
Write-Host ""
Write-Host "[3/3] 发布到 uni-admin 升级中心..." -ForegroundColor Cyan

# 查找线上最新的原生 App (APK) 版本号作为 min_uni_version
# wgt 热更新必须设置此字段，否则云函数的兼容性校验会跳过更新
# 注意：必须从服务器查询真实 APK 版本，不能用本地版本号！
#   本地版本号随 wgt 发版不断递增，可能远高于用户实际安装的 APK 版本。
#   如果 min_uni_version > 用户 APK 版本，云函数会认为不兼容，跳过 wgt 更新。
$latestNativeVersion = $null
Write-Host "  查询线上最新 APK 版本..." -ForegroundColor Gray
try {
    $nativeJson = node $apiScript latest-native-version 2>$null | Select-Object -Last 1
    if ($nativeJson) {
        $nativeInfo = $nativeJson | ConvertFrom-Json
        if ($nativeInfo -and $nativeInfo.version -and $nativeInfo.version -ne '') {
            $latestNativeVersion = $nativeInfo.version
        }
    }
} catch {
    Write-Host "  查询 APK 版本失败: $_" -ForegroundColor Yellow
}

if ($latestNativeVersion) {
    Write-Host "  线上 APK 版本: v$latestNativeVersion (将作为 minVersion)" -ForegroundColor Green
} else {
    Write-Host "  WARNING: 未查询到线上 APK 版本记录！" -ForegroundColor Red
    Write-Host "  wgt 热更新需要对应的 APK 基础版本，否则客户端无法检测到更新。" -ForegroundColor Red
    Write-Host "  请先使用 build-apk.ps1 发布一个 APK 基础版本。" -ForegroundColor Red
    $manualVersion = Read-Host "  手动输入 minVersion (留空则中止)"
    if ([string]::IsNullOrWhiteSpace($manualVersion)) {
        Write-Host "已取消" -ForegroundColor Yellow
        exit 0
    }
    $latestNativeVersion = $manualVersion
}

$publishArgs = @(
    $apiScript, "publish",
    "--type", "wgt",
    "--version", $newVersion,
    "--title", $releaseTitle,
    "--content", $releaseNote,
    "--file", $wgtFile,
    "--minVersion", $latestNativeVersion
)
if ($isSilent) { $publishArgs += "--silent" }

node @publishArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "  自动发布未成功，请手动在 uni-admin 操作" -ForegroundColor Yellow
    Write-Host "  wgt 文件: $wgtFile" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  发版完成! v$newVersion" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
