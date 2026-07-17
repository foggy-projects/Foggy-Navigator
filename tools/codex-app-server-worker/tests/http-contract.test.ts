import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import net, { type AddressInfo } from 'node:net'
import test from 'node:test'
import { createApp } from '../src/app.js'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'
import { AppServerRuntimeError } from '../src/app-server/runtime.js'
import { TaskStore } from '../src/persistence/task-store.js'
import type { TaskRequest } from '../src/models.js'
import { TaskManager } from '../src/task-manager.js'
import { GeneratedImageStore } from '../src/generated-image-store.js'
import { TerminationOperationReceiptLedger } from '../src/termination-operation.js'
import { FakeExecutor, tempDirectory, testConfig, waitFor } from './helpers.js'
import {
  ACTUAL_INSTANCE_HEADER,
  EXPECTED_INSTANCE_HEADER,
} from '../src/instance-affinity.js'

test('instance affinity guard rejects every task route before manager access', async t => {
  const config = testConfig(await tempDirectory('codex-app-instance-guard-'))
  let managerAccesses = 0
  const manager = new Proxy({}, {
    get() {
      managerAccesses++
      throw new Error('task manager must not be accessed on instance mismatch')
    },
  }) as TaskManager
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(config.stateDir, { recursive: true, force: true })
  })

  const headers = {
    ...authHeaders(),
    [EXPECTED_INSTANCE_HEADER]: 'another-instance',
    'Content-Type': 'application/json',
    'Idempotency-Key': 'instance-guard-task',
  }
  const requests: Array<[string, RequestInit]> = [
    ['/api/v1/tasks', { method: 'POST', headers, body: JSON.stringify({ prompt: 'must not persist' }) }],
    ['/api/v1/tasks/task-1/status', { headers }],
    ['/api/v1/tasks/task-1/subscribe?ack_seq=0', { headers }],
    ['/api/v1/tasks/task-1/generated-images/0123456789abcdef0123456789abcdef', { headers }],
    ['/api/v1/tasks/task-1/abort', { method: 'POST', headers }],
    ['/api/v1/tasks/task-1/respond', { method: 'POST', headers, body: JSON.stringify({ request_id: 'r', answers: {} }) }],
    ['/api/v1/tasks/task-1/context-usage', { headers }],
    ['/api/v1/tasks/task-1/compact-context', { method: 'POST', headers, body: JSON.stringify({ operation_id: 'op-1' }) }],
    ['/api/v1/tasks/task-1/compact-context/op-1', { headers }],
    ['/api/v1/tasks/task-1', { method: 'DELETE', headers }],
    ['/api/v1/processes', { headers }],
  ]
  for (const [path, init] of requests) {
    const response = await fetch(`${baseUrl}${path}`, init)
    assert.equal(response.status, 409)
    assert.deepEqual(await response.json(), { error: 'RUNTIME_INSTANCE_MISMATCH' })
    assert.equal(response.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
  }
  const malformed = await fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers,
    body: '{malformed',
  })
  assert.equal(malformed.status, 409)
  assert.equal(malformed.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
  assert.deepEqual(await malformed.json(), { error: 'RUNTIME_INSTANCE_MISMATCH' })

  const oversized = await requestWithDeclaredLength(baseUrl, headers, 26 * 1024 * 1024)
  assert.equal(oversized.status, 409)
  assert.equal(oversized.actualInstanceId, config.instanceId)
  assert.equal(oversized.serverClosed, true)
  assert.equal(managerAccesses, 0)
})

test('task and capability responses prove the actual instance while absent expectation remains compatible', async t => {
  const stateDir = await tempDirectory('codex-app-instance-proof-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const capability = await fetch(`${baseUrl}/api/v1/capabilities`, { headers: authHeaders() })
  assert.equal(capability.status, 200)
  assert.equal(capability.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
  const manifest = await capability.json() as Record<string, any>
  assert.equal(manifest.features.instance_affinity_guard, true)

  const accepted = await postTask(baseUrl, 'instance-proof-task', { prompt: 'compatible direct request' })
  assert.equal(accepted.response.status, 202)
  assert.equal(accepted.response.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
  await waitFor(() => manager.get('instance-proof-task')?.status === 'terminal')

  const status = await fetch(`${baseUrl}/api/v1/tasks/instance-proof-task/status`, {
    headers: { ...authHeaders(), [EXPECTED_INSTANCE_HEADER]: config.instanceId },
  })
  assert.notEqual(status.status, 409)
  assert.equal(status.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
})

test('managed process snapshots expose only fixed task-bound app-server identity without dispatching work', async t => {
  const stateDir = await tempDirectory('codex-app-managed-process-snapshot-')
  const config = testConfig(stateDir)
  const executor = new ManagedProcessSnapshotExecutor([{
    taskId: 'managed-process-task',
    pid: 4242,
    instanceId: 'instance-managed-process',
  }])
  const manager = new TaskManager(
    config,
    new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! }),
    executor,
  )
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const response = await fetch(`${baseUrl}/api/v1/processes`, { headers: authHeaders() })
  assert.equal(response.status, 200)
  assert.deepEqual(await response.json(), {
    processes: [{
      pid: 4242,
      command: 'codex-app-server',
      process_type: 'codex-app-server',
      is_orphan: false,
      foggy_task_id: 'managed-process-task',
      process_identity: 'app-server-instance:instance-managed-process',
    }],
    active_task_count: 1,
    total: 1,
  })
  assert.equal(executor.snapshotCalls, 1)
  assert.equal(executor.executeCalls, 0)
  assert.equal(executor.manualPidKillCalls, 0)
})

test('task-bound context compaction is idempotent and publishes the latest native usage snapshot', async t => {
  const stateDir = await tempDirectory('codex-app-context-maintenance-http-')
  const config = testConfig(stateDir)
  const executor = new ContextMaintenanceExecutor()
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const accepted = await postTask(baseUrl, 'context-maintenance-task', { prompt: 'seed thread' })
  assert.equal(accepted.response.status, 202)
  await waitFor(() => manager.get('context-maintenance-task')?.status === 'terminal')
  const compact = async () => fetch(`${baseUrl}/api/v1/tasks/context-maintenance-task/compact-context`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation_id: 'compact-op-1' }),
  })
  const first = await compact()
  assert.equal(first.status, 200)
  const firstBody = await first.json() as Record<string, unknown>
  assert.equal(firstBody.status, 'completed', JSON.stringify({ firstBody, compactCalls: executor.compactCalls }))
  const replay = await compact()
  assert.equal(replay.status, 200)
  assert.equal(executor.compactCalls, 1)

  const usage = await fetch(`${baseUrl}/api/v1/tasks/context-maintenance-task/context-usage`, {
    headers: authHeaders(),
  })
  assert.equal(usage.status, 200)
  assert.deepEqual(await usage.json(), {
    schema_version: 1,
    thread_id: 'thread-test',
    turn_id: 'compact-turn-test',
    observed_at: '2026-07-17T00:00:00.000Z',
    last_total_tokens: 12000,
    model_context_window: 270000,
    remaining_tokens: 258000,
    status: 'known',
  })
})

