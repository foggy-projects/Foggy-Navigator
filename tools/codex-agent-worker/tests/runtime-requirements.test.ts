import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { resolveCodexSdkRuntimeStatus } from '../src/runtime-requirements.ts'

function createWorkerFixture(t: test.TestContext, installedVersion?: string): string {
  const workerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-runtime-status-'))
  t.after(() => fs.rmSync(workerDir, { recursive: true, force: true }))
  fs.writeFileSync(path.join(workerDir, 'runtime-requirements.json'), JSON.stringify({
    codexSdk: { minimumVersion: '0.144.1', repairVersion: '0.144.1' },
  }))
  if (installedVersion) {
    const packageDir = path.join(workerDir, 'node_modules', '@openai', 'codex-sdk')
    fs.mkdirSync(packageDir, { recursive: true })
    fs.writeFileSync(path.join(packageDir, 'package.json'), JSON.stringify({ version: installedVersion }))
  }
  return workerDir
}

test('resolveCodexSdkRuntimeStatus exposes installed, minimum and compatible versions', (t) => {
  const workerDir = createWorkerFixture(t, '0.144.2')
  assert.deepEqual(resolveCodexSdkRuntimeStatus(workerDir), {
    installedVersion: '0.144.2',
    minimumVersion: '0.144.1',
    compatible: true,
    reason: 'compatible',
  })
})

test('resolveCodexSdkRuntimeStatus fails closed when requirements are unavailable', (t) => {
  const workerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-runtime-status-missing-'))
  t.after(() => fs.rmSync(workerDir, { recursive: true, force: true }))
  const status = resolveCodexSdkRuntimeStatus(workerDir)
  assert.equal(status.compatible, false)
  assert.equal(status.minimumVersion, 'unknown')
})
