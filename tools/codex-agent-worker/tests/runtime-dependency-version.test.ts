import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import {
  compareSemver,
  readInstalledDependencyVersion,
  readLockedDependencyVersion,
  selectPreservedDependencyVersion,
} from '../scripts/runtime-dependency-version.mjs'

test('SDK dependency preservation only selects a verifiably newer installed version', () => {
  assert.equal(compareSemver('0.145.0', '0.144.1'), 1)
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.145.0', lockedVersion: '0.144.1' }), '0.145.0')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.144.1', lockedVersion: '0.144.1' }), '')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.144.1', lockedVersion: '0.145.0' }), '')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: 'dev-build', lockedVersion: '0.144.1' }), '')
})

test('runtime dependency helper exposes semver comparison for monotonic direct SDK updates', () => {
  const helper = path.resolve('scripts/runtime-dependency-version.mjs')
  const result = spawnSync(process.execPath, [helper, '--compare', '0.145.0', '0.144.1'], { encoding: 'utf8' })
  assert.equal(result.status, 0)
  assert.equal(result.stdout, '1')
})

test('SDK dependency preservation reads the installed module and candidate lockfile independently', t => {
  const installedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-sdk-installed-'))
  const candidateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-sdk-candidate-'))
  t.after(() => {
    fs.rmSync(installedRoot, { recursive: true, force: true })
    fs.rmSync(candidateRoot, { recursive: true, force: true })
  })
  const installedPackage = path.join(installedRoot, 'node_modules', '@openai', 'codex-sdk')
  fs.mkdirSync(installedPackage, { recursive: true })
  fs.writeFileSync(path.join(installedPackage, 'package.json'), JSON.stringify({ version: '0.145.0' }))
  fs.writeFileSync(path.join(candidateRoot, 'package-lock.json'), JSON.stringify({
    packages: { 'node_modules/@openai/codex-sdk': { version: '0.144.1' } },
  }))
  assert.equal(readInstalledDependencyVersion(installedRoot, '@openai/codex-sdk'), '0.145.0')
  assert.equal(readLockedDependencyVersion(candidateRoot, '@openai/codex-sdk'), '0.144.1')
})

test('release installers apply the monotonic SDK lockfile policy before npm ci', () => {
  for (const source of ['release/install.sh', 'release/install.ps1']) {
    const content = fs.readFileSync(source, 'utf8')
    assert.match(content, /runtime-dependency-version\.mjs/)
    assert.match(content, /@openai\/codex-sdk/)
    assert.ok(content.indexOf('runtime-dependency-version.mjs') < content.indexOf('npm ci'))
  }
})

test('release installers remove retired ambiguous SDK updater names', () => {
  const installSh = fs.readFileSync('release/install.sh', 'utf8')
  const installPs1 = fs.readFileSync('release/install.ps1', 'utf8')
  assert.match(installSh, /rm -f "\$INSTALL_DIR\/update\.sh" "\$INSTALL_DIR\/update\.ps1"/)
  assert.match(installPs1, /Remove-Item \(Join-Path \$InstallDir "update\.sh"\), \(Join-Path \$InstallDir "update\.ps1"\)/)
})

test('direct SDK update scripts refuse a resolved version below the installed SDK', () => {
  for (const source of ['update-sdk.sh', 'update-sdk.ps1', 'release/update-sdk.sh', 'release/update-sdk.ps1']) {
    const content = fs.readFileSync(source, 'utf8')
    assert.match(content, /resolve_sdk_version|Resolve-SdkVersion/)
    assert.match(content, /--compare/)
    assert.match(content, /leaving it unchanged/)
  }
})
