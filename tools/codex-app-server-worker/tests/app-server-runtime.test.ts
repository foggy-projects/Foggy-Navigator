import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { AppServerEventBridge } from '../src/app-server/event-bridge.js'
import { buildCodexConfig } from '../src/app-server/executor.js'
import { EventBroadcast } from '../src/persistence/event-store.js'
import { GeneratedImageStore } from '../src/generated-image-store.js'
import { testConfig } from './helpers.js'
import {
  AppServerRuntimeError,
  AppServerRuntimeInstance,
  buildBundledAppServerArgs,
  isAppServerProcessTreeSafetyError,
  runAppServerTurn,
  type AppServerProcess,
  type SpawnAppServerProcess,
} from '../src/app-server/runtime.js'
import { createStubbornProcessTreeFixture, isProcessAlive } from './stubborn-app-server-fixture.js'

type JsonMessage = Record<string, any>

class FakeProcess extends EventEmitter {
  readonly stdin = new PassThrough()
  readonly stdout = new PassThrough()
  readonly stderr = new PassThrough()
  readonly pid = 100
  killed = false
  private buffer = ''
  private threadId = 'thread-1'

  constructor(
    readonly received: JsonMessage[],
    readonly committed: () => boolean,
    private readonly emitTestNotification = false,
    private readonly childMetadataError?: string,
    private readonly interruptBehavior?: 'error' | 'timeout' | 'turn-start-hang' | 'stale-terminal' | 'same-batch-events'
      | 'running-after-start' | 'progress-before-complete' | 'noise-before-stall' | 'interactive-request' | 'unknown-server-request'
      | 'server-resolved-request' | 'unexpected-image-generation',
    private readonly apiKeyLoginBehavior?: 'error' | 'invalid',
  ) {
    super()
    this.stdin.on('data', chunk => {
      this.buffer += String(chunk)
      while (this.buffer.includes('\n')) {
        const index = this.buffer.indexOf('\n')
        const line = this.buffer.slice(0, index)
        this.buffer = this.buffer.slice(index + 1)
        if (!line) continue
        const message = JSON.parse(line) as JsonMessage
        this.received.push(message)
        this.handle(message)
      }
    })
  }

  kill(signal: NodeJS.Signals | number = 'SIGTERM'): boolean {
    this.killed = true
    queueMicrotask(() => this.emit('exit', 0, signal))
    return true
  }

  send(message: JsonMessage): void {
    this.stdout.write(`${JSON.stringify(message)}\n`)
  }

  sendRaw(line: string): void {
    this.stdout.write(`${line}\n`)
  }

