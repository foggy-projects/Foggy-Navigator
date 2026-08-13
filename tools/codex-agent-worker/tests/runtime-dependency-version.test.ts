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

test('Windows release installer accepts valid empty runtime dependency helper output', () => {
  const content = fs.readFileSync('release/install.ps1', 'utf8')
  const captureIndex = content.indexOf('$PreservedSdkOutput = & node')
  const exitCheckIndex = content.indexOf('if ($LASTEXITCODE -ne 0)', captureIndex)
  const normalizeIndex = content.indexOf('$PreservedSdkVersion = if ($null -eq $PreservedSdkOutput)', exitCheckIndex)

  assert.ok(captureIndex >= 0, 'installer must capture helper output without trimming it inline')
  assert.ok(exitCheckIndex > captureIndex, 'installer must check the helper exit code after invocation')
  assert.ok(normalizeIndex > exitCheckIndex, 'installer must normalize null output only after checking the helper exit code')
  assert.doesNotMatch(content, /runtime-dependency-version\.mjs'[\s\S]*?--package '@openai\/codex-sdk'\)\.Trim\(\)/)
})

test('PowerShell null normalization expression handles a command with no stdout', {
  skip: process.platform !== 'win32' ? 'PowerShell behavior test requires Windows' : false,
}, () => {
  const command = [
    '$PreservedSdkOutput = & node -e "process.exit(0)"',
    'if ($LASTEXITCODE -ne 0) { exit 2 }',
    '$PreservedSdkVersion = if ($null -eq $PreservedSdkOutput) { "" } else { ([string]$PreservedSdkOutput).Trim() }',
    'if ($PreservedSdkVersion -ne "") { exit 3 }',
  ].join('; ')
  const result = spawnSync('powershell', ['-NoProfile', '-Command', command], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
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

test('PowerShell SDK resolvers silence npm informational stderr before capture', () => {
  for (const source of ['update-sdk.ps1', 'release/update-sdk.ps1']) {
    const content = fs.readFileSync(source, 'utf8')
    assert.match(content, /\$viewArgs = @\("--loglevel=silent", "view", "@openai\/codex-sdk@\$spec", "version"\)/)
  }
})
