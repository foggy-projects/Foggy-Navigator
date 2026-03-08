/**
 * uni-admin 升级中心 API 工具
 *
 * 功能：登录 uni-id → 上传文件到 uniCloud 云存储 → 创建版本记录
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
 *
 * uniCloud 客户端 API 协议说明：
 *   - 所有请求发送到 https://api.next.bspapp.com/client
 *   - 签名算法: HmacMD5(sortedQueryString, clientSecret)
 *   - 云函数调用: 先获取匿名 accessToken, 再通过 x-basement-token 头传递
 *   - clientDB 调用: 需在 functionArgs 中包含 uniIdToken
 */

const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
const ENV_PATH = path.join(ROOT, '.env.release')
const API_URL = 'https://api.next.bspapp.com/client'

// --- 默认配置 ---
const DEFAULTS = {
  UNICLOUD_SPACE_ID: 'mp-4af7054d-5a40-4315-8678-df36b44298bb',
  UNICLOUD_CLIENT_SECRET: 'bFl25CiSXhK7K979vXI2rA==',
  DCLOUD_APPID: '__UNI__AC2B8DB',
  // uni-admin 的 appid（用于 uni-id 登录上下文）
  ADMIN_APPID: '__UNI__28EB4A7',
  APP_NAME: 'Foggy Navigator',
  UNI_ADMIN_USERNAME: 'root',
}

// ============================================================
// 工具函数
// ============================================================