  private handle(message: JsonMessage): void {
    if (message.method === 'initialize') this.send({ id: message.id, result: {} })
    if (message.method === 'account/login/start') {
      if (this.apiKeyLoginBehavior === 'error') {
        this.send({ id: message.id, error: { code: -32000, message: 'login rejected' } })
      } else {
        this.send({ id: message.id, result: { type: this.apiKeyLoginBehavior === 'invalid' ? 'chatgpt' : 'apiKey' } })
      }
    }
    if (message.method === 'thread/start' || message.method === 'thread/resume') {
      this.threadId = message.params?.threadId || 'thread-1'
      this.send({ id: message.id, result: { thread: { id: this.threadId } } })
    }
    if (message.method === 'thread/unsubscribe') {
      this.send({ id: message.id, result: { status: 'notLoaded' } })
    }
    if (message.method === 'thread/loaded/list') {
      this.send({ id: message.id, result: { data: [this.threadId] } })
    }
    if (message.method === 'turn/start') {
      assert.equal(this.committed(), true, 'turn/start must be written only after durable commit callback')
      if (this.interruptBehavior === 'turn-start-hang') return
      if (this.interruptBehavior === 'same-batch-events') {
        this.stdout.write([
          { id: message.id, result: { turn: { id: 'turn-1', status: 'inProgress' } } },
          {
            method: 'item/agentMessage/delta',
            params: {
              threadId: this.threadId, turnId: 'turn-1', itemId: 'message-1', delta: 'EARLY_',
            },
          },
          {
            method: 'item/agentMessage/delta',
            params: {
              threadId: this.threadId, turnId: 'turn-1', itemId: 'message-1', delta: 'COMPLETE',
            },
          },
          {
            method: 'item/completed',
            params: {
              threadId: this.threadId,
              turnId: 'turn-1',
              item: { id: 'message-1', type: 'agentMessage', text: 'EARLY_COMPLETE' },
            },
          },
          {
            method: 'turn/completed',
            params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } },
          },
        ].map(event => JSON.stringify(event)).join('\n') + '\n')
        return
      }
      if (this.interruptBehavior === 'stale-terminal') {
        this.send({
          method: 'turn/completed',
          params: { threadId: this.threadId, turn: { id: 'stale-turn', status: 'failed' } },
        })
      }
      this.send({ id: message.id, result: { turn: { id: 'turn-1', status: 'inProgress' } } })
      if (this.interruptBehavior === 'unexpected-image-generation') {
        this.send({
          method: 'item/completed',
          params: {
            threadId: this.threadId,
            turnId: 'turn-1',
            item: {
              id: 'image-1',
              type: 'imageGeneration',
              status: 'completed',
              result: 'BASE64_IMAGE_MUST_NOT_CROSS_WORKER_BOUNDARY',
            },
          },
        })
      }
      if (this.interruptBehavior === 'interactive-request' || this.interruptBehavior === 'server-resolved-request') {
        this.send({
          id: 'server-input-1',
          method: 'item/tool/requestUserInput',
          params: {
            threadId: this.threadId,
            turnId: 'turn-1',
            itemId: 'item-input-1',
            questions: [{
              id: 'mode', header: 'Mode', question: 'Choose mode',
              options: [{ label: 'Safe', description: 'Use safe mode' }],
            }],
          },
        })
        if (this.interruptBehavior === 'server-resolved-request') {
          queueMicrotask(() => {
            this.send({
              method: 'serverRequest/resolved',
              params: { threadId: this.threadId, requestId: 'server-input-1' },
            })
            this.send({ method: 'turn/completed', params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } } })
          })
        }
      }
      if (this.interruptBehavior === 'unknown-server-request') {
        this.send({
          id: 'approval-1',
          method: 'item/commandExecution/requestApproval',
          params: { threadId: this.threadId, turnId: 'turn-1', itemId: 'command-1' },
        })
      }
      if (this.emitTestNotification) {
        this.send({ method: 'item/agentMessage/delta', params: { threadId: this.threadId, delta: 'test' } })
      }
      if (this.childMetadataError) {
        this.send({
          method: 'item/completed',
          params: {
            threadId: this.threadId,
            turnId: 'turn-1',
            item: { type: 'subAgentActivity', kind: 'started', agentThreadId: 'child-thread' },
          },
        })
      }
      if (this.interruptBehavior === 'progress-before-complete') {
        for (const delayMs of [15, 35, 55]) {
          setTimeout(() => this.send({
            method: 'item/agentMessage/delta',
            params: { threadId: this.threadId, turnId: 'turn-1', itemId: 'message-1', delta: '.' },
          }), delayMs)
        }
        setTimeout(() => this.send({
          method: 'turn/completed',
          params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } },
        }), 65)
      }
      if (this.interruptBehavior === 'noise-before-stall') {
        for (const delayMs of [10, 20, 30, 40]) {
          setTimeout(() => this.send({
            method: 'thread/tokenUsage/updated',
            params: {
              threadId: this.threadId,
              turnId: 'turn-1',
              tokenUsage: { last: { inputTokens: delayMs, outputTokens: 0 } },
            },
          }), delayMs)
        }
      }
      if (!this.interruptBehavior || this.interruptBehavior === 'stale-terminal') {
        this.send({ method: 'turn/completed', params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } } })
      }
    }
    if (message.id === 'server-input-1' && (message.result || message.error)) {
      this.send({
        method: 'serverRequest/resolved',
        params: { threadId: this.threadId, requestId: 'server-input-1' },
      })
      this.send({ method: 'turn/completed', params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } } })
    }
    if (message.id === 'approval-1' && message.error) {
      this.send({ method: 'turn/completed', params: { threadId: this.threadId, turn: { id: 'turn-1', status: 'completed' } } })
    }
    if (message.method === 'thread/read' && this.childMetadataError) {
      this.send({ id: message.id, error: { code: -32000, message: this.childMetadataError } })
    }
    if (message.method === 'turn/interrupt' && this.interruptBehavior === 'error') {
      this.send({ id: message.id, error: { code: -32000, message: 'interrupt failed' } })
    }
    if (message.method === 'turn/interrupt'
        && this.interruptBehavior !== 'error'
        && this.interruptBehavior !== 'timeout') {
      this.send({ id: message.id, result: {} })
    }
  }
}

