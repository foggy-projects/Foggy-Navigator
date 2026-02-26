/**
 * uni-admin 升级中心 API 工具
 *
 * 功能：登录 uni-id → 上传文件到 uniCloud 云存储 → 创建/更新版本记录
 *
 * 用法：
 *   node scripts/uni-admin-api.js publish --type native_app --version 1.0.0 \
 *     --title "v1.0.0" --content "首个版本" --file dist/xxx.apk
 *
 *   node scripts/uni-admin-api.js publish --type wgt --version 1.0.1 \
 *     --title "v1.0.1" --content "Bug fixes" --file dist/xxx.wgt [--silent]
 *
 * 环境变量（从 .env.release 读取）：
 *   UNI_ADMIN_USERNAME, UNI_ADMIN_PASSWORD, UNICLOUD_SPACE_ID
 */

const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
const ENV_PATH = path.join(ROOT, '.env.release')

// --- 默认配置 ---
const DEFAULTS = {
  UNICLOUD_SPACE_ID: 'mp-4af7054d-5a40-4315-8678-df36b44298bb',
  DCLOUD_APPID: '__UNI__AC2B8DB',
  APP_NAME: 'Foggy Navigator',
  UNI_ADMIN_USERNAME: 'root',
}

// --- 工具函数 ---
function readEnvFile(filePath) {
  const env = {}
  if (fs.existsSync(filePath)) {
    const content = fs.readFileSync(filePath, 'utf-8')
    for (const line of content.split('\n')) {
      const match = line.match(/^\s*([^#][^=]+?)\s*=\s*(.*)$/)
      if (match) env[match[1].trim()] = match[2].trim()
    }
  }
  return env
}

function getConfig() {
  const env = readEnvFile(ENV_PATH)
  return {
    spaceId: env.UNICLOUD_SPACE_ID || DEFAULTS.UNICLOUD_SPACE_ID,
    appid: env.DCLOUD_APPID || DEFAULTS.DCLOUD_APPID,
    appName: DEFAULTS.APP_NAME,
    username: env.UNI_ADMIN_USERNAME || DEFAULTS.UNI_ADMIN_USERNAME,
    password: env.UNI_ADMIN_PASSWORD || '',
  }
}

function parseArgs() {
  const args = process.argv.slice(2)
  const command = args[0]
  const opts = {}
  for (let i = 1; i < args.length; i++) {
    if (args[i].startsWith('--')) {
      const key = args[i].slice(2)
      if (key === 'silent' || key === 'force') {
        opts[key] = true
      } else {
        opts[key] = args[++i]
      }
    }
  }
  return { command, ...opts }
}

// --- uniCloud HTTP API ---
const API_BASE = 'https://api.next.bspapp.com'

async function uniCloudRequest(method, params, token, spaceId) {
  const body = {
    method,
    params: params || {},
    spaceId,
    timestamp: Date.now(),
  }
  if (token) body.token = token

  const resp = await fetch(`${API_BASE}/client`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return resp.json()
}

// --- 登录 uni-id ---
async function login(config) {
  console.log('  [1] 登录 uni-id...')
  const url = `https://fc-${config.spaceId}.next.bspapp.com/uni-id-co`
  const body = {
    method: 'loginByUsername',
    params: {
      username: config.username,
      password: config.password,
    },
    clientInfo: {
      PLATFORM: 'web',
      OS: 'web',
      APPID: config.appid,
    },
  }

  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const result = await resp.json()

  if (result.errCode === 0 && result.newToken) {
    console.log('  [1] 登录成功')
    return result.newToken.token
  }

  // 尝试备用格式
  if (result.token) return result.token
  if (result.data && result.data.token) return result.data.token

  throw new Error(`登录失败: ${JSON.stringify(result)}`)
}

// --- 上传文件到 uniCloud 云存储 ---
async function uploadFile(filePath, token, config) {
  const fileName = path.basename(filePath)
  const fileSize = fs.statSync(filePath).size
  const sizeMB = (fileSize / 1024 / 1024).toFixed(2)
  console.log(`  [2] 上传文件: ${fileName} (${sizeMB} MB)...`)

  // Step 1: 获取上传凭证
  const signResult = await uniCloudRequest(
    'serverless.file.resource.generateProximalSign',
    { env: 'public', filename: fileName },
    token,
    config.spaceId,
  )

  if (!signResult.data || !signResult.data.host) {
    throw new Error(`获取上传凭证失败: ${JSON.stringify(signResult)}`)
  }

  const { host, ossPath, policy, signature, accessKeyId, id } = signResult.data

  // Step 2: 上传到 OSS
  const { FormData, File } = await importFormData()
  const fileBuffer = fs.readFileSync(filePath)
  const formData = new FormData()
  formData.append('key', ossPath)
  formData.append('policy', policy)
  formData.append('OSSAccessKeyId', accessKeyId)
  formData.append('signature', signature)
  formData.append('success_action_status', '200')
  formData.append('file', new File([fileBuffer], fileName))

  const uploadResp = await fetch(host, {
    method: 'POST',
    body: formData,
  })

  if (!uploadResp.ok && uploadResp.status !== 200) {
    throw new Error(`OSS 上传失败: ${uploadResp.status} ${uploadResp.statusText}`)
  }

  // Step 3: 上报上传完成
  const reportResult = await uniCloudRequest(
    'serverless.file.resource.report',
    { id },
    token,
    config.spaceId,
  )

  // 构造 CDN URL
  const cdnUrl = `https://${config.spaceId}.cdn.bspapp.com/${ossPath}`
  console.log(`  [2] 上传成功: ${cdnUrl}`)
  return cdnUrl
}

async function importFormData() {
  // Node 18+ has native FormData and File
  if (globalThis.FormData && globalThis.File) {
    return { FormData: globalThis.FormData, File: globalThis.File }
  }
  // Fallback: try undici (bundled with Node 18+)
  try {
    const { FormData, File } = require('undici')
    return { FormData, File }
  } catch {
    throw new Error('需要 Node.js 18+ 或安装 undici 包')
  }
}

// --- 创建版本记录 ---
async function createVersion(opts, fileUrl, token, config) {
  console.log('  [3] 创建版本记录...')

  const versionData = {
    appid: config.appid,
    name: config.appName,
    title: opts.title || `v${opts.version}`,
    contents: opts.content || '',
    platform: ['Android'],
    type: opts.type, // 'native_app' or 'wgt'
    version: opts.version,
    min_uni_version: '',
    url: fileUrl,
    is_silently: opts.silent ? true : false,
    is_mandatory: opts.force ? true : false,
    stable_publish: true,
    create_date: Date.now(),
  }

  const result = await uniCloudRequest(
    'serverless.db.command',
    {
      $db: [
        { $method: 'collection', $param: ['opendb-app-versions'] },
        { $method: 'add', $param: [versionData] },
      ],
    },
    token,
    config.spaceId,
  )

  if (result.data && result.data.id) {
    console.log(`  [3] 版本记录已创建: ${result.data.id}`)
    return result.data.id
  }

  // 检查是否有错误
  if (result.code || result.errCode) {
    throw new Error(`创建版本记录失败: ${JSON.stringify(result)}`)
  }

  console.log(`  [3] 创建结果: ${JSON.stringify(result)}`)
  return null
}

// --- 主流程: 发布 ---
async function publish(opts) {
  // 验证参数
  if (!opts.type || !['native_app', 'wgt'].includes(opts.type)) {
    console.error('ERROR: --type must be native_app or wgt')
    process.exit(1)
  }
  if (!opts.version) {
    console.error('ERROR: --version is required')
    process.exit(1)
  }
  if (!opts.file || !fs.existsSync(opts.file)) {
    console.error(`ERROR: File not found: ${opts.file}`)
    process.exit(1)
  }

  const config = getConfig()
  if (!config.password) {
    console.error('ERROR: UNI_ADMIN_PASSWORD not set in .env.release')
    process.exit(1)
  }

  const typeName = opts.type === 'native_app' ? '原生App安装包' : 'wgt资源包'
  console.log('')
  console.log(`  发布 ${typeName} v${opts.version}`)
  console.log(`  文件: ${opts.file}`)
  console.log('')

  try {
    // 1. 登录
    const token = await login(config)

    // 2. 上传文件
    const fileUrl = await uploadFile(path.resolve(opts.file), token, config)

    // 3. 创建版本记录
    await createVersion(opts, fileUrl, token, config)

    console.log('')
    console.log('  ========================================')
    console.log(`  发布成功! v${opts.version} (${typeName})`)
    console.log('  ========================================')
    console.log('')
    console.log(`  下载链接: ${fileUrl}`)
    console.log(`  管理后台: https://sd-uni-admin.qlfloor.com/admin/`)
    console.log('')
    return true
  } catch (err) {
    console.error('')
    console.error(`  自动发布失败: ${err.message}`)
    console.error('')
    console.error('  请手动在 uni-admin 升级中心发布:')
    console.error('  1. 打开 https://sd-uni-admin.qlfloor.com/admin/')
    console.error('  2. 登录 → 系统管理 → App升级中心')
    console.error('  3. 切换到 Foggy Navigator')
    console.error(`  4. 发布新版 → ${typeName}`)
    console.error(`  5. 版本号: ${opts.version}`)
    console.error(`  6. 上传文件: ${path.resolve(opts.file)}`)
    console.error(`  7. 开启"上线发行" → 发布`)
    console.error('')

    // 尝试打开浏览器
    const adminUrl = `https://sd-uni-admin.qlfloor.com/admin/#/uni_modules/uni-upgrade-center/pages/version/add?appid=${config.appid}&name=${encodeURIComponent(config.appName)}&type=${opts.type}`
    try {
      if (process.platform === 'win32') {
        execSync(`start "" "${adminUrl}"`, { stdio: 'ignore' })
        console.log('  已在浏览器中打开升级中心')
      }
    } catch { /* ignore */ }

    return false
  }
}

// --- 入口 ---
async function main() {
  const opts = parseArgs()

  switch (opts.command) {
    case 'publish':
      const ok = await publish(opts)
      process.exit(ok ? 0 : 1)
      break
    default:
      console.log('用法:')
      console.log('  node uni-admin-api.js publish --type native_app|wgt --version X.Y.Z \\')
      console.log('    --title "标题" --content "更新内容" --file <path> [--silent] [--force]')
      process.exit(1)
  }
}

main().catch(err => {
  console.error('Fatal error:', err)
  process.exit(1)
})
