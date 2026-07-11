import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

const cli = path.resolve('scripts/lifecycle-marker.mjs')

test('transaction marker is exclusive, owner-bound, durable-shaped and progress-aware', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-marker-'))
  try {
    const marker = path.join(root, 'install', 'update.in-progress')
    const stage = path.join(root, '.caw-0123456789ab')
    const nonce = 'a'.repeat(32)
    assert.equal(run('create', '--path', marker, '--nonce', nonce, '--stage-root', stage).status, 0)
    assert.notEqual(run('create', '--path', marker, '--nonce', 'b'.repeat(32), '--stage-root', stage).status, 0)
    assert.equal(run('verify-owner', '--path', marker, '--nonce', nonce).status, 0)
    assert.notEqual(run('verify-owner', '--path', marker, '--nonce', 'b'.repeat(32)).status, 0)

    assert.equal(run('update', '--path', marker, '--nonce', nonce, '--phase', 'backing_up', '--append-backed-up', 'dist').status, 0)
    assert.equal(run('update', '--path', marker, '--nonce', nonce, '--phase', 'installing', '--append-installed', 'dist').status, 0)
    const value = JSON.parse(fs.readFileSync(marker, 'utf8')) as Record<string, unknown>
    assert.deepEqual(Object.keys(value).sort(), [
      'backed_up', 'created_at', 'installed', 'nonce', 'phase',
      'schema_version', 'stage_digest', 'stage_root', 'updated_at',
    ])
    assert.equal(value.schema_version, 1)
    assert.equal(value.phase, 'installing')
    assert.deepEqual(value.backed_up, ['dist'])
    assert.deepEqual(value.installed, ['dist'])
    assert.equal(String(value.stage_digest).length, 64)

    assert.notEqual(run('remove', '--path', marker, '--nonce', 'b'.repeat(32)).status, 0)
    assert.equal(fs.existsSync(marker), true)
    assert.equal(run('remove', '--path', marker, '--nonce', nonce).status, 0)
    assert.equal(fs.existsSync(marker), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('concurrent creators cannot replace the first transaction owner', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-race-'))
  try {
    const marker = path.join(root, 'install', 'update.in-progress')
    const stage = path.join(root, '.caw-0123456789ab')
    const nonces = Array.from({ length: 8 }, (_, index) => index.toString(16).repeat(32))
    const results = await Promise.all(nonces.map(nonce => runAsync(
      'create', '--path', marker, '--nonce', nonce, '--stage-root', stage,
    )))
    assert.equal(results.filter(result => result.status === 0).length, 1)
    const persisted = JSON.parse(fs.readFileSync(marker, 'utf8')) as { nonce: string }
    assert.ok(nonces.includes(persisted.nonce))
    assert.equal(results.filter((result, index) => result.status === 0 && nonces[index] === persisted.nonce).length, 1)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('lifecycle lock is exclusive, operation-bound, owner-released, and survives its creator exit', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-lock-'))
  try {
    const lock = path.join(root, 'install', 'lifecycle.lock')
    const nonce = 'a'.repeat(32)
    assert.equal(run('lock-acquire', '--path', lock, '--nonce', nonce, '--operation', 'update').status, 0)

    const persisted = JSON.parse(fs.readFileSync(lock, 'utf8')) as Record<string, unknown>
    assert.deepEqual(Object.keys(persisted).sort(), [
      'created_at', 'kind', 'operation', 'owner_digest', 'schema_version',
    ])
    assert.equal(persisted.kind, 'lifecycle_lock')
    assert.equal(persisted.operation, 'update')
    assert.equal(String(persisted.owner_digest).length, 64)
    assert.equal(fs.readFileSync(lock, 'utf8').includes(nonce), false)

    assert.equal(run(
      'lock-verify-owner', '--path', lock, '--nonce', nonce, '--operation', 'update',
    ).status, 0)
    assert.notEqual(run(
      'lock-verify-owner', '--path', lock, '--nonce', nonce, '--operation', 'start',
    ).status, 0)
    assert.notEqual(run(
      'lock-verify-owner', '--path', lock, '--nonce', 'b'.repeat(32), '--operation', 'update',
    ).status, 0)

    fs.utimesSync(lock, new Date(0), new Date(0))
    const contenders = await Promise.all(Array.from({ length: 6 }, (_, index) => runAsync(
      'lock-acquire', '--path', lock, '--nonce', String(index + 1).repeat(32), '--operation', 'start',
    )))
    assert.equal(contenders.filter(result => result.status === 0).length, 0)
    assert.equal(fs.existsSync(lock), true)

    assert.notEqual(run('lock-release', '--path', lock, '--nonce', 'b'.repeat(32)).status, 0)
    assert.equal(fs.existsSync(lock), true)
    assert.equal(run('lock-release', '--path', lock, '--nonce', nonce).status, 0)
    assert.equal(fs.existsSync(lock), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('concurrent lifecycle lock creators persist exactly one owner', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-lock-race-'))
  try {
    const lock = path.join(root, 'install', 'lifecycle.lock')
    const nonces = Array.from({ length: 8 }, (_, index) => index.toString(16).repeat(32))
    const results = await Promise.all(nonces.map(nonce => runAsync(
      'lock-acquire', '--path', lock, '--nonce', nonce, '--operation', 'start',
    )))
    assert.equal(results.filter(result => result.status === 0).length, 1)
    const winner = nonces.find((_, index) => results[index].status === 0)
    assert.ok(winner)
    assert.equal(run(
      'lock-verify-owner', '--path', lock, '--nonce', winner, '--operation', 'start',
    ).status, 0)
    assert.equal(run('lock-release', '--path', lock, '--nonce', winner).status, 0)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('write-once failure marker never overwrites the first safe reason', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-failure-'))
  try {
    const marker = path.join(root, 'state', 'lifecycle.failed')
    assert.equal(run('write-once', '--path', marker, '--reason', 'WORKER_START_IDENTITY_NOT_PROVEN').status, 0)
    assert.equal(run('write-once', '--path', marker, '--reason', 'WORKER_START_CLEANUP_NOT_PROVEN').status, 0)
    assert.equal(fs.readFileSync(marker, 'utf8'), 'WORKER_START_IDENTITY_NOT_PROVEN\n')
    assert.notEqual(run('write-once', '--path', marker, '--reason', '../unsafe').status, 0)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('marker operations reject symbolic-link targets', { skip: process.platform === 'win32' }, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-symlink-'))
  try {
    const real = path.join(root, 'real')
    const marker = path.join(root, 'marker')
    fs.writeFileSync(real, 'untrusted')
    fs.symlinkSync(real, marker)
    assert.notEqual(run('write-once', '--path', marker, '--reason', 'WORKER_START_IDENTITY_NOT_PROVEN').status, 0)
    assert.notEqual(run(
      'lock-acquire', '--path', marker, '--nonce', 'a'.repeat(32), '--operation', 'start',
    ).status, 0)
    assert.equal(fs.readFileSync(real, 'utf8'), 'untrusted')
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('lifecycle lock rejects a second hard-link identity', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-hardlink-'))
  try {
    const lock = path.join(root, 'lifecycle.lock')
    const alias = path.join(root, 'lifecycle.alias')
    const nonce = 'a'.repeat(32)
    assert.equal(run('lock-acquire', '--path', lock, '--nonce', nonce, '--operation', 'start').status, 0)
    fs.linkSync(lock, alias)
    assert.notEqual(run(
      'lock-verify-owner', '--path', lock, '--nonce', nonce, '--operation', 'start',
    ).status, 0)
    assert.notEqual(run('lock-release', '--path', lock, '--nonce', nonce).status, 0)
    assert.equal(fs.existsSync(lock), true)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

function run(...args: string[]) {
  return spawnSync(process.execPath, [cli, ...args], {
    cwd: path.resolve('.'),
    encoding: 'utf8',
    timeout: 20_000,
  })
}

function runAsync(...args: string[]): Promise<{ status: number | null; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {
      cwd: path.resolve('.'),
      stdio: ['ignore', 'ignore', 'pipe'],
    })
    let stderr = ''
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', chunk => { stderr += chunk })
    child.once('error', reject)
    child.once('exit', status => resolve({ status, stderr }))
  })
}
