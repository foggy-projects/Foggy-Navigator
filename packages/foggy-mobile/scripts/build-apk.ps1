<#
.SYNOPSIS
    Foggy Navigator APK 云端打包脚本（自动化）

.DESCRIPTION
    使用 HBuilderX CLI 提交云端打包任务。
    流程: pnpm build:app-android → HBuilderX CLI import → CLI pack → 下载 APK → 上传 uni-admin

    前提:
    1. HBuilderX 已安装且 CLI 可用（D:\work\HBuilderX\cli.exe 或配置 PATH）
    2. 已通过 HBuilderX 登录 DCloud 账号
    3. .env.release 中配置签名信息

    已知限制:
    HBuilderX CLI 的 pack 命令对 CLI 创建的 uni-app 项目有编译 bug（不传 -p app），
    因此采用 "先用 pnpm 编译 → 再让 HBuilderX 打包编译产物" 的方式绕过。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ManifestPath = Join-Path $ProjectRoot "src\manifest.json"
$EnvPath = Join-Path $ProjectRoot ".env.release"
$DistDir = Join-Path $ProjectRoot "dist"
$CompiledAppDir = Join-Path $DistDir "build\app"
$KeystoreDir = Join-Path $ProjectRoot "keystore"

# --- HBuilderX CLI ---
$HBUILDERX_CLI = "D:\work\HBuilderX\cli.exe"
if (-not (Test-Path $HBUILDERX_CLI)) {
    # 尝试 PATH 中查找
    $found = Get-Command "cli" -ErrorAction SilentlyContinue
    if ($found) {
        $HBUILDERX_CLI = $found.Source
    } else {
        Write-Host "ERROR: HBuilderX CLI 未找到。" -ForegroundColor Red
        Write-Host "  请安装 HBuilderX 或修改脚本中的 HBUILDERX_CLI 路径" -ForegroundColor Red
        exit 1
    }
}

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
$KEYSTORE_PASSWORD = $envConfig['KEYSTORE_PASSWORD']
$KEY_ALIAS = $envConfig['KEY_ALIAS']
$KEY_PASSWORD = $envConfig['KEY_PASSWORD']

# 默认值
if ([string]::IsNullOrWhiteSpace($KEY_ALIAS)) { $KEY_ALIAS = "foggy-navi" }
if ([string]::IsNullOrWhiteSpace($KEYSTORE_PASSWORD)) { $KEYSTORE_PASSWORD = "@Shundao888" }
if ([string]::IsNullOrWhiteSpace($KEY_PASSWORD)) { $KEY_PASSWORD = $KEYSTORE_PASSWORD }

# 读取版本号
$manifest = Get-Content $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$version = $manifest.versionName

# Keystore 路径
$KeystorePath = Join-Path $KeystoreDir "foggy-navigator.keystore"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Foggy Navigator - APK 云端打包" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "版本: v$version" -ForegroundColor Yellow
Write-Host "CLI:  $HBUILDERX_CLI" -ForegroundColor Yellow
Write-Host ""

# --- 验证签名文件 ---
if (-not (Test-Path $KeystorePath)) {
    Write-Host "ERROR: 签名文件不存在: $KeystorePath" -ForegroundColor Red
    Write-Host "  请先生成 keystore，示例:" -ForegroundColor Red
    Write-Host "  keytool -genkey -v -keystore $KeystorePath -alias $KEY_ALIAS -keyalg RSA -keysize 2048 -validity 36500" -ForegroundColor Gray
    exit 1
}

# ==============================================
# Step 1: 使用 pnpm 预编译 App 资源
# ==============================================
Write-Host "[1/5] 预编译 App Android 资源..." -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    pnpm build:app-android
    if ($LASTEXITCODE -ne 0) { throw "pnpm build:app-android failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path $CompiledAppDir)) {
    Write-Host "ERROR: 编译产物不存在: $CompiledAppDir" -ForegroundColor Red
    exit 1
}
Write-Host "  编译完成 → $CompiledAppDir" -ForegroundColor Green

# ==============================================
# Step 2: 复制编译产物到临时目录（绕过 HBuilderX CLI pack 的编译 bug）
# ==============================================
# 原因: HBuilderX CLI 的 pack 命令会重新编译项目，但内部调用 uni.js build 时
#       不传 -p app 参数，导致编译 H5 而非 App。当编译产物在项目树内时，pack 能
#       找到 node_modules/@dcloudio/vite-plugin-uni 并使用 CLI 编译器（有 bug）。
#       复制到项目树外的临时目录，pack 找不到 CLI 编译器，会使用 HBuilderX 内置
#       编译器（不受此 bug 影响）。
Write-Host ""
Write-Host "[2/5] 复制到临时目录..." -ForegroundColor Cyan

$TempPackDir = Join-Path $env:TEMP "foggy-app-pack"
if (Test-Path $TempPackDir) { Remove-Item $TempPackDir -Recurse -Force }
Copy-Item $CompiledAppDir $TempPackDir -Recurse
Write-Host "  已复制到: $TempPackDir" -ForegroundColor Green

# 关闭可能已打开的源项目（避免冲突）
& $HBUILDERX_CLI project close --path $ProjectRoot 2>$null
& $HBUILDERX_CLI project close --path $CompiledAppDir 2>$null

