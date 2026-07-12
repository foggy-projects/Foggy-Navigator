import {
  NATIVE_SUBTASK_FAILURE_CODE,
  type NativeSubtaskUpdateData,
  type WorkerEvent,
} from '../models.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { NativeSubtaskTracker, type AppServerNotification } from './native-subtask-tracker.js'

type AppServerEventBridgeOptions = {
  taskId: string
  broadcast: EventBroadcast
  rootThreadId?: string
  recordFileHint?: (event: WorkerEvent) => void
}

export class AppServerEventBridge {
  private readonly taskId: string
  private readonly broadcast: EventBroadcast
  private readonly recordFileHint?: (event: WorkerEvent) => void
  private readonly subtaskTracker: NativeSubtaskTracker
  private readonly streamedAgentMessageText = new Map<string, string>()
  private readonly completedAgentMessageIds = new Set<string>()
  private readonly startedToolIds = new Set<string>()
  private rootThreadId?: string
  private rootTurnId?: string
  private assistantText = ''
  private inputTokens = 0
  private outputTokens = 0
  private terminalFailure?: string

  constructor(options: AppServerEventBridgeOptions) {
    this.taskId = options.taskId
    this.broadcast = options.broadcast
    this.rootThreadId = options.rootThreadId
    this.recordFileHint = options.recordFileHint
    this.subtaskTracker = new NativeSubtaskTracker(options.rootThreadId)
  }

  setRootThreadId(threadId: string): void {
    this.rootThreadId = threadId
    this.subtaskTracker.setRootThreadId(threadId)
  }

  setRootTurnId(turnId: string): void {
    this.rootTurnId = turnId
    this.subtaskTracker.setRootTurnId(turnId)
  }

  handle(notification: AppServerNotification): void {
    for (const snapshot of this.subtaskTracker.handle(notification)) {
      const safeSnapshot = this.safeNativeSubtaskSnapshot(snapshot)
      this.emit({
        type: 'native_subtask_update',
        task_id: this.taskId,
        session_id: this.rootThreadId,
        data: safeSnapshot,
      })
    }

    const params = notification.params || {}
    const notificationThreadId = readString(params.threadId)
    if (!this.rootThreadId || notificationThreadId !== this.rootThreadId) return
    if (isRootTurnScoped(notification.method)) {
      if (!this.rootTurnId || readString(params.turnId) !== this.rootTurnId) return
    }

    switch (notification.method) {
      case 'item/agentMessage/delta':
        this.handleAgentMessageDelta(params)
        break
      case 'item/reasoning/summaryTextDelta':
        // Reasoning summaries are provider-private and never cross the Worker boundary.
        break
      case 'item/started':
        this.handleItemStarted(params)
        break
      case 'item/completed':
        this.handleItemCompleted(params)
        break
      case 'thread/tokenUsage/updated':
        this.handleTokenUsage(params)
        break
      case 'error':
        this.handleError(params)
        break
    }
  }

  private safeNativeSubtaskSnapshot(snapshot: NativeSubtaskUpdateData): NativeSubtaskUpdateData {
    const safeSnapshot = {
      ...snapshot,
      message: snapshot.status === 'failed' ? NATIVE_SUBTASK_FAILURE_CODE : undefined,
    }
    return Object.fromEntries(
      Object.entries(safeSnapshot).filter(([, value]) => value !== undefined),
    ) as unknown as NativeSubtaskUpdateData
  }

  getResult(): {
    assistantText: string
    inputTokens: number
    outputTokens: number
    terminalFailure?: string
  } {
    return {
      assistantText: this.assistantText,
      inputTokens: this.inputTokens,
      outputTokens: this.outputTokens,
      terminalFailure: this.terminalFailure,
    }
  }

  private handleAgentMessageDelta(params: Record<string, unknown>): void {
    const delta = readStringPreserveWhitespace(params.delta)
    const itemId = readString(params.itemId)
    if (!delta) return
    if (itemId) {
      if (this.completedAgentMessageIds.has(itemId)) return
      this.streamedAgentMessageText.set(
        itemId,
        `${this.streamedAgentMessageText.get(itemId) || ''}${delta}`,
      )
    }
    this.emit({
      type: 'assistant_text',
      subtype: 'text_delta',
      task_id: this.taskId,
      session_id: this.rootThreadId,
      content: delta,
    })
  }

