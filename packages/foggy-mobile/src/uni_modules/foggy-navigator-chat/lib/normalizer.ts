import type {
  ExecutionReportDigest,
  FoggyNavigatorAction,
  FoggyNavigatorChatMessage,
  FoggyNavigatorChatMode,
  FoggyNavigatorOpenMessage,
  FoggyNavigatorOpenMessageType,
  FoggyNavigatorSessionMessage,
  FoggyNavigatorTask,
  FoggyNavigatorTaskStatus,
  ToolExecutionBlock,
} from './types'

interface NormalizeOptions {
  mode?: FoggyNavigatorChatMode
  showToolCalls?: boolean
  showToolResults?: boolean
  showRuntimeEvents?: boolean
}

interface IngestOptions {
  includeUserMessages?: boolean
}

interface ExecutionReportFields {
  ref?: string
  digest?: ExecutionReportDigest
}

let localSeq = 0

export function nextNavigatorMessageId(prefix = 'fnc-msg'): string {
  localSeq += 1
  return `${prefix}-${Date.now()}-${localSeq}`
}

export function createFoggyMessageIngestor(options: NormalizeOptions = {}) {
  const display = resolveDisplayOptions(options)
  const messages: FoggyNavigatorChatMessage[] = []
  const seenOpenMessageIds = new Set<string>()
  const toolExecutionMessageIds = new Map<string, string>()
  let pendingActions: FoggyNavigatorAction[] = []

  function getMessages(): FoggyNavigatorChatMessage[] {
    return [...messages]
  }

  function clear() {
    messages.splice(0, messages.length)
    seenOpenMessageIds.clear()
    toolExecutionMessageIds.clear()
    pendingActions = []
  }

  function append(message: FoggyNavigatorChatMessage) {
    messages.push(message)
  }

  function ingestOpenMessages(
    openMessages: FoggyNavigatorOpenMessage[],
    taskId: string,
    status?: FoggyNavigatorTaskStatus,
    ingestOptions: IngestOptions = {},
  ) {
    for (const openMessage of openMessages) {
      const messageTaskId = firstString(openMessage.taskId, taskId) ?? taskId
      const id = openMessageId(messageTaskId, openMessage)
      if (seenOpenMessageIds.has(id)) continue
      seenOpenMessageIds.add(id)

      const content = openMessageContent(openMessage)
      const payload = normalizedPayload(openMessage)
      const actions = extractNavigatorActions(openMessage)
      const timestamp = openMessageTimestamp(openMessage)
      const report = extractExecutionReport(undefined, payload)

      if (openMessage.type === 'USER') {
        if (ingestOptions.includeUserMessages && content) {
          append({
            id,
            role: 'user',
            content: sanitizeNavigatorText(content),
            timestamp,
            taskId: messageTaskId,
            status,
            messageType: openMessage.type,
          })
        }
        continue
      }

      if (openMessage.type === 'TEXT' || openMessage.type === 'RESULT') {
        const mergedActions = mergeActions(pendingActions, actions)
        pendingActions = []
        if (display.mode === 'business' && isRuntimeArtifactContent(content, payload)) continue

        const businessContent = display.mode === 'business'
          ? userFacingMessageContent(stripRawJsonArtifact(content, mergedActions), display.mode)
          : userFacingMessageContent(content, display.mode)
        const visibleContent = businessContent || (mergedActions.length > 0 ? '可继续处理：' : '')
        if (!visibleContent) continue

        append({
          id,
          role: 'assistant',
          content: visibleContent,
          timestamp,
          taskId: messageTaskId,
          status,
          messageType: openMessage.type,
          terminal: openMessage.terminal,
          terminalStatus: openMessage.terminalStatus ?? null,
          actions: mergedActions.length > 0 ? mergedActions : undefined,
          executionReportRef: report.ref,
          executionReportDigest: report.digest,
        })
        continue
      }

      if (openMessage.type === 'ERROR') {
        const errorContent = display.mode === 'debug'
          ? sanitizeNavigatorText(content || '任务执行失败')
          : sanitizeErrorSummary(content || '任务执行失败')
        append({
          id,
          role: 'system',
          content: errorContent,
          timestamp,
          taskId: messageTaskId,
          status,
          messageType: openMessage.type,
          terminal: openMessage.terminal,
          terminalStatus: openMessage.terminalStatus ?? null,
          error: errorContent,
          executionReportRef: report.ref,
          executionReportDigest: report.digest,
        })
        continue
      }

      if (openMessage.type === 'STATE') {
        const subtype = firstString(payload.subtype, payload.stateType, payload.state_type)
        if (actions.length > 0) pendingActions = mergeActions(pendingActions, actions)
        if (display.showRuntimeEvents) {
          append({
            id,
            role: 'system',
            content: stateDisplayContent(content || stringifySafe(payload), display.mode, subtype),
            timestamp,
            taskId: messageTaskId,
            status,
            messageType: openMessage.type,
            terminal: openMessage.terminal,
            terminalStatus: openMessage.terminalStatus ?? null,
            process: true,
            processKind: 'state',
            executionReportRef: report.ref,
            executionReportDigest: report.digest,
          })
        }
        continue
      }

      if (openMessage.type === 'TOOL_CALL' || openMessage.type === 'TOOL_RESULT') {
        if (actions.length > 0) pendingActions = mergeActions(pendingActions, actions)
        const shouldShow = openMessage.type === 'TOOL_CALL' ? display.showToolCalls : display.showToolResults
        if (shouldShow || display.showToolCalls || display.showToolResults) {
          upsertToolExecution(messageTaskId, openMessage, id, status)
        }
        continue
      }

      if (actions.length > 0) {
        pendingActions = mergeActions(pendingActions, actions)
      }
    }
  }

  function ingestInitialTask(task: FoggyNavigatorTask) {
    if (Array.isArray(task.messages) && task.messages.length > 0) {
      ingestOpenMessages(task.messages, task.taskId, task.status)
    }
    if (task.terminal && !task.messages?.some((message) => message.terminal)) {
      appendLegacyTerminalMessage(task)
    }
  }

  function upsertToolExecution(
    taskId: string,
    openMessage: FoggyNavigatorOpenMessage,
    openMessageKey: string,
    status?: FoggyNavigatorTaskStatus,
  ) {
    const block = buildToolExecutionBlock(openMessage)
    const existingMessageId = toolExecutionMessageIds.get(block.toolCallId)
    const existing = existingMessageId
      ? messages.find((message) => message.id === existingMessageId && message.toolExecution)
      : undefined
    if (existing?.toolExecution) {
      existing.toolExecution = mergeToolExecution(existing.toolExecution, block)
      existing.content = toolExecutionTitle(existing.toolExecution)
      existing.timestamp = Math.max(existing.timestamp, openMessageTimestamp(openMessage))
      existing.status = status ?? existing.status
      return
    }

    const messageId = `${openMessageKey}:tool`
    toolExecutionMessageIds.set(block.toolCallId, messageId)
    append({
      id: messageId,
      role: 'system',
      content: toolExecutionTitle(block),
      timestamp: openMessageTimestamp(openMessage),
      taskId,
      status,
      messageType: openMessage.type,
      toolExecution: block,
      executionReportRef: block.executionReportRef,
      executionReportDigest: block.executionReportDigest,
    })
  }

  function appendLegacyTerminalMessage(task: FoggyNavigatorTask) {
    if (task.status === 'COMPLETED' && task.result) {
      append({
        id: nextNavigatorMessageId('fnc-result'),
        role: 'assistant',
        content: userFacingMessageContent(task.result, display.mode),
        timestamp: Date.now(),
        taskId: task.taskId,
        status: task.status,
        terminal: true,
        terminalStatus: task.terminalStatus ?? 'COMPLETED',
      })
      return
    }

    if (task.status === 'FAILED') {
      const errorContent = sanitizeErrorSummary(task.errorMessage || '任务执行失败')
      append({
        id: nextNavigatorMessageId('fnc-error'),
        role: 'system',
        content: errorContent,
        timestamp: Date.now(),
        taskId: task.taskId,
        status: task.status,
        terminal: true,
        terminalStatus: task.terminalStatus ?? 'FAILED',
        error: errorContent,
      })
    }
  }

  return {
    append,
    clear,
    getMessages,
    ingestInitialTask,
    ingestOpenMessages,
  }
}

