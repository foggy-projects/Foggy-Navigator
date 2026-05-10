import { ref, computed, type Ref, type ComputedRef, onUnmounted } from 'vue'
import { NavigatorApi } from '../api/navigatorApi'
import type {
  AgentTask,
  ChatMessage,
  NavigatorChatConfig,
  OpenTaskMessage,
  TaskMessagesPage,
  TaskStatus,
  TerminalStatus,
  ToolExecutionBlock,
  NavigatorAction,
} from '../types'

let _seq = 0
function nextId(): string {
  return `msg-${Date.now()}-${++_seq}`
}

export interface UseNavigatorChat {
  /** 所有消息（按时间排序） */
  messages: ComputedRef<ChatMessage[]>
  /** 是否正在等待 AI 响应 */
  isLoading: Ref<boolean>
  /** 当前任务状态 */
  taskStatus: Ref<TaskStatus | null>
  /** 当前多轮会话 contextId */
  contextId: Ref<string | null>
  /** 当前业务模式下的简短进度提示 */
  progressText: Ref<string>
  /** 错误信息 */
  error: Ref<string | null>
  /** 发送消息 */
  send: (content: string) => Promise<void>
  /** 取消当前任务 */
  cancel: () => Promise<void>
  /** 清空对话（重新开始新会话） */
  clear: () => void
}

/**
 * Navigator 对话 composable（核心逻辑）
 *
 * 封装了 ask → poll → 展示 的完整循环，支持多轮对话。
 *
 * @example
 * ```vue
 * <script setup>
 * import { useNavigatorChat } from '@foggy/navigator-chat-widget'
 *
 * const chat = useNavigatorChat({
 *   baseUrl: '/api/proxy/navigator',  // 通过自己后端代理
 *   agentId: 'xxx',
 * })
 * </script>
 * ```
 */
