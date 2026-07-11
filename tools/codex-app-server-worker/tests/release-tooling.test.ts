import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { collectReleaseEntries, createZip, listZipEntries, RELEASE_DIRECTORIES, RELEASE_FILES } from '../scripts/release-archive.mjs'
import { resolveReleaseVersion } from '../scripts/release-version.mjs'

test('release version matches package metadata, lockfile, and source APP_VERSION', () => {
  const packageJson = JSON.parse(fs.readFileSync('package.json', 'utf8')) as { version: string }
  assert.equal(resolveReleaseVersion(path.resolve('.')), packageJson.version)
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
    for (const file of RELEASE_FILES) fs.writeFileSync(path.join(root, file), '{}\r\n')
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
