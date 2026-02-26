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
Write-Host "当前版本: v$currentVersion (code: $currentVersionCode)" -ForegroundColor Yellow
Write-Host ""

# --- 输入新版本号 ---
$newVersion = Read-Host "请输入新版本号 (如 1.0.1, 回车跳过使用当前版本)"
if ([string]::IsNullOrWhiteSpace($newVersion)) {
    $newVersion = $currentVersion
    Write-Host "使用当前版本: v$newVersion" -ForegroundColor Gray
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

$apiScript = Join-Path $ScriptDir "uni-admin-api.js"
$publishArgs = @(
    $apiScript, "publish",
    "--type", "wgt",
    "--version", $newVersion,
    "--title", $releaseTitle,
    "--content", $releaseNote,
    "--file", $wgtFile
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
