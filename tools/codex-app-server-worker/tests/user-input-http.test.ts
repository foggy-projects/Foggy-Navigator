import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { type AddressInfo } from 'node:net'
import test from 'node:test'
import { createApp } from '../src/app.js'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

const SECRET_ANSWER = 'PRIVATE_USER_INPUT_ANSWER_SENTINEL'

test('HTTP request_user_input is durable, sanitized, once-only and releases the thread after completion', async t => {
  const stateDir = await tempDirectory('codex-app-user-input-http-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new InteractiveExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const first = await postTask(baseUrl, 'interactive-task', {
    prompt: 'ask one question', session_id: 'thread-interactive',
  })
  assert.equal(first.status, 202)
  await waitFor(() => Boolean(manager.get('interactive-task')?.pending_interaction))
  assert.equal(manager.get('interactive-task')?.status, 'running', 'persisted N-1-compatible phase stays running')

  const status = await fetchJson(`${baseUrl}/api/v1/tasks/interactive-task/status`)
  assert.equal(status.response.status, 200)
  assert.equal(status.body.status, 'awaiting_input')
  assert.equal(status.body.pending_interaction.request_id, 'request-interactive-task')
  assert.equal(status.body.pending_interaction.questions[1].is_secret, true)

  const retry = await postTask(baseUrl, 'interactive-task', {
    prompt: 'ask one question', session_id: 'thread-interactive',
  })
  assert.equal(retry.status, 202)
  assert.equal((await retry.json()).status, 'awaiting_input')
  const activeThread = await postTask(baseUrl, 'must-not-queue', {
    prompt: 'do not queue behind input', session_id: 'thread-interactive',
  })
  assert.equal(activeThread.status, 409)
  assert.deepEqual(await activeThread.json(), { error: 'APP_SERVER_THREAD_ACTIVE' })

  const wrongRequest = await respond(baseUrl, 'interactive-task', {
    request_id: 'wrong-request', answers: { choice: 'Safe' },
  })
  assert.equal(wrongRequest.response.status, 409)
  assert.equal(wrongRequest.body.error, 'USER_INPUT_REQUEST_MISMATCH')
  const invalidAnswer = await respond(baseUrl, 'interactive-task', {
    request_id: 'request-interactive-task', answers: { choice: 'Unknown option' },
  })
  assert.equal(invalidAnswer.response.status, 400)
  assert.equal(invalidAnswer.body.error, 'INVALID_USER_INPUT_RESPONSE')

  const answered = await respond(baseUrl, 'interactive-task', {
    request_id: 'request-interactive-task', answers: { choice: '1', secret: SECRET_ANSWER },
  })
  assert.equal(answered.response.status, 200)
  assert.deepEqual(answered.body, {
    task_id: 'interactive-task', status: 'running', request_id: 'request-interactive-task',
  })
  assert.deepEqual(executor.responses.get('interactive-task'), {
    answers: {
      choice: { answers: ['Safe'] },
      secret: { answers: [SECRET_ANSWER] },
    },
  })
  const repeated = await respond(baseUrl, 'interactive-task', {
    request_id: 'request-interactive-task', answers: { choice: 'Safe', secret: 'different' },
  })
  assert.equal(repeated.response.status, 409)
  assert.equal(repeated.body.error, 'USER_INPUT_ALREADY_RESPONDED')
  await waitFor(() => manager.get('interactive-task')?.status === 'terminal')

  const events = manager.getBroadcast('interactive-task').getEventsAfter(0)
  assert.equal(events.some(event => event.type === 'user_input_request'), true)
  assert.equal(events.some(event => event.type === 'user_input_resolved'
    && (event.data as Record<string, unknown>).reason === 'answered'), true)
  assert.doesNotMatch(JSON.stringify(events), new RegExp(SECRET_ANSWER))
  assert.doesNotMatch(await readAllJournals(stateDir), new RegExp(SECRET_ANSWER))

  const next = await postTask(baseUrl, 'next-turn', {
    prompt: 'continue after answer', session_id: 'thread-interactive',
  })
  assert.equal(next.status, 202, 'terminal completion releases the native-thread reservation')
  await waitFor(() => Boolean(manager.get('next-turn')?.pending_interaction))
  const abortStarted = Date.now()
  const aborted = await fetchJson(`${baseUrl}/api/v1/tasks/next-turn/abort`, { method: 'POST' })
  assert.equal(aborted.response.status, 200)
  assert.equal(aborted.body.abort_status, 'aborted')
  assert.ok(Date.now() - abortStarted < config.abortWaitTimeoutMs)
  await waitFor(() => manager.get('next-turn')?.status === 'terminal')
  const abortEvents = manager.getBroadcast('next-turn').getEventsAfter(0)
  assert.equal(abortEvents.some(event => event.type === 'user_input_resolved'
    && (event.data as Record<string, unknown>).reason === 'cleared'), true)
  const responseAfterAbort = await respond(baseUrl, 'next-turn', {
    request_id: 'request-next-turn', answers: { choice: 'Safe', secret: SECRET_ANSWER },
  })
  assert.equal(responseAfterAbort.response.status, 409)
  assert.equal(responseAfterAbort.body.error, 'USER_INPUT_NOT_PENDING')
})