test('generated images are served through an authenticated task route and removed with the tombstone', async t => {
  const stateDir = await tempDirectory('codex-app-generated-image-http-')
  const config = testConfig(stateDir, { imageGenerationMode: 'local' })
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await store.initialize()
  await store.accept('generated-image-task', { prompt: 'draw' })
  await store.transition('generated-image-task', 'terminal', { outcome: 'completed' })
  const imageBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 9, 8, 7])
  const image = new GeneratedImageStore(config).persist({
    taskId: 'generated-image-task',
    itemId: 'image-item-http',
    result: imageBytes.toString('base64'),
  })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const unauthorized = await fetch(
    `${baseUrl}/api/v1/tasks/generated-image-task/generated-images/${image.artifact_id}`,
  )
  assert.equal(unauthorized.status, 401)
  const response = await fetch(
    `${baseUrl}/api/v1/tasks/generated-image-task/generated-images/${image.artifact_id}`,
    { headers: authHeaders() },
  )
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'image/png')
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), imageBytes)

  const deleted = await fetch(`${baseUrl}/api/v1/tasks/generated-image-task`, {
    method: 'DELETE',
    headers: authHeaders(),
  })
  assert.equal(deleted.status, 200)
  const afterDelete = await fetch(
    `${baseUrl}/api/v1/tasks/generated-image-task/generated-images/${image.artifact_id}`,
    { headers: authHeaders() },
  )
  assert.equal(afterDelete.status, 404)
  await assert.rejects(fs.access(image.local_path), /ENOENT/)
})

test('task accept v1 is idempotent, conflicts on changed payload, and replays terminal SSE', async t => {
  const stateDir = await tempDirectory('codex-app-http-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const first = await postTask(baseUrl, 'task-contract', { prompt: 'inspect', model: 'codex-ultra' })
  assert.equal(first.response.status, 202)
  assert.deepEqual(first.body, { task_id: 'task-contract', status: 'accepted' })
  assert.equal(store.getRequest('task-contract').cwd, process.cwd())
  const retry = await postTask(baseUrl, 'task-contract', { model: 'codex-ultra', prompt: 'inspect' })
  assert.equal(retry.response.status, 202)
  assert.equal(retry.body.task_id, 'task-contract')
  const conflict = await postTask(baseUrl, 'task-contract', { prompt: 'different' })
  assert.equal(conflict.response.status, 409)
  assert.equal(conflict.body.error, 'IDEMPOTENCY_KEY_CONFLICT')
  await waitFor(() => manager.get('task-contract')?.status === 'terminal')
  assert.equal(executor.calls, 1)

  const status = await fetch(`${baseUrl}/api/v1/tasks/task-contract/status`, { headers: authHeaders() })
  const statusBody = await status.json() as Record<string, unknown>
  assert.equal(statusBody.status, 'terminal')
  assert.equal(statusBody.outcome, 'completed')
  const subscribe = await fetch(`${baseUrl}/api/v1/tasks/task-contract/subscribe?ack_seq=0`, { headers: authHeaders() })
  const stream = await subscribe.text()
  assert.match(stream, /sync_checkpoint/)
  assert.match(stream, /"type":"result"/)
})

test('concurrent terminal SSE replays are complete and release the on-demand broadcast', async t => {
  const stateDir = await tempDirectory('codex-app-http-terminal-replay-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  await postTask(baseUrl, 'terminal-replay-task', { prompt: 'complete once' })
  await waitFor(() => manager.get('terminal-replay-task')?.status === 'terminal')
  await waitFor(() => manager.runtimeMetrics().resident_broadcasts === 0)

  const responses = await Promise.all(Array.from({ length: 12 }, () => fetch(
    `${baseUrl}/api/v1/tasks/terminal-replay-task/subscribe?ack_seq=0`,
    { headers: authHeaders() },
  )))
  const streams = await Promise.all(responses.map(async response => {
    assert.equal(response.status, 200)
    return response.text()
  }))
  for (const stream of streams) {
    assert.match(stream, /sync_checkpoint/)
    assert.equal([...stream.matchAll(/"type":"result"/g)].length, 1)
  }
  await waitFor(() => manager.runtimeMetrics().resident_broadcasts === 0)
})

test('existing idempotency keys remain queryable during drain and readiness degradation', async t => {
  const stateDir = await tempDirectory('codex-app-http-existing-retry-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  await postTask(baseUrl, 'stable-existing-task', { prompt: 'same request' })
  await waitFor(() => manager.get('stable-existing-task')?.status === 'terminal')
  await manager.shutdown(0)
  config.stateEncryptionKey = undefined
  config.allowedCwds = []

  const retry = await postTask(baseUrl, 'stable-existing-task', { prompt: 'same request' })
  assert.equal(retry.response.status, 202)
  assert.equal(retry.body.status, 'terminal')
  assert.equal(executor.calls, 1)

  const conflict = await postTask(baseUrl, 'stable-existing-task', { prompt: 'changed request' })
  assert.equal(conflict.response.status, 409)
  assert.equal(conflict.body.error, 'IDEMPOTENCY_KEY_CONFLICT')

  const newTask = await postTask(baseUrl, 'new-task-while-degraded', { prompt: 'new request' })
  assert.equal(newTask.response.status, 503)
  assert.equal(newTask.body.error, 'APP_SERVER_RUNTIME_NOT_READY')
})

test('concurrent HTTP accepts coalesce by idempotency key before capacity checks', async t => {
  const stateDir = await tempDirectory('codex-app-http-concurrent-')
  const config = testConfig(stateDir, { maxConcurrentTasks: 1, maxQueuedTasks: 0 })
  const store = new SlowTaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const same = await Promise.all([
    postTask(baseUrl, 'task-concurrent', { prompt: 'same' }),
    postTask(baseUrl, 'task-concurrent', { prompt: 'same' }),
  ])
  assert.deepEqual(same.map(item => item.response.status), [202, 202])
  await waitFor(() => manager.get('task-concurrent')?.status === 'terminal')
  assert.equal(executor.calls, 1)

  const changed = await Promise.all([
    postTask(baseUrl, 'task-concurrent-conflict', { prompt: 'first' }),
    postTask(baseUrl, 'task-concurrent-conflict', { prompt: 'second' }),
  ])
  assert.deepEqual(changed.map(item => item.response.status).sort(), [202, 409])
  await waitFor(() => manager.get('task-concurrent-conflict')?.status === 'terminal')
})

test('capability manifest exposes the Java registry contract and exact schema lock', async t => {
  const stateDir = await tempDirectory('codex-app-capability-')
  const config = testConfig(stateDir, { runtimeRevision: 7 })
  config.modelAliases['retired-mini'] = 'gpt-5.4-mini:high'
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const response = await fetch(`${baseUrl}/api/v1/capabilities`, { headers: authHeaders() })
  const manifest = await response.json() as Record<string, any>
  assert.equal(manifest.contract_version, 1)
  assert.equal(manifest.runtime_type, 'APP_SERVER')
  assert.equal(manifest.runtime_id, 'test-runtime')
  assert.equal(manifest.runtime_revision, 7)
  assert.equal(manifest.instance_id, 'test-instance')
  assert.equal(manifest.app_server_protocol_version, '0.144.3')
  assert.equal(manifest.cli_version, '0.144.3')
  assert.equal(manifest.schema_digest, '6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f')
  assert.deepEqual(manifest.models, [
    'gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
    'gpt-5.4', 'gpt-5.3-codex-spark',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-sol'], [
    'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-terra'], [
    'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-luna'], ['low', 'medium', 'high', 'xhigh', 'max'])
  assert.equal(manifest.model_reasoning_matrix['gpt-5.4-mini'], undefined)
  assert.equal(manifest.model_aliases['retired-mini'], undefined)
  assert.equal(manifest.model_capabilities.aliases['retired-mini'], undefined)
  assert.equal(manifest.model_capabilities.dynamic_passthrough.route_selectable, false)
  assert.equal(manifest.features.image_generation, false)
  assert.equal(manifest.reasoning_efforts, undefined)
  assert.deepEqual(manifest.features.approval_modes, ['never'])
  assert.equal(manifest.features.interactive_user_input, true)
  assert.equal(manifest.features.interactive_user_input_experimental, true)
  assert.equal(manifest.features.additional_directories, false)
  assert.equal(manifest.features.committed_reconciliation, true)
  assert.equal(manifest.features.same_thread_turn_lock, true)
  assert.equal(manifest.features.same_cwd_write_lock, false)
})

test('task acceptance rejects retired Mini after direct or alias resolution', async t => {
  const stateDir = await tempDirectory('codex-app-retired-mini-')
  const config = testConfig(stateDir)
  config.modelAliases['retired-mini'] = 'gpt-5.4-mini'
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  for (const [index, model] of [
    'gpt-5.4-mini',
    'gpt-5.4-mini:high',
    'retired-mini',
    'retired-mini:xhigh',
  ].entries()) {
    const rejected = await postTask(baseUrl, `retired-mini-${index}`, { prompt: 'must not start', model })
    assert.equal(rejected.response.status, 400)
    assert.deepEqual(rejected.body, { error: 'UNSUPPORTED_CODEX_MODEL' })
    assert.equal(manager.get(`retired-mini-${index}`), undefined)
  }
  assert.equal(executor.calls, 0)
})

test('health and task acceptance fail closed without the state encryption key', async t => {
  const stateDir = await tempDirectory('codex-app-not-ready-')
  const config = testConfig(stateDir, { stateEncryptionKey: undefined })
  const store = new TaskStore({ stateDir, encryptionKey: Buffer.alloc(32) })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize({ resume: false })
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const health = await fetch(`${baseUrl}/health`)
  const healthBody = await health.json() as Record<string, any>
  assert.equal(healthBody.ready, false)
  assert.ok(healthBody.reasons.includes('STATE_ENCRYPTION_KEY_MISSING'))
  const accepted = await postTask(baseUrl, 'task-no-key', { prompt: 'must not start' })
  assert.equal(accepted.response.status, 503)
})

test('HTTP contract rejects every declared unsupported field instead of ignoring it', async t => {
  const stateDir = await tempDirectory('codex-app-unsupported-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const cases: Array<[string, Record<string, unknown>, string]> = [
    ['approval', { approval_policy: 'on-request' }, 'UNSUPPORTED_APPROVAL_POLICY'],
    ['attachments', { attachments: [{ url: 'https://example.test/a' }] }, 'UNSUPPORTED_ATTACHMENTS'],
    ['directories', { additional_directories: ['C:\\other'] }, 'UNSUPPORTED_ADDITIONAL_DIRECTORIES'],
    ['business', { business_runtime_context: {} }, 'UNSUPPORTED_BUSINESS_RUNTIME_CONTEXT'],
    ['turns', { max_turns: 2 }, 'UNSUPPORTED_MAX_TURNS'],
    ['env', { env_vars: { PATH: 'private' } }, 'UNSUPPORTED_ENV_VARS'],
    ['mcp-config', { codex_config: { mcp_servers: { private: { command: 'private' } } } }, 'UNSUPPORTED_CODEX_CONFIG_KEY'],
    ['sandbox-config', { codex_config: { sandbox_workspace_write: { writable_roots: ['/'] } } }, 'UNSUPPORTED_CODEX_CONFIG_KEY'],
    ['unknown', { misspelled_option: true }, 'UNSUPPORTED_REQUEST_FIELD'],
  ]
  for (const [name, field, expected] of cases) {
    const rejected = await postTask(baseUrl, `unsupported-${name}`, { prompt: 'x', ...field })
    assert.equal(rejected.response.status, 400)
    assert.equal(rejected.body.error, expected)
  }
})

test('an empty Worker token disables HTTP authentication for every control API', async t => {
  const stateDir = await tempDirectory('codex-app-no-token-')
  const config = testConfig(stateDir, { workerToken: '' })
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const health = await fetch(`${baseUrl}/health`)
  const body = await health.json() as Record<string, any>
  assert.equal(health.status, 200)
  assert.equal(body.reasons.includes('WORKER_TOKEN_MISSING'), false)
  assert.equal((await fetch(`${baseUrl}/api/v1/capabilities`)).status, 200)
  const accepted = await fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': 'no-auth-task',
    },
    body: JSON.stringify({ prompt: 'allowed without bearer token' }),
  })
  assert.equal(accepted.status, 202)
  await waitFor(() => manager.get('no-auth-task')?.status === 'terminal')
})

