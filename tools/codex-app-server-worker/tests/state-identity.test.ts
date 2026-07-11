import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  acquireStateWriterLease,
  canonicalizeStateDirectory,
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

test('pre-marker journal upgrade derives a safe public id and losslessly records an unsafe legacy id', async t => {
  const stateDir = await tempDirectory('codex-state-identity-unsafe-upgrade-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const tasks = path.join(stateDir, 'tasks')
  await fs.mkdir(tasks)
  await fs.writeFile(path.join(tasks, 'existing.jsonl'), '{"schema_version":1}\n', 'utf8')
  const legacy = 'worker:3062 / 北京'

  const upgraded = await initializeStateIdentity(stateDir, legacy)
  const expectedDigest = crypto.createHash('sha256').update(JSON.stringify(legacy), 'utf8').digest('hex')
  const marker = JSON.parse(await fs.readFile(
    path.join(stateDir, '.codex-store-identity.json'),
    'utf8',
  )) as Record<string, unknown>

  assert.equal(upgraded, `codex-legacy-${expectedDigest}`)
  assert.match(upgraded, /^[A-Za-z0-9._-]{1,128}$/)
  assert.equal(marker.schema_version, 2)
  assert.equal(marker.legacy_instance_id, legacy)
  assert.equal(await initializeStateIdentity(stateDir, 'ignored-after-migration'), upgraded)
})

test('schema v1 marker receives one compatibility migration without changing its public identity', async t => {
  const stateDir = await tempDirectory('codex-state-identity-v1-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await fs.writeFile(path.join(stateDir, '.codex-store-identity.json'), JSON.stringify({
    schema_version: 1,
    instance_id: 'legacy-safe-instance',
    created_at: '2026-07-10T00:00:00.000Z',
  }), 'utf8')

  const migrated = await initializeStateIdentity(stateDir, 'different-value')
  const marker = JSON.parse(await fs.readFile(
    path.join(stateDir, '.codex-store-identity.json'),
    'utf8',
  )) as Record<string, unknown>

  assert.equal(migrated, 'legacy-safe-instance')
  assert.equal(marker.schema_version, 2)
  for (const directory of ['tasks', 'events']) {
    const sentinel = JSON.parse(await fs.readFile(
      path.join(stateDir, directory, '.codex-store-generation.json'),
      'utf8',
    )) as Record<string, unknown>
    assert.equal(sentinel.generation_id, marker.generation_id)
  }
})

test('clearing only journal directories rotates a schema v2 state generation', async t => {
  const stateDir = await tempDirectory('codex-state-identity-cleared-journals-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const first = await initializeStateIdentity(stateDir, 'legacy-instance')

  await fs.rm(path.join(stateDir, 'tasks'), { recursive: true, force: true })
  await fs.rm(path.join(stateDir, 'events'), { recursive: true, force: true })
  const rotated = await initializeStateIdentity(stateDir, 'legacy-instance')

  assert.notEqual(rotated, first)
  assert.equal(await initializeStateIdentity(stateDir, 'legacy-instance'), rotated)
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

test('state writer lease safely reclaims a same-host dead recovery owner', async t => {
  const stateDir = await tempDirectory('codex-state-recovery-dead-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await fs.writeFile(path.join(stateDir, '.codex-store-writer-lease-recovery.json'), JSON.stringify({
    schema_version: 1,
    hostname: 'worker-host',
    pid: 101,
    nonce: '00000000-0000-4000-8000-000000000001',
    acquired_at: '2026-07-10T00:00:00.000Z',
  }), 'utf8')

  const recovered = await acquireStateWriterLease(stateDir, {
    hostname: 'worker-host',
    pid: 202,
    isProcessAlive: pid => pid === 202,
  })
  await recovered.release()
  await assert.rejects(fs.access(path.join(stateDir, '.codex-store-writer-lease-recovery.json')))
})

test('state writer lease fails closed for a recovery owner on another host', async t => {
  const stateDir = await tempDirectory('codex-state-recovery-host-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await fs.writeFile(path.join(stateDir, '.codex-store-writer-lease-recovery.json'), JSON.stringify({
    schema_version: 1,
    hostname: 'worker-host-a',
    pid: 101,
    nonce: '00000000-0000-4000-8000-000000000002',
    acquired_at: '2026-07-10T00:00:00.000Z',
  }), 'utf8')

  await assert.rejects(
    acquireStateWriterLease(stateDir, {
      hostname: 'worker-host-b',
      pid: 202,
      isProcessAlive: () => false,
    }),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_WRITER_LEASE_RECOVERY_CROSS_HOST',
  )
})

test('state metadata hardlinks fail closed', async t => {
  const stateDir = await tempDirectory('codex-state-hardlink-')
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  await initializeStateIdentity(stateDir, 'legacy-instance')
  const marker = path.join(stateDir, '.codex-store-identity.json')
  const alias = path.join(stateDir, 'identity-hardlink.json')
  try {
    await fs.link(marker, alias)
  } catch (error) {
    if (['EACCES', 'ENOTSUP', 'EPERM'].includes(String((error as NodeJS.ErrnoException).code))) {
      t.skip('filesystem does not support hardlinks')
      return
    }
    throw error
  }

  await assert.rejects(
    initializeStateIdentity(stateDir, 'legacy-instance'),
    error => error instanceof StateStoreSafetyError
      && error.code === 'CODEX_STATE_IDENTITY_CORRUPT',
  )
})

test('state directory aliases canonicalize to the physical directory', async t => {
  const target = await tempDirectory('codex-state-canonical-target-')
  const alias = `${target}-alias`
  t.after(async () => {
    await fs.rm(alias, { recursive: true, force: true })
    await fs.rm(target, { recursive: true, force: true })
  })
  try {
    await fs.symlink(target, alias, process.platform === 'win32' ? 'junction' : 'dir')
  } catch (error) {
    if (['EACCES', 'EPERM'].includes(String((error as NodeJS.ErrnoException).code))) {
      t.skip('filesystem does not permit directory links')
      return
    }
    throw error
  }

  assert.equal(await canonicalizeStateDirectory(alias), path.normalize(await fs.realpath(target)))
})
