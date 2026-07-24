import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  DEFAULT_RELEASE_BASE_URL,
  DEFAULT_RELEASE_OBS_BUCKET,
  RELEASE_PRODUCT,
  prepareReleaseAssets,
} from './release-assets.mjs'
import { resolveReleaseVersion } from './release-version.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export function compareSemver(left, right) {
  const parse = (value) => {
    const match = String(value).match(/^(\d+)\.(\d+)\.(\d+)(?:-([^+]+))?(?:\+.*)?$/)
    if (!match) throw new Error(`Invalid semantic version: ${value}`)
    return { core: match.slice(1, 4).map(Number), prerelease: match[4] || '' }
  }
  const a = parse(left)
  const b = parse(right)
  for (let index = 0; index < 3; index += 1) {
    if (a.core[index] !== b.core[index]) return a.core[index] < b.core[index] ? -1 : 1
  }
  if (a.prerelease === b.prerelease) return 0
  if (!a.prerelease) return 1
  if (!b.prerelease) return -1
  const leftIdentifiers = a.prerelease.split('.')
  const rightIdentifiers = b.prerelease.split('.')
  for (let index = 0; index < Math.max(leftIdentifiers.length, rightIdentifiers.length); index += 1) {
    if (index >= leftIdentifiers.length) return -1
    if (index >= rightIdentifiers.length) return 1
    const leftIdentifier = leftIdentifiers[index]
    const rightIdentifier = rightIdentifiers[index]
    if (leftIdentifier === rightIdentifier) continue
    const leftNumeric = /^\d+$/.test(leftIdentifier)
    const rightNumeric = /^\d+$/.test(rightIdentifier)
    if (leftNumeric && rightNumeric) {
      const normalizedLeft = leftIdentifier.replace(/^0+(?=\d)/, '')
      const normalizedRight = rightIdentifier.replace(/^0+(?=\d)/, '')
      if (normalizedLeft.length !== normalizedRight.length) return normalizedLeft.length < normalizedRight.length ? -1 : 1
      return normalizedLeft < normalizedRight ? -1 : 1
    }
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1
    return leftIdentifier < rightIdentifier ? -1 : 1
  }
  return 0
}

export function assertPublishAllowed(localManifest, remoteManifest, allowSameVersion) {
  if (!remoteManifest) return
  if (remoteManifest.product !== RELEASE_PRODUCT || remoteManifest.schemaVersion !== 1) {
    throw new Error('Remote latest.json has an unexpected product or schema')
  }
  const comparison = compareSemver(localManifest.version, remoteManifest.version)
  if (comparison < 0) {
    throw new Error(`Local version ${localManifest.version} is older than remote ${remoteManifest.version}`)
  }
  if (comparison > 0) return
  if (!allowSameVersion) {
    throw new Error(`Remote latest.json is already version ${remoteManifest.version}`)
  }
  for (const platform of ['linux', 'windows']) {
    if (remoteManifest.sha256?.[platform] !== localManifest.sha256[platform] ||
        remoteManifest.bytes?.[platform] !== localManifest.bytes[platform]) {
      throw new Error('Same-version metadata repair is allowed only when archive hash and size are unchanged')
    }
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  const outputDir = path.resolve(workerDir, options.outputDir || 'release/output')
  const version = resolveReleaseVersion(workerDir)
  const obsBucket = options.obsBucket || process.env.CODEX_APP_SERVER_RELEASE_OBS_BUCKET || DEFAULT_RELEASE_OBS_BUCKET
  const baseUrl = options.baseUrl || process.env.CODEX_APP_SERVER_RELEASE_BASE_URL || DEFAULT_RELEASE_BASE_URL
  const git = readGitMetadata()
  const assets = prepareReleaseAssets({
    workerDir,
    outputDir,
    version,
    baseUrl,
    gitCommit: git.commit,
    gitDirty: git.dirty,
  })

  const remote = await readRemoteLatest(baseUrl)
  assertPublishAllowed(assets.manifest, remote, options.allowSameVersion)
  const obsutil = findObsutil(options.obsutil || process.env.CODEX_APP_SERVER_OBSUTIL)
  const obsutilConfig = resolveObsutilConfig(options.obsutilConfig || process.env.CODEX_APP_SERVER_OBSUTIL_CONFIG)
  process.stdout.write(`Publishing ${RELEASE_PRODUCT} ${version}\n`)
  process.stdout.write(`OBS: ${obsBucket}\nURL: ${baseUrl}\n`)

  upload(obsutil, assets.archivePath, `${obsBucket}/${version}/${path.basename(assets.archivePath)}`, obsutilConfig)
  upload(obsutil, assets.checksumPath, `${obsBucket}/${version}/${path.basename(assets.checksumPath)}`, obsutilConfig)
  upload(obsutil, assets.installShPath, `${obsBucket}/install.sh`, obsutilConfig)
  upload(obsutil, assets.installPs1Path, `${obsBucket}/install.ps1`, obsutilConfig)
  // Narrow the concurrent-publisher window before committing the mutable pointer.
  const latestBeforeCommit = await readRemoteLatest(baseUrl)
  assertPublishAllowed(assets.manifest, latestBeforeCommit, options.allowSameVersion)
  // latest.json is the release commit point and must remain last.
  upload(obsutil, assets.latestPath, `${obsBucket}/latest.json`, obsutilConfig)

  await verifyPublishedRelease(baseUrl, assets)
  process.stdout.write(`Published and verified ${baseUrl}/latest.json\n`)
  process.stdout.write(`Linux:  curl -fsSL ${baseUrl}/install.sh | bash\n`)
  process.stdout.write(`Windows: irm ${baseUrl}/install.ps1 | iex\n`)
}

function parseArguments(args) {
  const options = { allowSameVersion: false }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--allow-same-version') options.allowSameVersion = true
    else if (['--output-dir', '--obs-bucket', '--base-url', '--obsutil', '--obsutil-config'].includes(argument)) {
      const value = args[index + 1]
      if (!value) throw new Error(`Missing value for ${argument}`)
      options[argument.slice(2).replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase())] = value
      index += 1
    } else throw new Error(`Unknown publish argument: ${argument}`)
  }
  return options
}