test('strict runtime persists committed before turn/start and has no SDK fallback path', async () => {
  const received: JsonMessage[] = []
  let committed = false
  const process = new FakeProcess(received, () => committed)
  const result = await runAppServerTurn({
    taskId: 'runtime-task',
    model: 'gpt-5.6-sol',
    reasoningEffort: 'ultra',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onExecutionCommitted: () => { committed = true },
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(result.threadId, 'thread-1')
  assert.equal(result.turn.status, 'completed')
  assert.deepEqual(received.map(message => message.method).filter(Boolean), [
    'initialize', 'initialized', 'thread/start', 'turn/start', 'thread/unsubscribe',
  ])
})

test('runtime resumes a persisted thread on the selected process and unsubscribes after the turn', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true)
  const cwd = '/workspace'
  const codexConfig = buildCodexConfig({ prompt: 'continue' }, undefined)
  const result = await runAppServerTurn({
    taskId: 'resume-task',
    model: 'gpt-5.6-sol',
    cwd,
    threadId: 'thread-existing',
    sandboxMode: 'read-only',
    codexConfig,
    input: 'continue',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })

  assert.equal(result.threadId, 'thread-existing')
  assert.deepEqual(received.find(message => message.method === 'thread/resume')?.params, {
    model: 'gpt-5.6-sol',
    cwd,
    sandbox: 'read-only',
    config: codexConfig,
    threadId: 'thread-existing',
  })
  assert.equal(codexConfig['features.image_generation'], false)
  assert.deepEqual(received.find(message => message.method === 'thread/unsubscribe')?.params, {
    threadId: 'thread-existing',
  })
})

test('API-key runtime enables ephemeral storage and completes login before becoming available', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true)
  let spawnOptions: Parameters<SpawnAppServerProcess>[0] | undefined
  const instance = await AppServerRuntimeInstance.start({
    env: { PATH: 'C:\\bin' },
    apiKey: 'dummy-runtime-key',
    spawnProcess: options => {
      spawnOptions = options
      return process as unknown as AppServerProcess
    },
    requestTimeoutMs: 1_000,
  })

  assert.equal(spawnOptions?.ephemeralApiKeyAuth, true)
  assert.equal(JSON.stringify(spawnOptions), '{"env":{"PATH":"C:\\\\bin"},"ephemeralApiKeyAuth":true}')
  assert.deepEqual(received.map(message => message.method).filter(Boolean), [
    'initialize', 'initialized', 'account/login/start',
  ])
  const login = received.find(message => message.method === 'account/login/start')
  assert.deepEqual(login?.params, { type: 'apiKey', apiKey: 'dummy-runtime-key' })
  await instance.close()
})

test('runtime rejects and closes an instance when ephemeral API-key login fails', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, undefined, 'error')

  await assert.rejects(AppServerRuntimeInstance.start({
    env: {},
    apiKey: 'dummy-rejected-key',
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  }), error => {
    assert.ok(error instanceof AppServerRuntimeError)
    assert.equal(error.message, 'Codex app-server ephemeral API-key login failed')
    assert.doesNotMatch(String(error.stack), /dummy-rejected-key/)
    const loginFailure = error.cause as Error
    assert.equal(loginFailure.message, 'Codex app-server ephemeral API-key login failed')
    const rpcFailure = loginFailure.cause as Error & { code?: number }
    assert.equal(rpcFailure.message, 'Codex app-server API-key login RPC failed')
    assert.equal(rpcFailure.code, -32000)
    return true
  })
  assert.equal(process.killed, true)
})

test('bundled launcher config contains only the ephemeral-store switch for API-key auth', () => {
  assert.deepEqual(buildBundledAppServerArgs(false).slice(1), ['app-server', '--stdio'])
  assert.deepEqual(buildBundledAppServerArgs(true).slice(1), [
    'app-server', '--stdio', '--config', 'cli_auth_credentials_store="ephemeral"',
  ])
})

test('persistent runtime initializes once and serves sequential exclusive turns', async () => {
  const received: JsonMessage[] = []
  let committed = true
  const process = new FakeProcess(received, () => committed)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  const options = {
    taskId: 'persistent-task',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only' as const,
    codexConfig: {},
    input: 'inspect',
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onExecutionCommitted: () => { committed = true },
  }
  await instance.runTurn(options)
  committed = false
  await instance.runTurn({ ...options, taskId: 'persistent-task-2' })
  assert.equal(received.filter(message => message.method === 'initialize').length, 1)
  assert.equal(received.filter(message => message.method === 'turn/start').length, 2)
  assert.equal(process.killed, false)
  instance.close()
  assert.equal(process.killed, true)
})