export function sessionMessagesToOpenMessages(
  messages: FoggyNavigatorSessionMessage[],
): FoggyNavigatorOpenMessage[] {
  return messages.map((message) => ({
    id: message.messageId ?? message.id,
    messageId: message.messageId ?? message.id,
    type: normalizeSessionMessageType(message),
    content: message.content,
    metadata: message.metadata,
    taskId: message.taskId,
    createdAt: message.createdAt,
    timestamp: message.timestamp ?? message.createdAt,
  }))
}

function normalizeSessionMessageType(message: FoggyNavigatorSessionMessage): FoggyNavigatorOpenMessageType {
  const rawType = firstString(message.type, message.metadata?.type)?.toUpperCase()
  if (rawType === 'USER') return 'USER'
  if (rawType === 'TEXT' || rawType === 'TEXT_COMPLETE') return 'TEXT'
  if (rawType === 'RESULT' || rawType === 'TASK_COMPLETED') return 'RESULT'
  if (rawType === 'TOOL_CALL' || rawType === 'TOOL_CALL_START') return 'TOOL_CALL'
  if (rawType === 'TOOL_RESULT' || rawType === 'TOOL_CALL_RESULT') return 'TOOL_RESULT'
  if (rawType === 'STATE' || rawType === 'STATUS') return 'STATE'
  if (rawType === 'ERROR') return 'ERROR'

  const role = message.role?.toUpperCase()
  if (role === 'USER') return 'USER'
  if (role === 'TOOL') return 'TOOL_RESULT'
  if (role === 'SYSTEM') return 'STATE'
  return 'TEXT'
}

