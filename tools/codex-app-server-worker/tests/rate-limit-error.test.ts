import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import test from 'node:test'
import type { ExecutionResult, TaskExecutor } from '../src/app-server/executor.js'
import { stableAppServerTurnErrorCode } from '../src/app-server/event-bridge.js'
import { TaskStore } from '../src/persistence/task-store.js'
import { TaskManager } from '../src/task-manager.js'
import { tempDirectory, testConfig, waitFor } from './helpers.js'

test('structured Codex failures map to safe stable codes without inspecting provider text', () => {
  const cases: Array<[unknown, string]> = [
    ['contextWindowExceeded', 'CODEX_CONTEXT_WINDOW_EXCEEDED'],
    ['sessionBudgetExceeded', 'CODEX_SESSION_BUDGET_EXCEEDED'],
    ['usageLimitExceeded', 'CODEX_ACCOUNT_RATE_LIMITED'],
    ['serverOverloaded', 'CODEX_SERVER_OVERLOADED'],
    ['cyberPolicy', 'CODEX_CYBER_POLICY_BLOCKED'],
    ['internalServerError', 'CODEX_PROVIDER_INTERNAL_ERROR'],
    ['unauthorized', 'CODEX_AUTH_FAILED'],
    ['badRequest', 'CODEX_BAD_REQUEST'],
    ['threadRollbackFailed', 'APP_SERVER_THREAD_ROLLBACK_FAILED'],
    ['sandboxError', 'APP_SERVER_SANDBOX_ERROR'],
    ['other', 'APP_SERVER_TURN_FAILED'],
    [{ httpConnectionFailed: {} }, 'CODEX_HTTP_CONNECTION_FAILED'],
    [{ responseStreamConnectionFailed: {} }, 'CODEX_RESPONSE_STREAM_CONNECTION_FAILED'],
    [{ responseStreamDisconnected: {} }, 'CODEX_RESPONSE_STREAM_DISCONNECTED'],
    [{ responseTooManyFailedAttempts: {} }, 'CODEX_RESPONSE_RETRY_EXHAUSTED'],
    [{ activeTurnNotSteerable: { turnKind: 'review' } }, 'APP_SERVER_ACTIVE_TURN_NOT_STEERABLE'],
    [{ httpConnectionFailed: { httpStatusCode: 401 } }, 'CODEX_AUTH_FAILED'],
    [{ httpConnectionFailed: { httpStatusCode: 429 } }, 'CODEX_ACCOUNT_RATE_LIMITED'],
    [{ responseStreamDisconnected: { httpStatusCode: 500 } }, 'CODEX_PROVIDER_UNAVAILABLE'],
    [{ responseStreamConnectionFailed: { httpStatusCode: 504 } }, 'CODEX_PROVIDER_TIMEOUT'],
  ]
  for (const [codexErrorInfo, expected] of cases) {
    assert.equal(stableAppServerTurnErrorCode({
      message: 'private provider details',
      codexErrorInfo,
    }), expected)
  }
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