function readEnvFile(filePath) {
  const env = {}
  if (fs.existsSync(filePath)) {
    const content = fs.readFileSync(filePath, 'utf-8')
    for (const line of content.split(/\r?\n/)) {
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
    clientSecret: DEFAULTS.UNICLOUD_CLIENT_SECRET,
    appid: env.DCLOUD_APPID || DEFAULTS.DCLOUD_APPID,
    adminAppid: DEFAULTS.ADMIN_APPID,
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

// ============================================================
// uniCloud 客户端 API — 签名 & 请求
// ============================================================

function hmacMD5(data, key) {
  return crypto.createHmac('md5', key).update(data).digest('hex')
}

function signBody(body, clientSecret) {
  let str = ''
  Object.keys(body).sort().forEach(k => {
    if (body[k]) str += '&' + k + '=' + body[k]
  })
  return hmacMD5(str.slice(1), clientSecret)
}

/** 云函数/文件操作请求（使用匿名 accessToken） */
async function cloudRequest(body, accessToken, clientSecret) {
  const headers = { 'Content-Type': 'application/json' }
  if (accessToken) {
    body.token = accessToken
    headers['x-basement-token'] = accessToken
  }
  headers['x-serverless-sign'] = signBody(body, clientSecret)
  const resp = await fetch(API_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  return resp.json()
}

/** clientDB 请求（需同时传匿名 token 和 uni-id JWT） */
async function clientDBRequest(body, accessToken, uniIdToken, clientSecret, adminAppid) {
  const headers = { 'Content-Type': 'application/json' }
  body.token = accessToken
  headers['x-basement-token'] = accessToken
  headers['x-serverless-sign'] = signBody(body, clientSecret)
  headers['x-client-token'] = uniIdToken
  headers['x-client-info'] = encodeURIComponent(JSON.stringify({
    PLATFORM: 'web', OS: 'web', APPID: adminAppid, uniPlatform: 'web',
  }))
  const resp = await fetch(API_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  return resp.json()
}

// ============================================================
// Step 1: 获取匿名 accessToken
// ============================================================

async function getAccessToken(config) {
  const result = await cloudRequest({
    method: 'serverless.auth.user.anonymousAuthorize',
    params: '{}',
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, null, config.clientSecret)

  if (!result.success || !result.data || !result.data.accessToken) {
    throw new Error(`获取 accessToken 失败: ${JSON.stringify(result)}`)
  }
  return result.data.accessToken
}

// ============================================================
// Step 2: 登录 uni-id
// ============================================================

async function login(config, accessToken) {
  console.log('  [1] 登录 uni-id...')
  const result = await cloudRequest({
    method: 'serverless.function.runtime.invoke',
    params: JSON.stringify({
      functionTarget: 'uni-id-co',
      functionArgs: {
        method: 'login',
        params: [{ username: config.username, password: config.password }],
        clientInfo: {
          PLATFORM: 'web', OS: 'web',
          APPID: config.adminAppid, uniPlatform: 'web',
        },
      },
    }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, config.clientSecret)

  if (result.data && result.data.errCode === 0 && result.data.newToken) {
    console.log('  [1] 登录成功')
    return result.data.newToken.token
  }

  throw new Error(`登录失败: ${JSON.stringify(result.data || result.error)}`)
}

// ============================================================
// Step 3: 上传文件到 uniCloud 云存储
// ============================================================

async function uploadFile(filePath, accessToken, config) {
  const fileName = path.basename(filePath)
  const fileSize = fs.statSync(filePath).size
  const sizeMB = (fileSize / 1024 / 1024).toFixed(2)
  console.log(`  [2] 上传文件: ${fileName} (${sizeMB} MB)...`)

  // 获取上传凭证
  const signResult = await cloudRequest({
    method: 'serverless.file.resource.generateProximalSign',
    params: JSON.stringify({ env: 'public', filename: fileName }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, config.clientSecret)

  if (!signResult.success || !signResult.data || !signResult.data.host) {
    throw new Error(`获取上传凭证失败: ${JSON.stringify(signResult)}`)
  }

  const { host, ossPath, policy, signature, accessKeyId, securityToken, id } = signResult.data

  // 上传到 OSS
  const { FormData, File } = await importFormData()
  const fileBuffer = fs.readFileSync(filePath)
  const formData = new FormData()
  formData.append('key', ossPath)
  formData.append('policy', policy)
  formData.append('OSSAccessKeyId', accessKeyId)
  formData.append('signature', signature)
  if (securityToken) {
    formData.append('x-oss-security-token', securityToken)
  }
  formData.append('success_action_status', '200')
  formData.append('file', new File([fileBuffer], fileName))

  const ossUrl = host.startsWith('http') ? host : `https://${host}`
  console.log(`  [2] 上传到 OSS: ${ossUrl}`)
  let uploadResp
  try {
    uploadResp = await fetch(ossUrl, {
      method: 'POST',
      body: formData,
    })
  } catch (fetchErr) {
    throw new Error(`OSS fetch 失败: ${fetchErr.message} (${fetchErr.cause ? fetchErr.cause.message : 'no cause'})`)
  }

  if (!uploadResp.ok && uploadResp.status !== 200) {
    const errText = await uploadResp.text().catch(() => '')
    throw new Error(`OSS 上传失败: ${uploadResp.status} ${uploadResp.statusText} ${errText}`)
  }

  // 上报上传完成
  await cloudRequest({
    method: 'serverless.file.resource.report',
    params: JSON.stringify({ id }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, config.clientSecret)

  const cdnUrl = `https://${config.spaceId}.cdn.bspapp.com/${ossPath}`
  console.log(`  [2] 上传成功: ${cdnUrl}`)
  return cdnUrl
}

async function importFormData() {
  if (globalThis.FormData && globalThis.File) {
    return { FormData: globalThis.FormData, File: globalThis.File }
  }
  try {
    const { FormData, File } = require('undici')
    return { FormData, File }
  } catch {
    throw new Error('需要 Node.js 18+ 或安装 undici 包')
  }
}

// ============================================================
// Step 4: 创建版本记录（通过 DCloud-clientDB）
// ============================================================

async function createVersion(opts, fileUrl, accessToken, uniIdToken, config) {
  console.log('  [3] 创建版本记录...')

  const versionData = {
    appid: config.appid,
    name: config.appName,
    title: opts.title || `v${opts.version}`,
    contents: opts.content || '',
    platform: ['Android'],
    type: opts.type,
    version: opts.version,
    min_uni_version: opts.minVersion || '',
    url: fileUrl,
    is_silently: opts.silent ? true : false,
    is_mandatory: opts.force ? true : false,
    uni_platform: 'android',
    stable_publish: true,
    create_env: 'upgrade-center',
  }

  const result = await clientDBRequest({
    method: 'serverless.function.runtime.invoke',
    params: JSON.stringify({
      functionTarget: 'DCloud-clientDB',
      functionArgs: {
        command: {
          $db: [
            { $method: 'collection', $param: ['opendb-app-versions'] },
            { $method: 'add', $param: [versionData] },
          ],
        },
        action: '',
        multiCommand: false,
        uniIdToken,
      },
    }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, uniIdToken, config.clientSecret, config.adminAppid)

  if (!result.success) {
    throw new Error(`创建版本记录失败: ${JSON.stringify(result.error || result)}`)
  }

  const data = typeof result.data === 'string' ? JSON.parse(result.data) : result.data
  if (data.errCode && data.errCode !== 0) {
    throw new Error(`创建版本记录失败: ${data.errMsg || JSON.stringify(data)}`)
  }

  const docId = data.id || (data.data && data.data.id)
  if (docId) {
    console.log(`  [3] 版本记录已创建: ${docId}`)
  } else {
    console.log(`  [3] 版本记录已创建`)
  }
  return docId
}

// ============================================================
// 查询线上最新版本
// ============================================================

async function latestVersion() {
  const config = getConfig()
  if (!config.password) {
    console.error('ERROR: UNI_ADMIN_PASSWORD not set in .env.release')
    process.exit(1)
  }

  // Suppress login() console.log output — it goes to stdout and pollutes JSON parsing
  const origLog = console.log
  console.log = (...args) => console.error(...args)
  let accessToken, uniIdToken
  try {
    accessToken = await getAccessToken(config)
    uniIdToken = await login(config, accessToken)
  } finally {
    console.log = origLog
  }

  const result = await clientDBRequest({
    method: 'serverless.function.runtime.invoke',
    params: JSON.stringify({
      functionTarget: 'DCloud-clientDB',
      functionArgs: {
        command: {
          $db: [
            { $method: 'collection', $param: ['opendb-app-versions'] },
            { $method: 'where', $param: [{ appid: config.appid }] },
            { $method: 'field', $param: [{ version: true, type: true, create_date: true }] },
            { $method: 'orderBy', $param: ['create_date', 'desc'] },
            { $method: 'limit', $param: [1] },
            { $method: 'get', $param: [] },
          ],
        },
        action: '',
        multiCommand: false,
        uniIdToken,
      },
    }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, uniIdToken, config.clientSecret, config.adminAppid)

  if (!result.success) {
    throw new Error(`查询版本失败: ${JSON.stringify(result.error || result)}`)
  }

  const data = typeof result.data === 'string' ? JSON.parse(result.data) : result.data
  if (data.errCode && data.errCode !== 0) {
    throw new Error(`查询版本失败: ${data.errMsg || JSON.stringify(data)}`)
  }

  const records = data.data || []
  if (records.length === 0 || !records[0].version) {
    // 没有任何版本记录
    console.log(JSON.stringify({ version: '', type: '' }))
    return
  }

  const latest = records[0]
  console.log(JSON.stringify({ version: latest.version, type: latest.type || '' }))
}

// ============================================================
// 查询线上最新原生 App 版本（用于 wgt 的 minVersion）
// ============================================================

async function latestNativeVersion() {
  const config = getConfig()
  if (!config.password) {
    console.error('ERROR: UNI_ADMIN_PASSWORD not set in .env.release')
    process.exit(1)
  }

  const origLog = console.log
  console.log = (...args) => console.error(...args)
  let accessToken, uniIdToken
  try {
    accessToken = await getAccessToken(config)
    uniIdToken = await login(config, accessToken)
  } finally {
    console.log = origLog
  }

  const result = await clientDBRequest({
    method: 'serverless.function.runtime.invoke',
    params: JSON.stringify({
      functionTarget: 'DCloud-clientDB',
      functionArgs: {
        command: {
          $db: [
            { $method: 'collection', $param: ['opendb-app-versions'] },
            { $method: 'where', $param: [{ appid: config.appid, type: 'native_app' }] },
            { $method: 'field', $param: [{ version: true, create_date: true }] },
            { $method: 'orderBy', $param: ['create_date', 'desc'] },
            { $method: 'limit', $param: [1] },
            { $method: 'get', $param: [] },
          ],
        },
        action: '',
        multiCommand: false,
        uniIdToken,
      },
    }),
    spaceId: config.spaceId,
    timestamp: Date.now(),
  }, accessToken, uniIdToken, config.clientSecret, config.adminAppid)

  if (!result.success) {
    throw new Error(`查询版本失败: ${JSON.stringify(result.error || result)}`)
  }

  const data = typeof result.data === 'string' ? JSON.parse(result.data) : result.data
  if (data.errCode && data.errCode !== 0) {
    throw new Error(`查询版本失败: ${data.errMsg || JSON.stringify(data)}`)
  }

  const records = data.data || []
  if (records.length === 0 || !records[0].version) {
    console.log(JSON.stringify({ version: '' }))
    return
  }

  console.log(JSON.stringify({ version: records[0].version }))
}

// ============================================================
// 主流程: 发布
// ============================================================

async function publish(opts) {
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
    // 获取匿名 accessToken
    const accessToken = await getAccessToken(config)

    // 1. 登录 uni-id
    const uniIdToken = await login(config, accessToken)

    // 2. 上传文件
    const fileUrl = await uploadFile(path.resolve(opts.file), accessToken, config)

    // 3. 创建版本记录
    await createVersion(opts, fileUrl, accessToken, uniIdToken, config)

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

// ============================================================
// 入口
// ============================================================

async function main() {
  const opts = parseArgs()

  switch (opts.command) {
    case 'publish': {
      const ok = await publish(opts)
      process.exit(ok ? 0 : 1)
      break
    }
    case 'latest-version': {
      await latestVersion()
      break
    }
    case 'latest-native-version': {
      await latestNativeVersion()
      break
    }
    default:
      console.log('用法:')
      console.log('  node uni-admin-api.js publish --type native_app|wgt --version X.Y.Z \\')
      console.log('    --title "标题" --content "更新内容" --file <path> [--silent] [--force]')
      console.log('')
      console.log('  node uni-admin-api.js latest-version')
      console.log('    查询线上最新版本（任意类型）')
      console.log('')
      console.log('  node uni-admin-api.js latest-native-version')
      console.log('    查询线上最新原生 App 版本（用于 wgt 的 minVersion）')
      process.exit(1)
  }
}

main().catch(err => {
  console.error('Fatal error:', err)
  process.exit(1)
})