function resolveDisplayOptions(options: NormalizeOptions): Required<NormalizeOptions> {
  return {
    mode: options.mode ?? 'business',
    showToolCalls: options.showToolCalls ?? false,
    showToolResults: options.showToolResults ?? true,
    showRuntimeEvents: options.showRuntimeEvents ?? false,
  }
}

function openMessageId(taskId: string, message: FoggyNavigatorOpenMessage): string {
  const id = message.id ?? message.messageId
  if (id) return String(id)
  return `${taskId}:${message.type}:${message.timestamp ?? message.createdAt ?? ''}:${openMessageContent(message)}`
}

function openMessageContent(message: FoggyNavigatorOpenMessage): string {
  const content = message.content
  if (typeof content === 'string') return content
  if (content == null) return ''
  return stringifySafe(content)
}

function openMessageTimestamp(message: FoggyNavigatorOpenMessage): number {
  if (typeof message.timestamp === 'number') return message.timestamp
  const value = message.timestamp ?? message.createdAt
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    if (!Number.isNaN(parsed)) return parsed
  }
  return Date.now()
}

function normalizedPayload(message: FoggyNavigatorOpenMessage): Record<string, unknown> {
  const candidates = [
    message.payload,
    message.metadata,
    message.result,
    message.args,
    parseMaybeJson(message.content),
  ]
  const merged: Record<string, unknown> = {}
  for (const candidate of candidates) {
    const object = objectValue(candidate)
    if (object) Object.assign(merged, sanitizeValue(object))
  }
  return merged
}