test('external mode exposes health state but fails closed for every business API', async t => {
  const stateDir = await tempDirectory('codex-app-external-gate-')
  const config = testConfig(stateDir, { externalEnabled: true, workerToken: '' })
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize({ resume: false })
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const healthResponse = await fetch(`${baseUrl}/health`)
  assert.equal(healthResponse.status, 200)
  const health = await healthResponse.json() as Record<string, any>
  assert.equal(health.status, 'degraded')
  assert.equal(health.ready, false)
  assert.equal(health.mode, 'external-enabled')
  assert.equal(health.external_enabled, true)
  assert.equal(health.external_ready, false)
  assert.equal(health.auth_configured, false)
  assert.ok(health.reasons.includes('EXTERNAL_AUTH_TOKEN_REQUIRED'))
  assert.ok(health.reasons.includes('EXTERNAL_EXECUTION_POLICY_PENDING'))

  for (const path of ['/api/v1/capabilities', '/api/v1/tasks/missing/status']) {
    const response = await fetch(`${baseUrl}${path}`)
    assert.equal(response.status, 503)
    assert.deepEqual(await response.json(), {
      error: 'EXTERNAL_WORKER_UNREADY',
      reasons: ['EXTERNAL_AUTH_TOKEN_REQUIRED', 'EXTERNAL_EXECUTION_POLICY_PENDING'],
    })
  }

  config.workerToken = 'external-secret-sentinel'
  const tokenHealth = await (await fetch(`${baseUrl}/health`)).json() as Record<string, any>
  assert.equal(tokenHealth.auth_configured, true)
  assert.deepEqual(tokenHealth.reasons.filter((reason: string) => reason.startsWith('EXTERNAL_')), [
    'EXTERNAL_EXECUTION_POLICY_PENDING',
  ])
  assert.equal(JSON.stringify(tokenHealth).includes(config.workerToken), false)
  const stillClosed = await fetch(`${baseUrl}/api/v1/capabilities`, {
    headers: { Authorization: `Bearer ${config.workerToken}` },
  })
  assert.equal(stillClosed.status, 503)
  assert.deepEqual(await stillClosed.json(), {
    error: 'EXTERNAL_WORKER_UNREADY',
    reasons: ['EXTERNAL_EXECUTION_POLICY_PENDING'],
  })
})

test('malformed JSON returns a stable 400 without echoing parser details or body', async t => {
  const stateDir = await tempDirectory('codex-app-invalid-json-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  const response = await fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json', 'Idempotency-Key': 'invalid-json' },
    body: '{"prompt":"SECRET_BODY_SENTINEL"',
  })
  const text = await response.text()
  assert.equal(response.status, 400)
  assert.equal(text, '{"error":"INVALID_JSON_BODY"}')
  assert.doesNotMatch(text, /SECRET_BODY_SENTINEL|SyntaxError|Unexpected end|position/i)
})