  private handleItemStarted(params: Record<string, unknown>): void {
    const item = asRecord(params.item)
    const itemId = readString(item?.id)
    if (!item || !itemId) return

    if (item.type === 'commandExecution') {
      this.startedToolIds.add(itemId)
      this.emitToolUse('command_execution', { command: item.command }, itemId)
    } else if (item.type === 'mcpToolCall') {
      this.startedToolIds.add(itemId)
      this.emitToolUse(`${readString(item.server) || 'mcp'}:${readString(item.tool) || 'unknown'}`, asRecord(item.arguments), itemId)
    } else if (item.type === 'dynamicToolCall') {
      this.startedToolIds.add(itemId)
      this.emitToolUse(readString(item.tool) || 'dynamic_tool', asRecord(item.arguments), itemId)
    }
  }

  private handleItemCompleted(params: Record<string, unknown>): void {
    const item = asRecord(params.item)
    const itemId = readString(item?.id)
    if (!item || !itemId) return

    switch (item.type) {
      case 'agentMessage': {
        if (this.completedAgentMessageIds.has(itemId)) break
        const streamedText = this.streamedAgentMessageText.get(itemId)
        const text = readStringPreserveWhitespace(item.text) || streamedText
        if (!text) break
        this.completedAgentMessageIds.add(itemId)
        this.streamedAgentMessageText.delete(itemId)
        this.assistantText = text
        this.emit({
          type: 'assistant_text',
          task_id: this.taskId,
          session_id: this.rootThreadId,
          content: text,
        })
        break
      }
      case 'commandExecution': {
        if (!this.startedToolIds.has(itemId)) {
          this.emitToolUse('command_execution', { command: item.command }, itemId)
        }
        this.emit({
          type: 'tool_result',
          task_id: this.taskId,
          session_id: this.rootThreadId,
          tool: 'command_execution',
          output: readStringPreserveWhitespace(item.aggregatedOutput) || '',
          tool_use_id: itemId,
          is_error: item.status === 'failed',
        })
        break
      }
      case 'fileChange':
        this.emitToolUse('file_change', {
          changes: Array.isArray(item.changes) ? item.changes : [],
          status: item.status,
        }, itemId)
        break
      case 'mcpToolCall': {
        const tool = `${readString(item.server) || 'mcp'}:${readString(item.tool) || 'unknown'}`
        if (!this.startedToolIds.has(itemId)) {
          this.emitToolUse(tool, asRecord(item.arguments), itemId)
        }
        const error = asRecord(item.error)
        this.emit({
          type: 'tool_result',
          task_id: this.taskId,
          session_id: this.rootThreadId,
          tool,
          output: item.result === null || item.result === undefined
            ? (readString(error?.message) || '')
            : JSON.stringify(item.result),
          tool_use_id: itemId,
          is_error: item.status === 'failed',
        })
        break
      }
      case 'dynamicToolCall': {
        const tool = readString(item.tool) || 'dynamic_tool'
        if (!this.startedToolIds.has(itemId)) {
          this.emitToolUse(tool, asRecord(item.arguments), itemId)
        }
        this.emit({
          type: 'tool_result',
          task_id: this.taskId,
          session_id: this.rootThreadId,
          tool,
          output: JSON.stringify(item.contentItems ?? null),
          tool_use_id: itemId,
          is_error: item.success === false,
        })
        break
      }
    }
  }

  private handleTokenUsage(params: Record<string, unknown>): void {
    const tokenUsage = asRecord(params.tokenUsage)
    const last = asRecord(tokenUsage?.last)
    this.inputTokens = readNumber(last?.inputTokens) ?? this.inputTokens
    this.outputTokens = readNumber(last?.outputTokens) ?? this.outputTokens
  }

  private handleError(params: Record<string, unknown>): void {
    if (params.willRetry === true) return
    // Raw provider failures may include credentials, paths, prompts, or tool output.
    // The outer task manager emits only stable codes at the Worker boundary.
    this.terminalFailure = stableAppServerTurnErrorCode(params.error)
  }

  private emitToolUse(tool: string, input: Record<string, unknown> | undefined, toolUseId: string): void {
    this.emit({
      type: 'tool_use',
      task_id: this.taskId,
      session_id: this.rootThreadId,
      tool,
      input,
      tool_use_id: toolUseId,
    })
  }

  private emit(event: Omit<WorkerEvent, 'seq'>): void {
    const eventWithSeq: WorkerEvent = { ...event, seq: this.broadcast.nextSeq() }
    this.recordFileHint?.(eventWithSeq)
    this.broadcast.emit(eventWithSeq)
  }
}

export type SafeAppServerTurnFailure = {
  code: string
  kind: string
  providerStatus?: number
}