# 导入临时目录
& $HBUILDERX_CLI project open --path $TempPackDir
if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: project open 返回非零，继续尝试..." -ForegroundColor Yellow
}
Write-Host "  已导入: $TempPackDir" -ForegroundColor Green

# ==============================================
# Step 3: 提交云端打包
# ==============================================
Write-Host ""
Write-Host "[3/5] 提交 DCloud 云端打包..." -ForegroundColor Cyan
Write-Host "  平台: Android" -ForegroundColor Gray
Write-Host "  包名: com.foggy.navigator" -ForegroundColor Gray
Write-Host "  签名: $KeystorePath (alias: $KEY_ALIAS)" -ForegroundColor Gray
Write-Host ""

$packArgs = @(
    "pack",
    "--platform", "android",
    "--project", $TempPackDir,
    "--android.androidpacktype", "0",
    "--android.packagename", "com.foggy.navigator",
    "--android.certfile", $KeystorePath,
    "--android.certpassword", $KEYSTORE_PASSWORD,
    "--android.storepassword", $KEY_PASSWORD,
    "--android.certalias", $KEY_ALIAS
)

Write-Host "  执行: cli $($packArgs -join ' ')" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  等待云端打包完成（通常 2-5 分钟）..." -ForegroundColor Yellow
Write-Host ""

# 设置 UTF-8 编码以正确捕获 CLI 输出
$prevEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$packOutput = & $HBUILDERX_CLI @packArgs 2>&1 | Tee-Object -Variable packResult
$packExitCode = $LASTEXITCODE

[Console]::OutputEncoding = $prevEncoding

# 输出打包日志
$packResult | ForEach-Object { Write-Host "  $_" }

if ($packExitCode -ne 0) {
    Write-Host ""
    Write-Host "ERROR: 云端打包失败 (exit code: $packExitCode)" -ForegroundColor Red
    Write-Host "  请检查 HBuilderX 控制台或日志:" -ForegroundColor Red
    Write-Host "  $env:APPDATA\HBuilder X\.log" -ForegroundColor Gray
    exit 1
}

# 尝试从输出中提取下载 URL（DCloud 下载链接格式: https://app.liuyingyong.cn/build/download/<uuid>）
$downloadUrl = ($packResult | Select-String -Pattern 'https?://app\.liuyingyong\.cn/build/download/[a-f0-9-]+' | Select-Object -First 1)

# ==============================================
# Step 4: 下载 APK
# ==============================================
Write-Host ""
Write-Host "[4/5] 下载 APK..." -ForegroundColor Cyan

if (-not (Test-Path $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir | Out-Null
}

$apkDest = Join-Path $DistDir "foggy-navigator-$version.apk"

if ($downloadUrl) {
    $url = $downloadUrl.Matches[0].Value
    Write-Host "  下载地址: $url" -ForegroundColor Gray
    try {
        Invoke-WebRequest -Uri $url -OutFile $apkDest -UseBasicParsing
        $apkSize = (Get-Item $apkDest).Length
        Write-Host "  APK 已下载: $apkDest ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor Green
    } catch {
        Write-Host "  自动下载失败: $_" -ForegroundColor Yellow
        Write-Host "  请手动下载: $url" -ForegroundColor Yellow
    }
} else {
    Write-Host "  未从打包输出中找到下载链接。" -ForegroundColor Yellow
    Write-Host "  请查看上方输出，手动下载 APK 到: $apkDest" -ForegroundColor Yellow

    # 检查 HBuilderX unpackage 目录
    $unpackageApk = Get-ChildItem -Path (Join-Path $CompiledAppDir "unpackage\release\apk") -Filter "*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($unpackageApk) {
        Copy-Item $unpackageApk.FullName $apkDest -Force
        $apkSize = (Get-Item $apkDest).Length
        Write-Host "  从 unpackage 找到 APK 并复制: $apkDest ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor Green
    }
}

# ==============================================
# 清理: 关闭临时项目，删除临时目录，重新打开源项目
# ==============================================
& $HBUILDERX_CLI project close --path $TempPackDir 2>$null
if (Test-Path $TempPackDir) { Remove-Item $TempPackDir -Recurse -Force -ErrorAction SilentlyContinue }
& $HBUILDERX_CLI project open --path $ProjectRoot 2>$null

# ==============================================
# Step 5: 上传到 uni-admin 升级中心
# ==============================================
Write-Host ""
Write-Host "[5/5] 发布到 uni-admin 升级中心..." -ForegroundColor Cyan

$apkPath = $apkDest
if (-not (Test-Path $apkPath)) {
    Write-Host "  APK 文件不存在，跳过自动发布" -ForegroundColor Yellow
} else {
    $publishTitle = Read-Host "  更新标题 (回车使用默认: v$version)"
    if ([string]::IsNullOrWhiteSpace($publishTitle)) { $publishTitle = "v$version" }

    $publishContent = Read-Host "  更新内容 (回车使用默认)"
    if ([string]::IsNullOrWhiteSpace($publishContent)) { $publishContent = "Foggy Navigator v$version" }

    $scriptDir = Join-Path $ProjectRoot "scripts"
    $apiScript = Join-Path $scriptDir "uni-admin-api.js"

    node $apiScript publish --type native_app --version $version --title $publishTitle --content $publishContent --file $apkPath

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "  自动发布未成功，请手动在 uni-admin 操作" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  APK 构建流程完成" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
