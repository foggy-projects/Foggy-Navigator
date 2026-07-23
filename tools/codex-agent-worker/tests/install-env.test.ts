import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { configureInstallEnv } from '../scripts/configure-install-env.mjs'

function temporaryInstall(t: test.TestContext): { root: string; envPath: string } {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-install-env-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const envPath = path.join(root, '.env')
  fs.writeFileSync(envPath, [
    'CODEX_WORKER_TOKEN="test-token"',
    '# CODEX_NAVIGATOR_WORKER_ID=',
    '# CODEX_NAVIGATOR_WORKER_CREDENTIAL=',
    '# CODEX_TERMINATION_OPERATION_LEDGER_DIR=/var/lib/foggy/codex-sdk/termination-operations',
    '',
  ].join('\n'))
  return { root, envPath }
}

test('fresh install persists the exact Worker identity and an absolute durable ledger', t => {
  const { root, envPath } = temporaryInstall(t)

  const result = configureInstallEnv({
    envPath,
    installDir: root,
    workerId: '36508966',
  })

  const content = fs.readFileSync(envPath, 'utf8')
  assert.match(content, /^CODEX_NAVIGATOR_WORKER_ID="36508966"$/m)
  assert.match(content, /^CODEX_TERMINATION_OPERATION_LEDGER_DIR=".*termination-operations"$/m)
  assert.equal(content.includes('CODEX_NAVIGATOR_WORKER_CREDENTIAL="'), false)
  assert.equal(result.workerIdConfigured, true)
  assert.equal(result.ledgerReady, true)
  assert.equal(fs.statSync(result.ledgerDir).isDirectory(), true)
  assert.equal(path.isAbsolute(result.ledgerDir), true)
})

test('upgrade preserves existing identity and ledger unless an explicit override is supplied', t => {
  const { root, envPath } = temporaryInstall(t)
  const originalLedger = path.join(root, 'persistent-ledger')
  fs.appendFileSync(envPath, [
    'CODEX_NAVIGATOR_WORKER_ID="existing-worker"',
    `CODEX_TERMINATION_OPERATION_LEDGER_DIR=${JSON.stringify(originalLedger)}`,
    'CODEX_NAVIGATOR_WORKER_CREDENTIAL="preserve-only"',
    '',
  ].join('\n'))

  // This assertion exercises preservation of the existing .env value. Do not let an
  // ambient host-level installer override turn it into an explicit replacement.
  const preserved = configureInstallEnv({ envPath, installDir: root, ledgerDir: '' })
  let content = fs.readFileSync(envPath, 'utf8')
  assert.match(content, /^CODEX_NAVIGATOR_WORKER_ID="existing-worker"$/m)
  assert.match(content, new RegExp(`^CODEX_TERMINATION_OPERATION_LEDGER_DIR=${JSON.stringify(originalLedger).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'm'))
  assert.match(content, /^CODEX_NAVIGATOR_WORKER_CREDENTIAL="preserve-only"$/m)
  assert.equal(preserved.ledgerDir, originalLedger)

  const replacementLedger = path.join(root, 'replacement-ledger')
  configureInstallEnv({
    envPath,
    installDir: root,
    workerId: 'replacement-worker',
    ledgerDir: replacementLedger,
  })
  content = fs.readFileSync(envPath, 'utf8')
  assert.equal((content.match(/^CODEX_NAVIGATOR_WORKER_ID=/gm) || []).length, 1)
  assert.equal((content.match(/^CODEX_TERMINATION_OPERATION_LEDGER_DIR=/gm) || []).length, 1)
  assert.match(content, /^CODEX_NAVIGATOR_WORKER_ID="replacement-worker"$/m)
  assert.match(content, new RegExp(`^CODEX_TERMINATION_OPERATION_LEDGER_DIR=${JSON.stringify(replacementLedger).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'm'))
})

test('installer configuration rejects ambiguous identities and non-absolute ledger paths', t => {
  const { root, envPath } = temporaryInstall(t)
  assert.throws(() => configureInstallEnv({
    envPath,
    installDir: root,
    workerId: 'worker with spaces',
  }), /Worker identity/)
  assert.throws(() => configureInstallEnv({
    envPath,
    installDir: root,
    ledgerDir: 'relative/ledger',
  }), /ledger path must be absolute/)
})
