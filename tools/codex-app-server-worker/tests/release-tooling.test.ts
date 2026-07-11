import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { collectReleaseEntries, createZip, listZipEntries, RELEASE_DIRECTORIES, RELEASE_FILES } from '../scripts/release-archive.mjs'
import { createLatestManifest, injectReleaseBaseUrl, parseChecksumSidecar, prepareReleaseAssets } from '../scripts/release-assets.mjs'
import { assertPublishAllowed, compareSemver, verifyPublishedRelease } from '../scripts/publish-obs.mjs'
import { resolveReleaseVersion } from '../scripts/release-version.mjs'
import { discoverTestFiles } from '../scripts/run-tests.mjs'

test('release version matches package metadata, lockfile, and source APP_VERSION', () => {
  const packageJson = JSON.parse(fs.readFileSync('package.json', 'utf8')) as { version: string }
  assert.equal(resolveReleaseVersion(path.resolve('.')), packageJson.version)
})

test('npm test uses the cross-platform test launcher instead of a shell glob', () => {
  const packageJson = JSON.parse(fs.readFileSync('package.json', 'utf8')) as { scripts: { test: string } }
  assert.equal(packageJson.scripts.test, 'node scripts/run-tests.mjs')
})

test('cross-platform test launcher recursively discovers only test entrypoints', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-test-launcher-'))
  try {
    fs.mkdirSync(path.join(root, 'nested'), { recursive: true })
    fs.writeFileSync(path.join(root, 'root.test.ts'), '')
    fs.writeFileSync(path.join(root, 'nested', 'nested.test.ts'), '')
    fs.writeFileSync(path.join(root, 'nested', 'fixture.ts'), '')
    assert.deepEqual(
      discoverTestFiles(root).map((file) => path.relative(root, file).replaceAll('\\', '/')),
      ['nested/nested.test.ts', 'root.test.ts'],
    )
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('candidate verification scripts resolve their location on supported Node 18', () => {
  for (const script of ['scripts/clean.mjs', 'scripts/verify-app-server-schema.mjs']) {
    const source = fs.readFileSync(script, 'utf8')
    assert.doesNotMatch(source, /import\.meta\.(?:dirname|filename)/)
    assert.match(source, /fileURLToPath\(import\.meta\.url\)/)
  }
})

test('release version validation rejects source and lockfile drift', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-release-version-test-'))
  try {
    fs.mkdirSync(path.join(root, 'src'), { recursive: true })
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ version: '0.1.1' }))
    fs.writeFileSync(path.join(root, 'package-lock.json'), JSON.stringify({
      version: '0.1.1',
      packages: { '': { version: '0.1.1' } },
    }))
    fs.writeFileSync(path.join(root, 'src', 'version.ts'), "export const APP_VERSION = '0.1.0'\n")

    assert.throws(() => resolveReleaseVersion(root), /Source APP_VERSION 0\.1\.0 does not match package version 0\.1\.1/)

    fs.writeFileSync(path.join(root, 'src', 'version.ts'), "export const APP_VERSION = '0.1.1'\n")
    fs.writeFileSync(path.join(root, 'package-lock.json'), JSON.stringify({
      version: '0.1.0',
      packages: { '': { version: '0.1.0' } },
    }))
    assert.throws(() => resolveReleaseVersion(root), /package-lock\.json version does not match package version 0\.1\.1/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('release archive is deterministic and excludes runtime identity, state, auth and dependencies', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-release-test-'))
  try {
    for (const directory of RELEASE_DIRECTORIES) {
      fs.mkdirSync(path.join(root, directory), { recursive: true })
      fs.writeFileSync(path.join(root, directory, 'content.txt'), 'line1\r\nline2\r\n')
    }
    for (const file of RELEASE_FILES) {
      fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
      fs.writeFileSync(path.join(root, file), '{}\r\n')
    }
    fs.mkdirSync(path.join(root, 'scripts', 'logs'), { recursive: true })
    fs.writeFileSync(path.join(root, 'scripts', 'logs', 'worker.log'), 'secret')
    fs.mkdirSync(path.join(root, 'tests', 'node_modules'), { recursive: true })
    fs.writeFileSync(path.join(root, 'tests', 'node_modules', 'dependency.js'), 'secret')
    fs.writeFileSync(path.join(root, 'src', 'auth.json'), 'secret')
    fs.writeFileSync(path.join(root, 'lifecycle.lock'), 'runtime lock')

    const first = createZip(collectReleaseEntries(root, '0.1.0'))
    const second = createZip(collectReleaseEntries(root, '0.1.0'))
    assert.equal(crypto.createHash('sha256').update(first).digest('hex'), crypto.createHash('sha256').update(second).digest('hex'))
    const names = listZipEntries(first)
    assert.ok(names.includes('codex-app-server-worker/VERSION'))
    assert.ok(names.includes('codex-app-server-worker/dist/content.txt'))
    assert.equal(names.some((name) => /(?:^|\/)(?:logs|node_modules)(?:\/|$)|auth\.json$/i.test(name)), false)
    assert.equal(names.some(name => name.endsWith('/lifecycle.lock')), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('OBS release assets bind one cross-platform archive to immutable integrity metadata', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-obs-assets-test-'))
  try {
    const outputDir = path.join(root, 'release', 'output')
    fs.mkdirSync(outputDir, { recursive: true })
    fs.writeFileSync(path.join(root, 'release', 'remote-install.ps1'), '$ReleaseBaseUrl = "__RELEASE_BASE_URL__"\nif ($ReleaseBaseUrl -eq "__RELEASE_BASE_URL__") { exit 1 }\n')
    fs.writeFileSync(path.join(root, 'release', 'remote-install.sh'), 'RELEASE_BASE_URL="__RELEASE_BASE_URL__"\n[ "$RELEASE_BASE_URL" != "__RELEASE_BASE_URL__" ]\n')
    const version = '1.2.3'
    const archiveName = `codex-app-server-worker-${version}.zip`
    const archive = Buffer.from('deterministic release bytes')
    const checksum = crypto.createHash('sha256').update(archive).digest('hex')
    fs.writeFileSync(path.join(outputDir, archiveName), archive)
    fs.writeFileSync(path.join(outputDir, `${archiveName}.sha256`), `${checksum}  ${archiveName}\n`)

    const assets = prepareReleaseAssets({
      workerDir: root,
      outputDir,
      version,
      baseUrl: 'https://releases.example.test/codex-app-server-worker/',
      released: '2026-07-11',
      buildTimeUtc: '2026-07-11T00:00:00Z',
      gitCommit: 'a'.repeat(40),
      gitDirty: false,
    })
    assert.equal(assets.manifest.files.windows, `${version}/${archiveName}`)
    assert.equal(assets.manifest.files.linux, assets.manifest.files.windows)
    assert.equal(assets.manifest.sha256.windows, checksum)
    assert.equal(assets.manifest.bytes.windows, archive.length)
    assert.equal(JSON.parse(fs.readFileSync(assets.latestPath, 'utf8')).product, 'codex-app-server-worker')
    assert.match(fs.readFileSync(assets.installPs1Path, 'utf8'), /\$ReleaseBaseUrl = "https:\/\/releases\.example\.test\/codex-app-server-worker"/)
    assert.match(fs.readFileSync(assets.installShPath, 'utf8'), /RELEASE_BASE_URL="https:\/\/releases\.example\.test\/codex-app-server-worker"/)
    assert.match(fs.readFileSync(assets.installShPath, 'utf8'), /__RELEASE_BASE_URL__/)

    fs.appendFileSync(path.join(outputDir, archiveName), 'tampered')
    assert.throws(() => prepareReleaseAssets({ workerDir: root, outputDir, version, baseUrl: 'https://releases.example.test' }), /does not match its SHA-256/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('release metadata and publish guard reject drift, downgrade, and unsafe repair', () => {
  const manifest = createLatestManifest({
    version: '1.2.3',
    archiveName: 'codex-app-server-worker-1.2.3.zip',
    checksum: 'a'.repeat(64),
    bytes: 42,
    released: '2026-07-11',
    buildTimeUtc: '2026-07-11T00:00:00Z',
  })
  assert.equal(parseChecksumSidecar(`${'a'.repeat(64)}  codex-app-server-worker-1.2.3.zip\n`, 'codex-app-server-worker-1.2.3.zip'), 'a'.repeat(64))
  assert.throws(() => parseChecksumSidecar(`${'a'.repeat(64)}  other.zip\n`, 'codex-app-server-worker-1.2.3.zip'), /must name/)
  assert.throws(() => injectReleaseBaseUrl('no assignment', 'https://example.test', 'shell'), /exactly one/)
  assert.equal(compareSemver('1.2.3', '1.2.2'), 1)
  assert.equal(compareSemver('1.2.3-rc.1', '1.2.3'), -1)
  assert.equal(compareSemver('1.2.3-rc.10', '1.2.3-rc.2'), 1)
  assert.equal(compareSemver('1.2.3-1', '1.2.3-alpha'), -1)
  assert.doesNotThrow(() => assertPublishAllowed(manifest, null, false))
  assert.throws(() => assertPublishAllowed(manifest, manifest, false), /already version/)
  assert.doesNotThrow(() => assertPublishAllowed(manifest, structuredClone(manifest), true))
  const changed = structuredClone(manifest)
  changed.sha256.windows = 'b'.repeat(64)
  assert.throws(() => assertPublishAllowed(manifest, changed, true), /hash and size are unchanged/)
  const newer = structuredClone(manifest)
  newer.version = '1.2.4'
  assert.throws(() => assertPublishAllowed(manifest, newer, true), /older than remote/)
})

test('publisher verifies remote archive and bootstrap bytes instead of trusting object existence', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-remote-verify-test-'))
  try {
    const archive = Buffer.from('verified remote archive')
    const checksum = crypto.createHash('sha256').update(archive).digest('hex')
    const installPs1Path = path.join(root, 'install.ps1')
    const installShPath = path.join(root, 'install.sh')
    fs.writeFileSync(installPs1Path, 'windows bootstrap\n')
    fs.writeFileSync(installShPath, 'linux bootstrap\n')
    const manifest = createLatestManifest({
      version: '1.2.3',
      archiveName: 'codex-app-server-worker-1.2.3.zip',
      checksum,
      bytes: archive.length,
      released: '2026-07-11',
      buildTimeUtc: '2026-07-11T00:00:00Z',
    })
    const fetchImpl = async (input: string | URL) => {
      const url = String(input)
      if (url.includes('latest.json')) return new Response(JSON.stringify(manifest))
      if (url.includes(manifest.files.windows)) return new Response(archive)
      if (url.includes('install.ps1')) return new Response(fs.readFileSync(installPs1Path))
      if (url.includes('install.sh')) return new Response(fs.readFileSync(installShPath))
      return new Response('', { status: 404 })
    }
    const assets = { manifest, installPs1Path, installShPath }
    await assert.doesNotReject(verifyPublishedRelease('https://releases.example.test', assets, { fetchImpl, attempts: 1 }))
    const corruptFetch = async (input: string | URL) => String(input).includes(manifest.files.windows)
      ? new Response('corrupt')
      : fetchImpl(input)
    await assert.rejects(
      verifyPublishedRelease('https://releases.example.test', assets, { fetchImpl: corruptFetch, attempts: 1 }),
      /byte length or SHA-256/,
    )
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('remote bootstraps verify integrity and preserve the installed updater contract', () => {
  const ps = fs.readFileSync('release/remote-install.ps1', 'utf8')
  const sh = fs.readFileSync('release/remote-install.sh', 'utf8')
  for (const script of [ps, sh]) {
    assert.match(script, /latest\.json\?ts=/)
    assert.match(script, /schemaVersion/)
    assert.match(script, /sha256/)
    assert.match(script, /archive size|archive Size|archive size|archive.*size/i)
    assert.match(script, /existing installation|Existing installation|Updating existing/)
    assert.match(script, /update\.(?:ps1|sh)/)
    assert.match(script, /already installed.*nothing to do/)
    assert.match(script, /refusing downgrade/)
    assert.match(script, /update\.in-progress/)
    assert.match(script, /lifecycle\.lock/)
    assert.match(script, /stop\.failed/)
    assert.match(script, /lifecycle\.failed/)
    assert.match(script, /incomplete; repairing/)
    assert.match(script, /remains stopped/)
  }
  assert.ok(ps.indexOf('Get-FileHash') < ps.indexOf('Expand-Archive'))
  assert.ok(sh.indexOf('sha256sum') < sh.indexOf('unzip -q'))
  assert.match(sh, /if \[\[ "\$version_comparison" == 1 \]\]; then/)
  assert.doesNotMatch(sh, /\[\[ "\$version_comparison" == -1 \]\] \|\|/)

  const publisher = fs.readFileSync('scripts/publish-obs.mjs', 'utf8')
  const latestUpload = publisher.indexOf('`${obsBucket}/latest.json`')
  assert.ok(latestUpload > publisher.indexOf('`${obsBucket}/install.ps1`'))
  assert.ok(latestUpload > publisher.indexOf('assets.archivePath'))
  assert.match(publisher, /latestBeforeCommit/)
  assert.match(fs.readFileSync('scripts/package-release.mjs', 'utf8'), /process\.argv\.includes\('--upload'\)/)
  assert.doesNotMatch(fs.readFileSync('release/package.ps1', 'utf8'), /\[string\]\$Version/)
})

test('update scripts validate before drain and define rollback paths', () => {
  const ps = fs.readFileSync('update.ps1', 'utf8')
  const sh = fs.readFileSync('update.sh', 'utf8')
  for (const [script, markers] of [
    [ps, ["@('ci')", "@('test')", "@('run', 'verify:schema')", "@('run', 'typecheck')", "@('run', 'build')", '$StopScript =']],
    [sh, ['npm ci', 'npm test', 'verify:schema', 'typecheck', 'npm run build', 'bash "$INSTALL_DIR/stop.sh"']],
  ] as const) {
    const ci = script.indexOf(markers[0])
    const testIndex = script.indexOf(markers[1])
    const schema = script.indexOf(markers[2])
    const typecheck = script.indexOf(markers[3])
    const build = script.indexOf(markers[4])
    const drainMarker = markers[5]
    const stop = script.indexOf(drainMarker, build)
    assert.ok(ci >= 0 && ci < testIndex && testIndex < schema && schema < typecheck && typecheck < build && build < stop)
    assert.match(script, /[Rr]ollback|Restore-PreviousInstall/)
    assert.match(script, /\.env/)
    assert.match(script, /logs/)
    assert.match(script, /node_modules/)
    assert.match(script, /CODEX_APP_SERVER_UPDATE_STEP_TIMEOUT_SEC/)
  }
  assert.match(ps, /taskkill\.exe \/PID \$Process\.Id \/T \/F/)
  assert.match(sh, /collect_tree "\$command_pid"/)
  assert.match(sh, /for tree_pid in "\$\{TREE_PIDS\[@\]\}"; do kill -KILL/)
})
