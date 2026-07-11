import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

export const RELEASE_SCHEMA_VERSION = 1
export const RELEASE_PRODUCT = 'codex-app-server-worker'
export const DEFAULT_RELEASE_OBS_BUCKET = 'obs://obs-fe55/codex-app-server-worker'
export const DEFAULT_RELEASE_BASE_URL = 'https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-app-server-worker'

const SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/i

export function normalizeReleaseBaseUrl(raw) {
  const value = String(raw || '').trim().replace(/\/+$/, '')
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error('Release base URL must be an absolute HTTP(S) URL')
  }
  const loopback = ['127.0.0.1', '::1', 'localhost'].includes(url.hostname.toLowerCase())
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) {
    throw new Error('Release base URL must use HTTPS unless it targets loopback')
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('Release base URL must not contain credentials, query parameters, or fragments')
  }
  if (!/^https?:\/\/(?:\[[0-9A-Fa-f:]+\]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?(?:\/[A-Za-z0-9._~-]+)*$/.test(value)) {
    throw new Error('Release base URL contains characters that are unsafe for bootstrap injection')
  }
  return value
}

export function parseChecksumSidecar(source, archiveName) {
  const lines = String(source).split(/\r?\n/).filter(line => line.trim())
  if (lines.length !== 1) throw new Error('Release checksum sidecar must contain exactly one entry')
  const match = lines[0].match(/^([0-9a-f]{64})\s+(.+)$/i)
  if (!match || match[2] !== archiveName) {
    throw new Error(`Release checksum sidecar must name ${archiveName}`)
  }
  return match[1].toLowerCase()
}

export function createLatestManifest({
  version,
  archiveName,
  checksum,
  bytes,
  released,
  buildTimeUtc,
  gitCommit = '',
  gitDirty = false,
}) {
  if (!SEMVER_PATTERN.test(version)) throw new Error(`Invalid release version: ${version}`)
  if (archiveName !== `${RELEASE_PRODUCT}-${version}.zip`) {
    throw new Error(`Unexpected release archive name: ${archiveName}`)
  }
  if (!SHA256_PATTERN.test(checksum)) throw new Error('Release checksum must be SHA-256')
  if (!Number.isSafeInteger(bytes) || bytes <= 0) throw new Error('Release archive size must be positive')
  const artifactPath = `${version}/${archiveName}`
  return {
    schemaVersion: RELEASE_SCHEMA_VERSION,
    product: RELEASE_PRODUCT,
    version,
    released,
    buildTimeUtc,
    gitCommit,
    gitDirty: Boolean(gitDirty),
    files: {
      linux: artifactPath,
      windows: artifactPath,
    },
    sha256: {
      linux: checksum.toLowerCase(),
      windows: checksum.toLowerCase(),
    },
    bytes: {
      linux: bytes,
      windows: bytes,
    },
  }
}

export function injectReleaseBaseUrl(template, baseUrl, platform) {
  const normalized = normalizeReleaseBaseUrl(baseUrl)
  const assignment = platform === 'powershell'
    ? '$ReleaseBaseUrl = "__RELEASE_BASE_URL__"'
    : 'RELEASE_BASE_URL="__RELEASE_BASE_URL__"'
  if (template.split(assignment).length !== 2) {
    throw new Error(`Expected exactly one ${platform} release URL assignment`)
  }
  const replacement = platform === 'powershell'
    ? `$ReleaseBaseUrl = "${normalized}"`
    : `RELEASE_BASE_URL="${normalized}"`
  return template.replace(assignment, replacement)
}

export function prepareReleaseAssets({
  workerDir,
  outputDir,
  version,
  baseUrl,
  released = new Date().toISOString().slice(0, 10),
  buildTimeUtc = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
  gitCommit = '',
  gitDirty = false,
}) {
  const archiveName = `${RELEASE_PRODUCT}-${version}.zip`
  const archivePath = path.join(outputDir, archiveName)
  const checksumPath = `${archivePath}.sha256`
  if (!fs.existsSync(archivePath) || !fs.statSync(archivePath).isFile()) {
    throw new Error(`Release archive not found: ${archivePath}`)
  }
  if (!fs.existsSync(checksumPath) || !fs.statSync(checksumPath).isFile()) {
    throw new Error(`Release checksum not found: ${checksumPath}`)
  }

  const archive = fs.readFileSync(archivePath)
  const checksum = crypto.createHash('sha256').update(archive).digest('hex')
  const sidecarChecksum = parseChecksumSidecar(fs.readFileSync(checksumPath, 'utf8'), archiveName)
  if (checksum !== sidecarChecksum) throw new Error('Release archive does not match its SHA-256 sidecar')

  const manifest = createLatestManifest({
    version,
    archiveName,
    checksum,
    bytes: archive.length,
    released,
    buildTimeUtc,
    gitCommit,
    gitDirty,
  })
  fs.mkdirSync(outputDir, { recursive: true })
  const latestPath = path.join(outputDir, 'latest.json')
  const installPs1Path = path.join(outputDir, 'install.ps1')
  const installShPath = path.join(outputDir, 'install.sh')
  writeUtf8Lf(latestPath, `${JSON.stringify(manifest, null, 2)}\n`)
  writeUtf8Lf(installPs1Path, injectReleaseBaseUrl(
    fs.readFileSync(path.join(workerDir, 'release', 'remote-install.ps1'), 'utf8'),
    baseUrl,
    'powershell',
  ))
  writeUtf8Lf(installShPath, injectReleaseBaseUrl(
    fs.readFileSync(path.join(workerDir, 'release', 'remote-install.sh'), 'utf8'),
    baseUrl,
    'shell',
  ))
  fs.chmodSync(installShPath, 0o755)
  return { archiveName, archivePath, checksumPath, checksum, manifest, latestPath, installPs1Path, installShPath }
}

function writeUtf8Lf(filePath, content) {
  fs.writeFileSync(filePath, String(content).replaceAll('\r\n', '\n').replaceAll('\r', '\n'), 'utf8')
}
