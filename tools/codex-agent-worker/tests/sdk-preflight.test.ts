import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  SdkPreflightError,
  compareSemver,
  ensureSdk,
  inspectSdkCompatibility,
  installSdkWithRegistryFallback,
  parseBoolean,
  resolveNpmInvocation,
  validateTargetVersion,
} from '../scripts/ensure-sdk.mjs'

const quietLogger = { log: () => {}, warn: () => {} }

function createWorkerFixture(t: test.TestContext, installedVersion?: string): string {
  const workerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-sdk-preflight-'))
  t.after(() => fs.rmSync(workerDir, { recursive: true, force: true }))
  fs.writeFileSync(path.join(workerDir, 'runtime-requirements.json'), JSON.stringify({
    schemaVersion: 1,
    codexSdk: {
      minimumVersion: '0.144.1',
      repairVersion: '0.144.1',
    },
  }))
  if (installedVersion !== undefined) writeInstalledVersion(workerDir, installedVersion)
  return workerDir
}

function writeInstalledVersion(workerDir: string, version: string): void {
  const packageDir = path.join(workerDir, 'node_modules', '@openai', 'codex-sdk')
  fs.mkdirSync(packageDir, { recursive: true })
  fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ version }))
}

test('compareSemver handles stable and prerelease versions', () => {
  assert.equal(compareSemver('0.144.1', '0.144.1'), 0)
  assert.equal(compareSemver('0.144.2', '0.144.1'), 1)
  assert.equal(compareSemver('0.143.9', '0.144.1'), -1)
  assert.equal(compareSemver('0.144.1-beta.1', '0.144.1'), -1)
})

test('inspectSdkCompatibility distinguishes compatible, old, missing and invalid SDKs', (t) => {
  const compatibleDir = createWorkerFixture(t, '0.144.1')
  assert.equal(inspectSdkCompatibility(compatibleDir).compatible, true)

  const oldDir = createWorkerFixture(t, '0.142.5')
  assert.deepEqual(
    { compatible: inspectSdkCompatibility(oldDir).compatible, reason: inspectSdkCompatibility(oldDir).reason },
    { compatible: false, reason: 'below-minimum' }
  )

  const missingDir = createWorkerFixture(t)
  assert.equal(inspectSdkCompatibility(missingDir).reason, 'not-installed')

  const invalidDir = createWorkerFixture(t, 'dev-build')
  assert.equal(inspectSdkCompatibility(invalidDir).reason, 'invalid-version')
})

test('ensureSdk repairs an old SDK to the fixed validated version and rechecks it', (t) => {
  const workerDir = createWorkerFixture(t, '0.142.5')
  let requestedVersion = ''
  const status = ensureSdk({
    workerDir,
    autoUpdate: true,
    logger: quietLogger,
    install: ({ version }) => {
      requestedVersion = version
      writeInstalledVersion(workerDir, version)
      return true
    },
  })

  assert.equal(requestedVersion, '0.144.1')
  assert.equal(status.installedVersion, '0.144.1')
  assert.equal(status.compatible, true)
  assert.equal(status.repaired, true)
})

test('ensureSdk blocks startup when automatic repair is disabled', (t) => {
  const workerDir = createWorkerFixture(t, '0.142.5')
  assert.throws(
    () => ensureSdk({ workerDir, autoUpdate: false, logger: quietLogger }),
    (error: unknown) => error instanceof SdkPreflightError && error.exitCode === 3
  )
})

test('ensureSdk blocks startup when install or post-install verification fails', (t) => {
  const installFailureDir = createWorkerFixture(t, '0.142.5')
  assert.throws(
    () => ensureSdk({
      workerDir: installFailureDir,
      logger: quietLogger,
      install: () => false,
    }),
    (error: unknown) => error instanceof SdkPreflightError && error.exitCode === 4
  )

  const recheckFailureDir = createWorkerFixture(t, '0.142.5')
  assert.throws(
    () => ensureSdk({
      workerDir: recheckFailureDir,
      logger: quietLogger,
      install: () => true,
    }),
    (error: unknown) => error instanceof SdkPreflightError && error.exitCode === 4
  )
})

test('installSdkWithRegistryFallback retries the official registry once', () => {
  const calls: string[][] = []
  const result = installSdkWithRegistryFallback({
    workerDir: process.cwd(),
    version: '0.144.1',
    omitDev: true,
    logger: quietLogger,
    npmInvocation: { command: 'npm', argsPrefix: [] },
    runCommand: (_command, args) => {
      calls.push(args)
      if (args.includes('config')) {
        return { status: 0, stdout: 'https://registry.npmmirror.com/\n', stderr: '' }
      }
      return {
        status: args.some(arg => arg === '--registry=https://registry.npmjs.org/') ? 0 : 1,
        stdout: '',
        stderr: '',
      }
    },
  })

  assert.equal(result, true)
  assert.equal(calls.filter(args => args[0] === 'install').length, 2)
  assert.deepEqual(calls.at(-1), [
    'install',
    '@openai/codex-sdk@0.144.1',
    '--omit=dev',
    '--registry=https://registry.npmjs.org/',
  ])
})

test('resolveNpmInvocation runs npm through the current Node installation', () => {
  const invocation = resolveNpmInvocation()
  const result = invocation.command === process.execPath
    ? true
    : process.platform !== 'win32' || invocation.command.toLowerCase().includes('cmd')
  assert.equal(result, true)
})

test('validateTargetVersion rejects downgrades unless force is explicit', (t) => {
  const workerDir = createWorkerFixture(t, '0.144.1')
  assert.equal(validateTargetVersion(workerDir, '0.144.2').compatible, true)
  assert.throws(
    () => validateTargetVersion(workerDir, '0.142.5'),
    (error: unknown) => error instanceof SdkPreflightError && error.exitCode === 2
  )
  assert.equal(validateTargetVersion(workerDir, '0.142.5', true).forced, true)
})

test('parseBoolean supports the auto-update environment switch', () => {
  assert.equal(parseBoolean(undefined, true), true)
  assert.equal(parseBoolean('false', true), false)
  assert.equal(parseBoolean('OFF', true), false)
  assert.equal(parseBoolean('yes', false), true)
  assert.throws(() => parseBoolean('sometimes', true), SdkPreflightError)
})
