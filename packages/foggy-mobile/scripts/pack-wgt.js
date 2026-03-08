/**
 * 将 uni-app Android 构建产物打包为 .wgt 热更新包
 *
 * 用法: node scripts/pack-wgt.js
 * 输出: dist/foggy-navigator-{version}.wgt
 */

const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
const BUILD_DIR = path.join(ROOT, 'dist', 'build', 'app')
const DIST_DIR = path.join(ROOT, 'dist')

// 读取版本号
const manifest = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'src', 'manifest.json'), 'utf-8'),
)
const version = manifest.versionName || '1.0.0'

// 检查构建产物
if (!fs.existsSync(BUILD_DIR)) {
  console.error(`[pack-wgt] Build directory not found: ${BUILD_DIR}`)
  console.error('Run "pnpm build:app-android" first.')
  process.exit(1)
}

// 确保输出目录存在
if (!fs.existsSync(DIST_DIR)) {
  fs.mkdirSync(DIST_DIR, { recursive: true })
}

const wgtName = `foggy-navigator-${version}.wgt`
const wgtPath = path.join(DIST_DIR, wgtName)

// 使用 PowerShell 的 Compress-Archive 打包（Windows 环境无需额外依赖）
// wgt 本质是 zip 格式
const tempZip = path.join(DIST_DIR, `${wgtName}.zip`)

try {
  // 删除旧文件
  if (fs.existsSync(wgtPath)) fs.unlinkSync(wgtPath)
  if (fs.existsSync(tempZip)) fs.unlinkSync(tempZip)

  // 使用 PowerShell Compress-Archive
  const psCmd = `Compress-Archive -Path "${BUILD_DIR}\\*" -DestinationPath "${tempZip}" -Force`
  execSync(`powershell -NoProfile -Command "${psCmd}"`, { stdio: 'inherit' })

  // 重命名为 .wgt
  fs.renameSync(tempZip, wgtPath)

  const stat = fs.statSync(wgtPath)
  const sizeMB = (stat.size / 1024 / 1024).toFixed(2)
  console.log(`[pack-wgt] Created: ${wgtName} (${sizeMB} MB)`)
  console.log(`[pack-wgt] Path: ${wgtPath}`)
} catch (err) {
  console.error('[pack-wgt] Failed to create wgt package:', err.message)
  // 清理临时文件
  if (fs.existsSync(tempZip)) fs.unlinkSync(tempZip)
  process.exit(1)
}
