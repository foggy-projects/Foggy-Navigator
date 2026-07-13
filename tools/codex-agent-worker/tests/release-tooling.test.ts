import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  collectReleaseEntries,
  createTarGz,
  createZip,
  listTarGzEntries,
  listZipEntries,
  RELEASE_DIRECTORIES,
  RELEASE_FILES,
} from '../scripts/release-archive.mjs'
import {
  archiveName,
  parseChecksumSidecar,
  prepareReleaseAssets,
  RELEASE_PLATFORMS,
} from '../scripts/release-assets.mjs'
import {
  assertGitPublishReady,
  assertPublishAllowed,
  compareSemver,
  verifyPublishedRelease,
} from '../scripts/publish-obs.mjs'
import { removeSmokeTempDirectory, resolveSmokeLevel, verifyArchiveStructure } from '../scripts/release-smoke.mjs'
import { resolveReleaseVersion } from '../scripts/release-version.mjs'

function makeReleaseTree(root: string, version = '1.2.3') {
  for (const directory of RELEASE_DIRECTORIES) {
    fs.mkdirSync(path.join(root, directory), { recursive: true })
    fs.writeFileSync(path.join(root, directory, directory === 'dist' ? 'index.js' : 'guide.md'), 'line1\r\nline2\r\n')
  }
  for (const file of RELEASE_FILES) {
    const target = path.join(root, file)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, '{}\r\n')
  }
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ version }))
  fs.writeFileSync(path.join(root, 'package-lock.json'), JSON.stringify({ version, packages: { '': { version } } }))
}

function writeReleaseArchives(root: string, version: string) {
  const outputDir = path.join(root, 'release', 'output')
  fs.mkdirSync(outputDir, { recursive: true })
  const entries = collectReleaseEntries(root, version)
  for (const platform of RELEASE_PLATFORMS) {
    const name = archiveName(version, platform)
    const bytes = platform === 'windows' ? createZip(entries) : createTarGz(entries)
    const hash = crypto.createHash('sha256').update(bytes).digest('hex')
    fs.writeFileSync(path.join(outputDir, name), bytes)
    fs.writeFileSync(path.join(outputDir, `${name}.sha256`), `${hash}  ${name}\n`)
  }
  return outputDir
}