test('distinct concurrent accepts reserve the same native thread atomically', async t => {
  const stateDir = await tempDirectory('codex-app-thread-reservation-race-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new InteractiveExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  const responses = await Promise.all([
    postTask(baseUrl, 'thread-race-a', { prompt: 'a', session_id: 'thread-race' }),
    postTask(baseUrl, 'thread-race-b', { prompt: 'b', session_id: 'thread-race' }),
  ])
  assert.deepEqual(responses.map(response => response.status).sort(), [202, 409])
  const rejected = responses.find(response => response.status === 409)!
  assert.deepEqual(await rejected.json(), { error: 'APP_SERVER_THREAD_ACTIVE' })
  const acceptedTaskId = responses[0]!.status === 202 ? 'thread-race-a' : 'thread-race-b'
  await waitFor(() => Boolean(manager.get(acceptedTaskId)?.pending_interaction))
  const aborted = await fetchJson(`${baseUrl}/api/v1/tasks/${acceptedTaskId}/abort`, { method: 'POST' })
  assert.equal(aborted.response.status, 200)
})

test('respond fails closed when the live app-server affinity no longer matches durable state', async t => {
  const stateDir = await tempDirectory('codex-app-user-input-affinity-')
  const config = testConfig(stateDir)
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, new InteractiveExecutor())
  await manager.initialize()
  const server = createApp(config, manager).listen(0, '127.0.0.1')
  await new Promise<void>(resolve => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()))
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  await postTask(baseUrl, 'affinity-input', { prompt: 'ask', session_id: 'thread-affinity' })
  await waitFor(() => Boolean(manager.get('affinity-input')?.pending_interaction))
  await store.patch('affinity-input', { app_server_instance_id: 'replaced-runtime' })
  const response = await respond(baseUrl, 'affinity-input', {
    request_id: 'request-affinity-input',
    answers: { choice: 'Safe', secret: SECRET_ANSWER },
  })
  assert.equal(response.response.status, 409)
  assert.equal(response.body.error, 'USER_INPUT_RUNTIME_AFFINITY_LOST')
  assert.equal(manager.get('affinity-input')?.status, 'terminal')
  assert.equal(manager.get('affinity-input')?.error_code, 'USER_INPUT_CHANNEL_LOST')
  assert.equal(manager.get('affinity-input')?.pending_interaction, undefined)
  assert.doesNotMatch(await readAllJournals(stateDir), new RegExp(SECRET_ANSWER))
})

test('bounded auto resolution sends an empty in-memory response and closes the pending projection', async t => {
  const originalSetTimeout = globalThis.setTimeout
  globalThis.setTimeout = ((handler: TimerHandler, timeout?: number, ...args: any[]) => (
    originalSetTimeout(handler, timeout === 60_000 ? 1 : timeout, ...args)
  )) as typeof globalThis.setTimeout
  t.after(() => { globalThis.setTimeout = originalSetTimeout })
  const stateDir = await tempDirectory('codex-app-user-input-auto-')
  const config = testConfig(stateDir)
  t.after(() => fs.rm(stateDir, { recursive: true, force: true }))
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const executor = new AutoInteractiveExecutor()
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()

  await manager.accept('auto-input', { prompt: 'non-blocking question', session_id: 'thread-auto' })
  await waitFor(() => manager.get('auto-input')?.status === 'terminal')
  assert.deepEqual(executor.response, { answers: {} })
  assert.equal(manager.get('auto-input')?.pending_interaction, undefined)
  const events = manager.getBroadcast('auto-input').getEventsAfter(0)
  assert.equal(events.some(event => event.type === 'user_input_resolved'
    && (event.data as Record<string, unknown>).reason === 'auto_resolved'), true)
  await assert.rejects(manager.respondToUserInput('auto-input', {
    request_id: 'request-auto', answers: { mode: 'Safe' },
  }), { code: 'USER_INPUT_NOT_PENDING' })
})

