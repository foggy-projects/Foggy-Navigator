import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type { AppConfig } from '../src/config.js'
import { createTestEncryptionKey } from '../src/config.js'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'

export async function tempDirectory(prefix: string): Promise<string> {
  return fs.mkdtemp(path.join(os.tmpdir(), prefix))
}

export function testConfig(stateDir: string, overrides: Partial<AppConfig> = {}): AppConfig {
  const absoluteStateDir = path.resolve(stateDir)
  return {
    port: 0,
    host: '127.0.0.1',
    workerName: 'test-app-server-worker',
    navigatorWorkerId: 'test-navigator-worker',
    workerToken: 'test-worker-token',
    externalEnabled: false,
    runtimeId: 'test-runtime',
    runtimeRevision: 1,
    instanceId: 'test-instance',
    openaiApiKey: '',
    openaiBaseUrl: '',
    codexHome: path.join(
      path.dirname(absoluteStateDir),
      `${path.basename(absoluteStateDir)}-codex-app-server-test-home`,
    ),
    codexBizHomeRoot: '',
    allowedCwds: [process.cwd()],
    maxConcurrentTasks: 2,
    maxQueuedTasks: 8,
    poolMaxInstances: 1,
    poolMaxInstancesPerLane: 1,
    poolMaxQueue: 4,
    poolAcquireTimeoutMs: 1_000,
    poolIdleTtlMs: 1_000,
    poolMaxLifetimeMs: 10_000,
    poolMaxTasksPerInstance: 10,
    shutdownTimeoutMs: 1_000,
    abortWaitTimeoutMs: 500,
    turnStallTimeoutMs: 15_000,
    stateDir,
    imageGenerationMode: 'disabled',
    imageGenerationOutputDir: path.join(stateDir, 'generated-images'),
    imageGenerationMaxBytes: 16 * 1024 * 1024,
    stateEncryptionKey: createTestEncryptionKey(),
    defaultModel: 'codex-latest',
    modelAliases: {
      'codex-latest': 'gpt-5.6-sol',
      'codex-ultra': 'gpt-5.6-sol:ultra',
      'codex-max': 'gpt-5.6-sol:max',
    },
    ...overrides,
  }
}

export class FakeExecutor implements TaskExecutor {
  calls = 0
  sideEffects = 0
  stateAtSideEffect: string | undefined
  readonly requests: Array<Parameters<TaskExecutor['execute']>[0]['request']> = []
  constructor(private readonly stateReader?: () => string | undefined) {}

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    this.calls++
    this.requests.push(options.request)
    await options.callbacks.onInstanceResolved('instance-test')
    await options.callbacks.onThreadResolved('thread-test')
    await options.callbacks.onExecutionCommitted('thread-test')
    this.stateAtSideEffect = this.stateReader?.()
    this.sideEffects++
    await options.callbacks.onTurnStarted('thread-test', 'turn-test')
    options.broadcast.emit({
      type: 'assistant_text',
      task_id: options.taskId,
      session_id: 'thread-test',
      content: 'done',
    })
    return {
      threadId: 'thread-test',
      turnId: 'turn-test',
      status: 'completed',
      assistantText: 'done',
      inputTokens: 1,
      outputTokens: 1,
      model: 'gpt-5.6-sol',
      durationMs: 1,
    }
  }
}

export async function waitFor(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('condition timed out')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}