async function readRemoteLatest(baseUrl, fetchImpl = fetch) {
  let response
  try {
    response = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/latest.json?ts=${Date.now()}`, {
      headers: { 'cache-control': 'no-cache' },
    })
  } catch (error) {
    throw new Error(`Unable to read remote latest.json: ${error instanceof Error ? error.message : String(error)}`)
  }
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`Unable to read remote latest.json: HTTP ${response.status}`)
  try {
    return await response.json()
  } catch {
    throw new Error('Remote latest.json is not valid JSON')
  }
}

function findObsutil(configured) {
  const candidates = [
    configured,
    process.platform === 'win32' ? 'D:\\work\\obsutil_windows_amd64_5.7.9\\obsutil.exe' : undefined,
    process.platform === 'win32' ? 'C:\\Windows\\obsutil.exe' : undefined,
    process.platform === 'win32' ? path.join(os.homedir(), 'obsutil', 'obsutil.exe') : undefined,
    'obsutil',
  ].filter(Boolean)
  for (const candidate of candidates) {
    const result = spawnSync(candidate, ['version'], { stdio: 'ignore', shell: false })
    if (!result.error && result.status === 0) return candidate
  }
  throw new Error('obsutil was not found or is not executable')
}

export function resolveObsutilConfig(configured, homeDir = os.homedir()) {
  const candidate = configured || path.join(homeDir, '.obsutilconfig')
  return fs.existsSync(candidate) ? path.resolve(candidate) : ''
}

function upload(obsutil, source, destination, obsutilConfig) {
  const args = ['cp', source, destination, '-f']
  if (obsutilConfig) args.push('-config=' + obsutilConfig)
  const result = spawnSync(obsutil, args, { stdio: 'inherit', shell: false })
  if (result.error || result.status !== 0) throw new Error(`obsutil upload failed: ${destination}`)
}

export async function verifyPublishedRelease(baseUrl, assets, options = {}) {
  const fetchImpl = options.fetchImpl || fetch
  const attempts = options.attempts || 5
  const retryMs = options.retryMs ?? 1_000
  const expected = assets.manifest
  let lastError
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const actual = await readRemoteLatest(baseUrl, fetchImpl)
      if (actual?.version !== expected.version || actual?.sha256?.windows !== expected.sha256.windows ||
          actual?.bytes?.windows !== expected.bytes.windows) {
        throw new Error('remote latest.json does not match the published release')
      }
      const archiveResponse = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/${expected.files.windows}?ts=${Date.now()}`, {
        headers: { 'cache-control': 'no-cache' },
      })
      if (!archiveResponse.ok) throw new Error(`${expected.files.windows} returned HTTP ${archiveResponse.status}`)
      const archive = Buffer.from(await archiveResponse.arrayBuffer())
      const archiveHash = crypto.createHash('sha256').update(archive).digest('hex')
      if (archive.length !== expected.bytes.windows || archiveHash !== expected.sha256.windows) {
        throw new Error('remote release archive failed byte length or SHA-256 verification')
      }
      for (const [relativePath, localPath] of [['install.ps1', assets.installPs1Path], ['install.sh', assets.installShPath]]) {
        const response = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/${relativePath}?ts=${Date.now()}`, {
          headers: { 'cache-control': 'no-cache' },
        })
        if (!response.ok) throw new Error(`${relativePath} returned HTTP ${response.status}`)
        const remoteBytes = Buffer.from(await response.arrayBuffer())
        const localBytes = fs.readFileSync(localPath)
        if (!remoteBytes.equals(localBytes)) throw new Error(`${relativePath} does not match the generated bootstrap`)
      }
      return
    } catch (error) {
      lastError = error
      if (attempt + 1 < attempts && retryMs > 0) await new Promise(resolve => setTimeout(resolve, retryMs))
    }
  }
  throw new Error(`OBS verification failed: ${lastError instanceof Error ? lastError.message : String(lastError)}`)
}

function readGitMetadata() {
  const commitResult = spawnSync('git', ['-C', workerDir, 'rev-parse', 'HEAD'], { encoding: 'utf8' })
  const statusResult = spawnSync('git', ['-C', workerDir, 'status', '--short', '--', '.'], { encoding: 'utf8' })
  return {
    commit: commitResult.status === 0 ? commitResult.stdout.trim() : '',
    dirty: statusResult.status === 0 && Boolean(statusResult.stdout.trim()),
  }
}

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url
if (isMain) {
  main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  })
}