test('persistent runtime reports thread IDs loaded in its own app-server memory', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })

  assert.deepEqual(await instance.listLoadedThreads(), ['thread-1'])
  assert.equal(received.some(message => message.method === 'thread/loaded/list'), true)
  await instance.close()
})

test('turn progress watchdog interrupts and retires a live process that stops emitting events', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'running-after-start')

  await assert.rejects(runAppServerTurn({
    taskId: 'stalled-runtime',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
    turnStallTimeoutMs: 25,
    interruptTimeoutMs: 25,
  }), error => {
    assert.ok(error instanceof AppServerRuntimeError)
    assert.equal(error.code, 'APP_SERVER_TURN_STALLED')
    assert.equal(error.reason, 'stalled')
    return true
  })
  assert.equal(received.some(message => message.method === 'turn/interrupt'), true)
  assert.equal(process.killed, true)
})

test('disabled image generation fails closed before base64 reaches the notification consumer', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'unexpected-image-generation')
  const forwarded: JsonMessage[] = []

  await assert.rejects(runAppServerTurn({
    taskId: 'unexpected-image-generation',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: { 'features.image_generation': false },
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: notification => forwarded.push(notification),
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
    interruptTimeoutMs: 100,
  }), error => {
    assert.ok(error instanceof AppServerRuntimeError)
    assert.equal(error.code, 'APP_SERVER_UNEXPECTED_IMAGE_GENERATION')
    assert.equal(error.reason, 'protocol')
    assert.equal(error.executionCommitted, true)
    assert.equal(error.turnMayHaveStarted, true)
    return true
  })

  assert.equal(forwarded.some(notification => JSON.stringify(notification).includes('BASE64_IMAGE')), false)
  assert.equal(received.some(message => message.method === 'turn/interrupt'), true)
  assert.equal(process.killed, true)
})

test('turn progress notifications reset the watchdog until completion', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'progress-before-complete')
  const result = await runAppServerTurn({
    taskId: 'progress-runtime',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
    turnStallTimeoutMs: 25,
  })
  assert.equal(result.turn.status, 'completed')
  assert.equal(received.some(message => message.method === 'turn/interrupt'), false)
})

test('token usage noise does not keep a stalled turn alive', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'noise-before-stall')

  await assert.rejects(runAppServerTurn({
    taskId: 'noisy-stalled-runtime',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
    turnStallTimeoutMs: 25,
    interruptTimeoutMs: 25,
  }), error => {
    assert.ok(error instanceof AppServerRuntimeError)
    assert.equal(error.code, 'APP_SERVER_TURN_STALLED')
    return true
  })
  assert.equal(received.some(message => message.method === 'turn/interrupt'), true)
  assert.equal(process.killed, true)
})

test('runtime enables and answers only the pinned request_user_input server request', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'interactive-request')
  let resolved = 0
  const result = await runAppServerTurn({
    taskId: 'interactive-runtime',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: buildCodexConfig({ prompt: 'choose', codex_config: { tool_output_token_limit: 4_096 } }, undefined),
    input: 'choose',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onUserInputRequest: async request => {
      assert.equal(request.requestId, 'server-input-1')
      assert.equal(request.questions[0]?.id, 'mode')
      return { answers: { mode: { answers: ['Safe'] } } }
    },
    onUserInputResolved: resolution => {
      assert.equal(resolution.requestId, 'server-input-1')
      resolved++
    },
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(result.turn.status, 'completed')
  assert.equal(resolved, 1)
  const initialize = received.find(message => message.method === 'initialize')
  assert.equal(initialize?.params?.capabilities?.experimentalApi, true)
  const threadStart = received.find(message => message.method === 'thread/start')
  assert.equal(threadStart?.params?.config?.['features.default_mode_request_user_input'], true)
  assert.equal(threadStart?.params?.config?.['features.image_generation'], false)
  assert.equal(threadStart?.params?.config?.['notice.hide_rate_limit_model_nudge'], true)
  assert.equal(threadStart?.params?.config?.approval_policy, 'never')
  const response = received.find(message => message.id === 'server-input-1' && message.result)
  assert.deepEqual(response?.result, { answers: { mode: { answers: ['Safe'] } } })
})