test('release version requires package and lockfile agreement', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-version-'))
  try {
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ version: '1.2.3' }))
    fs.writeFileSync(path.join(root, 'package-lock.json'), JSON.stringify({ version: '1.2.3', packages: { '': { version: '1.2.3' } } }))
    assert.equal(resolveReleaseVersion(root), '1.2.3')
    fs.writeFileSync(path.join(root, 'package-lock.json'), JSON.stringify({ version: '1.2.2', packages: { '': { version: '1.2.2' } } }))
    assert.throws(() => resolveReleaseVersion(root), /does not match/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('cross-platform release archives are deterministic and contain the same canonical payload', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-archive-'))
  try {
    makeReleaseTree(root)
    const entries = collectReleaseEntries(root, '1.2.3')
    const firstZip = createZip(entries)
    const secondZip = createZip(entries)
    const firstTar = createTarGz(entries)
    const secondTar = createTarGz(entries)
    assert.deepEqual(firstZip, secondZip)
    assert.deepEqual(firstTar, secondTar)
    assert.deepEqual(listZipEntries(firstZip), listTarGzEntries(firstTar))
    assert.ok(listZipEntries(firstZip).includes('codex-worker/VERSION'))
    assert.ok(listZipEntries(firstZip).includes('codex-worker/bin/codex-worker'))
    assert.equal(listZipEntries(firstZip).some(name => name.includes('/release/')), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('basic release smoke validates every platform checksum and required archive entry', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-basic-smoke-'))
  try {
    makeReleaseTree(root)
    const outputDir = writeReleaseArchives(root, '1.2.3')
    assert.doesNotThrow(() => verifyArchiveStructure(outputDir, '1.2.3'))
    fs.appendFileSync(path.join(outputDir, archiveName('1.2.3', 'linux')), 'tampered')
    assert.throws(() => verifyArchiveStructure(outputDir, '1.2.3'), /SHA-256/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('auto smoke skips documentation-only changes and escalates release/runtime changes', () => {
  assert.equal(resolveSmokeLevel('auto', ['docs/upstream-integration.md', 'tests/config.test.ts']), 'skip')
  assert.equal(resolveSmokeLevel('auto', ['src/models.ts']), 'basic')
  assert.equal(resolveSmokeLevel('auto', ['release/install.sh']), 'full')
  assert.equal(resolveSmokeLevel('auto', ['src/codex/sdk-wrapper.ts']), 'full')
  assert.equal(resolveSmokeLevel('basic', ['release/install.sh']), 'basic')
})

test('release smoke cleanup removes an extracted candidate tree', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-smoke-cleanup-'))
  fs.mkdirSync(path.join(root, 'codex-worker', 'node_modules', 'package'), { recursive: true })
  fs.writeFileSync(path.join(root, 'codex-worker', 'node_modules', 'package', 'index.js'), 'export {}\n')
  removeSmokeTempDirectory(root)
  assert.equal(fs.existsSync(root), false)
})

test('release assets include integrity, evidence, and exact bootstrap URL injection', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-assets-'))
  try {
    makeReleaseTree(root)
    fs.writeFileSync(path.join(root, 'release', 'remote-install.sh'), 'RELEASE_BASE_URL="__RELEASE_BASE_URL__"\n')
    fs.writeFileSync(path.join(root, 'release', 'remote-install.ps1'), '$ReleaseBaseUrl = "__RELEASE_BASE_URL__"\n')
    const outputDir = writeReleaseArchives(root, '1.2.3')
    const assets = prepareReleaseAssets({
      workerDir: root,
      outputDir,
      version: '1.2.3',
      baseUrl: 'https://release.example.test/codex-worker/',
      gitCommit: 'a'.repeat(40),
      gitDirty: false,
      smokeLevel: 'basic',
      verification: { level: 'basic', checks: ['archive-structure'] },
      released: '2026-07-12',
      buildTimeUtc: '2026-07-12T00:00:00.000Z',
    })
    assert.equal(assets.manifest.product, 'codex-agent-worker')
    assert.equal(assets.manifest.smokeLevel, 'basic')
    assert.equal(Object.keys(assets.manifest.sha256).length, 3)
    assert.match(fs.readFileSync(assets.installShPath, 'utf8'), /https:\/\/release\.example\.test\/codex-worker/)
    assert.equal(parseChecksumSidecar(`${'a'.repeat(64)}  file.zip\n`, 'file.zip'), 'a'.repeat(64))
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('publisher rejects dirty, unpushed, downgrade, and unsafe same-version releases', () => {
  assert.throws(() => assertGitPublishReady({ commit: 'a', upstream: 'a', dirty: true }, {}), /clean/)
  assert.throws(() => assertGitPublishReady({ commit: 'a', upstream: 'b', dirty: false }, {}), /pushed upstream/)
  assert.doesNotThrow(() => assertGitPublishReady({ commit: 'a', upstream: 'a', dirty: false }, {}))
  const manifest = {
    schemaVersion: 1,
    product: 'codex-agent-worker',
    version: '1.2.3',
    sha256: { linux: 'a', macos: 'b', windows: 'c' },
    bytes: { linux: 1, macos: 2, windows: 3 },
  }
  assert.equal(compareSemver('1.2.3', '1.2.2'), 1)
  assert.throws(() => assertPublishAllowed(manifest, { ...manifest, version: '1.2.4' }, false), /older/)
  assert.throws(() => assertPublishAllowed(manifest, manifest, false), /already version/)
  assert.doesNotThrow(() => assertPublishAllowed(manifest, structuredClone(manifest), true))
  const changed = structuredClone(manifest)
  changed.sha256.windows = 'different'
  assert.throws(() => assertPublishAllowed(manifest, changed, true), /hash and size/)
})

test('publisher source requires verified package evidence before OBS mutation', () => {
  const publisher = fs.readFileSync('scripts/publish-obs.mjs', 'utf8')
  const packageScript = fs.readFileSync('scripts/package-release.mjs', 'utf8')
  assert.match(publisher, /packageVerificationSkipped/)
  assert.match(packageScript, /publishing refuses a candidate built with --skip-verify/i)
})

test('post-publish verification reads and hashes all platform archives plus mutable bootstraps', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-publish-verify-'))
  try {
    makeReleaseTree(root)
    fs.writeFileSync(path.join(root, 'release', 'remote-install.sh'), 'RELEASE_BASE_URL="__RELEASE_BASE_URL__"\n')
    fs.writeFileSync(path.join(root, 'release', 'remote-install.ps1'), '$ReleaseBaseUrl = "__RELEASE_BASE_URL__"\n')
    const outputDir = writeReleaseArchives(root, '1.2.3')
    const assets = prepareReleaseAssets({
      workerDir: root,
      outputDir,
      version: '1.2.3',
      baseUrl: 'https://release.example.test',
      gitCommit: 'a',
      gitDirty: false,
      smokeLevel: 'basic',
      verification: { level: 'basic' },
      released: '2026-07-12',
      buildTimeUtc: '2026-07-12T00:00:00.000Z',
    })
    const fetchImpl = async (input: string | URL) => {
      const url = String(input)
      if (url.includes('latest.json')) return new Response(JSON.stringify(assets.manifest))
      for (const platform of RELEASE_PLATFORMS) {
        if (url.includes(assets.manifest.files[platform])) return new Response(fs.readFileSync(assets.assets[platform].archivePath))
      }
      if (url.includes('install.sh')) return new Response(fs.readFileSync(assets.installShPath))
      if (url.includes('install.ps1')) return new Response(fs.readFileSync(assets.installPs1Path))
      if (url.includes('release-evidence.json')) return new Response(fs.readFileSync(assets.evidencePath))
      return new Response('', { status: 404 })
    }
    await assert.doesNotReject(verifyPublishedRelease('https://release.example.test', assets, { fetchImpl, attempts: 1 }))
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('remote installers verify product, schema, size, and SHA-256 before extraction', () => {
  const shell = fs.readFileSync('release/remote-install.sh', 'utf8')
  const powershell = fs.readFileSync('release/remote-install.ps1', 'utf8')
  for (const source of [shell, powershell]) {
    assert.match(source, /schemaVersion/)
    assert.match(source, /codex-agent-worker/)
    assert.match(source, /SHA-256|sha256/)
    assert.match(source, /size mismatch/i)
  }
  assert.ok(shell.indexOf('ACTUAL_SHA256=') < shell.indexOf('tar xzf'))
  assert.ok(powershell.indexOf('Get-FileHash') < powershell.indexOf('Expand-Archive'))
})
