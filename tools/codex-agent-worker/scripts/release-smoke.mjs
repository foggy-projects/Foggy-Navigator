import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import zlib from 'node:zlib'
import { archiveName, collectArchiveMetadata, RELEASE_PLATFORMS } from './release-assets.mjs'
import { listTarGzEntries, listZipEntries } from './release-archive.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DOC_ONLY_PATTERN = /^(?:docs\/|tests\/|README\.md$|.*\.md$)/
const FULL_SMOKE_PATTERNS = [
  /^(?:release\/|update-worker\.)/,
  /^(?:package(?:-lock)?\.json|runtime-requirements\.json)$/,
  /^scripts\/(?:ensure-sdk|release-)/,
  /^src\/(?:codex\/|routes\/(?:query|tasks|processes)\.ts$|auth\.ts$|config\.ts$|runtime-requirements\.ts$)/,
]

function gitOutput(args) {
  const result = spawnSync('git', ['-C', workerDir, ...args], { encoding: 'utf8' })
  return result.status === 0 ? result.stdout.trim() : ''
}

export function detectChangedFiles() {
  const configured = process.env.RELEASE_CHANGED_FILES?.trim()
  if (configured) return [...new Set(configured.split(/[\r\n,]+/).map(value => value.trim()).filter(Boolean))]
  const prefix = 'tools/codex-agent-worker/'
  const committed = gitOutput(['diff', '--name-only', 'HEAD^', 'HEAD', '--', '.'])
  const working = gitOutput(['diff', '--name-only', 'HEAD', '--', '.'])
  return [...new Set(`${committed}\n${working}`.split(/\r?\n/)
    .map(value => value.trim().replaceAll('\\', '/'))
    .filter(Boolean)
    .map(value => value.startsWith(prefix) ? value.slice(prefix.length) : value))]
}

export function resolveSmokeLevel(requested, changedFiles = detectChangedFiles()) {
  if (!['auto', 'skip', 'basic', 'full'].includes(requested)) throw new Error(`Invalid smoke level: ${requested}`)
  if (requested !== 'auto') return requested
  if (changedFiles.length > 0 && changedFiles.every(file => DOC_ONLY_PATTERN.test(file))) return 'skip'
  if (changedFiles.some(file => FULL_SMOKE_PATTERNS.some(pattern => pattern.test(file)))) return 'full'
  return 'basic'
}

function expectedReleaseEntries() {
  return [
    'codex-worker/VERSION',
    'codex-worker/dist/index.js',
    'codex-worker/package.json',
    'codex-worker/package-lock.json',
    'codex-worker/install.sh',
    'codex-worker/install.ps1',
    'codex-worker/bin/codex-worker',
  ]
}

export function verifyArchiveStructure(outputDir, version) {
  const metadata = collectArchiveMetadata(outputDir, version)
  for (const platform of RELEASE_PLATFORMS) {
    const archivePath = metadata.assets[platform].archivePath
    const archiveBytes = fs.readFileSync(archivePath)
    const entries = platform === 'windows' ? listZipEntries(archiveBytes) : listTarGzEntries(archiveBytes)
    for (const expected of expectedReleaseEntries()) {
      if (!entries.includes(expected)) throw new Error(`${path.basename(archivePath)} is missing ${expected}`)
    }
    if (entries.some(name => /(?:^|\/)(?:node_modules|\.env|logs)(?:\/|$)/.test(name))) {
      throw new Error(`${path.basename(archivePath)} contains forbidden runtime or secret files`)
    }
  }
  return metadata
}

function safeTarget(root, entryName) {
  const target = path.resolve(root, entryName)
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) throw new Error(`Unsafe archive entry: ${entryName}`)
  return target
}

function extractZip(bytes, destination) {
  let offset = 0
  while (offset + 30 <= bytes.length && bytes.readUInt32LE(offset) === 0x04034b50) {
    const method = bytes.readUInt16LE(offset + 8)
    const compressedSize = bytes.readUInt32LE(offset + 18)
    const nameLength = bytes.readUInt16LE(offset + 26)
    const extraLength = bytes.readUInt16LE(offset + 28)
    if (method !== 0) throw new Error('Release smoke supports only deterministic stored ZIP entries')
    const name = bytes.subarray(offset + 30, offset + 30 + nameLength).toString('utf8')
    const dataStart = offset + 30 + nameLength + extraLength
    const target = safeTarget(destination, name)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, bytes.subarray(dataStart, dataStart + compressedSize))
    offset = dataStart + compressedSize
  }
}

