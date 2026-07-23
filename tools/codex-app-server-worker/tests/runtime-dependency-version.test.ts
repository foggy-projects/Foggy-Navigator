import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  compareSemver,
  readInstalledDependencyVersion,
  readLockedDependencyVersion,
  selectPreservedDependencyVersion,
} from '../scripts/runtime-dependency-version.mjs'

test('app-server runtime preservation only selects a verifiably newer installed Codex CLI', () => {
  assert.equal(compareSemver('0.145.0', '0.144.3'), 1)
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.145.0', lockedVersion: '0.144.3' }), '0.145.0')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.144.3', lockedVersion: '0.144.3' }), '')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: '0.144.3', lockedVersion: '0.145.0' }), '')
  assert.equal(selectPreservedDependencyVersion({ installedVersion: 'invalid', lockedVersion: '0.144.3' }), '')
})

test('app-server runtime preservation reads installed Codex CLI and candidate lockfile separately', t => {
  const installedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-installed-'))
  const candidateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-candidate-'))
  t.after(() => {
    fs.rmSync(installedRoot, { recursive: true, force: true })
    fs.rmSync(candidateRoot, { recursive: true, force: true })
  })
  const installedPackage = path.join(installedRoot, 'node_modules', '@openai', 'codex')
  fs.mkdirSync(installedPackage, { recursive: true })
  fs.writeFileSync(path.join(installedPackage, 'package.json'), JSON.stringify({ version: '0.145.0' }))
  fs.writeFileSync(path.join(candidateRoot, 'package-lock.json'), JSON.stringify({
    packages: { 'node_modules/@openai/codex': { version: '0.144.3' } },
  }))
  assert.equal(readInstalledDependencyVersion(installedRoot, '@openai/codex'), '0.145.0')
  assert.equal(readLockedDependencyVersion(candidateRoot, '@openai/codex'), '0.144.3')
})

test('app-server updaters rewrite the candidate lockfile before candidate validation', () => {
  const shell = fs.readFileSync('update.sh', 'utf8')
  assert.match(shell, /runtime-dependency-version\.mjs/)
  assert.match(shell, /@openai\/codex/)
  assert.ok(shell.indexOf('runtime-dependency-version.mjs') < shell.indexOf('run_with_timeout npm ci'))

  const powershell = fs.readFileSync('update.ps1', 'utf8')
  assert.match(powershell, /runtime-dependency-version\.mjs/)
  assert.match(powershell, /@openai\/codex/)
  assert.ok(powershell.indexOf('runtime-dependency-version.mjs') < powershell.indexOf("Invoke-Npm @('ci')"))
})