export function extractNavigatorActions(message: FoggyNavigatorOpenMessage): FoggyNavigatorAction[] {
  const candidates = [
    message.metadata,
    message.metadata?.args,
    parseMaybeJson(message.content),
    parseMaybeJson(message.result),
    parseMaybeJson(message.args),
    field(parseMaybeJson(message.result), 'structured_output'),
    field(parseMaybeJson(message.result), 'structuredOutput'),
    normalizedPayload(message),
  ]
  const actions: FoggyNavigatorAction[] = []
  for (const candidate of candidates) collectActions(candidate, actions)
  const seen = new Set<string>()
  return actions.filter((action) => {
    const key = `${action.type}:${action.url ?? ''}:${action.label}:${stringifySafe(action.payload)}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

const ACTION_CONTAINER_KEYS = [
  'actions',
  'artifacts',
  'artifact',
  'data',
  'result',
  'structured_output',
  'structuredOutput',
  'output',
  'payload',
  'args',
]

function collectActions(value: unknown, actions: FoggyNavigatorAction[], seen = new WeakSet<object>()) {
  const parsed = parseMaybeJson(value)
  if (!parsed || typeof parsed !== 'object') return
  if (seen.has(parsed)) return
  seen.add(parsed)

  if (Array.isArray(parsed)) {
    for (const item of parsed) collectActions(item, actions, seen)
    return
  }

  const object = parsed as Record<string, unknown>
  const type = firstString(object.type, object.actionType, object.action, object.artifactType)
  if (type && isActionType(type)) {
    actions.push({
      id: firstString(object.id, object.actionId) ?? `action-${actions.length + 1}`,
      type,
      label: firstString(object.label, object.title, object.name) ?? actionLabel(type, object),
      url: firstString(object.url, object.href, object.link, object.path),
      payload: objectValue(sanitizeValue(object.payload ?? object.params ?? object)),
      raw: sanitizeValue(object),
    })
  }

  for (const key of ACTION_CONTAINER_KEYS) {
    collectActions(object[key], actions, seen)
  }
}

function isActionType(type: string): boolean {
  return /OPEN_TMS_PAGE|OPEN_.*PAGE|DOWNLOAD|REPORT|DETAIL|LINK/i.test(type)
}

function actionLabel(type: string, action?: Record<string, unknown>): string {
  const page = action ? pageLabel(action) : undefined
  if (/OPEN_TMS_PAGE|OPEN_.*PAGE/i.test(type) && page) return `打开${page}`
  if (/DOWNLOAD/i.test(type)) return '下载文件'
  if (/REPORT/i.test(type)) return '查看报表'
  if (/DETAIL/i.test(type)) return '打开详情页'
  return '打开页面'
}

function pageLabel(action: Record<string, unknown>): string | undefined {
  const value = firstString(
    action.pageLabel,
    action.pageTitle,
    action.routeLabel,
    action.routeTitle,
    action.pageName,
  )
  if (!value) return undefined
  return value.replace(/^[/#]+/, '') || undefined
}

function mergeActions(...groups: FoggyNavigatorAction[][]): FoggyNavigatorAction[] {
  const output: FoggyNavigatorAction[] = []
  const seen = new Set<string>()
  for (const group of groups) {
    for (const action of group) {
      const key = `${action.type}:${action.url ?? ''}:${action.label}:${stringifySafe(action.payload)}`
      if (seen.has(key)) continue
      seen.add(key)
      output.push(action)
    }
  }
  return output
}

function buildToolExecutionBlock(message: FoggyNavigatorOpenMessage): ToolExecutionBlock {
  const payload = normalizedPayload(message)
  const resultObject = objectValue(parseMaybeJson(payload.result))
  const nestedResult = objectValue(resultObject?.result)
  const timestamp = openMessageTimestamp(message)
  const phase = message.type === 'TOOL_RESULT' ? 'result' : 'call'
  const toolCallId = firstString(
    payload.toolCallId,
    payload.tool_call_id,
    payload.id,
    payload.callId,
    message.messageId,
    message.id,
  ) ?? `${firstString(payload.toolName, payload.tool_name, payload.name) ?? 'tool'}:${openMessageContent(message)}`
  const toolName = firstString(
    payload.toolName,
    payload.tool_name,
    payload.name,
    payload.tool,
    phase === 'call' ? message.content : undefined,
  ) ?? 'unknown_tool'
  const functionName = firstString(payload.functionName, payload.function_name)
  const argsObject = objectValue(payload.args)
  const functionId = firstString(
    payload.functionId,
    payload.function_id,
    argsObject?.functionId,
    argsObject?.function_id,
    resultObject?.functionId,
    resultObject?.function_id,
    nestedResult?.functionId,
    nestedResult?.function_id,
  )
  const rawStatus = firstString(payload.status, resultObject?.status, nestedResult?.status)
  const error = payload.error ?? resultObject?.error ?? nestedResult?.error
  const success = payload.success ?? resultObject?.success ?? nestedResult?.success
  const report = extractExecutionReport(undefined, payload, resultObject, nestedResult)

  let status: ToolExecutionBlock['status'] = 'running'
  if (phase === 'result') {
    const lower = (rawStatus ?? '').toLowerCase()
    if (lower.includes('cancel')) status = 'cancelled'
    else if (lower.includes('skip')) status = 'skipped'
    else if (error || success === false) status = 'failed'
    else status = 'success'
  }

  const durationMs = numberValue(
    payload.durationMs,
    payload.duration_ms,
    resultObject?.durationMs,
    resultObject?.duration_ms,
  )

  const block: ToolExecutionBlock = {
    toolCallId,
    toolName,
    functionName,
    functionId,
    displayName: toolDisplayName({ toolName, functionName, functionId, status }),
    status,
    startedAt: phase === 'call' ? timestamp : undefined,
    finishedAt: phase === 'result' ? timestamp : undefined,
    durationMs,
    args: payload.args ?? payload.arguments ?? payload.input,
    result: phase === 'result' ? payload.result ?? message.result : undefined,
    error,
    summary: [],
    executionReportRef: report.ref,
    executionReportDigest: report.digest,
  }
  block.summary = buildToolSummary(block, payload, resultObject, nestedResult)
  return block
}

function mergeToolExecution(previous: ToolExecutionBlock, next: ToolExecutionBlock): ToolExecutionBlock {
  const merged: ToolExecutionBlock = {
    ...previous,
    ...next,
    startedAt: previous.startedAt ?? next.startedAt,
    finishedAt: next.finishedAt ?? previous.finishedAt,
    args: next.args ?? previous.args,
    result: next.result ?? previous.result,
    error: next.error ?? previous.error,
    executionReportRef: next.executionReportRef ?? previous.executionReportRef,
    executionReportDigest: mergeExecutionReportDigest(previous.executionReportDigest, next.executionReportDigest),
  }
  merged.summary = buildToolSummary(merged)
  return merged
}

function buildToolSummary(
  block: ToolExecutionBlock,
  ...sources: Array<Record<string, unknown> | undefined>
): string[] {
  const parts: string[] = []
  for (const source of sources) {
    if (!source) continue
    const status = firstString(source.status)
    const code = firstString(source.code)
    const message = firstString(source.message)
    const errorType = firstString(source.errorType, source.error_type)
    if (status) parts.push(status)
    if (code) parts.push(`code=${code}`)
    if (message) parts.push(message)
    if (errorType) parts.push(`errorType=${errorType}`)
  }
  if (block.error && typeof block.error === 'string') parts.push(sanitizeErrorSummary(block.error))
  if (parts.length > 0) return parts
  return [block.status === 'running' ? '执行中' : statusLabel(block.status)]
}

function extractExecutionReport(
  existing: ExecutionReportFields | undefined,
  ...sources: Array<Record<string, unknown> | undefined>
): ExecutionReportFields {
  let ref = existing?.ref
  let digest = existing?.digest

  for (const source of sources) {
    if (!source) continue
    const directRef = firstString(
      source.executionReportRef,
      source.execution_report_ref,
      source.reportRef,
      source.report_ref,
    )
    if (directRef) ref = directRef

    const directDigest = normalizeExecutionReportDigest(
      source.executionReportDigest
        ?? source.execution_report_digest
        ?? source.reportDigest
        ?? source.report_digest
        ?? source.frameExecutionReportDigest
        ?? source.frame_execution_report_digest,
    )
    if (directDigest) digest = mergeExecutionReportDigest(digest, directDigest)

    const nestedReport = objectValue(source.executionReport ?? source.execution_report ?? source.report)
    if (nestedReport && nestedReport !== source) {
      const nested = extractExecutionReport({ ref, digest }, nestedReport)
      ref = nested.ref
      digest = nested.digest
    }
  }

  const digestRef = firstString(digest?.reportRef, digest?.executionReportRef, digest?.execution_report_ref)
  if (!ref && digestRef) ref = digestRef
  if (ref && digest?.reportRef !== ref) {
    digest = mergeExecutionReportDigest(digest, { reportRef: ref })
  }
  return { ref, digest }
}

function normalizeExecutionReportDigest(value: unknown): ExecutionReportDigest | undefined {
  const parsed = parseMaybeJson(value)
  if (typeof parsed === 'string' && parsed.trim()) return { summary: parsed.trim() }
  const object = objectValue(parsed)
  if (!object) return undefined
  const safe = sanitizeValue(object) as Record<string, unknown>
  const digest: ExecutionReportDigest = { ...safe }
  const status = firstString(safe.status, safe.state)
  const summary = firstString(safe.summary, safe.resultSummary, safe.result_summary, safe.text, safe.message)
  const error = firstString(safe.error, safe.errorMessage, safe.error_message)
  const reportRef = firstString(safe.reportRef, safe.report_ref, safe.executionReportRef, safe.execution_report_ref)
  if (status) digest.status = status
  if (summary) digest.summary = summary
  if (error) digest.error = error
  if (reportRef) digest.reportRef = reportRef
  return digest
}

function mergeExecutionReportDigest(
  previous: ExecutionReportDigest | undefined,
  next: ExecutionReportDigest | undefined,
): ExecutionReportDigest | undefined {
  if (!previous) return next
  if (!next) return previous
  return { ...previous, ...next }
}

function toolExecutionTitle(block: ToolExecutionBlock): string {
  const duration = block.durationMs != null ? ` ${(block.durationMs / 1000).toFixed(1)}s` : ''
  return `${block.displayName} ${statusLabel(block.status)}${duration}`
}

function toolDisplayName(input: {
  toolName: string
  functionName?: string
  functionId?: string
  status: ToolExecutionBlock['status']
}): string {
  if (input.toolName === 'submit_skill_result') {
    if (input.status === 'running') return '正在准备结果'
    if (input.status === 'success') return '处理完成'
    if (input.status === 'failed') return '结果准备失败'
    return '准备结果'
  }
  if (input.toolName === 'invoke_business_function') {
    if (input.status === 'running') return '正在调用业务能力'
    if (input.status === 'success') return '业务能力调用完成'
    if (input.status === 'failed') return '业务能力调用失败'
  }
  if (input.functionId && input.functionId !== input.toolName) return input.functionId
  return input.functionName || input.toolName
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

function stateDisplayContent(content: string, mode: FoggyNavigatorChatMode, subtype?: string): string {
  if (mode === 'debug') return sanitizeNavigatorText(content)
  if (subtype === 'task_progress') return '任务处理中'
  const connectionText = userFacingConnectionText(content)
  if (connectionText) return connectionText
  return sanitizeNavigatorText(content)
}

function userFacingMessageContent(content: string, mode: FoggyNavigatorChatMode): string {
  if (mode === 'debug') return sanitizeNavigatorText(content)
  const connectionText = userFacingConnectionText(content)
  if (connectionText) return connectionText
  return sanitizeNavigatorText(content)
}

function userFacingConnectionText(content: string): string | null {
  const lower = content.toLowerCase()
  if (!lower.includes('langgraph') || !lower.includes('worker')) return null
  if (lower.includes('connect')) return '正在连接至道同。'
  return null
}

function isRuntimeArtifactContent(content: string, payload: Record<string, unknown>): boolean {
  const trimmed = content.trim()
  if (!trimmed) return false
  const subtype = firstString(payload.subtype, payload.stateType, payload.state_type)
  if (subtype === 'skill_frame_open' || subtype === 'skill_frame_close') return true
  const toolName = firstString(payload.toolName, payload.tool_name, payload.name)
  if (toolName && isInternalToolName(toolName)) return true
  return isRuntimeArtifactText(trimmed)
}

function isRuntimeArtifactText(content: string): boolean {
  if (/^(Opening|Reusing|Resuming|Closing) frame for skill:\s*[\w.-]+$/i.test(content)) return true
  if (/^Frame closed:\s*[\w.-]+$/i.test(content)) return true
  if (/^(Calling tool|Tool)\s+(invoke_business_skill|invoke_business_function|submit_skill_result)\b/i.test(content)) return true
  return isInternalToolName(content)
}

function isInternalToolName(toolName: string): boolean {
  return toolName === 'invoke_business_skill'
    || toolName === 'invoke_business_function'
    || toolName === 'submit_skill_result'
}

function stripRawJsonArtifact(content: string, actions: FoggyNavigatorAction[]): string {
  const parsed = parseMaybeJson(content)
  if (parsed && typeof parsed === 'object') {
    const summary = extractDisplayText(parsed)
    return summary ? sanitizeNavigatorText(summary) : (actions.length > 0 ? '' : sanitizeNavigatorText(content))
  }
  return sanitizeNavigatorText(content)
}

function extractDisplayText(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  const object = value as Record<string, unknown>
  return firstString(object.answer, object.summary, object.message, object.content, object.text, object.description) ?? ''
}

export function sanitizeNavigatorText(content: string): string {
  return content.replace(/\r\n/g, '\n')
}

function sanitizeErrorSummary(content: string): string {
  const safe = sanitizeNavigatorText(content)
  if (/exception|stack|trace|invoke_business_function|submit_skill_result|task_scoped_token|adapterConfigJson|manifestJson|client_app_secret|runtime credential|authorization|worker gateway|gateway url|secret/i.test(safe)) {
    return '操作执行失败，请稍后重试或联系管理员。'
  }
  return safe
}

function sanitizeValue(value: unknown, seen = new WeakSet<object>()): unknown {
  if (Array.isArray(value)) return value.map((item) => sanitizeValue(item, seen))
  if (!value || typeof value !== 'object') return value
  if (seen.has(value)) return '[Circular]'
  seen.add(value)

  const output: Record<string, unknown> = {}
  for (const [key, nestedValue] of Object.entries(value as Record<string, unknown>)) {
    if (isSensitiveKey(key)) {
      output[key] = '[redacted]'
    } else {
      output[key] = sanitizeValue(nestedValue, seen)
    }
  }
  return output
}

function isSensitiveKey(key: string): boolean {
  return /task_scoped_token|token|secret|credential|adapterConfigJson|manifestJson|authorization|authHeader|gatewayUrl|workerGateway|worker_gateway/i.test(key)
}

export function renderBasicMarkdown(content: string): string {
  if (!content) return ''
  const escaped = escapeHtml(content)
  const withCode = escaped.replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
  const withInlineCode = withCode.replace(/`([^`]+)`/g, '<code>$1</code>')
  const withBold = withInlineCode.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  return withBold
    .split(/\n{2,}/)
    .map((paragraph) => `<p>${paragraph.replace(/\n/g, '<br/>')}</p>`)
    .join('')
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function parseMaybeJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const trimmed = value.trim()
  if (!trimmed) return value
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return value
  try {
    return JSON.parse(trimmed)
  } catch {
    return value
  }
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  const parsed = parseMaybeJson(value)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    return parsed as Record<string, unknown>
  }
  return undefined
}

function field(value: unknown, key: string): unknown {
  const object = objectValue(value)
  return object?.[key]
}

function stringifySafe(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
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