test('command approvals and unknown server requests remain rejected while approval policy stays never', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'unknown-server-request')
  let userInputCalls = 0
  const result = await runAppServerTurn({
    taskId: 'unknown-server-request',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: { approval_policy: 'never', 'features.default_mode_request_user_input': true },
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onUserInputRequest: async () => {
      userInputCalls++
      return { answers: {} }
    },
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(result.turn.status, 'completed')
  assert.equal(userInputCalls, 0)
  const rejected = received.find(message => message.id === 'approval-1' && message.error)
  assert.equal(rejected?.error?.code, -32601)
  assert.equal(rejected?.error?.message, 'Unsupported server request')
})

test('serverRequest/resolved clears an in-flight request without sending a stale response', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'server-resolved-request')
  let observedResolution = 0
  const result = await runAppServerTurn({
    taskId: 'server-resolved',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: { approval_policy: 'never', 'features.default_mode_request_user_input': true },
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onUserInputRequest: () => new Promise(() => undefined),
    onUserInputResolved: () => { observedResolution++ },
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(result.turn.status, 'completed')
  assert.equal(observedResolution, 1)
  assert.equal(received.some(message => message.id === 'server-input-1' && (message.result || message.error)), false)
})

test('turn-started callback failure retires and closes a still-running instance before rejection', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'running-after-start')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  let fatalCount = 0
  instance.onFatal(() => { fatalCount++ })

  await assert.rejects(instance.runTurn({
    taskId: 'turn-started-persistence-failure',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: new AbortController().signal,
    onNotification: () => undefined,
    onTurnStarted: () => { throw new Error('turn journal failed') },
  }), error => {
    assert.ok(error instanceof AppServerRuntimeError)
    assert.equal(error.executionCommitted, true)
    assert.equal(error.turnMayHaveStarted, true)
    assert.equal(error.threadId, 'thread-1')
    assert.equal(error.turnId, 'turn-1')
    assert.match(error.message, /turn journal failed/)
    return true
  })

  assert.equal(instance.isHealthy(), false)
  assert.equal(instance.isActive(), false)
  assert.equal(process.killed, true, 'runTurn must await child close before rejecting')
  assert.equal(fatalCount, 1)
  assert.equal(received.filter(message => message.method === 'turn/start').length, 1)
  await assert.rejects(instance.runTurn({
    taskId: 'must-not-reuse',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: new AbortController().signal,
    onNotification: () => undefined,
  }), /unavailable/)
})

test('persistent runtime interrupts the exact thread and turn requested by recovery', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })

  await instance.interruptTurn('thread-exact', 'turn-exact')

  const interrupt = received.find(message => message.method === 'turn/interrupt')
  assert.deepEqual(interrupt?.params, { threadId: 'thread-exact', turnId: 'turn-exact' })
  assert.equal(instance.isHealthy(), true)
  instance.close()
})

test('throwing notification and fatal observers are isolated and retire the instance', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  let observedFatal = 0
  instance.onFatal(() => { throw new Error('observer failed') })
  instance.onFatal(() => { observedFatal++ })
  await assert.rejects(instance.runTurn({
    taskId: 'throwing-handler',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: new AbortController().signal,
    onNotification: () => { throw new Error('consumer failed') },
  }), /notification handler failed/)
  assert.equal(instance.isHealthy(), false)
  assert.equal(observedFatal, 1)
  instance.close()
})

test('child lifecycle notifications never trigger a supplemental metadata request', async () => {
  const received: JsonMessage[] = []
  const secret = 'Bearer SECRET_METADATA_SENTINEL C:\\private\\prompt.txt'
  const process = new FakeProcess(received, () => true, false, secret)
  await runAppServerTurn({
    taskId: 'metadata-log-task',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(received.some(message => message.method === 'thread/read'), false)
})

test('malformed child JSON marks the persistent instance unhealthy without an uncaught exception', async () => {
  const process = new FakeProcess([], () => true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  let fatal = 0
  instance.onFatal(() => { fatal++ })
  process.sendRaw('not-json')
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(instance.isHealthy(), false)
  assert.equal(fatal, 1)
  instance.close()
})

test('runtime close escalates to SIGKILL when the child ignores graceful shutdown', async () => {
  const process = new StubbornProcess([], () => true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  await instance.close(20)
  assert.deepEqual(process.signals, ['SIGTERM', 'SIGKILL'])
})

test('runtime close removes a stubborn app-server descendant before resolving', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'close')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: fixture.spawnProcess,
    requestTimeoutMs: 1_000,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
  })
  const descendantPid = await fixture.readDescendantPid()
  const processTreeRoot = path.join(fixture.stateDir, 'runtime-process-trees')
  const snapshotDirectory = fs.readdirSync(processTreeRoot, { withFileTypes: true })
    .find(entry => entry.isDirectory())
  assert.ok(snapshotDirectory)
  const snapshotText = fs.readFileSync(path.join(processTreeRoot, snapshotDirectory.name, 'tree.json'), 'utf8')
  const snapshot = JSON.parse(snapshotText) as { processes: Array<Record<string, unknown>> }
  assert.equal(snapshot.processes.some(processIdentity => 'command' in processIdentity), false)
  assert.doesNotMatch(snapshotText, /stubborn-app-server|setInterval/)

  await instance.close(50)

  assert.equal(isProcessAlive(descendantPid), false)
  assert.equal(fs.existsSync(path.join(fixture.stateDir, 'runtime-process-trees')), false)
})