export function useNavigatorChat(config: NavigatorChatConfig): UseNavigatorChat {
  const api = new NavigatorApi(config)
  const pollInterval = config.pollInterval ?? 2000
  const timeout = config.timeout ?? 300_000
  const display = resolveDisplayOptions(config)

  const rawMessages = ref<ChatMessage[]>([])
  const isLoading = ref(false)
  const taskStatus = ref<TaskStatus | null>(null)
  const contextId = ref<string | null>(null)
  const progressText = ref('正在理解问题...')
  const error = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let currentTaskId: string | null = null
  let nextCursor: string | null = null
  let pollStartTime = 0
  const seenOpenMessageIds = new Set<string>()
  const toolExecutionMessageIds = new Map<string, string>()
  const pendingToolCallIdsByTask = new Map<string, string[]>()
  let toolSeq = 0

  const messages = computed(() =>
    [...rawMessages.value].sort((a, b) => a.timestamp - b.timestamp)
  )

  onUnmounted(() => {
    stopPolling()
  })

  async function send(content: string): Promise<void> {
    if (isLoading.value) return
    error.value = null

    // 添加用户消息
    rawMessages.value.push({
      id: nextId(),
      role: 'user',
      content,
      timestamp: Date.now(),
    })

    isLoading.value = true
    taskStatus.value = 'SUBMITTED'
    progressText.value = '正在理解问题...'

    try {
      const task = await api.ask(content, contextId.value ?? undefined)
      currentTaskId = task.taskId
      contextId.value = task.contextId
      taskStatus.value = task.status
      nextCursor = task.nextCursor ?? null
      seenOpenMessageIds.clear()
      ingestInitialTaskMessages(task)

      // 开始轮询
      pollStartTime = Date.now()
      if (task.terminal) {
        finishTask(task.taskId, task.terminalStatus ?? null, task.status)
      } else {
        startPolling(task.taskId)
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      error.value = msg
      isLoading.value = false
      taskStatus.value = null
      rawMessages.value.push({
        id: nextId(),
        role: 'system',
        content: `发送失败: ${sanitizeErrorSummary(msg)}`,
        timestamp: Date.now(),
        error: msg,
      })
    }
  }

  function startPolling(taskId: string) {
    stopPolling()
    pollTimer = setTimeout(() => doPoll(taskId), pollInterval)
  }

  async function doPoll(taskId: string) {
    if (currentTaskId !== taskId) return // 已切换到其他任务

    try {
      const page = await api.getTaskMessages(taskId, nextCursor, 50)
      if (page.contextId) contextId.value = page.contextId
      if (page.status) taskStatus.value = page.status as TaskStatus
      ingestTaskMessages(page)
      nextCursor = page.nextCursor ?? nextCursor

      const terminalMessage = page.messages.find((message) => message.terminal)
      if (page.terminal || terminalMessage) {
        finishTask(
          taskId,
          terminalMessage?.terminalStatus ?? page.terminalStatus ?? null,
          page.status as TaskStatus
        )
        return
      }

      // 检查超时
      if (Date.now() - pollStartTime > timeout) {
        stopPolling()
        isLoading.value = false
        error.value = '等待超时'
        rawMessages.value.push({
          id: nextId(),
          role: 'system',
          content: `等待响应超时（${Math.round(timeout / 1000)}秒）`,
          timestamp: Date.now(),
          error: 'timeout',
        })
        return
      }

      // 继续轮询
      pollTimer = setTimeout(() => doPoll(taskId), pollInterval)
    } catch (e: unknown) {
      // 网络错误，重试
      pollTimer = setTimeout(() => doPoll(taskId), pollInterval * 2)
    }
  }

  async function cancel(): Promise<void> {
    if (!currentTaskId) return
    try {
      await api.cancelTask(currentTaskId)
    } catch {
      // ignore
    }
    stopPolling()
    isLoading.value = false
    taskStatus.value = 'CANCELED'
    currentTaskId = null
    nextCursor = null
  }

  function clear() {
    stopPolling()
    rawMessages.value = []
    isLoading.value = false
    taskStatus.value = null
    contextId.value = null
    progressText.value = '正在理解问题...'
    error.value = null
    currentTaskId = null
    nextCursor = null
    seenOpenMessageIds.clear()
    toolExecutionMessageIds.clear()
    pendingToolCallIdsByTask.clear()
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function isTerminal(status: TaskStatus): boolean {
    return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELED'
  }

  function ingestInitialTaskMessages(task: AgentTask) {
    if (Array.isArray(task.messages) && task.messages.length > 0) {
      ingestOpenMessages(task.messages, task.taskId, task.status)
    }
    if (task.terminal && !task.messages?.some((message) => message.terminal)) {
      appendLegacyTerminalMessage(task)
    }
  }

  function ingestTaskMessages(page: TaskMessagesPage) {
    ingestOpenMessages(page.messages ?? [], page.taskId, page.status as TaskStatus)
  }

  function ingestOpenMessages(openMessages: OpenTaskMessage[], taskId: string, status?: TaskStatus) {
    for (const message of openMessages) {
      const id = openMessageId(taskId, message)
      if (seenOpenMessageIds.has(id)) continue
      seenOpenMessageIds.add(id)

      const content = openMessageContent(message)
      const sanitizedMessage = sanitizeValue(message) as OpenTaskMessage
      const actions = extractActions(sanitizedMessage)
      if (message.type === 'USER') {
        continue
      }
      if (actions.length > 0) {
        rawMessages.value.push({
          id: `${id}:actions`,
          role: 'assistant',
          content: businessActionIntro(actions),
          timestamp: openMessageTimestamp(message),
          taskId,
          status,
          messageType: message.type,
          terminal: message.terminal,
          terminalStatus: message.terminalStatus ?? null,
          actions,
        })
      }
      if ((message.type === 'TEXT' || message.type === 'RESULT') && content) {
        const businessContent = display.mode === 'business' ? stripRawJsonArtifact(content, actions) : sanitizeText(content)
        if (!businessContent && actions.length > 0) {
          continue
        }
        rawMessages.value.push({
          id,
          role: 'assistant',
          content: businessContent,
          timestamp: openMessageTimestamp(message),
          taskId,
          status,
          messageType: message.type,
          terminal: message.terminal,
          terminalStatus: message.terminalStatus ?? null,
        })
      } else if (message.type === 'ERROR') {
        const errMsg = display.mode === 'business'
          ? sanitizeErrorSummary(content || '任务执行失败')
          : sanitizeText(content || '任务执行失败')
        error.value = errMsg
        rawMessages.value.push({
          id,
          role: 'system',
          content: errMsg,
          timestamp: openMessageTimestamp(message),
          taskId,
          status,
          messageType: message.type,
          terminal: message.terminal,
          terminalStatus: message.terminalStatus ?? null,
          error: errMsg,
        })
      } else if (message.type === 'STATE') {
        progressText.value = stateProgressText(content)
        if (display.showRuntimeEvents) {
          rawMessages.value.push({
            id,
            role: 'system',
            content: sanitizeText(content || stringifySafe(sanitizedMessage)),
            timestamp: openMessageTimestamp(message),
            taskId,
            status,
            messageType: message.type,
            terminal: message.terminal,
            terminalStatus: message.terminalStatus ?? null,
            process: true,
            processKind: 'state',
          })
        }
      } else if (message.type === 'TOOL_CALL') {
        progressText.value = '正在查询数据...'
        if (display.showToolCalls || display.showToolResults) {
          upsertToolExecution(taskId, message, 'call', id, status)
        }
      } else if (message.type === 'TOOL_RESULT') {
        progressText.value = '正在生成回答...'
        if (display.showToolCalls || display.showToolResults) {
          upsertToolExecution(taskId, message, 'result', id, status)
        }
      }
    }
  }

  function finishTask(taskId: string, terminalStatus: TerminalStatus | null, status?: TaskStatus) {
    stopPolling()
    isLoading.value = false
    currentTaskId = null
    nextCursor = null
    if (terminalStatus === 'FAILED' || status === 'FAILED') {
      const lastError = [...rawMessages.value].reverse().find((message) => message.taskId === taskId && message.error)
      error.value = lastError?.content ?? '任务执行失败'
    }
    if (status && isTerminal(status)) {
      taskStatus.value = status
    } else if (terminalStatus === 'COMPLETED' || terminalStatus === 'FAILED') {
      taskStatus.value = terminalStatus
    }
  }

  function appendLegacyTerminalMessage(task: AgentTask) {
    if (task.status === 'COMPLETED' && task.result) {
      rawMessages.value.push({
        id: nextId(),
        role: 'assistant',
        content: task.result,
        timestamp: Date.now(),
        taskId: task.taskId,
        status: task.status,
        terminal: true,
        terminalStatus: task.terminalStatus ?? 'COMPLETED',
        durationMs: task.durationMs ?? undefined,
        costUsd: task.costUsd ?? undefined,
      })
    } else if (task.status === 'FAILED') {
      const errMsg = task.errorMessage || '任务执行失败'
      error.value = errMsg
      rawMessages.value.push({
        id: nextId(),
        role: 'system',
        content: errMsg,
        timestamp: Date.now(),
        taskId: task.taskId,
        status: task.status,
        terminal: true,
        terminalStatus: task.terminalStatus ?? 'FAILED',
        error: errMsg,
      })
    }
  }

  function openMessageId(taskId: string, message: OpenTaskMessage): string {
    const id = message.id ?? message.messageId
    if (id) return String(id)
    return `${taskId}:${message.type}:${message.timestamp ?? message.createdAt ?? ''}:${openMessageContent(message)}`
  }

  function openMessageContent(message: OpenTaskMessage): string {
    const content = message.content
    if (typeof content === 'string') return content
    if (content == null) return ''
    return JSON.stringify(content, null, 2)
  }

  function openMessageTimestamp(message: OpenTaskMessage): number {
    if (typeof message.timestamp === 'number') return message.timestamp
    const value = message.timestamp ?? message.createdAt
    if (typeof value === 'string') {
      const parsed = Date.parse(value)
      if (!Number.isNaN(parsed)) return parsed
    }
    return Date.now()
  }

  return {
    messages,
    isLoading,
    taskStatus,
    contextId,
    progressText,
    error,
    send,
    cancel,
    clear,
  }

  function upsertToolExecution(
    taskId: string,
    message: OpenTaskMessage,
    phase: 'call' | 'result',
    messageId: string,
    status?: TaskStatus
  ) {
    const timestamp = openMessageTimestamp(message)
    const payload = normalizedPayload(message)
    const toolCallId = resolveToolCallId(taskId, message, phase, payload)
    const existingMessageId = toolExecutionMessageIds.get(toolCallId)
    const existing = existingMessageId
      ? rawMessages.value.find((item) => item.id === existingMessageId)?.toolExecution
      : undefined

    const next = mergeToolExecution(existing, toolCallId, message, payload, phase, timestamp)
    if (existingMessageId) {
      const target = rawMessages.value.find((item) => item.id === existingMessageId)
      if (target) {
        target.toolExecution = next
        target.content = toolExecutionTitle(next)
        target.timestamp = Math.min(target.timestamp, timestamp)
      }
      return
    }

    const blockMessageId = `${messageId}:tool-execution`
    toolExecutionMessageIds.set(toolCallId, blockMessageId)
    rawMessages.value.push({
      id: blockMessageId,
      role: 'system',
      content: toolExecutionTitle(next),
      timestamp,
      taskId,
      status,
      messageType: 'TOOL_CALL',
      terminal: message.terminal,
      terminalStatus: message.terminalStatus ?? null,
      process: true,
      processKind: 'tool_execution',
      toolExecution: next,
    })
  }

  function resolveToolCallId(
    taskId: string,
    message: OpenTaskMessage,
    phase: 'call' | 'result',
    payload: Record<string, unknown>
  ): string {
    const stable = firstString(
      message.toolCallId,
      message.callId,
      message.invocationId,
      message.runId,
      payload.toolCallId,
      payload.tool_call_id,
      payload.callId,
      payload.call_id,
      payload.invocationId,
      payload.invocation_id,
      payload.runId,
      payload.run_id
    )
    if (stable) return stable

    const sequence = firstString(payload.sequence, payload.seq, message.sequence)
    const functionName = firstString(payload.functionName, payload.function_name, payload.functionId, payload.function_id, payload.toolName)
    if (sequence && functionName) return `${taskId}:${functionName}:${sequence}`

    if (phase === 'result') {
      const queue = pendingToolCallIdsByTask.get(taskId)
      const pending = queue?.shift()
      if (pending) return pending
    }

    const generated = `${taskId}:tool:${++toolSeq}`
    if (phase === 'call') {
      const queue = pendingToolCallIdsByTask.get(taskId) ?? []
      queue.push(generated)
      pendingToolCallIdsByTask.set(taskId, queue)
    }
    return generated
  }
}

interface DisplayOptions {
  mode: 'business' | 'debug'
  showRuntimeEvents: boolean
  showToolCalls: boolean
  showToolResults: boolean
}

function resolveDisplayOptions(config: NavigatorChatConfig): DisplayOptions {
  const mode = config.mode ?? (config.debugMode ? 'debug' : 'business')
  const debug = mode === 'debug'
  return {
    mode,
    showRuntimeEvents: config.showRuntimeEvents ?? debug,
    showToolCalls: config.showToolCalls ?? debug,
    showToolResults: config.showToolResults ?? debug,
  }
}

const SENSITIVE_KEYS = new Set([
  'token',
  'accesstoken',
  'refreshtoken',
  'authorization',
  'xtmsagenttoken',
  'clientsecret',
  'appsecret',
  'taskscopedtoken',
  'adapterconfigjson',
  'manifestjson',
])
const SENSITIVE_KEY_PATTERNS = [
  'token',
  'accessToken',
  'refreshToken',
  'Authorization',
  'X-TMS-Agent-Token',
  'clientSecret',
  'appSecret',
  'task_scoped_token',
  'taskScopedToken',
  'adapterConfigJson',
  'manifestJson',
]

function isSensitiveKey(key: string): boolean {
  const normalized = key.replace(/[-_]/g, '').toLowerCase()
  return SENSITIVE_KEYS.has(normalized)
}

function sanitizeValue(value: unknown, seen = new WeakSet<object>()): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeValue(item, seen))
  }
  if (value && typeof value === 'object') {
    if (seen.has(value)) return '[Circular]'
    seen.add(value)
    const output: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      output[key] = isSensitiveKey(key) ? '[REDACTED]' : sanitizeValue(item, seen)
    }
    return output
  }
  if (typeof value === 'string') return sanitizeText(value)
  return value
}

