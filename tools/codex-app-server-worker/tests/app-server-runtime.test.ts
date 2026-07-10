import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import test from 'node:test'
import fs from 'node:fs'
import path from 'node:path'
import {
  AppServerRuntimeInstance,
  runAppServerTurn,
  type AppServerProcess,
} from '../src/app-server/runtime.js'

type JsonMessage = Record<string, any>

class FakeProcess extends EventEmitter {
  readonly stdin = new PassThrough()
  readonly stdout = new PassThrough()
  readonly stderr = new PassThrough()
  readonly pid = 100
  killed = false
  private buffer = ''

  constructor(
    readonly received: JsonMessage[],
    readonly committed: () => boolean,
    private readonly emitTestNotification = false,
    private readonly childMetadataError?: string,
    private readonly interruptBehavior?: 'error' | 'timeout' | 'turn-start-hang' | 'stale-terminal',
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
    if (message.method === 'thread/start') this.send({ id: message.id, result: { thread: { id: 'thread-1' } } })
    if (message.method === 'turn/start') {
      assert.equal(this.committed(), true, 'turn/start must be written only after durable commit callback')
      if (this.interruptBehavior === 'turn-start-hang') return
      if (this.interruptBehavior === 'stale-terminal') {
        this.send({
          method: 'turn/completed',
          params: { threadId: 'thread-1', turn: { id: 'stale-turn', status: 'failed' } },
        })
      }
      this.send({ id: message.id, result: { turn: { id: 'turn-1', status: 'inProgress' } } })
      if (this.emitTestNotification) {
        this.send({ method: 'item/agentMessage/delta', params: { threadId: 'thread-1', delta: 'test' } })
      }
      if (this.childMetadataError) {
        this.send({
          method: 'item/completed',
          params: {
            threadId: 'thread-1',
            turnId: 'turn-1',
            item: { type: 'subAgentActivity', kind: 'started', agentThreadId: 'child-thread' },
          },
        })
      }
      if (!this.interruptBehavior || this.interruptBehavior === 'stale-terminal') {
        this.send({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } })
      }
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
    'initialize', 'initialized', 'thread/start', 'turn/start',
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

test('independent package pins the CLI and has no Codex SDK dependency', () => {
  const packageJson = JSON.parse(fs.readFileSync(path.resolve('package.json'), 'utf8')) as {
    dependencies: Record<string, string>
  }
  assert.equal(packageJson.dependencies['@openai/codex'], '0.144.1')
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
