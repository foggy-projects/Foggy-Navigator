import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  prepareReleaseAssets,
  RELEASE_PLATFORMS,
  RELEASE_PRODUCT,
  RELEASE_SCHEMA_VERSION,
} from './release-assets.mjs'
import { resolveReleaseVersion } from './release-version.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export function compareSemver(left, right) {
  const parse = value => {
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
  const leftParts = a.prerelease.split('.')
  const rightParts = b.prerelease.split('.')
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    if (index >= leftParts.length) return -1
    if (index >= rightParts.length) return 1
    if (leftParts[index] === rightParts[index]) continue
    const leftNumeric = /^\d+$/.test(leftParts[index])
    const rightNumeric = /^\d+$/.test(rightParts[index])
    if (leftNumeric && rightNumeric) return Number(leftParts[index]) < Number(rightParts[index]) ? -1 : 1
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1
    return leftParts[index] < rightParts[index] ? -1 : 1
  }
  return 0
}

export function assertPublishAllowed(localManifest, remoteManifest, allowSameVersion) {
  if (!remoteManifest) return
  if (remoteManifest.product && (remoteManifest.product !== RELEASE_PRODUCT || remoteManifest.schemaVersion !== RELEASE_SCHEMA_VERSION)) {
    throw new Error('Remote latest.json has an unexpected product or schema')
  }
  const comparison = compareSemver(localManifest.version, remoteManifest.version)
  if (comparison < 0) throw new Error(`Local version ${localManifest.version} is older than remote ${remoteManifest.version}`)
  if (comparison > 0) return
  if (!allowSameVersion) throw new Error(`Remote latest.json is already version ${remoteManifest.version}`)
  for (const platform of RELEASE_PLATFORMS) {
    if (remoteManifest.sha256?.[platform] !== localManifest.sha256[platform] ||
        remoteManifest.bytes?.[platform] !== localManifest.bytes[platform]) {
      throw new Error('Same-version repair is allowed only when every archive hash and size is unchanged')
    }
  }
}

function parseArguments(args) {
  const options = { allowSameVersion: false, allowDirty: false, allowUnpushed: false }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--allow-same-version') options.allowSameVersion = true
    else if (argument === '--allow-dirty') options.allowDirty = true
    else if (argument === '--allow-unpushed') options.allowUnpushed = true
    else if (['--output-dir', '--obs-bucket', '--base-url', '--obsutil', '--obsutil-config', '--smoke-result'].includes(argument)) {
      const value = args[index + 1]
      if (!value) throw new Error(`Missing value for ${argument}`)
      options[argument.slice(2).replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase())] = value
      index += 1
    } else throw new Error(`Unknown publish argument: ${argument}`)
  }
  return options
}

function loadDotEnv(filePath) {
  if (!fs.existsSync(filePath)) return
  for (const line of fs.readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
    if (!match || process.env[match[1]] !== undefined) continue
    process.env[match[1]] = match[2].trim().replace(/^['"]|['"]$/g, '')
  }
}

function git(args) {
  return spawnSync('git', ['-C', workerDir, ...args], { encoding: 'utf8' })
}

export function readGitMetadata() {
  const commitResult = git(['rev-parse', 'HEAD'])
  const statusResult = git(['status', '--short', '--', '.'])
  const upstreamResult = git(['rev-parse', '@{upstream}'])
  return {
    commit: commitResult.status === 0 ? commitResult.stdout.trim() : '',
    dirty: statusResult.status !== 0 || Boolean(statusResult.stdout.trim()),
    upstream: upstreamResult.status === 0 ? upstreamResult.stdout.trim() : '',
  }
}

export function assertGitPublishReady(metadata, options) {
  if (metadata.dirty && !options.allowDirty) throw new Error('Publishing requires a clean Codex Worker worktree; use --allow-dirty only for an explicit recovery')
  if ((!metadata.upstream || metadata.upstream !== metadata.commit) && !options.allowUnpushed) {
    throw new Error('Publishing requires HEAD to match its pushed upstream; use --allow-unpushed only in a controlled release environment')
  }
}

async function readRemoteLatest(baseUrl, fetchImpl = fetch) {
  let response
  try {
    response = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/latest.json?ts=${Date.now()}`, { headers: { 'cache-control': 'no-cache' } })
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
    process.platform === 'win32' ? 'C:\\Windows\\obsutil.exe' : undefined,
    process.platform === 'win32' ? path.join(os.homedir(), 'obsutil', 'obsutil.exe') : undefined,
    path.join(os.homedir(), 'obsutil', process.platform === 'win32' ? 'obsutil.exe' : 'obsutil'),
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
  let lastError
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const actual = await readRemoteLatest(baseUrl, fetchImpl)
      if (JSON.stringify(actual) !== JSON.stringify(assets.manifest)) throw new Error('remote latest.json does not match the published manifest')
      for (const platform of RELEASE_PLATFORMS) {
        const response = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/${assets.manifest.files[platform]}?ts=${Date.now()}`, { headers: { 'cache-control': 'no-cache' } })
        if (!response.ok) throw new Error(`${assets.manifest.files[platform]} returned HTTP ${response.status}`)
        const bytes = Buffer.from(await response.arrayBuffer())
        const sha256 = crypto.createHash('sha256').update(bytes).digest('hex')
        if (bytes.length !== assets.manifest.bytes[platform] || sha256 !== assets.manifest.sha256[platform]) {
          throw new Error(`${platform} archive failed byte length or SHA-256 verification`)
        }
      }
      for (const [relativePath, localPath] of [
        ['install.ps1', assets.installPs1Path],
        ['install.sh', assets.installShPath],
        [assets.manifest.evidence, assets.evidencePath],
      ]) {
        const response = await fetchImpl(`${baseUrl.replace(/\/+$/, '')}/${relativePath}?ts=${Date.now()}`, { headers: { 'cache-control': 'no-cache' } })
        if (!response.ok) throw new Error(`${relativePath} returned HTTP ${response.status}`)
        if (!Buffer.from(await response.arrayBuffer()).equals(fs.readFileSync(localPath))) throw new Error(`${relativePath} does not match local release bytes`)
      }
      return
    } catch (error) {
      lastError = error
      if (attempt + 1 < attempts && retryMs > 0) await new Promise(resolve => setTimeout(resolve, retryMs))
    }
  }
  throw new Error(`OBS verification failed: ${lastError instanceof Error ? lastError.message : String(lastError)}`)
}