test('initial process-tree capture failure preserves safe evidence and reports an unsafe runtime', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'close')
  const wrongEntry = path.join(fixture.stateDir, 'wrong-entry.mjs')
  fs.writeFileSync(wrongEntry, 'export {}\n')

  await assert.rejects(
    AppServerRuntimeInstance.start({
      env: {},
      spawnProcess: fixture.spawnProcess,
      requestTimeoutMs: 1_000,
      processTreeStateDir: fixture.stateDir,
      processTreeEntry: wrongEntry,
    }),
    error => isAppServerProcessTreeSafetyError(error),
  )

  const descendantPid = await fixture.readDescendantPid()
  assert.equal(isProcessAlive(descendantPid), true, 'detached residue cannot be declared clean')
  const processTreeRoot = path.join(fixture.stateDir, 'runtime-process-trees')
  const snapshotDirectory = fs.readdirSync(processTreeRoot, { withFileTypes: true })
    .find(entry => entry.isDirectory())
  assert.ok(snapshotDirectory)
  const evidenceText = fs.readFileSync(path.join(processTreeRoot, snapshotDirectory.name, 'capture.failure'), 'utf8')
  const evidence = JSON.parse(evidenceText) as Record<string, unknown>
  assert.deepEqual(Object.keys(evidence).sort(), [
    'captured_at',
    'cleanup_proven',
    'entry_sha256',
    'reason',
    'root_pid',
    'schema_version',
  ])
  assert.equal(evidence.reason, 'INITIAL_CAPTURE_FAILED')
  assert.equal(evidence.cleanup_proven, false)
  assert.match(String(evidence.entry_sha256), /^[a-f0-9]{64}$/)
  assert.doesNotMatch(evidenceText, /wrong-entry|stubborn-app-server|setInterval|Bearer|token/i)
})

test('unproven close retains process-tree evidence and rejects instead of releasing ownership', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'close')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: fixture.spawnProcess,
    requestTimeoutMs: 1_000,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
  })
  const descendantPid = await fixture.readDescendantPid()
  const processTreeRoot = path.join(fixture.stateDir, 'runtime-process-trees')
  const snapshotDirectory = fs.readdirSync(processTreeRoot, { withFileTypes: true })
    .find(entry => entry.isDirectory())
  assert.ok(snapshotDirectory)
  fs.rmSync(path.join(processTreeRoot, snapshotDirectory.name, 'tree.json'))

  await assert.rejects(instance.close(25), error => isAppServerProcessTreeSafetyError(error))

  assert.equal(isProcessAlive(descendantPid), true)
  const evidenceText = fs.readFileSync(path.join(processTreeRoot, snapshotDirectory.name, 'cleanup.failure'), 'utf8')
  assert.equal((JSON.parse(evidenceText) as Record<string, unknown>).reason, 'CLOSE_CLEANUP_UNPROVEN')
  assert.doesNotMatch(evidenceText, /stubborn-app-server|setInterval|Bearer|token/i)
})

test('final process-tree extension failure remains fatal after stale-snapshot cleanup succeeds', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'repair-tree-on-term')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: fixture.spawnProcess,
    requestTimeoutMs: 1_000,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
  })
  const descendantPid = await fixture.readDescendantPid()
  const processTreeRoot = path.join(fixture.stateDir, 'runtime-process-trees')
  const snapshotDirectory = fs.readdirSync(processTreeRoot, { withFileTypes: true })
    .find(entry => entry.isDirectory())
  assert.ok(snapshotDirectory)
  const trackerDirectory = path.join(processTreeRoot, snapshotDirectory.name)
  const snapshotPath = path.join(trackerDirectory, 'tree.json')
  fs.copyFileSync(snapshotPath, path.join(trackerDirectory, 'tree.backup'))
  fs.writeFileSync(snapshotPath, '{invalid snapshot')

  await assert.rejects(instance.close(50), error => isAppServerProcessTreeSafetyError(error))

  assert.equal(isProcessAlive(descendantPid), false, 'stale snapshot cleanup remains best-effort')
  assert.equal(fs.existsSync(trackerDirectory), true, 'unsafe cleanup evidence must be retained')
  const evidenceText = fs.readFileSync(path.join(trackerDirectory, 'cleanup.failure'), 'utf8')
  assert.equal((JSON.parse(evidenceText) as Record<string, unknown>).reason, 'CLOSE_CLEANUP_UNPROVEN')
  assert.doesNotMatch(evidenceText, /stubborn-app-server|setInterval|Bearer|token/i)
})