test('empty cwd allowlist is not ready and omitted cwd cannot bypass a different allowlist', async t => {
  const stateDir = await tempDirectory('codex-app-cwd-gate-')
  const noRoots = testConfig(stateDir, { allowedCwds: [] })
  const noRootsStore = new TaskStore({ stateDir, encryptionKey: noRoots.stateEncryptionKey! })
  const noRootsManager = new TaskManager(noRoots, noRootsStore, new FakeExecutor())
  await noRootsManager.initialize()
  const noRootsServer = createApp(noRoots, noRootsManager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => noRootsServer.once('listening', resolve))
  const noRootsUrl = `http://127.0.0.1:${(noRootsServer.address() as AddressInfo).port}`
  const health = await (await fetch(`${noRootsUrl}/health`)).json() as Record<string, any>
  assert.equal(health.ready, false)
  assert.ok(health.reasons.includes('ALLOWED_CWDS_MISSING'))
  assert.equal((await postTask(noRootsUrl, 'no-roots', { prompt: 'x' })).response.status, 503)
  await new Promise<void>(resolve => noRootsServer.close(() => resolve()))

  const otherState = `${stateDir}-other`
  const allowedOtherRoot = path.join(stateDir, 'not-current')
  await fs.mkdir(allowedOtherRoot)
  const differentRoot = testConfig(otherState, { allowedCwds: [allowedOtherRoot] })
  const differentStore = new TaskStore({ stateDir: otherState, encryptionKey: differentRoot.stateEncryptionKey! })
  const differentManager = new TaskManager(differentRoot, differentStore, new FakeExecutor())
  await differentManager.initialize()
  const differentServer = createApp(differentRoot, differentManager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => differentServer.once('listening', resolve))
  const differentUrl = `http://127.0.0.1:${(differentServer.address() as AddressInfo).port}`
  assert.equal((await postTask(differentUrl, 'omitted-cwd', { prompt: 'x' })).response.status, 403)
  await new Promise<void>(resolve => differentServer.close(() => resolve()))
  await fs.rm(otherState, { recursive: true, force: true })
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
})

test('filesystem-root allowlists accept every directory on the volume', async t => {
  const root = await tempDirectory('codex-app-http-private-cwd-')
  const stateDir = path.join(root, 'state')
  const codexHome = path.join(root, 'codex-home')
  const codexBizHomeRoot = path.join(root, 'biz-homes')
  const workspace = path.join(root, 'workspace')
  await Promise.all([
    fs.mkdir(stateDir),
    fs.mkdir(codexHome),
    fs.mkdir(codexBizHomeRoot),
    fs.mkdir(workspace),
  ])
  const config = testConfig(stateDir, {
    codexHome,
    codexBizHomeRoot,
    allowedCwds: [path.parse(root).root],
  })
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new FakeExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(root, { recursive: true, force: true })
  })

  assert.equal((await postTask(baseUrl, 'ordinary-workspace', { prompt: 'x', cwd: workspace })).response.status, 202)
  await waitFor(() => manager.get('ordinary-workspace')?.status === 'terminal')
  for (const [index, allowed] of [root, stateDir, codexHome, codexBizHomeRoot].entries()) {
    const response = await postTask(baseUrl, `root-cwd-${index}`, { prompt: 'x', cwd: allowed })
    assert.equal(response.response.status, 202)
    await waitFor(() => manager.get(`root-cwd-${index}`)?.status === 'terminal')
  }
})

