import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  acquireStateWriterLease,
  initializeStateIdentity,
  StateStoreSafetyError,
} from '../src/persistence/state-identity.js'
import { tempDirectory } from './helpers.js'

test('state generation identity persists across restart and changes after full store rebuild', async t => {
  const stateDir = await tempDirectory('codex-state-identity-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))

  const first = await initializeStateIdentity(stateDir, 'legacy-instance')
  const restarted = await initializeStateIdentity(stateDir, 'different-legacy-value')
  assert.equal(restarted, first)
  assert.match(first, /^codex-store-[0-9a-f-]{36}$/)

  await fs.rm(stateDir, { recursive: true, force: true })
  const rebuilt = await initializeStateIdentity(stateDir, 'legacy-instance')
  assert.notEqual(rebuilt, first)
})

test('pre-marker journal upgrade preserves the legacy instance identity exactly once', async t => {
  const stateDir = await tempDirectory('codex-state-identity-upgrade-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const tasks = path.join(stateDir, 'tasks')
  await fs.mkdir(tasks)
  await fs.writeFile(path.join(tasks, 'existing.jsonl'), '{"schema_version":1}\n', 'utf8')

  const upgraded = await initializeStateIdentity(stateDir, 'legacy-calculated-instance')
  const restarted = await initializeStateIdentity(stateDir, 'another-value')

  assert.equal(upgraded, 'legacy-calculated-instance')
  assert.equal(restarted, upgraded)
})

test('corrupt state identity marker fails closed', async t => {
  const stateDir = await tempDirectory('codex-state-identity-corrupt-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await fs.writeFile(path.join(stateDir, '.codex-store-identity.json'), '{broken', 'utf8')

  await assert.rejects(
    initializeStateIdentity(stateDir, 'legacy-instance'),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_IDENTITY_CORRUPT',
  )
})

test('state writer lease rejects a concurrent live owner and releases gracefully', async t => {
  const stateDir = await tempDirectory('codex-state-lease-live-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = await acquireStateWriterLease(stateDir)

  await assert.rejects(
    acquireStateWriterLease(stateDir),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_ACTIVE',
  )

  await first.release()
  const replacement = await acquireStateWriterLease(stateDir)
  await replacement.release()
})

test('state writer lease recovers only a confirmed same-host dead pid', async t => {
  const stateDir = await tempDirectory('codex-state-lease-dead-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await acquireStateWriterLease(stateDir, {
    hostname: 'worker-host',
    pid: 101,
    isProcessAlive: () => false,
  })

  const recovered = await acquireStateWriterLease(stateDir, {
    hostname: 'worker-host',
    pid: 202,
    isProcessAlive: pid => pid === 202,
  })
  await recovered.release()
})

test('state writer lease fails closed for an owner on another host', async t => {
  const stateDir = await tempDirectory('codex-state-lease-host-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = await acquireStateWriterLease(stateDir, {
    hostname: 'worker-host-a',
    pid: 101,
    isProcessAlive: () => true,
  })

  await assert.rejects(
    acquireStateWriterLease(stateDir, {
      hostname: 'worker-host-b',
      pid: 202,
      isProcessAlive: () => false,
    }),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_CROSS_HOST',
  )
  await first.release()
})