test('aborted turn does not return to its lease owner before descendant cleanup', async t => {
  const fixture = await createStubbornProcessTreeFixture(t, 'turn')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: fixture.spawnProcess,
    requestTimeoutMs: 1_000,
    processTreeStateDir: fixture.stateDir,
    processTreeEntry: fixture.entry,
  })
  const descendantPid = await fixture.readDescendantPid()
  const controller = new AbortController()
  const running = instance.runTurn({
    taskId: 'process-tree-abort',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: controller.signal,
    interruptTimeoutMs: 25,
    onNotification: () => undefined,
  })
  await fixture.waitForTurnStart()
  controller.abort()

  await assert.rejects(running)

  assert.equal(isProcessAlive(descendantPid), false, 'runtime rejection is the lease/lock release boundary')
  assert.equal(instance.isHealthy(), false)
})

for (const behavior of ['error', 'timeout'] as const) {
  test(`interrupt ${behavior} retires the instance instead of confirming a still-running turn`, async () => {
    const received: JsonMessage[] = []
    const process = new FakeProcess(received, () => true, false, undefined, behavior)
    const instance = await AppServerRuntimeInstance.start({
      env: {},
      spawnProcess: () => process as unknown as AppServerProcess,
      requestTimeoutMs: 50,
    })
    const controller = new AbortController()
    const running = instance.runTurn({
      taskId: `interrupt-${behavior}`,
      model: 'gpt-5.6-sol',
      sandboxMode: 'read-only',
      codexConfig: {},
      input: 'inspect',
      signal: controller.signal,
      onNotification: () => undefined,
    })
    for (let index = 0; index < 50 && !received.some(message => message.method === 'turn/start'); index++) {
      await new Promise(resolve => setTimeout(resolve, 2))
    }
    controller.abort()
    await assert.rejects(running)
    assert.equal(instance.isHealthy(), false)
    assert.equal(process.killed, true)
  })
}

test('abort force-retires an instance whose turn/start request never returns', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true, false, undefined, 'turn-start-hang')
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 5_000,
  })
  const controller = new AbortController()
  const startedAt = Date.now()
  const running = instance.runTurn({
    taskId: 'turn-start-hang',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: controller.signal,
    onNotification: () => undefined,
    interruptTimeoutMs: 50,
  })
  for (let index = 0; index < 50 && !received.some(message => message.method === 'turn/start'); index++) {
    await new Promise(resolve => setTimeout(resolve, 2))
  }
  assert.equal(received.some(message => message.method === 'turn/start'), true)
  controller.abort()
  await assert.rejects(running)
  assert.ok(Date.now() - startedAt < 1_000)
  assert.equal(instance.isHealthy(), false)
  assert.equal(process.killed, true)
})

test('abort during durable commit never writes turn/start or leaves a false pre-start classification', async () => {
  const received: JsonMessage[] = []
  const process = new FakeProcess(received, () => true)
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  const controller = new AbortController()
  let releaseCommit!: () => void
  const commitGate = new Promise<void>(resolve => { releaseCommit = resolve })
  let commitEntered = false
  const running = instance.runTurn({
    taskId: 'abort-during-commit',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    signal: controller.signal,
    onNotification: () => undefined,
    onExecutionCommitted: async () => {
      commitEntered = true
      await commitGate
    },
  })
  for (let index = 0; index < 50 && !commitEntered; index++) {
    await new Promise(resolve => setTimeout(resolve, 2))
  }
  assert.equal(commitEntered, true)
  controller.abort()
  releaseCommit()
  await assert.rejects(running, error => (
    error instanceof Error
    && 'executionCommitted' in error
    && (error as Error & { executionCommitted: boolean }).executionCommitted
  ))
  assert.equal(received.some(message => message.method === 'turn/start'), false)
  assert.equal(instance.isHealthy(), true)
  instance.close()
})