class InteractiveExecutor implements TaskExecutor {
  readonly responses = new Map<string, unknown>()

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    const threadId = options.request.session_id || `thread-${options.taskId}`
    const turnId = `turn-${options.taskId}`
    const runtimeInstanceId = `runtime-${options.taskId}`
    await options.callbacks.onInstanceResolved(runtimeInstanceId, 'lane-interactive')
    await options.callbacks.onThreadResolved(threadId)
    await options.callbacks.onExecutionCommitted(threadId)
    await options.callbacks.onTurnStarted(threadId, turnId)
    const response = await options.callbacks.onUserInputRequest({
      requestId: `request-${options.taskId}`,
      method: 'item/tool/requestUserInput',
      threadId,
      turnId,
      itemId: `item-${options.taskId}`,
      questions: [
        {
          id: 'choice', header: 'Mode', question: 'Choose mode',
          options: [
            { label: 'Safe', description: 'Safe mode' },
            { label: 'Fast', description: 'Fast mode' },
          ],
          is_other: false,
          is_secret: false,
        },
        {
          id: 'secret', header: 'Secret', question: 'Private answer',
          is_other: true,
          is_secret: true,
        },
      ],
    }, runtimeInstanceId)
    this.responses.set(options.taskId, response)
    return {
      threadId,
      turnId,
      status: 'completed',
      assistantText: 'continued',
      inputTokens: 1,
      outputTokens: 1,
      model: 'gpt-5.6-sol',
      durationMs: 1,
    }
  }
}

class AutoInteractiveExecutor implements TaskExecutor {
  response?: unknown

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    await options.callbacks.onInstanceResolved('runtime-auto', 'lane-auto')
    await options.callbacks.onThreadResolved('thread-auto')
    await options.callbacks.onExecutionCommitted('thread-auto')
    await options.callbacks.onTurnStarted('thread-auto', 'turn-auto')
    this.response = await options.callbacks.onUserInputRequest({
      requestId: 'request-auto',
      method: 'item/tool/requestUserInput',
      threadId: 'thread-auto',
      turnId: 'turn-auto',
      itemId: 'item-auto',
      autoResolutionMs: 60_000,
      questions: [{
        id: 'mode', header: 'Mode', question: 'Optional mode?',
        options: [{ label: 'Safe', description: 'Safe mode' }],
        is_other: false, is_secret: false,
      }],
    }, 'runtime-auto')
    return {
      threadId: 'thread-auto', turnId: 'turn-auto', status: 'completed',
      assistantText: 'continued automatically', inputTokens: 0, outputTokens: 0,
      model: 'gpt-5.6-sol', durationMs: 1,
    }
  }
}

async function postTask(baseUrl: string, taskId: string, body: Record<string, unknown>): Promise<Response> {
  return fetch(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json', 'Idempotency-Key': taskId },
    body: JSON.stringify(body),
  })
}

async function respond(baseUrl: string, taskId: string, body: Record<string, unknown>) {
  return fetchJson(`${baseUrl}/api/v1/tasks/${taskId}/respond`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function fetchJson(url: string, init: RequestInit = {}) {
  const response = await fetch(url, {
    ...init,
    headers: { ...authHeaders(), ...(init.headers || {}) },
  })
  return { response, body: await response.json() as Record<string, any> }
}

function authHeaders(): Record<string, string> {
  return { Authorization: 'Bearer test-worker-token' }
}

async function readAllJournals(stateDir: string): Promise<string> {
  const roots = ['tasks', 'events']
  const chunks: string[] = []
  for (const root of roots) {
    const directory = path.join(stateDir, root)
    for (const file of await fs.readdir(directory).catch(() => [])) {
      chunks.push(await fs.readFile(path.join(directory, file), 'utf8'))
    }
  }
  return chunks.join('\n')
}
