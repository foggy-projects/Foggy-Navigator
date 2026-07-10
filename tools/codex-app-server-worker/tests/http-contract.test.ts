import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import type { AddressInfo } from 'node:net'
import test from 'node:test'
import { createApp } from '../src/app.js'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'
import { TaskStore } from '../src/persistence/task-store.js'
import type { TaskRequest } from '../src/models.js'
import { TaskManager } from '../src/task-manager.js'
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
    ['/api/v1/tasks/task-1/abort', { method: 'POST', headers }],
    ['/api/v1/tasks/task-1', { method: 'DELETE', headers }],
  ]
  for (const [path, init] of requests) {
    const response = await fetch(`${baseUrl}${path}`, init)
    assert.equal(response.status, 409)
    assert.deepEqual(await response.json(), { error: 'RUNTIME_INSTANCE_MISMATCH' })
    assert.equal(response.headers.get(ACTUAL_INSTANCE_HEADER), config.instanceId)
  }
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
  assert.equal(manifest.app_server_protocol_version, '0.144.1')
  assert.equal(manifest.cli_version, '0.144.1')
  assert.equal(manifest.schema_digest, '6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f')
  assert.deepEqual(manifest.models, [
    'gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
    'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.3-codex-spark',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-sol'], [
    'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-terra'], [
    'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
  ])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.6-luna'], ['low', 'medium', 'high', 'xhigh', 'max'])
  assert.deepEqual(manifest.model_reasoning_matrix['gpt-5.4-mini'], ['low', 'medium', 'high', 'xhigh'])
  assert.equal(manifest.model_reasoning_matrix['gpt-5.4-mini'].includes('max'), false)
  assert.equal(manifest.model_reasoning_matrix['gpt-5.4-mini'].includes('ultra'), false)
  assert.equal(manifest.model_capabilities.dynamic_passthrough.route_selectable, false)
  assert.equal(manifest.reasoning_efforts, undefined)
  assert.deepEqual(manifest.features.approval_modes, ['never'])
  assert.equal(manifest.features.additional_directories, false)
  assert.equal(manifest.features.committed_reconciliation, true)
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

test('health remains public but control APIs fail closed when Worker token is missing', async t => {
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
  assert.equal(body.ready, false)
  assert.ok(body.reasons.includes('WORKER_TOKEN_MISSING'))
  assert.equal((await fetch(`${baseUrl}/api/v1/capabilities`)).status, 503)
  assert.equal((await fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: 'must not start' }),
  })).status, 503)
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
  const differentRoot = testConfig(otherState, { allowedCwds: [path.join(stateDir, 'not-current')] })
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
  const abort = await fetch(`${baseUrl}/api/v1/tasks/task-abort/abort`, { method: 'POST', headers: authHeaders() })
  assert.equal(abort.status, 200)
  const abortBody = await abort.json() as Record<string, unknown>
  assert.equal(abortBody.abort_status, 'aborted')
  await waitFor(() => manager.get('task-abort')?.status === 'terminal')
  assert.equal(manager.get('task-abort')?.outcome, 'aborted')
  const repeatedAbort = await fetch(`${baseUrl}/api/v1/tasks/task-abort/abort`, {
    method: 'POST', headers: authHeaders(),
  })
  assert.equal(repeatedAbort.status, 200)
  const repeatedBody = await repeatedAbort.json() as Record<string, unknown>
  assert.equal(repeatedBody.abort_status, 'aborted')
  assert.equal(repeatedBody.outcome, 'aborted')
  assert.equal(executor.calls, 1)
})

test('abort reports an authoritative terminal outcome while pending interruption remains non-2xx', async t => {
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
    method: 'POST', headers: authHeaders(),
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
    method: 'POST', headers: authHeaders(),
  })
  assert.equal(pendingAbort.status, 409)
  assert.equal((await pendingAbort.json() as Record<string, unknown>).error, 'ABORT_PENDING')
  assert.equal(pendingManager.get('abort-pending')?.status, 'running')
  pendingExecutor.finish()
  await waitFor(() => pendingManager.get('abort-pending')?.status === 'terminal')
  await new Promise<void>(resolve => pendingServer.close(() => resolve()))
  await fs.rm(pendingState, { recursive: true, force: true })
  t.after(() => undefined)
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

class SlowTaskStore extends TaskStore {
  override async accept(taskId: string, request: TaskRequest) {
    await new Promise(resolve => setTimeout(resolve, 25))
    return super.accept(taskId, request)
  }
}
