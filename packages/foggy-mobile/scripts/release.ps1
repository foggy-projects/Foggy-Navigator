<#
.SYNOPSIS
    Foggy Navigator 移动端一键发版脚本（wgt 热更新）

.DESCRIPTION
    流程:
    1. 读取当前版本号
    2. 提示输入新版本号 + 更新内容
    3. 更新 manifest.json 和 package.json
    4. 构建 wgt 包
    5. 上传到 uniCloud 云存储
    6. 创建升级中心版本记录

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/release.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ManifestPath = Join-Path $ProjectRoot "src\manifest.json"
$PackagePath = Join-Path $ProjectRoot "package.json"
$EnvPath = Join-Path $ProjectRoot ".env.release"

# --- 读取配置 ---
function Read-EnvFile($path) {
    $env = @{}
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            if ($_ -match '^\s*([^#][^=]+?)\s*=\s*(.*)$') {
                $env[$Matches[1].Trim()] = $Matches[2].Trim()
            }
        }
    }
    return $env
}

$envConfig = Read-EnvFile $EnvPath
$APPID = $envConfig['DCLOUD_APPID']
if (-not $APPID) { $APPID = '__UNI__AC2B8DB' }

$UNICLOUD_SPACE_ID = 'mp-4af7054d-5a40-4315-8678-df36b44298bb'
$UNICLOUD_API = "https://fc-$UNICLOUD_SPACE_ID.next.bspapp.com"

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
$releaseNote = Read-Host "请输入更新内容 (一行描述)"
if ([string]::IsNullOrWhiteSpace($releaseNote)) {
    $releaseNote = "Bug fixes and improvements"
}

# --- 是否静默更新 ---
$silentInput = Read-Host "是否静默更新? (y/N)"
$isSilent = $silentInput -eq 'y' -or $silentInput -eq 'Y'

Write-Host ""
Write-Host "--- 确认发版信息 ---" -ForegroundColor Green
Write-Host "版本: v$newVersion (code: $newVersionCode)"
Write-Host "更新内容: $releaseNote"
Write-Host "静默更新: $isSilent"
Write-Host ""
$confirm = Read-Host "确认发版? (y/N)"
if ($confirm -ne 'y' -and $confirm -ne 'Y') {
    Write-Host "已取消" -ForegroundColor Yellow
    exit 0
}

# --- Step 1: 更新版本号 ---
Write-Host ""
Write-Host "[1/4] 更新版本号..." -ForegroundColor Cyan

# 更新 manifest.json（使用字符串替换避免 JSON 格式化问题）
$manifestContent = Get-Content $ManifestPath -Raw
$manifestContent = $manifestContent -replace '"versionName"\s*:\s*"[^"]*"', "`"versionName`" : `"$newVersion`""
$manifestContent = $manifestContent -replace '"versionCode"\s*:\s*"[^"]*"', "`"versionCode`" : `"$newVersionCode`""
Set-Content -Path $ManifestPath -Value $manifestContent -NoNewline

# 更新 package.json
$pkgContent = Get-Content $PackagePath -Raw
$pkgContent = $pkgContent -replace '"version"\s*:\s*"[^"]*"', "`"version`": `"$newVersion`""
Set-Content -Path $PackagePath -Value $pkgContent -NoNewline

Write-Host "  manifest.json -> v$newVersion (code: $newVersionCode)"
Write-Host "  package.json  -> v$newVersion"

# --- Step 2: 构建 wgt ---
Write-Host ""
Write-Host "[2/4] 构建 wgt 包..." -ForegroundColor Cyan

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
Write-Host "  wgt 文件: $wgtFile ($([math]::Round($wgtSize / 1MB, 2)) MB)"

# --- Step 3: 上传到云存储 ---
Write-Host ""
Write-Host "[3/4] 上传 wgt 到 uniCloud 云存储..." -ForegroundColor Cyan

$wgtFileName = "foggy-navigator-$newVersion.wgt"
$uploadUrl = "$UNICLOUD_API/upload"

try {
    # 使用 uniCloud HTTP 上传接口
    $boundary = [System.Guid]::NewGuid().ToString()
    $fileBytes = [System.IO.File]::ReadAllBytes($wgtFile)
    $fileBase64 = [Convert]::ToBase64String($fileBytes)

    # 通过云函数上传文件
    $uploadBody = @{
        action = 'uploadFile'
        filename = $wgtFileName
        file = $fileBase64
    } | ConvertTo-Json

    $uploadResponse = Invoke-RestMethod -Uri "$UNICLOUD_API/uni-upgrade-center" -Method Post -Body $uploadBody -ContentType "application/json"

    if ($uploadResponse.fileID) {
        $fileUrl = $uploadResponse.fileID
        Write-Host "  上传成功: $fileUrl" -ForegroundColor Green
    } else {
        Write-Host "  云存储上传失败，请手动上传 wgt 文件到 uni-admin" -ForegroundColor Yellow
        Write-Host "  文件路径: $wgtFile"
        $fileUrl = Read-Host "请输入 wgt 文件的下载地址"
    }
} catch {
    Write-Host "  自动上传失败: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "  请手动上传 wgt 文件到 uni-admin 云存储或其他托管服务" -ForegroundColor Yellow
    Write-Host "  文件路径: $wgtFile"
    $fileUrl = Read-Host "请输入 wgt 文件的下载地址"
}

if ([string]::IsNullOrWhiteSpace($fileUrl)) {
    Write-Host "未提供下载地址，跳过版本记录创建" -ForegroundColor Yellow
    exit 0
}

# --- Step 4: 创建版本记录 ---
Write-Host ""
Write-Host "[4/4] 创建升级中心版本记录..." -ForegroundColor Cyan

$versionRecord = @{
    action = 'createVersion'
    appid = $APPID
    platform = @('Android')
    type = 'wgt'
    version = $newVersion
    url = $fileUrl
    note = $releaseNote
    is_silently = $isSilent
    is_mandatory = $false
    create_date = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
} | ConvertTo-Json

try {
    $result = Invoke-RestMethod -Uri "$UNICLOUD_API/uni-upgrade-center" -Method Post -Body $versionRecord -ContentType "application/json"
    if ($result.code -eq 0) {
        Write-Host "  版本记录创建成功!" -ForegroundColor Green
    } else {
        Write-Host "  版本记录创建失败: $($result.message)" -ForegroundColor Yellow
        Write-Host "  请在 uni-admin 管理后台手动创建版本记录" -ForegroundColor Yellow
    }
} catch {
    Write-Host "  自动创建失败: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "  请在 uni-admin 管理后台 (sd-uni-admin.qlfloor.com) 手动创建版本记录" -ForegroundColor Yellow
    Write-Host "  版本: v$newVersion | 类型: wgt | 地址: $fileUrl" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  发版完成! v$newVersion" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "后续步骤:" -ForegroundColor Cyan
Write-Host "  1. 登录 uni-admin (sd-uni-admin.qlfloor.com) 确认版本记录" -ForegroundColor Gray
Write-Host "  2. 在 App 中验证热更新是否生效" -ForegroundColor Gray
Write-Host ""