function sanitizeText(text: string): string {
  let output = text
  for (const key of SENSITIVE_KEY_PATTERNS) {
    output = output.replace(new RegExp(`("${key}"\\s*:\\s*)"[^"]*"`, 'gi'), '$1"[REDACTED]"')
  }
  output = output.replace(/(Authorization\s*[:=]\s*)(Bearer\s+)?[^\s",}]+/gi, '$1[REDACTED]')
  output = output.replace(/(X-TMS-Agent-Token\s*[:=]\s*)[^\s",}]+/gi, '$1[REDACTED]')
  return output
}

function stringifySafe(value: unknown): string {
  return JSON.stringify(sanitizeValue(value), null, 2)
}

function parseMaybeJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const trimmed = value.trim()
  if (!trimmed || !['{', '['].includes(trimmed[0])) return value
  try {
    return JSON.parse(trimmed)
  } catch {
    return value
  }
}

function normalizedPayload(message: OpenTaskMessage): Record<string, unknown> {
  const contentObject = parseMaybeJson(message.content)
  const payload: Record<string, unknown> = {}
  if (contentObject && typeof contentObject === 'object' && !Array.isArray(contentObject)) {
    Object.assign(payload, contentObject as Record<string, unknown>)
  }
  if (message.metadata) Object.assign(payload, message.metadata)
  Object.assign(payload, message)
  return sanitizeValue(payload) as Record<string, unknown>
}

function mergeToolExecution(
  existing: ToolExecutionBlock | undefined,
  toolCallId: string,
  message: OpenTaskMessage,
  payload: Record<string, unknown>,
  phase: 'call' | 'result',
  timestamp: number
): ToolExecutionBlock {
  const result = payload.result ?? payload.data ?? parseMaybeJson(message.content)
  const resultObject = result && typeof result === 'object' ? result as Record<string, unknown> : undefined
  const nestedResult = resultObject?.result && typeof resultObject.result === 'object'
    ? resultObject.result as Record<string, unknown>
    : undefined
  const toolName = firstString(
    payload.toolName,
    payload.tool_name,
    payload.name,
    message.toolName,
    existing?.toolName,
    phase === 'call' ? message.content : undefined
  ) || 'unknown_tool'
  const functionName = firstString(payload.functionName, payload.function_name, existing?.functionName)
  const argsObject = payload.args && typeof payload.args === 'object'
    ? payload.args as Record<string, unknown>
    : undefined
  const functionId = firstString(
    payload.functionId,
    payload.function_id,
    argsObject?.functionId,
    argsObject?.function_id,
    nestedResult?.functionId,
    nestedResult?.function_id,
    resultObject?.functionId,
    resultObject?.function_id,
    existing?.functionId
  )
  const args = payload.args ?? payload.arguments ?? payload.input ?? existing?.args
  const successValue = payload.success ?? resultObject?.success ?? resultObject?.ok
  const rawStatus = firstString(payload.status, resultObject?.status, nestedResult?.status)
  const error = payload.error ?? resultObject?.error ?? nestedResult?.error ?? existing?.error

  let executionStatus: ToolExecutionBlock['status'] = existing?.status ?? 'running'
  if (phase === 'result') {
    if (String(rawStatus ?? '').toLowerCase().includes('cancel')) executionStatus = 'cancelled'
    else if (String(rawStatus ?? '').toLowerCase().includes('skip')) executionStatus = 'skipped'
    else if (error || successValue === false) executionStatus = 'failed'
    else executionStatus = 'success'
  }

  const startedAt = existing?.startedAt ?? (phase === 'call' ? timestamp : undefined)
  const finishedAt = phase === 'result' ? timestamp : existing?.finishedAt
  const durationMs = numberValue(payload.durationMs, payload.duration_ms, resultObject?.durationMs, resultObject?.duration_ms)
    ?? (startedAt && finishedAt ? Math.max(0, finishedAt - startedAt) : existing?.durationMs)

  const block: ToolExecutionBlock = {
    toolCallId,
    toolName,
    functionName,
    functionId,
    displayName: functionId && functionId !== toolName ? `${functionId} via ${toolName}` : (functionName || toolName),
    status: executionStatus,
    startedAt,
    finishedAt,
    durationMs,
    args,
    result: phase === 'result' ? result : existing?.result,
    error,
    rawCall: phase === 'call' ? sanitizeValue(message) : existing?.rawCall,
    rawResult: phase === 'result' ? sanitizeValue(message) : existing?.rawResult,
    summary: [],
    trace: extractTrace(payload, resultObject, nestedResult),
  }
  block.summary = buildToolSummary(block, payload, resultObject, nestedResult)
  return block
}

function buildToolSummary(
  block: ToolExecutionBlock,
  payload: Record<string, unknown>,
  resultObject?: Record<string, unknown>,
  nestedResult?: Record<string, unknown>
): string[] {
  const status = firstString(payload.status, resultObject?.status, nestedResult?.status)
  const code = firstString(payload.code, resultObject?.code, nestedResult?.code)
  const message = firstString(payload.message, resultObject?.message, nestedResult?.message)
  const errorType = firstString(payload.errorType, payload.error_type, resultObject?.errorType, resultObject?.error_type)
  const parts: string[] = []
  if (status) parts.push(status)
  if (code) parts.push(`code=${code}`)
  if (message) parts.push(message)
  if (errorType) parts.push(`errorType=${errorType}`)
  if (block.error && typeof block.error === 'string') parts.push(block.error)
  return parts.length > 0 ? parts : [block.status === 'running' ? '执行中' : statusLabel(block.status)]
}

function extractTrace(...sources: Array<Record<string, unknown> | undefined>): Record<string, unknown> {
  const trace: Record<string, unknown> = {}
  for (const source of sources) {
    if (!source) continue
    for (const key of ['traceId', 'requestId', 'contextId', 'taskId', 'runId', 'invocationId', 'toolCallId']) {
      const value = source[key] ?? source[toSnakeCase(key)]
      if (value != null && value !== '') trace[key] = value
    }
  }
  return sanitizeValue(trace) as Record<string, unknown>
}

function toSnakeCase(value: string): string {
  return value.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`)
}

function toolExecutionTitle(block: ToolExecutionBlock): string {
  const duration = block.durationMs != null ? ` ${(block.durationMs / 1000).toFixed(1)}s` : ''
  return `工具调用 ${block.displayName} ${statusLabel(block.status)}${duration}`
}

function statusLabel(status: ToolExecutionBlock['status']): string {
  switch (status) {
    case 'running': return '执行中'
    case 'success': return '成功'
    case 'failed': return '失败'
    case 'skipped': return '已跳过'
    case 'cancelled': return '已取消'
  }
}

function firstString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return undefined
}

function numberValue(...values: unknown[]): number | undefined {
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value)
  }
  return undefined
}

function stateProgressText(content: string): string {
  const lower = content.toLowerCase()
  if (lower.includes('connect')) return '正在连接服务...'
  if (lower.includes('tool') || lower.includes('query') || lower.includes('查询')) return '正在查询数据...'
  if (lower.includes('generat') || lower.includes('answer') || lower.includes('回答')) return '正在生成回答...'
  return '正在处理...'
}

function sanitizeErrorSummary(content: string): string {
  const safe = sanitizeText(content)
  if (/exception|stack|trace|invoke_business_function|submit_skill_result|task_scoped_token/i.test(safe)) {
    return '操作执行失败，请稍后重试或联系管理员。'
  }
  return safe
}

function stripRawJsonArtifact(content: string, actions: NavigatorAction[]): string {
  const parsed = parseMaybeJson(content)
  if (parsed && typeof parsed === 'object') {
    const summary = extractDisplayText(parsed)
    return summary ? sanitizeText(summary) : ''
  }
  return sanitizeText(content)
}

function businessActionIntro(_actions: NavigatorAction[]): string {
  return ''
}

function extractDisplayText(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  const obj = value as Record<string, unknown>
  return firstString(
    obj.answer,
    obj.summary,
    obj.message,
    obj.content,
    obj.text,
    obj.description
  ) ?? ''
}

function extractActions(message: OpenTaskMessage): NavigatorAction[] {
  const candidates = [
    parseMaybeJson(message.content),
    message.metadata,
    message.result,
    normalizedPayload(message),
  ]
  const actions: NavigatorAction[] = []
  for (const candidate of candidates) collectActions(candidate, actions)
  const seen = new Set<string>()
  return actions.filter((action) => {
    const key = `${action.type}:${action.url ?? ''}:${action.label}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function collectActions(value: unknown, actions: NavigatorAction[]) {
  if (!value || typeof value !== 'object') return
  if (Array.isArray(value)) {
    for (const item of value) collectActions(item, actions)
    return
  }
  const obj = value as Record<string, unknown>
  const type = firstString(obj.type, obj.actionType, obj.action, obj.artifactType)
  if (type && isActionType(type)) {
    actions.push({
      id: firstString(obj.id, obj.actionId) ?? `action-${actions.length + 1}`,
      type,
      label: firstString(obj.label, obj.title, obj.name) ?? actionLabel(type),
      url: firstString(obj.url, obj.href, obj.link, obj.path),
      payload: sanitizeValue(obj.payload ?? obj.params ?? obj),
      raw: sanitizeValue(obj),
    })
  }
  for (const key of ['actions', 'artifacts', 'artifact', 'data', 'result']) {
    collectActions(obj[key], actions)
  }
}

function isActionType(type: string): boolean {
  return /OPEN_TMS_PAGE|OPEN_.*PAGE|DOWNLOAD|REPORT|DETAIL|LINK/i.test(type)
}

function actionLabel(type: string): string {
  if (/DOWNLOAD/i.test(type)) return '下载文件'
  if (/REPORT/i.test(type)) return '查看报表'
  if (/DETAIL/i.test(type)) return '打开详情页'
  return '打开页面'
}
