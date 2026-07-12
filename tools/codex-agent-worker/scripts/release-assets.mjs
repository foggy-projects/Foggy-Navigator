import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

export const RELEASE_PRODUCT = 'codex-agent-worker'
export const RELEASE_SCHEMA_VERSION = 1
export const RELEASE_PLATFORMS = ['linux', 'macos', 'windows']

export function checksum(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex')
}

export function archiveName(version, platform) {
  const extension = platform === 'windows' ? 'zip' : 'tar.gz'
  return `codex-worker-${version}-${platform}.${extension}`
}

export function parseChecksumSidecar(content, expectedName) {
  const match = String(content).trim().match(/^([0-9a-f]{64})\s+\*?(.+)$/i)
  if (!match) throw new Error(`Invalid SHA-256 sidecar for ${expectedName}`)
  if (match[2] !== expectedName) throw new Error(`SHA-256 sidecar must name ${expectedName}`)
  return match[1].toLowerCase()
}

export function injectReleaseBaseUrl(source, baseUrl, platform) {
  const normalized = baseUrl.replace(/\/+$/, '')
  const pattern = platform === 'powershell'
    ? /\$ReleaseBaseUrl\s*=\s*"__RELEASE_BASE_URL__"/g
    : /RELEASE_BASE_URL="__RELEASE_BASE_URL__"/g
  const matches = source.match(pattern) || []
  if (matches.length !== 1) throw new Error(`Expected exactly one release URL placeholder in ${platform} bootstrap`)
  return source.replace(pattern, platform === 'powershell'
    ? `$ReleaseBaseUrl = "${normalized}"`
    : `RELEASE_BASE_URL="${normalized}"`)
}

export function collectArchiveMetadata(outputDir, version, platforms = RELEASE_PLATFORMS) {
  const files = {}
  const sha256 = {}
  const bytes = {}
  const assets = {}
  for (const platform of platforms) {
    const name = archiveName(version, platform)
    const archivePath = path.join(outputDir, name)
    const checksumPath = `${archivePath}.sha256`
    if (!fs.existsSync(archivePath) || !fs.existsSync(checksumPath)) {
      throw new Error(`Missing release archive or checksum for ${platform}: ${name}`)
    }
    const archiveBytes = fs.readFileSync(archivePath)
    const expected = parseChecksumSidecar(fs.readFileSync(checksumPath, 'utf8'), name)
    const actual = checksum(archiveBytes)
    if (actual !== expected) throw new Error(`${name} does not match its SHA-256 sidecar`)
    files[platform] = `${version}/${name}`
    sha256[platform] = actual
    bytes[platform] = archiveBytes.length
    assets[platform] = { archivePath, checksumPath }
  }
  return { files, sha256, bytes, assets }
}

export function createLatestManifest({ version, metadata, released, buildTimeUtc, gitCommit, gitDirty, smokeLevel }) {
  return {
    schemaVersion: RELEASE_SCHEMA_VERSION,
    product: RELEASE_PRODUCT,
    version,
    released,
    buildTimeUtc,
    gitCommit: gitCommit || '',
    gitDirty: Boolean(gitDirty),
    smokeLevel,
    files: metadata.files,
    sha256: metadata.sha256,
    bytes: metadata.bytes,
    evidence: `${version}/release-evidence.json`,
  }
}

export function prepareReleaseAssets({
  workerDir,
  outputDir,
  version,
  baseUrl,
  gitCommit,
  gitDirty,
  smokeLevel,
  verification,
  released = new Date().toISOString().slice(0, 10),
  buildTimeUtc = new Date().toISOString(),
}) {
  const metadata = collectArchiveMetadata(outputDir, version)
  const manifest = createLatestManifest({
    version,
    metadata,
    released,
    buildTimeUtc,
    gitCommit,
    gitDirty,
    smokeLevel,
  })
  const evidence = {
    schemaVersion: 1,
    product: RELEASE_PRODUCT,
    version,
    released,
    buildTimeUtc,
    gitCommit: gitCommit || '',
    gitDirty: Boolean(gitDirty),
    smokeLevel,
    verification,
    artifacts: RELEASE_PLATFORMS.map(platform => ({
      platform,
      file: manifest.files[platform],
      bytes: manifest.bytes[platform],
      sha256: manifest.sha256[platform],
    })),
  }
  const latestPath = path.join(outputDir, 'latest.json')
  const evidencePath = path.join(outputDir, 'release-evidence.json')
  const installShPath = path.join(outputDir, 'install.sh')
  const installPs1Path = path.join(outputDir, 'install.ps1')
  fs.writeFileSync(latestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  fs.writeFileSync(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, 'utf8')
  fs.writeFileSync(installShPath, injectReleaseBaseUrl(
    fs.readFileSync(path.join(workerDir, 'release', 'remote-install.sh'), 'utf8'),
    baseUrl,
    'shell',
  ).replaceAll('\r\n', '\n'), 'utf8')
  fs.writeFileSync(installPs1Path, injectReleaseBaseUrl(
    fs.readFileSync(path.join(workerDir, 'release', 'remote-install.ps1'), 'utf8'),
    baseUrl,
    'powershell',
  ).replaceAll('\r\n', '\n'), 'utf8')
  return { manifest, evidence, latestPath, evidencePath, installShPath, installPs1Path, ...metadata }
}
