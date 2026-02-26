<#
.SYNOPSIS
    Foggy Navigator APK 基座包构建脚本

.DESCRIPTION
    使用 DCloud CLI 提交云端打包任务，等待打包完成后下载 APK。
    首次使用需在 .env.release 中配置 DCloud 账号和签名信息。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ManifestPath = Join-Path $ProjectRoot "src\manifest.json"
$EnvPath = Join-Path $ProjectRoot ".env.release"
$DistDir = Join-Path $ProjectRoot "dist"

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
$DCLOUD_USERNAME = $envConfig['DCLOUD_USERNAME']
$DCLOUD_PASSWORD = $envConfig['DCLOUD_PASSWORD']
$DCLOUD_APPID = $envConfig['DCLOUD_APPID']
$KEYSTORE_PATH = $envConfig['KEYSTORE_PATH']
$KEYSTORE_PASSWORD = $envConfig['KEYSTORE_PASSWORD']
$KEY_ALIAS = $envConfig['KEY_ALIAS']
$KEY_PASSWORD = $envConfig['KEY_PASSWORD']

# 读取版本号
$manifest = Get-Content $ManifestPath -Raw | ConvertFrom-Json
$version = $manifest.versionName

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Foggy Navigator - APK 云端打包" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "版本: v$version" -ForegroundColor Yellow
Write-Host "AppID: $DCLOUD_APPID" -ForegroundColor Yellow
Write-Host ""

# --- 验证配置 ---
if ([string]::IsNullOrWhiteSpace($DCLOUD_USERNAME) -or [string]::IsNullOrWhiteSpace($DCLOUD_PASSWORD)) {
    Write-Host "ERROR: 请在 .env.release 中配置 DCLOUD_USERNAME 和 DCLOUD_PASSWORD" -ForegroundColor Red
    exit 1
}

# --- Step 1: 编译 App ---
Write-Host "[1/4] 编译 App Android..." -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    pnpm build:app-android
    if ($LASTEXITCODE -ne 0) { throw "App build failed" }
} finally {
    Pop-Location
}
Write-Host "  编译完成" -ForegroundColor Green

# --- Step 2: 检查/生成签名 ---
Write-Host ""
Write-Host "[2/4] 检查签名配置..." -ForegroundColor Cyan

if ([string]::IsNullOrWhiteSpace($KEYSTORE_PATH) -or -not (Test-Path $KEYSTORE_PATH)) {
    $keystoreDir = Join-Path $ProjectRoot "keystore"
    if (-not (Test-Path $keystoreDir)) {
        New-Item -ItemType Directory -Path $keystoreDir | Out-Null
    }
    $KEYSTORE_PATH = Join-Path $keystoreDir "foggy-navigator.keystore"

    if (-not (Test-Path $KEYSTORE_PATH)) {
        Write-Host "  未找到签名文件，正在生成..." -ForegroundColor Yellow

        $keytoolArgs = @(
            "-genkey", "-v",
            "-keystore", $KEYSTORE_PATH,
            "-alias", $(if ($KEY_ALIAS) { $KEY_ALIAS } else { "foggy-navi" }),
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "36500",
            "-storepass", $(if ($KEYSTORE_PASSWORD) { $KEYSTORE_PASSWORD } else { "foggy123" }),
            "-keypass", $(if ($KEY_PASSWORD) { $KEY_PASSWORD } else { "foggy123" }),
            "-dname", "CN=Foggy Navigator, OU=Dev, O=Foggy, L=Shanghai, ST=Shanghai, C=CN"
        )

        $keytool = Get-Command keytool -ErrorAction SilentlyContinue
        if ($keytool) {
            & keytool @keytoolArgs
            Write-Host "  签名文件已生成: $KEYSTORE_PATH" -ForegroundColor Green
        } else {
            Write-Host "  WARNING: keytool 不可用，请确保 Java 已安装" -ForegroundColor Yellow
            Write-Host "  你也可以在 HBuilderX 中使用 DCloud 公共测试证书打包" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  使用已有签名: $KEYSTORE_PATH" -ForegroundColor Green
    }
} else {
    Write-Host "  使用配置签名: $KEYSTORE_PATH" -ForegroundColor Green
}

# --- Step 3: 提交云端打包 ---
Write-Host ""
Write-Host "[3/4] 提交 DCloud 云端打包任务..." -ForegroundColor Cyan
Write-Host ""
Write-Host "  ================================================" -ForegroundColor Yellow
Write-Host "  DCloud 云端打包需要通过 HBuilderX 提交" -ForegroundColor Yellow
Write-Host "  ================================================" -ForegroundColor Yellow
Write-Host ""
Write-Host "  请按以下步骤操作:" -ForegroundColor Cyan
Write-Host "  1. 打开 HBuilderX" -ForegroundColor Gray
Write-Host "  2. 导入项目: $ProjectRoot" -ForegroundColor Gray
Write-Host "  3. 菜单: 发行 -> 原生App-云打包" -ForegroundColor Gray
Write-Host "  4. 选择 Android, 配置签名信息:" -ForegroundColor Gray
Write-Host "     - Keystore: $KEYSTORE_PATH" -ForegroundColor Gray
Write-Host "     - Alias: $KEY_ALIAS" -ForegroundColor Gray
Write-Host "  5. 点击打包，等待完成" -ForegroundColor Gray
Write-Host ""
Write-Host "  打包完成后，APK 会下载到 HBuilderX 的 unpackage 目录" -ForegroundColor Gray
Write-Host ""

# 等待用户确认 APK 路径
$apkSource = Read-Host "请输入打包完成的 APK 文件路径 (或按回车跳过)"

# --- Step 4: 复制到 dist ---
Write-Host ""
Write-Host "[4/4] 整理输出..." -ForegroundColor Cyan

if (-not (Test-Path $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir | Out-Null
}

$apkDest = Join-Path $DistDir "foggy-navigator-$version.apk"

if (-not [string]::IsNullOrWhiteSpace($apkSource) -and (Test-Path $apkSource)) {
    Copy-Item $apkSource $apkDest -Force
    $apkSize = (Get-Item $apkDest).Length
    Write-Host "  APK 已复制: $apkDest ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor Green
} else {
    Write-Host "  未提供 APK 路径，跳过复制" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  APK 构建流程完成" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "后续步骤:" -ForegroundColor Cyan
Write-Host "  1. 将 APK 上传到应用分发平台或自有服务器" -ForegroundColor Gray
Write-Host "  2. 在 uni-admin 升级中心创建 native_app 类型版本记录" -ForegroundColor Gray
Write-Host "  3. 首次安装后，后续可使用 wgt 热更新: scripts/release.ps1" -ForegroundColor Gray
Write-Host ""