test('a late completion from an older turn cannot terminate the current root turn', async () => {
  const process = new FakeProcess([], () => true, false, undefined, 'stale-terminal')
  const result = await runAppServerTurn({
    taskId: 'stale-terminal',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: () => undefined,
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })
  assert.equal(result.turn.id, 'turn-1')
  assert.equal(result.turn.status, 'completed')
})

test('turn/start response and same-batch notifications replay only after turn correlation', async t => {
  const eventsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-turn-correlation-'))
  t.after(() => fs.rmSync(eventsDir, { recursive: true, force: true }))
  const broadcast = new EventBroadcast('same-batch-task', eventsDir)
  const bridge = new AppServerEventBridge({
    taskId: 'same-batch-task',
    broadcast,
    rootThreadId: 'thread-1',
  })
  const process = new FakeProcess([], () => true, false, undefined, 'same-batch-events')

  const result = await runAppServerTurn({
    taskId: 'same-batch-task',
    model: 'gpt-5.6-sol',
    sandboxMode: 'read-only',
    codexConfig: {},
    input: 'inspect',
    env: {},
    signal: new AbortController().signal,
    onNotification: notification => bridge.handle(notification),
    onTurnStarted: (_threadId, turnId) => {
      assert.equal(turnId, 'turn-1')
      bridge.setRootTurnId(turnId)
    },
    spawnProcess: () => process as unknown as AppServerProcess,
    requestTimeoutMs: 1_000,
  })

  await broadcast.flush()
  const assistantEvents = broadcast.getEventsAfter(0)
    .filter(event => event.type === 'assistant_text')
  assert.equal(result.turn.status, 'completed')
  assert.equal(bridge.getResult().assistantText, 'EARLY_COMPLETE')
  assert.deepEqual(assistantEvents.map(event => ({
    subtype: event.subtype,
    content: event.content,
  })), [
    { subtype: 'text_delta', content: 'EARLY_' },
    { subtype: 'text_delta', content: 'COMPLETE' },
    { subtype: undefined, content: 'EARLY_COMPLETE' },
  ])
})

test('local image mode persists image bytes and emits metadata without base64', async t => {
  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-generated-image-'))
  t.after(() => fs.rmSync(stateDir, { recursive: true, force: true }))
  const config = testConfig(stateDir, { imageGenerationMode: 'local' })
  const broadcast = new EventBroadcast('image-task', path.join(stateDir, 'events'))
  const bridge = new AppServerEventBridge({
    taskId: 'image-task',
    broadcast,
    rootThreadId: 'thread-image',
    generatedImageStore: new GeneratedImageStore(config),
  })
  bridge.setRootTurnId('turn-image')
  const imageBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3])
  const encoded = imageBytes.toString('base64')

  bridge.handle({
    method: 'item/completed',
    params: {
      threadId: 'thread-image',
      turnId: 'turn-image',
      item: {
        type: 'imageGeneration',
        id: 'image-item-1',
        status: 'completed',
        result: encoded,
        revisedPrompt: 'draw a compact test image',
      },
    },
  })
  await broadcast.flush()

  const events = broadcast.getEventsAfter(0)
  const imageEvent = events.find(event => event.type === 'image_generation')
  assert.ok(imageEvent)
  assert.equal(imageEvent.tool, 'image_generation')
  assert.equal(imageEvent.data?.contract_version, 1)
  assert.equal('local_path' in imageEvent.data!, true)
  assert.equal(JSON.stringify(imageEvent).includes(encoded), false)
  const localPath = (imageEvent.data as { local_path: string }).local_path
  assert.deepEqual(fs.readFileSync(localPath), imageBytes)
  assert.equal(fs.statSync(localPath).mode & 0o777, 0o600)
})

test('independent package pins the CLI and has no Codex SDK dependency', () => {
  const packageJson = JSON.parse(fs.readFileSync(path.resolve('package.json'), 'utf8')) as {
    dependencies: Record<string, string>
  }
  assert.equal(packageJson.dependencies['@openai/codex'], '0.144.3')
  assert.equal(packageJson.dependencies['@openai/codex-sdk'], undefined)
})

class StubbornProcess extends FakeProcess {
  readonly signals: Array<NodeJS.Signals | number> = []

  override kill(signal: NodeJS.Signals | number = 'SIGTERM'): boolean {
    this.killed = true
    this.signals.push(signal)
    if (signal === 'SIGKILL') queueMicrotask(() => this.emit('exit', null, signal))
    return true
  }
}