async function main() {
  loadDotEnv(path.join(workerDir, '.env'))
  const options = parseArguments(process.argv.slice(2))
  const outputDir = path.resolve(workerDir, options.outputDir || 'release/output')
  const version = resolveReleaseVersion(workerDir)
  const obsBucket = options.obsBucket || process.env.RELEASE_OBS_BUCKET
  const baseUrl = options.baseUrl || process.env.RELEASE_BASE_URL
  if (!obsBucket || !baseUrl) throw new Error('RELEASE_OBS_BUCKET and RELEASE_BASE_URL must be configured')
  const smokePath = path.resolve(workerDir, options.smokeResult || path.join(outputDir, 'smoke-result.json'))
  if (!fs.existsSync(smokePath)) throw new Error('A smoke-result.json produced by package-release is required for publishing')
  const verification = JSON.parse(fs.readFileSync(smokePath, 'utf8'))
  if (verification.packageVerificationSkipped) throw new Error('Publishing refuses a candidate built with skipped package verification')
  if (!['skip', 'basic', 'full'].includes(verification.level)) throw new Error('smoke-result.json has an invalid effective smoke level')
  const gitMetadata = readGitMetadata()
  assertGitPublishReady(gitMetadata, options)
  const assets = prepareReleaseAssets({
    workerDir,
    outputDir,
    version,
    baseUrl,
    gitCommit: gitMetadata.commit,
    gitDirty: gitMetadata.dirty,
    smokeLevel: verification.level,
    verification,
  })
  const remote = await readRemoteLatest(baseUrl)
  assertPublishAllowed(assets.manifest, remote, options.allowSameVersion)
  const obsutil = findObsutil(options.obsutil || process.env.CODEX_WORKER_OBSUTIL)
  const obsutilConfig = resolveObsutilConfig(options.obsutilConfig || process.env.CODEX_WORKER_OBSUTIL_CONFIG)
  process.stdout.write(`Publishing ${RELEASE_PRODUCT} ${version}\nOBS: ${obsBucket}\nURL: ${baseUrl}\n`)
  for (const platform of RELEASE_PLATFORMS) {
    const item = assets.assets[platform]
    upload(obsutil, item.archivePath, `${obsBucket}/${version}/${path.basename(item.archivePath)}`, obsutilConfig)
    upload(obsutil, item.checksumPath, `${obsBucket}/${version}/${path.basename(item.checksumPath)}`, obsutilConfig)
  }
  upload(obsutil, assets.evidencePath, `${obsBucket}/${version}/release-evidence.json`, obsutilConfig)
  upload(obsutil, assets.installShPath, `${obsBucket}/install.sh`, obsutilConfig)
  upload(obsutil, assets.installPs1Path, `${obsBucket}/install.ps1`, obsutilConfig)
  const latestBeforeCommit = await readRemoteLatest(baseUrl)
  assertPublishAllowed(assets.manifest, latestBeforeCommit, options.allowSameVersion)
  upload(obsutil, assets.latestPath, `${obsBucket}/latest.json`, obsutilConfig)
  await verifyPublishedRelease(baseUrl, assets)
  process.stdout.write(`Published and verified ${baseUrl}/latest.json\n`)
}

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url
if (isMain) main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
  process.exitCode = 1
})
