import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import test from 'node:test'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'
import { stableAppServerTurnErrorCode } from '../src/app-server/event-bridge.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

test('only structured usage limit and provider HTTP 429 errors get the stable account code', () => {
  assert.equal(stableAppServerTurnErrorCode({ codexErrorInfo: 'usageLimitExceeded' }),
    'CODEX_ACCOUNT_RATE_LIMITED')
  assert.equal(stableAppServerTurnErrorCode({
    message: 'private provider details',
    codexErrorInfo: { httpConnectionFailed: { httpStatusCode: 429 } },
  }), 'CODEX_ACCOUNT_RATE_LIMITED')
  assert.equal(stableAppServerTurnErrorCode({
    codexErrorInfo: { responseStreamDisconnected: { httpStatusCode: 500 } },
  }), 'APP_SERVER_TURN_FAILED')
  assert.equal(stableAppServerTurnErrorCode({ message: '429 in untrusted text' }), 'APP_SERVER_TURN_FAILED')
})

test('account-limited tasks persist and emit only the stable code without changing the requested model', async t => {
  const stateDir = await tempDirectory('codex-rate-limit-error-')
  const config = testConfig(stateDir)
  const executor = new AccountLimitedExecutor()
  const store = new TaskStore({ stateDir, encryptionKey: config.stateEncryptionKey! })
  const manager = new TaskManager(config, store, executor)
  await manager.initialize()
  t.after(async () => {
    await manager.shutdown(1_000)
    await fs.rm(stateDir, { recursive: true, force: true })
  })

  await manager.accept('rate-limited-task', { prompt: 'keep exact model', model: 'gpt-5.6-sol:ultra' })
  await waitFor(() => manager.get('rate-limited-task')?.status === 'terminal')

  const record = manager.get('rate-limited-task')!
  assert.equal(record.error_code, 'CODEX_ACCOUNT_RATE_LIMITED')
  assert.equal(record.model, 'gpt-5.6-sol')
  assert.equal(record.reasoning_effort, 'ultra')
  assert.deepEqual(executor.models, ['gpt-5.6-sol:ultra'])
  const terminal = manager.getBroadcast('rate-limited-task').getEventsAfter(0)
    .find(event => event.type === 'error')
  assert.equal(terminal?.subtype, 'CODEX_ACCOUNT_RATE_LIMITED')
  assert.equal(JSON.stringify(terminal).includes('private provider details'), false)
})

class AccountLimitedExecutor implements TaskExecutor {
  readonly models: Array<string | undefined> = []

  async execute(options: Parameters<TaskExecutor['execute']>[0]): Promise<ExecutionResult> {
    this.models.push(options.request.model)
    await options.callbacks.onInstanceResolved('instance-limited', 'lane-limited')
    await options.callbacks.onThreadResolved('thread-limited')
    await options.callbacks.onExecutionCommitted('thread-limited')
    await options.callbacks.onTurnStarted('thread-limited', 'turn-limited')
    return {
      threadId: 'thread-limited',
      turnId: 'turn-limited',
      status: 'failed',
      assistantText: '',
      inputTokens: 0,
      outputTokens: 0,
      model: options.request.model || 'gpt-5.6-sol',
      durationMs: 1,
      errorCode: 'CODEX_ACCOUNT_RATE_LIMITED',
    }
  }
}