test('abort endpoint interrupts an active task without creating another execution', async t => {
  const stateDir = await tempDirectory('codex-app-abort-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new BlockingExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  await postTask(baseUrl, 'task-abort', { prompt: 'wait' })
  await waitFor(() => manager.get('task-abort')?.status === 'running')
  const abort = await fetch(`${baseUrl}/api/v1/tasks/task-abort/abort`, {
    method: 'POST', headers: terminationHeaders('task-abort'),
  })
  assert.equal(abort.status, 200)
  const abortBody = await abort.json() as Record<string, unknown>
  assert.equal(abortBody.abort_status, 'aborted')
  await waitFor(() => manager.get('task-abort')?.status === 'terminal')
  assert.equal(manager.get('task-abort')?.outcome, 'aborted')
  const repeatedAbort = await fetch(`${baseUrl}/api/v1/tasks/task-abort/abort`, {
    method: 'POST', headers: terminationHeaders('task-abort'),
  })
  assert.equal(repeatedAbort.status, 200)
  const repeatedBody = await repeatedAbort.json() as Record<string, unknown>
  assert.equal(repeatedBody.abort_status, 'already_terminal')
  assert.equal(repeatedBody.outcome, 'aborted')
  assert.equal(executor.calls, 1)
})

test('abort reports an authoritative terminal outcome while a pending interruption remains cancel-requested', async t => {
  const completedState = await tempDirectory('codex-app-abort-complete-')
  const completedConfig = testConfig(completedState)
  const completedStore = new TaskStore({ stateDir: completedState, encryptionKey: completedConfig.stateEncryptionKey! })
  const completedManager = new TaskManager(completedConfig, completedStore, new CompletionWinsExecutor())
  await completedManager.initialize()
  const completedServer = createApp(completedConfig, completedManager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => completedServer.once('listening', resolve))
  const completedUrl = `http://127.0.0.1:${(completedServer.address() as AddressInfo).port}`
  await postTask(completedUrl, 'abort-completion-wins', { prompt: 'complete' })
  await waitFor(() => completedManager.get('abort-completion-wins')?.status === 'running')
  const completedAbort = await fetch(`${completedUrl}/api/v1/tasks/abort-completion-wins/abort`, {
    method: 'POST', headers: terminationHeaders('abort-completion-wins'),
  })
  assert.equal(completedAbort.status, 200)
  const completedBody = await completedAbort.json() as Record<string, unknown>
  assert.equal(completedBody.abort_status, 'already_terminal')
  assert.equal(completedBody.outcome, 'completed')
  assert.equal(completedManager.get('abort-completion-wins')?.outcome, 'completed')
  await new Promise<void>(resolve => completedServer.close(() => resolve()))
  await fs.rm(completedState, { recursive: true, force: true })

  const pendingState = await tempDirectory('codex-app-abort-pending-')
  const pendingConfig = testConfig(pendingState, { abortWaitTimeoutMs: 100 })
  const pendingStore = new TaskStore({ stateDir: pendingState, encryptionKey: pendingConfig.stateEncryptionKey! })
  const pendingExecutor = new PendingAbortExecutor()
  const pendingManager = new TaskManager(pendingConfig, pendingStore, pendingExecutor)
  await pendingManager.initialize()
  const pendingServer = createApp(pendingConfig, pendingManager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => pendingServer.once('listening', resolve))
  const pendingUrl = `http://127.0.0.1:${(pendingServer.address() as AddressInfo).port}`
  await postTask(pendingUrl, 'abort-pending', { prompt: 'wait' })
  await waitFor(() => pendingManager.get('abort-pending')?.status === 'running')
  const pendingAbort = await fetch(`${pendingUrl}/api/v1/tasks/abort-pending/abort`, {
    method: 'POST', headers: terminationHeaders('abort-pending'),
  })
  assert.equal(pendingAbort.status, 202)
  const pendingBody = await pendingAbort.json() as Record<string, unknown>
  assert.equal(pendingBody.lifecycle_status, 'CANCEL_REQUESTED')
  assert.equal(pendingBody.abort_status, 'cancel_requested')
  const pendingStatus = await fetch(`${pendingUrl}/api/v1/tasks/abort-pending/status`, {
    headers: authHeaders(),
  })
  assert.equal(pendingStatus.status, 200)
  const pendingStatusBody = await pendingStatus.json() as Record<string, any>
  assert.equal(pendingStatusBody.lifecycle_status, 'CANCEL_REQUESTED')
  assert.equal(pendingStatusBody.termination_operation.status, 'CANCEL_REQUESTED')
  assert.equal(pendingStatusBody.termination_operation.task_id, 'abort-pending')
  assert.equal(pendingStatusBody.termination_operation.worker_id, pendingConfig.navigatorWorkerId)
  assert.deepEqual(pendingStatusBody.available_actions, ['CONTINUE_WAIT', 'QUERY_DIAGNOSTICS'])
  assert.equal(pendingManager.get('abort-pending')?.status, 'running')
  pendingExecutor.finish()
  await waitFor(() => pendingManager.get('abort-pending')?.status === 'terminal')
  await new Promise<void>(resolve => pendingServer.close(() => resolve()))
  await fs.rm(pendingState, { recursive: true, force: true })
  t.after(() => undefined)
})

test('termination endpoints require a signed, task-bound, single-use operation and bind manual kill to its PID', async t => {
  const stateDir = await tempDirectory('codex-app-termination-operation-')
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('manual-kill-task', { prompt: 'wait for an authorized operation' })
  await seed.transition('manual-kill-task', 'starting')
  await seed.transition('manual-kill-task', 'committed', { thread_id: 'thread-manual-kill' })
  await seed.transition('manual-kill-task', 'running', {
    thread_id: 'thread-manual-kill',
    turn_id: 'turn-manual-kill',
    app_server_instance_id: 'instance-manual-kill',
  })
  const executor = new ManualKillExecutor()
  const manager = new TaskManager(config, seed, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const unsigned = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST', headers: authHeaders(),
  })
  assert.equal(unsigned.status, 400)
  assert.equal((await unsigned.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_MISSING')

  const invalidSignature = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, signatureToken: 'wrong-worker-token',
    }),
  })
  assert.equal(invalidSignature.status, 403)
  assert.equal((await invalidSignature.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_SIGNATURE_INVALID')

  const expired = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, expiresAt: new Date(Date.now() - 60_000).toISOString(),
    }),
  })
  assert.equal(expired.status, 400)
  assert.equal((await expired.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_EXPIRED')

  const missingWorkerBinding = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, workerId: '',
    }),
  })
  assert.equal(missingWorkerBinding.status, 400)
  assert.equal((await missingWorkerBinding.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_INVALID')

  const missingProcessIdentity = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, expectedProcessIdentity: null,
    }),
  })
  assert.equal(missingProcessIdentity.status, 400)
  assert.equal((await missingProcessIdentity.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_INVALID')

  const wrongWorkerBinding = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, workerId: 'another-navigator-worker',
    }),
  })
  assert.equal(wrongWorkerBinding.status, 409)
  assert.equal((await wrongWorkerBinding.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_MISMATCH')

  const wrongTask = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('some-other-task', { kind: 'MANUAL_PID_KILL', expectedPid: 4242 }),
  })
  assert.equal(wrongTask.status, 409)
  assert.equal((await wrongTask.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_MISMATCH')

  const wrongPid = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', { kind: 'MANUAL_PID_KILL', expectedPid: 4243 }),
  })
  assert.equal(wrongPid.status, 409)
  assert.equal((await wrongPid.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_MISMATCH')

  const invalidOrigin = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, origin: 'UPSTREAM_USER',
    }),
  })
  assert.equal(invalidOrigin.status, 400)
  assert.equal((await invalidOrigin.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_INVALID')

  const validHeaders = terminationHeaders('manual-kill-task', {
    kind: 'MANUAL_PID_KILL', expectedPid: 4242,
  })
  const killed = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST', headers: validHeaders,
  })
  assert.equal(killed.status, 200)
  const killedBody = await killed.json() as Record<string, any>
  assert.equal(killedBody.observed_exit, true)
  assert.equal(killedBody.lifecycle_status, 'ABORTED')
  assert.equal(killedBody.termination_operation.status, 'OBSERVED_EXIT')
  assert.equal(killedBody.termination_operation.task_id, 'manual-kill-task')
  assert.equal(killedBody.termination_operation.worker_id, config.navigatorWorkerId)
  assert.equal(killedBody.termination_operation.expected_pid, 4242)
  assert.equal(killedBody.termination_operation.expected_process_identity, 'app-server-instance:instance-manual-kill')
  assert.deepEqual(executor.calls, [{ taskId: 'manual-kill-task', pid: 4242 }])

  const replayed = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST', headers: validHeaders,
  })
  assert.equal(replayed.status, 409)
  assert.equal((await replayed.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_REPLAYED')

  const corruptOperationId = 'manual-kill-corrupt-ledger-operation'
  const corruptReceipt = new TerminationOperationReceiptLedger(
    path.join(stateDir, 'termination-operations', 'receipts'),
  ).receiptPathFor(config.navigatorWorkerId, corruptOperationId)
  await fs.mkdir(path.dirname(corruptReceipt), { recursive: true })
  await fs.writeFile(corruptReceipt, '{not-json', 'utf8')
  const unavailable = await fetch(`${baseUrl}/api/v1/tasks/manual-kill-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('manual-kill-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, operationId: corruptOperationId,
    }),
  })
  assert.equal(unavailable.status, 503)
  assert.equal((await unavailable.json() as Record<string, unknown>).error,
    'TERMINATION_OPERATION_REPLAY_LEDGER_UNAVAILABLE')
  assert.deepEqual(executor.calls, [{ taskId: 'manual-kill-task', pid: 4242 }])
})

test('a fresh manual PID capability cannot attribute a prior terminal task to itself', async t => {
  const stateDir = await tempDirectory('codex-app-terminal-manual-kill-')
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('already-terminal-manual-kill', { prompt: 'historical task' })
  await seed.transition('already-terminal-manual-kill', 'starting')
  await seed.transition('already-terminal-manual-kill', 'committed', { thread_id: 'thread-historical' })
  await seed.transition('already-terminal-manual-kill', 'running', {
    thread_id: 'thread-historical',
    turn_id: 'turn-historical',
    app_server_instance_id: 'instance-historical',
  })
  const historicalOperation = {
    operation_id: 'historical-manual-pid-operation',
    task_id: 'already-terminal-manual-kill',
    worker_id: config.navigatorWorkerId,
    kind: 'MANUAL_PID_KILL' as const,
    origin: 'ADMIN_MANUAL' as const,
    actor_id: 'historical-operator',
    actor_type: 'MANUAL_OPERATOR',
    authorization_decision_id: 'historical-decision',
    reason_code: 'MANUAL_PID_KILL',
    correlation_id: 'historical-correlation',
    issued_at: new Date(0).toISOString(),
    expires_at: new Date(Date.now() + 60_000).toISOString(),
    requested_at: new Date(0).toISOString(),
    status: 'OBSERVED_EXIT' as const,
    expected_pid: 4242,
    expected_process_identity: 'app-server-instance:instance-historical',
    result_code: 'TASK_ABORTED',
    observed_exit_at: new Date(0).toISOString(),
  }
  await seed.transition('already-terminal-manual-kill', 'terminal', {
    outcome: 'aborted',
    error_code: 'TASK_ABORTED',
    termination_operation: historicalOperation,
  })
  const executor = new ManualKillExecutor()
  const manager = new TaskManager(config, seed, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const response = await fetch(`${baseUrl}/api/v1/tasks/already-terminal-manual-kill/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('already-terminal-manual-kill', {
      kind: 'MANUAL_PID_KILL',
      expectedPid: 4242,
      operationId: 'fresh-manual-pid-operation',
      expectedProcessIdentity: 'app-server-instance:wrong-or-recycled-runtime',
    }),
  })

  assert.equal(response.status, 202)
  const body = await response.json() as Record<string, any>
  assert.equal(body.status, 'terminal')
  assert.equal(body.lifecycle_status, 'CANCEL_REQUESTED')
  assert.equal(body.observed_exit, false)
  assert.equal(body.termination_operation.operation_id, historicalOperation.operation_id)
  assert.equal(body.termination_operation.task_id, historicalOperation.task_id)
  assert.equal(body.termination_operation.status, 'OBSERVED_EXIT')
  assert.deepEqual(executor.calls, [])
  const record = manager.get('already-terminal-manual-kill')
  assert.equal(record?.status, 'terminal')
  assert.equal(record?.termination_operation?.operation_id, historicalOperation.operation_id)
})