function extractTarGz(bytes, destination) {
  const tar = zlib.gunzipSync(bytes)
  let offset = 0
  while (offset + 512 <= tar.length) {
    const header = tar.subarray(offset, offset + 512)
    if (header.every(byte => byte === 0)) break
    const readString = (start, length) => header.subarray(start, start + length).toString('utf8').replace(/\0.*$/, '')
    const namePart = readString(0, 100)
    const prefix = readString(345, 155)
    const name = prefix ? `${prefix}/${namePart}` : namePart
    const size = Number.parseInt(readString(124, 12).trim() || '0', 8)
    const mode = Number.parseInt(readString(100, 8).trim() || '644', 8)
    const target = safeTarget(destination, name)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, tar.subarray(offset + 512, offset + 512 + size), { mode })
    offset += 512 + Math.ceil(size / 512) * 512
  }
}

async function waitForHealth(baseUrl, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(2_000) })
      if (response.ok) return await response.json()
      lastError = new Error(`HTTP ${response.status}`)
    } catch (error) {
      lastError = error
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(`Candidate worker did not become healthy: ${lastError instanceof Error ? lastError.message : String(lastError)}`)
}

async function availablePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 0
      server.close(error => error ? reject(error) : resolve(port))
    })
  })
}

async function runFullSmoke(outputDir, version) {
  const platform = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macos' : 'linux'
  const archivePath = path.join(outputDir, archiveName(version, platform))
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-release-smoke-'))
  let child
  let logs = ''
  try {
    const archiveBytes = fs.readFileSync(archivePath)
    if (platform === 'windows') extractZip(archiveBytes, tempRoot)
    else extractTarGz(archiveBytes, tempRoot)
    const candidateDir = path.join(tempRoot, 'codex-worker')
    const install = spawnSync('npm', ['ci', '--omit=dev'], {
      cwd: candidateDir,
      stdio: 'inherit',
      shell: process.platform === 'win32',
    })
    if (install.status !== 0) throw new Error(`Candidate npm ci failed with exit code ${install.status}`)
    const port = await availablePort()
    const home = path.join(tempRoot, 'home')
    fs.mkdirSync(home, { recursive: true })
    child = spawn(process.execPath, ['dist/index.js'], {
      cwd: candidateDir,
      env: {
        ...process.env,
        HOME: home,
        USERPROFILE: home,
        CODEX_HOME: path.join(home, '.codex'),
        CODEX_WORKER_HOST: '127.0.0.1',
        CODEX_WORKER_PORT: String(port),
        CODEX_WORKER_TOKEN: '',
        OPENAI_API_KEY: '',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    child.stdout.on('data', chunk => { logs += chunk.toString() })
    child.stderr.on('data', chunk => { logs += chunk.toString() })
    const health = await waitForHealth(`http://127.0.0.1:${port}`, 15_000)
    if (health.version !== version) throw new Error(`Candidate health version ${health.version} does not match ${version}`)
    return { platform, healthStatus: health.status, version: health.version }
  } catch (error) {
    const detail = logs.trim() ? `\nCandidate logs:\n${logs.slice(-4_000)}` : ''
    throw new Error(`${error instanceof Error ? error.message : String(error)}${detail}`)
  } finally {
    if (child && child.exitCode === null) {
      child.kill('SIGTERM')
      await new Promise(resolve => setTimeout(resolve, 300))
      if (child.exitCode === null) child.kill('SIGKILL')
    }
    fs.rmSync(tempRoot, { recursive: true, force: true })
  }
}

export async function runReleaseSmoke({ requestedLevel, outputDir, version, changedFiles }) {
  const level = resolveSmokeLevel(requestedLevel, changedFiles)
  const result = { requestedLevel, level, changedFiles: changedFiles || detectChangedFiles(), checks: [] }
  if (level === 'skip') return result
  verifyArchiveStructure(outputDir, version)
  result.checks.push('archive-structure', 'sha256-sidecars', 'forbidden-file-scan')
  if (level === 'full') {
    result.candidate = await runFullSmoke(outputDir, version)
    result.checks.push('candidate-npm-ci', 'candidate-health')
  }
  return result
}

async function main() {
  const args = process.argv.slice(2)
  const read = (name, fallback) => {
    const index = args.indexOf(name)
    return index >= 0 ? args[index + 1] : fallback
  }
  const version = read('--version')
  if (!version) throw new Error('--version is required')
  const outputDir = path.resolve(workerDir, read('--output-dir', 'release/output'))
  const result = await runReleaseSmoke({ requestedLevel: read('--level', 'auto'), outputDir, version })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
}

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url
if (isMain) main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
  process.exitCode = 1
})