export function stableAppServerTurnErrorCode(value: unknown): string {
  return classifyAppServerTurnFailure(value).code
}

export function classifyAppServerTurnFailure(value: unknown): SafeAppServerTurnFailure {
  const error = asRecord(value)
  const info = error?.codexErrorInfo
  if (typeof info === 'string') {
    const code = STRING_ERROR_CODES[info]
    return code
      ? { code, kind: info }
      : { code: 'APP_SERVER_TURN_FAILED', kind: 'unknown' }
  }
  const variant = readCodexErrorVariant(info)
  if (!variant) return { code: 'APP_SERVER_TURN_FAILED', kind: 'unknown' }
  const providerStatus = readProviderStatus(variant.detail)
  const statusCode = providerStatus === undefined ? undefined : providerStatusCode(providerStatus)
  return {
    code: statusCode || VARIANT_ERROR_CODES[variant.kind] || 'APP_SERVER_TURN_FAILED',
    kind: variant.kind,
    providerStatus,
  }
}

export function shouldRetireAppServerAfterTurnFailure(code: string): boolean {
  return !REUSABLE_TURN_FAILURE_CODES.has(code)
}

const STRING_ERROR_CODES: Readonly<Record<string, string>> = Object.freeze({
  contextWindowExceeded: 'CODEX_CONTEXT_WINDOW_EXCEEDED',
  sessionBudgetExceeded: 'CODEX_SESSION_BUDGET_EXCEEDED',
  usageLimitExceeded: 'CODEX_ACCOUNT_RATE_LIMITED',
  serverOverloaded: 'CODEX_SERVER_OVERLOADED',
  cyberPolicy: 'CODEX_CYBER_POLICY_BLOCKED',
  internalServerError: 'CODEX_PROVIDER_INTERNAL_ERROR',
  unauthorized: 'CODEX_AUTH_FAILED',
  badRequest: 'CODEX_BAD_REQUEST',
  threadRollbackFailed: 'APP_SERVER_THREAD_ROLLBACK_FAILED',
  sandboxError: 'APP_SERVER_SANDBOX_ERROR',
  other: 'APP_SERVER_TURN_FAILED',
})

const VARIANT_ERROR_CODES: Readonly<Record<string, string>> = Object.freeze({
  httpConnectionFailed: 'CODEX_HTTP_CONNECTION_FAILED',
  responseStreamConnectionFailed: 'CODEX_RESPONSE_STREAM_CONNECTION_FAILED',
  responseStreamDisconnected: 'CODEX_RESPONSE_STREAM_DISCONNECTED',
  responseTooManyFailedAttempts: 'CODEX_RESPONSE_RETRY_EXHAUSTED',
  activeTurnNotSteerable: 'APP_SERVER_ACTIVE_TURN_NOT_STEERABLE',
})

const REUSABLE_TURN_FAILURE_CODES = new Set([
  'CODEX_CONTEXT_WINDOW_EXCEEDED',
  'CODEX_SESSION_BUDGET_EXCEEDED',
  'CODEX_ACCOUNT_RATE_LIMITED',
  'CODEX_SERVER_OVERLOADED',
  'CODEX_CYBER_POLICY_BLOCKED',
  'CODEX_AUTH_FAILED',
  'CODEX_BAD_REQUEST',
  'APP_SERVER_ACTIVE_TURN_NOT_STEERABLE',
])

function readCodexErrorVariant(value: unknown): { kind: string; detail: unknown } | undefined {
  const info = asRecord(value)
  if (!info) return undefined
  for (const kind of Object.keys(VARIANT_ERROR_CODES)) {
    if (Object.prototype.hasOwnProperty.call(info, kind)) return { kind, detail: info[kind] }
  }
  return undefined
}

function readProviderStatus(value: unknown): number | undefined {
  const status = asRecord(value)?.httpStatusCode
  return typeof status === 'number' && Number.isInteger(status) ? status : undefined
}

function providerStatusCode(status: number): string | undefined {
  if (status === 401 || status === 403) return 'CODEX_AUTH_FAILED'
  if (status === 408 || status === 504) return 'CODEX_PROVIDER_TIMEOUT'
  if (status === 429) return 'CODEX_ACCOUNT_RATE_LIMITED'
  if (status >= 500 && status <= 599) return 'CODEX_PROVIDER_UNAVAILABLE'
  return undefined
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function readStringPreserveWhitespace(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function readNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function isRootTurnScoped(method: string): boolean {
  return method.startsWith('item/') || method === 'thread/tokenUsage/updated' || method === 'error'
}
