import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import dotenv from 'dotenv'
import { configureInstallAllowedCwds } from '../scripts/configure-install-env.mjs'

test('fresh-install env writer preserves the template and writes platform roots literally', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex install env #'))
  const envFile = path.join(root, '.env')
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.writeFileSync(envFile, [
    '# worker configuration',
    'CODEX_APP_SERVER_WORKER_TOKEN=',
    'CODEX_APP_SERVER_ALLOWED_CWDS=',
    'CODEX_HOME=',
    '',
  ].join('\r\n'))

  const allowedCwds = 'D:\\,E:\\'
  configureInstallAllowedCwds(envFile, allowedCwds)

  const configured = fs.readFileSync(envFile, 'utf8')
  assert.match(configured, /# worker configuration\r\n/)
  assert.equal(dotenv.parse(configured).CODEX_APP_SERVER_ALLOWED_CWDS, allowedCwds)
  assert.equal(dotenv.parse(configured).CODEX_APP_SERVER_WORKER_TOKEN, '')
  assert.equal(dotenv.parse(configured).CODEX_HOME, '')
})

test('fresh-install env writer fails closed on a malformed template or value', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-install-env-invalid-'))
  const envFile = path.join(root, '.env')
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))

  fs.writeFileSync(envFile, 'CODEX_HOME=\n')
  assert.throws(() => configureInstallAllowedCwds(envFile, '/'), /is missing/)
  fs.writeFileSync(envFile, 'CODEX_APP_SERVER_ALLOWED_CWDS=\nCODEX_APP_SERVER_ALLOWED_CWDS=/srv\n')
  assert.throws(() => configureInstallAllowedCwds(envFile, '/'), /exactly one assignment/)
  assert.throws(() => configureInstallAllowedCwds(envFile, '/\nINJECTED=true'), /invalid/)
})

test('installer applies the platform cwd default only when creating .env', {
  skip: !['win32', 'linux'].includes(process.platform),
}, t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex installer defaults #'))
  const sourceDir = path.join(root, 'source package #1')
  const freshInstall = path.join(root, 'fresh install #1')
  const existingInstall = path.join(root, 'existing install #1')
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  prepareInstallerSource(sourceDir)
  prepareFreshInstall(freshInstall)

  const freshResult = runInstaller(sourceDir, freshInstall)
  assert.equal(freshResult.status, 0, `${freshResult.stdout}\n${freshResult.stderr}`)
  const freshEnv = dotenv.parse(fs.readFileSync(path.join(freshInstall, '.env'), 'utf8'))
  assert.equal(freshEnv.CODEX_APP_SERVER_ALLOWED_CWDS, expectedPlatformDefault())

  fs.mkdirSync(existingInstall, { recursive: true })
  const existingBytes = Buffer.from('# operator-owned\r\nCODEX_APP_SERVER_ALLOWED_CWDS=X:\\\r\n', 'utf8')
  fs.writeFileSync(path.join(existingInstall, '.env'), existingBytes)
  const existingResult = runInstaller(sourceDir, existingInstall)
  assert.equal(existingResult.status, 0, `${existingResult.stdout}\n${existingResult.stderr}`)
  assert.deepEqual(fs.readFileSync(path.join(existingInstall, '.env')), existingBytes)
})

function prepareInstallerSource(sourceDir: string): void {
  fs.mkdirSync(sourceDir, { recursive: true })
  if (process.platform === 'win32') {
    fs.copyFileSync('install.ps1', path.join(sourceDir, 'install.ps1'))
    fs.writeFileSync(path.join(sourceDir, 'update.ps1'), 'param([string]$Package, [string]$InstallDir, [switch]$NoRestart)\nexit 0\n')
  } else {
    fs.copyFileSync('install.sh', path.join(sourceDir, 'install.sh'))
    fs.writeFileSync(path.join(sourceDir, 'update.sh'), '#!/usr/bin/env bash\nexit 0\n')
  }
}

function prepareFreshInstall(installDir: string): void {
  fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
  fs.mkdirSync(path.join(installDir, 'node_modules'), { recursive: true })
  fs.writeFileSync(path.join(installDir, '.env.example'), 'CODEX_APP_SERVER_ALLOWED_CWDS=\nCODEX_HOME=\n')
  fs.copyFileSync('scripts/configure-install-env.mjs', path.join(installDir, 'scripts', 'configure-install-env.mjs'))
  fs.cpSync(path.resolve('node_modules', 'dotenv'), path.join(installDir, 'node_modules', 'dotenv'), { recursive: true })
}

function runInstaller(sourceDir: string, installDir: string) {
  return process.platform === 'win32'
    ? spawnSync('powershell.exe', [
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(sourceDir, 'install.ps1'),
      '-InstallDir', installDir,
    ], { encoding: 'utf8', timeout: 20_000 })
    : spawnSync('bash', [
      path.join(sourceDir, 'install.sh'), '--install-dir', installDir,
    ], { encoding: 'utf8', timeout: 20_000 })
}

function expectedPlatformDefault(): string {
  if (process.platform !== 'win32') return '/'
  const command = [
    '$roots = @([IO.DriveInfo]::GetDrives() | ForEach-Object {',
    "try { if ($_.IsReady) { $root = $_.RootDirectory.FullName; if ($root -match '^[A-Za-z]:\\\\$' -and -not $root.Equals('C:\\', [StringComparison]::OrdinalIgnoreCase)) { $root } } } catch {}",
    '} | Sort-Object -Unique)',
    "[Console]::Write(($roots -join ','))",
  ].join('\n')
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', command], {
    encoding: 'utf8',
    timeout: 10_000,
  })
  assert.equal(result.status, 0, result.stderr)
  return result.stdout
}