test('a pending termination operation rejects different signed operations before dispatch', async t => {
  const stateDir = await tempDirectory('codex-app-termination-operation-conflict-')
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('operation-conflict-task', { prompt: 'wait for one termination decision' })
  await seed.transition('operation-conflict-task', 'starting')
  await seed.transition('operation-conflict-task', 'committed', { thread_id: 'thread-operation-conflict' })
  await seed.transition('operation-conflict-task', 'running', {
    thread_id: 'thread-operation-conflict',
    turn_id: 'turn-operation-conflict',
    app_server_instance_id: 'instance-manual-kill',
  })
  const executor = new PendingTerminationOperationExecutor()
  const manager = new TaskManager(config, seed, executor)
  await manager.initialize({ resume: false })
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const first = await fetch(`${baseUrl}/api/v1/tasks/operation-conflict-task/abort`, {
    method: 'POST',
    headers: terminationHeaders('operation-conflict-task', { operationId: 'remote-cancel-operation-a' }),
  })
  assert.equal(first.status, 202)
  assert.equal((await first.json() as Record<string, any>).termination_operation.operation_id, 'remote-cancel-operation-a')

  const manual = await fetch(`${baseUrl}/api/v1/tasks/operation-conflict-task/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders('operation-conflict-task', {
      kind: 'MANUAL_PID_KILL', expectedPid: 4242, operationId: 'manual-pid-operation-b',
    }),
  })
  assert.equal(manual.status, 409)
  assert.equal((await manual.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_PENDING')

  const secondRemote = await fetch(`${baseUrl}/api/v1/tasks/operation-conflict-task/abort`, {
    method: 'POST',
    headers: terminationHeaders('operation-conflict-task', { operationId: 'remote-cancel-operation-c' }),
  })
  assert.equal(secondRemote.status, 409)
  assert.equal((await secondRemote.json() as Record<string, unknown>).error, 'TERMINATION_OPERATION_PENDING')
  assert.deepEqual(executor.manualCalls, [])
  const record = manager.get('operation-conflict-task')
  assert.equal(record?.status, 'running')
  assert.equal(record?.termination_operation?.operation_id, 'remote-cancel-operation-a')
})

test('active manual PID termination serializes its durable outcome with the execution unwind', async () => {
  for (const observedExit of [true, false]) {
    const suffix = observedExit ? 'verified' : 'unconfirmed'
    const stateDir = await tempDirectory(`codex-app-active-manual-${suffix}-`)
    const config = testConfig(stateDir)
    const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
    const executor = new ActiveManualPidTerminationExecutor(observedExit)
    const manager = new TaskManager(config, store, executor)
    await manager.initialize()
    const server = createApp(config, manager).listen(0, '127.0.0.1')
    await new Promise<void>(resolve => server.once('listening', resolve))
    const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
    try {
      const taskId = `active-manual-${suffix}`
      assert.equal((await postTask(baseUrl, taskId, { prompt: 'wait for authorized manual termination' })).response.status, 202)
      await waitFor(() => manager.get(taskId)?.status === 'running')

      const response = await fetch(`${baseUrl}/api/v1/tasks/${taskId}/processes/4242/kill`, {
        method: 'POST', headers: terminationHeaders(taskId, {
          kind: 'MANUAL_PID_KILL', expectedPid: 4242,
        }),
      })
      assert.equal(response.status, observedExit ? 200 : 202)
      const body = await response.json() as Record<string, any>
      assert.equal(body.observed_exit, observedExit)
      await waitFor(() => manager.activeCount() === 0)

      const record = manager.get(taskId)
      assert.equal(executor.manualPidKillCalls, 1)
      if (observedExit) {
        assert.equal(record?.status, 'terminal')
        assert.equal(record?.outcome, 'aborted')
        assert.deepEqual(record?.attention || [], [])
        const errors = manager.getBroadcast(taskId).getEventsAfter(0)
          .filter(event => event.type === 'error')
        assert.equal(errors.filter(event => event.subtype === 'TASK_ABORTED').length, 1)
        assert.equal(errors.some(event => event.subtype === 'PROCESS_UNVERIFIED'), false)
      } else {
        assert.notEqual(record?.status, 'terminal')
        assert.equal(record?.termination_operation?.result_code, 'MANUAL_PID_KILL_UNCONFIRMED')
        assert.deepEqual(record?.attention?.map(item => `${item.status}:${item.reason_code}`), [
          'TERMINATION_UNCONFIRMED:MANUAL_PID_KILL_UNCONFIRMED',
        ])
      }
    } finally {
      await new Promise<void>(resolve => server.close(() => resolve()))
      await fs.rm(stateDir, { recursive: true, force: true })
    }
  }
})

test('a signed manual PID request yields to an observed provider terminal without false cancellation provenance', async t => {
  const stateDir = await tempDirectory('codex-app-provider-terminal-manual-race-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new ProviderTerminalManualRaceExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const taskId = 'provider-terminal-manual-race'
  assert.equal((await postTask(baseUrl, taskId, { prompt: 'complete from the provider' })).response.status, 202)
  await waitFor(() => manager.get(taskId)?.status === 'running')

  const response = await fetch(`${baseUrl}/api/v1/tasks/${taskId}/processes/4242/kill`, {
    method: 'POST',
    headers: terminationHeaders(taskId, { kind: 'MANUAL_PID_KILL', expectedPid: 4242 }),
  })
  assert.equal(response.status, 202)
  const body = await response.json() as Record<string, any>
  assert.equal(body.observed_exit, false)
  assert.equal(body.provider_terminal_observed, true)
  assert.equal(body.lifecycle_status, 'RUNNING')
  assert.equal(body.termination_operation, undefined)

  await waitFor(() => manager.get(taskId)?.status === 'terminal')
  const record = manager.get(taskId)
  assert.equal(executor.manualPidKillCalls, 1)
  assert.equal(record?.outcome, 'completed')
  assert.equal(record?.abort_requested_at, undefined)
  assert.equal(record?.termination_operation, undefined)
  assert.deepEqual(record?.attention || [], [])
  const events = manager.getBroadcast(taskId).getEventsAfter(0)
  assert.equal(events.filter(event => event.type === 'result').length, 1)
  assert.equal(events.some(event => event.type === 'error' && event.subtype === 'TASK_ABORTED'), false)
})

test('terminal cleanup removes payload/events but preserves a permanent idempotency tombstone', async t => {
  const stateDir = await tempDirectory('codex-app-tombstone-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })
  await postTask(baseUrl, 'task-tombstone', { prompt: 'sensitive prompt', api_key: 'sk-sensitive' })
  await waitFor(() => manager.get('task-tombstone')?.status === 'terminal')
  const removed = await fetch(`${baseUrl}/api/v1/tasks/task-tombstone`, {
    method: 'DELETE', headers: authHeaders(),
  })
  assert.equal(removed.status, 200)
  assert.equal((await removed.json() as Record<string, unknown>).tombstoned, true)
  assert.equal(manager.get('task-tombstone')?.request_payload, undefined)
  assert.ok(manager.get('task-tombstone')?.tombstoned_at)
  assert.equal((await fs.readdir(path.join(stateDir, 'events'))).length, 0)
  const taskJournals = await fs.readdir(path.join(stateDir, 'tasks'))
  const journal = await fs.readFile(path.join(stateDir, 'tasks', taskJournals[0]!), 'utf8')
  assert.doesNotMatch(journal, /request_payload|ciphertext|sensitive prompt|sk-sensitive/)

  const same = await postTask(baseUrl, 'task-tombstone', { prompt: 'sensitive prompt', api_key: 'sk-sensitive' })
  assert.equal(same.response.status, 202)
  assert.equal(same.body.status, 'terminal')
  assert.equal(executor.calls, 1)
  const conflict = await postTask(baseUrl, 'task-tombstone', { prompt: 'run again' })
  assert.equal(conflict.response.status, 409)
})

test('subscribe does not hang for a committed task awaiting P2 reconciliation', async t => {
  const stateDir = await tempDirectory('codex-app-recovery-subscribe-')
  const config = testConfig(stateDir)
  const seed = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  await seed.initialize()
  await seed.accept('task-unknown', { prompt: 'do not replay' })
  await seed.transition('task-unknown', 'starting')
  await seed.transition('task-unknown', 'committed', { thread_id: 'thread-unknown' })

  const recovered = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new FakeExecutor()
  const manager = new TaskManager(config, recovered, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const response = await fetch(`${baseUrl}/api/v1/tasks/task-unknown/subscribe`, {
    headers: authHeaders(),
    signal: AbortSignal.timeout(1_000),
  })
  const body = await response.text()
  assert.equal(response.status, 200)
  assert.match(body, /sync_checkpoint/)
  assert.equal(manager.get('task-unknown')?.recovery_required, true)
  assert.equal(executor.calls, 0)
})

async function postTask(baseUrl: string, key: string, body: Record<string, unknown>) {
  const response = await fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(body),
  })
  return { response, body: await response.json() as Record<string, any> }
}

function authHeaders(): Record<string, string> {
  return { Authorization: 'Bearer test-worker-token' }
}

function terminationHeaders(
  taskId: string,
  options: {
    kind?: 'REMOTE_CANCEL' | 'MANUAL_PID_KILL'
    origin?: 'UPSTREAM_USER' | 'UPSTREAM_SYSTEM' | 'ADMIN_MANUAL'
    expectedPid?: number
    operationId?: string
    expiresAt?: string
    signatureToken?: string
    targetWorkerId?: string
    workerId?: string
    /** null deliberately omits the claim for negative-contract coverage. */
    expectedProcessIdentity?: string | null
  } = {},
): Record<string, string> {
  const kind = options.kind || 'REMOTE_CANCEL'
  const issuedAt = new Date().toISOString()
  const operation: Record<string, unknown> = {
    schema_version: 1,
    operation_id: options.operationId || `operation-${crypto.randomUUID()}`,
    task_id: taskId,
    worker_id: options.workerId === undefined ? 'test-navigator-worker' : options.workerId,
    kind,
    origin: options.origin || (kind === 'MANUAL_PID_KILL' ? 'ADMIN_MANUAL' : 'UPSTREAM_USER'),
    actor_id: 'test-user',
    actor_type: 'USER',
    authorization_decision_id: 'decision-test',
    reason_code: 'USER_REQUESTED',
    correlation_id: `correlation-${crypto.randomUUID()}`,
    issued_at: issuedAt,
    expires_at: options.expiresAt || new Date(Date.now() + 60_000).toISOString(),
  }
  if (options.expectedPid !== undefined) operation.expected_pid = options.expectedPid
  const expectedProcessIdentity = options.expectedProcessIdentity === undefined
    ? (kind === 'MANUAL_PID_KILL' ? 'app-server-instance:instance-manual-kill' : undefined)
    : options.expectedProcessIdentity
  if (expectedProcessIdentity !== undefined && expectedProcessIdentity !== null) {
    operation.expected_process_identity = expectedProcessIdentity
  }
  if (options.targetWorkerId !== undefined) operation.target_worker_id = options.targetWorkerId
  const encoded = Buffer.from(JSON.stringify(operation)).toString('base64url')
  const signature = crypto.createHmac('sha256', options.signatureToken || 'test-worker-token')
    .update(encoded)
    .digest('base64url')
  return {
    ...authHeaders(),
    'X-Navigator-Termination-Operation': encoded,
    'X-Navigator-Termination-Signature': signature,
  }
}

async function requestWithDeclaredLength(
  baseUrl: string,
  headers: Record<string, string>,
  contentLength: number,
): Promise<{ status: number; actualInstanceId?: string; serverClosed: boolean }> {
  const url = new URL('/api/v1/tasks', baseUrl)
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: url.hostname, port: Number(url.port) })
    let response = ''
    let socketError: Error | undefined
    const timeout = setTimeout(() => {
      socket.destroy()
      reject(new Error('server did not close the mismatched oversized request'))
    }, 2_000)
    socket.setEncoding('utf8')
    socket.on('data', chunk => { response += chunk })
    socket.on('error', error => { socketError = error })
    socket.once('connect', () => {
      const requestHeaders = {
        Host: `${url.hostname}:${url.port}`,
        Connection: 'keep-alive',
        ...headers,
        'Content-Length': String(contentLength),
      }
      const serialized = Object.entries(requestHeaders)
        .map(([name, value]) => `${name}: ${value}`)
        .join('\r\n')
      socket.write(`POST ${url.pathname} HTTP/1.1\r\n${serialized}\r\n\r\n{`)
    })
    socket.once('close', () => {
      clearTimeout(timeout)
      const lines = response.slice(0, response.indexOf('\r\n\r\n')).split('\r\n')
      const status = Number(lines[0]?.split(' ')[1] || 0)
      const actualInstanceId = lines
        .map(line => line.split(/:\s*/, 2))
        .find(([name]) => name?.toLowerCase() === ACTUAL_INSTANCE_HEADER.toLowerCase())?.[1]
      if (!status) {
        reject(socketError || new Error('server closed without an HTTP response'))
        return
      }
      resolve({ status, actualInstanceId, serverClosed: true })
    })
  })
}

class BlockingExecutor implements TaskExecutor {
  calls = 0
  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    this.calls++
    await options.callbacks.onThreadResolved('thread-abort')
    await options.callbacks.onExecutionCommitted('thread-abort')
    await options.callbacks.onTurnStarted('thread-abort', 'turn-abort')
    await new Promise<void>(resolve => options.signal.addEventListener('abort', () => resolve(), { once: true }))
    return {
      threadId: 'thread-abort',
      turnId: 'turn-abort',
      status: 'interrupted',
      assistantText: '',
      inputTokens: 0,
      outputTokens: 0,
      model: 'gpt-5.6-sol',
      durationMs: 1,
    }
  }
}

class CompletionWinsExecutor implements TaskExecutor {
  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('instance-complete', 'lane-complete')
    await options.callbacks.onThreadResolved('thread-complete')
    await options.callbacks.onExecutionCommitted('thread-complete')
    await options.callbacks.onTurnStarted('thread-complete', 'turn-complete')
    await new Promise<void>(resolve => options.signal.addEventListener('abort', () => resolve(), { once: true }))
    return {
      threadId: 'thread-complete', turnId: 'turn-complete', status: 'completed',
      assistantText: 'completed first', inputTokens: 0, outputTokens: 0, model: 'gpt-5.6-sol', durationMs: 1,
    }
  }
}

class PendingAbortExecutor implements TaskExecutor {
  private resolve!: () => void
  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('instance-pending', 'lane-pending')
    await options.callbacks.onThreadResolved('thread-pending')
    await options.callbacks.onExecutionCommitted('thread-pending')
    await options.callbacks.onTurnStarted('thread-pending', 'turn-pending')
    await new Promise<void>(resolve => { this.resolve = resolve })
    return {
      threadId: 'thread-pending', turnId: 'turn-pending', status: 'interrupted',
      assistantText: '', inputTokens: 0, outputTokens: 0, model: 'gpt-5.6-sol', durationMs: 1,
    }
  }
  finish(): void { this.resolve() }
}

class ManagedProcessSnapshotExecutor implements TaskExecutor {
  snapshotCalls = 0
  executeCalls = 0
  manualPidKillCalls = 0

  constructor(private readonly snapshots: Array<{
    taskId: string
    pid: number
    instanceId: string
  }>) {}

  async execute(): Promise<ExecutionResult> {
    this.executeCalls++
    throw new Error('process snapshot must not execute a task')
  }

  listManagedTaskProcesses() {
    this.snapshotCalls++
    return this.snapshots
  }

  async manualPidKill(): Promise<{ observed_exit: boolean }> {
    this.manualPidKillCalls++
    return { observed_exit: false }
  }
}

class ManualKillExecutor implements TaskExecutor {
  readonly calls: Array<{ taskId: string; pid: number }> = []

  async execute(): Promise<ExecutionResult> {
    throw new Error('seeded manual-kill task must not be executed')
  }

  async manualPidKill(taskId: string, pid: number): Promise<{ observed_exit: boolean }> {
    this.calls.push({ taskId, pid })
    return { observed_exit: true }
  }
}

class PendingTerminationOperationExecutor implements TaskExecutor {
  readonly manualCalls: Array<{ taskId: string; pid: number }> = []

  async execute(): Promise<ExecutionResult> {
    throw new Error('seeded operation-conflict task must not be executed')
  }

  async requestExplicitAbort(): Promise<'requested'> {
    return 'requested'
  }

  async reconcile(): Promise<{ status: 'unknown'; threadId: string }> {
    return { status: 'unknown', threadId: 'thread-operation-conflict' }
  }

  async manualPidKill(taskId: string, pid: number): Promise<{ observed_exit: boolean }> {
    this.manualCalls.push({ taskId, pid })
    return { observed_exit: true }
  }
}

class ActiveManualPidTerminationExecutor implements TaskExecutor {
  private rejectExecution?: (error: Error) => void
  manualPidKillCalls = 0

  constructor(private readonly observedExit: boolean) {}

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('instance-manual-kill', 'lane-manual-kill')
    await options.callbacks.onThreadResolved('thread-active-manual-kill')
    await options.callbacks.onExecutionCommitted('thread-active-manual-kill')
    await options.callbacks.onTurnStarted('thread-active-manual-kill', 'turn-active-manual-kill')
    return new Promise<ExecutionResult>((_resolve, reject) => {
      this.rejectExecution = reject
    })
  }

  async manualPidKill(): Promise<{ observed_exit: boolean }> {
    this.manualPidKillCalls++
    const code = this.observedExit
      ? 'APP_SERVER_AUTHORIZED_PROCESS_EXIT'
      : 'APP_SERVER_MANUAL_TERMINATION_UNCONFIRMED'
    this.rejectExecution?.(new AppServerRuntimeError('authorized manual PID test outcome', {
      executionCommitted: true,
      turnMayHaveStarted: !this.observedExit,
      threadId: 'thread-active-manual-kill',
      turnId: 'turn-active-manual-kill',
      reason: this.observedExit ? 'aborted' : 'runtime',
      code,
    }))
    // Give execute() a chance to enter its catch path while the signed manual
    // operation still owns the task-operation lock.
    await Promise.resolve()
    return { observed_exit: this.observedExit }
  }
}

class ProviderTerminalManualRaceExecutor implements TaskExecutor {
  manualPidKillCalls = 0
  private completeProviderTurn?: () => void

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('instance-manual-kill', 'lane-manual-kill')
    await options.callbacks.onThreadResolved('thread-provider-terminal-race')
    await options.callbacks.onExecutionCommitted('thread-provider-terminal-race')
    await options.callbacks.onTurnStarted('thread-provider-terminal-race', 'turn-provider-terminal-race')
    await new Promise<void>(resolve => { this.completeProviderTurn = resolve })
    return {
      threadId: 'thread-provider-terminal-race',
      turnId: 'turn-provider-terminal-race',
      status: 'completed',
      assistantText: 'provider completion',
      model: 'gpt-5.6-sol',
      durationMs: 1,
    }
  }

  async manualPidKill(): Promise<{ observed_exit: false; provider_terminal_observed: true }> {
    this.manualPidKillCalls++
    this.completeProviderTurn?.()
    // Let execute() reach TaskManager's per-task terminal gate while this
    // signed PID request still owns that gate.
    await Promise.resolve()
    return { observed_exit: false, provider_terminal_observed: true }
  }
}

class ContextMaintenanceExecutor extends FakeExecutor {
  compactCalls = 0

  async compactContext(
    options: Parameters<NonNullable<TaskExecutor['compactContext']>>[0],
  ): Promise<Awaited<ReturnType<NonNullable<TaskExecutor['compactContext']>>>> {
    this.compactCalls++
    await options.onContextUsage({
      schema_version: 1,
      thread_id: options.record.thread_id!,
      turn_id: 'compact-turn-test',
      observed_at: '2026-07-17T00:00:00.000Z',
      last_total_tokens: 12_000,
      model_context_window: 270_000,
      remaining_tokens: 258_000,
      status: 'known',
    })
    return {
      threadId: options.record.thread_id!,
      turnId: 'compact-turn-test',
      status: 'completed',
    }
  }
}

class SlowTaskStore extends TaskStore {
  override async accept(taskId: string, request: TaskRequest) {
    await new Promise(resolve => setTimeout(resolve, 25))
    return super.accept(taskId, request)
  }
}
