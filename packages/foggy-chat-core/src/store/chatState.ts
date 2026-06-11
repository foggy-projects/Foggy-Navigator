import { ref, computed, type ComputedRef, type Ref } from 'vue'
import { AipMessageType } from '../types/aip'
import type {
  AipMessage,
  TextPayload,
  ToolCallStartPayload,
  ToolCallResultPayload,
  ToolCallErrorPayload,
  ThinkingPayload,
  StateSyncPayload,
  ErrorPayload,
  TaskCompletedPayload,
  ConfirmationRequestPayload,
  ConfirmationResponsePayload,
} from '../types/aip'
import type {
  ChatMessage,
  ConnectionStatus,
  ExecutionReportDigest,
  NavigatorUiAction,
  NavigatorUiArtifact,
  NavigatorUiArtifactKind,
  NavigatorUiArtifactOpenMode,
} from '../types/chat'

let _msgSeq = 0
function nextId(): string {
  return `msg-${Date.now()}-${++_msgSeq}`
}

export interface ChatState {
  messages: Ref<ChatMessage[]>
  sortedMessages: ComputedRef<ChatMessage[]>
  connectionStatus: Ref<ConnectionStatus>
  conversationStatus: Ref<string>
  isThinking: Ref<boolean>
  processAipMessage: (aip: AipMessage) => void
  addUserMessage: (content: string, sessionId?: string, images?: Array<{ name: string; url: string }>) => void
  setConnectionStatus: (status: ConnectionStatus) => void
  clearMessages: () => void
  resolvePermission: (permissionId: string, status: 'approved' | 'denied') => void
  resolveSkillApproval: (taskId: string, decision: 'approved' | 'rejected') => void
}

function formatArguments(args: unknown): string {
  if (!args) return ''
  if (typeof args === 'string') return args
  try {
    const str = JSON.stringify(args)
    // Treat empty object as no arguments
    return str === '{}' ? '' : str
  } catch {
    return String(args)
  }
}

interface ExecutionReportFields {
  ref?: string
  digest?: ExecutionReportDigest
}

const EXECUTION_REPORT_CONTAINER_KEYS = [
  'executionReport',
  'execution_report',
  'report',
  'result',
  'data',
  'payload',
]

function extractExecutionReport(
  existing: ExecutionReportFields | undefined,
  ...sources: unknown[]
): ExecutionReportFields {
  let ref = existing?.ref
  let digest = existing?.digest
  const seen = new WeakSet<object>()

  function visit(value: unknown) {
    const source = objectValue(parseMaybeJson(value))
    if (!source || seen.has(source)) return
    seen.add(source)

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

    for (const key of EXECUTION_REPORT_CONTAINER_KEYS) {
      visit(source[key])
    }
  }

  for (const source of sources) visit(source)

  const digestRef = firstString(
    digest?.reportRef,
    digest?.report_ref,
    digest?.executionReportRef,
    digest?.execution_report_ref,
  )
  if (!ref && digestRef) ref = digestRef
  if (ref && digest?.reportRef !== ref) {
    digest = mergeExecutionReportDigest(digest, { reportRef: ref })
  }
  return { ref, digest }
}

function normalizeExecutionReportDigest(value: unknown): ExecutionReportDigest | undefined {
  const parsed = parseMaybeJson(value)
  if (typeof parsed === 'string' && parsed.trim()) {
    return { summary: parsed.trim() }
  }
  const source = objectValue(parsed)
  if (!source) return undefined

  const digest: ExecutionReportDigest = { ...source }
  const status = firstString(source.status, source.state)
  const summary = firstString(source.summary, source.resultSummary, source.result_summary, source.message, source.text)
  const error = firstNullableString(source.error, source.errorMessage, source.error_message)
  const reportRef = firstString(source.reportRef, source.report_ref, source.executionReportRef, source.execution_report_ref)
  const taskId = firstString(source.taskId, source.task_id)
  const frameId = firstString(source.frameId, source.frame_id)
  const skillId = firstString(source.skillId, source.skill_id)
  const frameKind = firstString(source.frameKind, source.frame_kind)
  const generatedAt = firstString(source.generatedAt, source.generated_at)

  if (status) digest.status = status
  if (summary) digest.summary = summary
  if (error !== undefined) digest.error = error
  if (reportRef) digest.reportRef = reportRef
  if (taskId) digest.taskId = taskId
  if (frameId) digest.frameId = frameId
  if (skillId) digest.skillId = skillId
  if (frameKind) digest.frameKind = frameKind
  if (generatedAt) digest.generatedAt = generatedAt
  return digest
}

function executionReportProps(report: ExecutionReportFields): Partial<ChatMessage> {
  return {
    ...(report.ref ? { executionReportRef: report.ref } : {}),
    ...(report.digest ? { executionReportDigest: report.digest } : {}),
  }
}

function applyExecutionReport(target: ChatMessage, report: ExecutionReportFields) {
  if (report.ref) target.executionReportRef = report.ref
  if (report.digest) {
    target.executionReportDigest = mergeExecutionReportDigest(target.executionReportDigest, report.digest)
  }
  if (target.executionReportRef && target.executionReportDigest?.reportRef !== target.executionReportRef) {
    target.executionReportDigest = mergeExecutionReportDigest(target.executionReportDigest, {
      reportRef: target.executionReportRef,
    })
  }
}

function mergeExecutionReportDigest(
  previous: ExecutionReportDigest | undefined,
  next: ExecutionReportDigest,
): ExecutionReportDigest {
  return { ...(previous ?? {}), ...next }
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

function objectValue(value: unknown): Record<string, unknown> | undefined {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return undefined
}

function firstString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return undefined
}

function firstNullableString(...values: unknown[]): string | null | undefined {
  for (const value of values) {
    if (value === null) return null
    const text = firstString(value)
    if (text) return text
  }
  return undefined
}

const UI_ACTION_TYPES = new Set(['OPEN_ARTIFACT', 'OPEN_TMS_PAGE'])
const ARTIFACT_KINDS = new Set<NavigatorUiArtifactKind>(['route', 'iframe', 'link'])
const ARTIFACT_OPEN_MODES = new Set<NavigatorUiArtifactOpenMode>([
  'side_panel',
  'dialog',
  'new_tab',
  'current_page',
])
const UI_ACTION_CONTAINER_KEYS = [
  'action',
  'actions',
  'uiAction',
  'ui_action',
  'uiActions',
  'ui_actions',
  'structuredOutput',
  'structured_output',
  'args',
  'result',
  'data',
  'payload',
  'output',
]

function extractUiActions(...sources: unknown[]): NavigatorUiAction[] {
  const actions: NavigatorUiAction[] = []
  const seen = new WeakSet<object>()
  const seenKeys = new Set<string>()

  function visit(value: unknown) {
    const parsed = parseMaybeJson(value)
    if (Array.isArray(parsed)) {
      for (const item of parsed) visit(item)
      return
    }
    const source = objectValue(parsed)
    if (!source || seen.has(source)) return
    seen.add(source)

    const action = normalizeUiAction(source)
    if (action) {
      const key = action.artifact.id
        || `${action.type}:${action.artifact.kind}:${action.artifact.uri || action.artifact.routeName || action.artifact.routePath || action.label || ''}`
      if (!seenKeys.has(key)) {
        seenKeys.add(key)
        actions.push(action)
      }
    }

    for (const key of UI_ACTION_CONTAINER_KEYS) {
      visit(source[key])
    }
  }

  for (const source of sources) visit(source)
  return actions
}

function normalizeUiAction(source: Record<string, unknown>): NavigatorUiAction | undefined {
  const type = firstString(source.type, source.action)
  const hasArtifact = objectValue(source.artifact) != null
  if (!type && !hasArtifact) return undefined
  const actionType = type || 'OPEN_ARTIFACT'
  if (!UI_ACTION_TYPES.has(actionType) && !hasArtifact) return undefined

  const artifactSource = objectValue(source.artifact)
  const artifact = normalizeArtifact(artifactSource ?? source, actionType)
  if (!artifact) return undefined

  return {
    type: hasArtifact ? actionType : 'OPEN_ARTIFACT',
    label: firstString(source.label, artifact.title, source.title),
    artifact,
    context: objectValue(source.context),
    raw: source,
  }
}

function normalizeArtifact(
  source: Record<string, unknown>,
  actionType: string,
): NavigatorUiArtifact | undefined {
  const kindValue = firstString(source.kind)
  const fallbackKind = actionType === 'OPEN_TMS_PAGE' ? 'route' : undefined
  const kind = ARTIFACT_KINDS.has(kindValue as NavigatorUiArtifactKind)
    ? kindValue as NavigatorUiArtifactKind
    : fallbackKind
  if (!kind) return undefined

  const openModeValue = firstString(source.openMode, source.open_mode)
  const openMode = ARTIFACT_OPEN_MODES.has(openModeValue as NavigatorUiArtifactOpenMode)
    ? openModeValue as NavigatorUiArtifactOpenMode
    : undefined
  const uri = firstString(source.uri, source.url, source.href, source.path, source.fallbackUrl, source.fallback_url)
  const artifact: NavigatorUiArtifact = {
    ...source,
    kind,
    ...(firstString(source.id, source.artifactId, source.artifact_id) ? {
      id: firstString(source.id, source.artifactId, source.artifact_id),
    } : {}),
    ...(firstString(source.title, source.label, source.name) ? {
      title: firstString(source.title, source.label, source.name),
    } : {}),
    ...(uri ? { uri } : {}),
    ...(firstString(source.routeName, source.route_name) ? {
      routeName: firstString(source.routeName, source.route_name),
    } : {}),
    ...(firstString(source.routePath, source.route_path) ? {
      routePath: firstString(source.routePath, source.route_path),
    } : {}),
    ...(openMode ? { openMode } : {}),
    ...(firstString(source.fallbackUrl, source.fallback_url) ? {
      fallbackUrl: firstString(source.fallbackUrl, source.fallback_url),
    } : {}),
    ...(firstString(source.sandbox) ? { sandbox: firstString(source.sandbox) } : {}),
  }

  if (artifact.kind === 'route' && !artifact.routeName && !artifact.routePath && !artifact.uri) return undefined
  if ((artifact.kind === 'iframe' || artifact.kind === 'link') && !artifact.uri && !artifact.fallbackUrl) return undefined
  return artifact
}

function uiActionProps(actions: NavigatorUiAction[]): Partial<ChatMessage> {
  return actions.length ? { uiActions: actions } : {}
}

function mergeUiActions(target: ChatMessage, actions: NavigatorUiAction[]) {
  if (!actions.length) return
  const existing = target.uiActions ?? []
  const keys = new Set(existing.map(actionKey))
  for (const action of actions) {
    const key = actionKey(action)
    if (!keys.has(key)) {
      existing.push(action)
      keys.add(key)
    }
  }
  target.uiActions = existing
}

function actionKey(action: NavigatorUiAction): string {
  return action.artifact.id
    || `${action.type}:${action.artifact.kind}:${action.artifact.uri || action.artifact.routeName || action.artifact.routePath || action.label || ''}`
}

export function createChatState(): ChatState {
  const messages = ref<ChatMessage[]>([])
  const connectionStatus = ref<ConnectionStatus>('disconnected')
  const conversationStatus = ref<string>('')
  const isThinking = ref(false)

  const sortedMessages = computed(() =>
    [...messages.value].sort((a, b) => a.timestamp - b.timestamp),
  )

  function removeWaitingHint() {
    const idx = messages.value.findIndex(
      (m) => m.type === AipMessageType.STATE_SYNC && (m.raw as Record<string, unknown>)?.subtype === 'waiting',
    )
    if (idx >= 0) messages.value.splice(idx, 1)
  }

  function processAipMessage(aip: AipMessage) {
    // Clear waiting hint when a real event arrives (except STATE_SYNC/waiting itself)
    const raw = aip.payload as Record<string, unknown> | undefined
    const report = extractExecutionReport(undefined, raw)
    const uiActions = extractUiActions(raw)
    if (!(aip.type === AipMessageType.STATE_SYNC && raw?.subtype === 'waiting')) {
      removeWaitingHint()
    }

    switch (aip.type) {
      case AipMessageType.TEXT_CHUNK: {
        const p = aip.payload as TextPayload
        isThinking.value = false
        const lastChunk = [...messages.value].reverse().find(
          (m) => m.type === AipMessageType.TEXT_CHUNK && m.sender === 'assistant',
        )
        if (lastChunk) {
          lastChunk.content += p.content
          applyExecutionReport(lastChunk, report)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'assistant',
            content: p.content,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(uiActions),
          })
        }
        break
      }
      case AipMessageType.TEXT_COMPLETE: {
        const p = aip.payload as TextPayload
        isThinking.value = false
        // Skip result events — their text content was already emitted by assistant_text.
        // The result event still carries metadata (cost, tokens) handled by useTaskPane.
        if (raw?.isResult === true) {
          appendExecutionReportMessage(aip, report)
          break
        }
        const lastChunk = [...messages.value].reverse().find(
          (m) => m.type === AipMessageType.TEXT_CHUNK && m.sender === 'assistant',
        )
        if (lastChunk) {
          lastChunk.content = p.content
          lastChunk.type = AipMessageType.TEXT_COMPLETE
          applyExecutionReport(lastChunk, report)
        } else {
          if (!p.content && appendExecutionReportMessage(aip, report)) break
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'assistant',
            content: p.content,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(extractUiActions(raw, p.content)),
          })
        }
        break
      }
      case AipMessageType.THINKING: {
        const p = aip.payload as ThinkingPayload
        isThinking.value = true
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'assistant',
          content: '',
          thought: p.thought,
          timestamp: aip.timestamp,
        })
        break
      }
      case AipMessageType.TOOL_CALL_START: {
        const p = aip.payload as ToolCallStartPayload
        isThinking.value = false
        // Map backend fields: arguments → command fallback
        const command = p.command ?? p.path ?? formatArguments(p.arguments)
        const existingResult = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_RESULT,
        )
        if (existingResult) {
          existingResult.type = AipMessageType.TOOL_CALL_START
          existingResult.content = command
          existingResult.thought = p.thought
          existingResult.toolName = p.toolName
          existingResult.timestamp = Math.min(existingResult.timestamp, aip.timestamp)
          applyExecutionReport(existingResult, report)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: command,
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            thought: p.thought,
            raw: aip.payload,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
          })
        }
        break
      }
      case AipMessageType.TOOL_CALL_RESULT: {
        const p = aip.payload as ToolCallResultPayload
        // Map backend fields: data → output fallback
        const output = p.output ?? (typeof p.data === 'string' ? p.data : p.data ? formatArguments(p.data) : '')
        const success = p.success !== false
        const toolUiActions = extractUiActions(raw, p.output, p.data)
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.toolOutput = output
          existing.toolSuccess = success
          applyExecutionReport(existing, report)
          mergeUiActions(existing, toolUiActions)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            toolOutput: output,
            toolSuccess: success,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(toolUiActions),
          })
        }
        break
      }
      case AipMessageType.TOOL_CALL_ERROR: {
        const p = aip.payload as ToolCallErrorPayload
        const existing = messages.value.find(
          (m) => m.toolCallId === p.toolCallId && m.type === AipMessageType.TOOL_CALL_START,
        )
        if (existing) {
          existing.error = p.errorMessage
          applyExecutionReport(existing, report)
        } else {
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'tool',
            content: '',
            toolCallId: p.toolCallId,
            toolName: p.toolName,
            error: p.errorMessage,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
          })
        }
        break
      }
      case AipMessageType.STATE_SYNC: {
        const p = aip.payload as StateSyncPayload
        const raw = aip.payload as unknown as Record<string, unknown>
        const subtype = raw.subtype as string | undefined
        if (subtype === 'auto_compact' || subtype === 'context_compression') {
          // Context compression hint — render as lightweight divider
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Context compressed',
            raw: { subtype },
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(uiActions),
          })
          break
        }
        if (subtype === 'reconnected') {
          const taskId = raw.taskId as string | undefined
          const alreadyRendered = messages.value.some((m) => {
            if (m.type !== AipMessageType.STATE_SYNC) return false
            const msgRaw = m.raw as Record<string, unknown> | undefined
            if (msgRaw?.subtype !== 'reconnected') return false
            if (!taskId) return true
            return msgRaw.taskId === taskId
          })
          if (alreadyRendered) break
          // Stream reconnected hint — lightweight divider (same as compression hint)
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Task stream reconnected',
            raw: taskId ? { subtype, taskId } : { subtype },
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(uiActions),
          })
          break
        }
        if (subtype === 'sync_checkpoint' || subtype === 'reconnected') {
          // Internal sync events — consume silently, do not render
          break
        }
        if (subtype === 'approval_required' || subtype === 'skill_approval_request') {
          // Skill/FSScript approval request from langgraph-biz-worker.
          messages.value.push({
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content: (raw.content as string) || 'Skill requires approval',
            approvalStatus: 'pending',
            raw,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
          })
          break
        }
        if (subtype === 'waiting') {
          // "Waiting for response" hint — replace previous waiting msg to avoid stacking
          const existingIdx = messages.value.findIndex(
            (m) => m.type === AipMessageType.STATE_SYNC && (m.raw as Record<string, unknown>)?.subtype === 'waiting',
          )
          const elapsed = (raw.elapsedSeconds as number) || 0
          const timeout = (raw.timeoutSeconds as number) || 600
          const mins = Math.floor(elapsed / 60)
          const content = mins > 0
            ? `Waiting for response... (${mins}min elapsed)`
            : `Waiting for response...`
          const msg: ChatMessage = {
            id: aip.messageId,
            type: aip.type,
            sender: 'system',
            content,
            raw: { subtype, elapsedSeconds: elapsed, timeoutSeconds: timeout },
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(uiActions),
          }
          if (existingIdx >= 0) {
            messages.value[existingIdx] = msg
          } else {
            messages.value.push(msg)
          }
          break
        }
        // Default: use status if available, otherwise fall back to content field
        const statusText = p.status || (raw.content as string)
        if (!statusText) {
          appendExecutionReportMessage(aip, report)
          break
        }
        if (conversationStatus.value === statusText) break
        conversationStatus.value = statusText
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: p.status ? `状态变更: ${p.status}` : statusText,
          timestamp: aip.timestamp,
          ...executionReportProps(report),
          ...uiActionProps(uiActions),
        })
        break
      }
      case AipMessageType.ERROR: {
        const p = aip.payload as ErrorPayload
        // Backend may store error text as 'content' (WorkerStreamRelay) or 'error' (ErrorPayload)
        const raw = aip.payload as Record<string, unknown>
        const errorText = p.error || (raw?.content as string) || ''
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: '',
          error: errorText,
          reconnectable: raw?.reconnectable === true,
          raw: { taskId: raw?.taskId, reconnectable: raw?.reconnectable },
          timestamp: aip.timestamp,
          ...executionReportProps(report),
        })
        break
      }
      case AipMessageType.TASK_COMPLETED: {
        const p = aip.payload as TaskCompletedPayload
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: p.resultSummary || '',
          raw: p,
          timestamp: aip.timestamp,
          ...executionReportProps(report),
          ...uiActionProps(uiActions),
        })
        break
      }
      case AipMessageType.SESSION_START: {
        const p = aip.payload as TextPayload
        const content = p?.content
        if (content) {
          messages.value.push({
            id: aip.messageId,
            type: AipMessageType.SESSION_START,
            sender: 'assistant',
            content,
            timestamp: aip.timestamp,
            ...executionReportProps(report),
            ...uiActionProps(uiActions),
          })
        } else {
          appendExecutionReportMessage(aip, report)
        }
        break
      }
      case AipMessageType.CHECKPOINT: {
        // Checkpoint events are stored silently — not rendered as chat messages
        break
      }
      case AipMessageType.CONFIRMATION_REQUEST: {
        const p = aip.payload as ConfirmationRequestPayload
        messages.value.push({
          id: aip.messageId,
          type: aip.type,
          sender: 'system',
          content: '',
          toolName: p.toolName,
          permissionId: p.permissionId,
          permissionStatus: 'pending',
          questions: p.questions,
          planReview: p.planReview,
          allowedPrompts: p.allowedPrompts,
          plan: p.plan,
          raw: p,
          timestamp: aip.timestamp,
          ...executionReportProps(report),
        })
        break
      }
      case AipMessageType.CONFIRMATION_RESPONSE: {
        const p = aip.payload as ConfirmationResponsePayload
        // Find the matching CONFIRMATION_REQUEST and resolve it
        const target = messages.value.find(
          (m) => m.permissionId === p.permissionId && m.type === AipMessageType.CONFIRMATION_REQUEST,
        )
        if (target) {
          target.permissionStatus = p.decision === 'allow' ? 'approved' : 'denied'
          // Restore answered values from persisted answers
          if (p.answers && target.questions) {
            const vals: Record<number, string> = {}
            target.questions.forEach((q, qi) => {
              if (p.answers![q.question]) {
                vals[qi] = p.answers![q.question]
              }
            })
            target.answeredValues = vals
          }
        }
        break
      }
    }
  }

  function appendExecutionReportMessage(aip: AipMessage, report: ExecutionReportFields): boolean {
    if (!report.ref) return false
    if (messages.value.some((message) => message.executionReportRef === report.ref)) return false
    messages.value.push({
      id: aip.messageId,
      type: AipMessageType.STATE_SYNC,
      sender: 'system',
      content: '执行报告',
      raw: aip.payload,
      timestamp: aip.timestamp,
      ...executionReportProps(report),
    })
    return true
  }

  function addUserMessage(content: string, _sessionId?: string, images?: Array<{ name: string; url: string }>) {
    const msg: ChatMessage = {
      id: nextId(),
      type: AipMessageType.TEXT_COMPLETE,
      sender: 'user',
      content,
      timestamp: Date.now(),
    }
    if (images && images.length > 0) {
      msg.images = images
    }
    messages.value.push(msg)
  }

  function setConnectionStatus(status: ConnectionStatus) {
    connectionStatus.value = status
  }

  function resolvePermission(permissionId: string, status: 'approved' | 'denied') {
    const msg = messages.value.find(
      (m) => m.permissionId === permissionId && m.type === AipMessageType.CONFIRMATION_REQUEST,
    )
    if (msg) {
      msg.permissionStatus = status
    }
  }

  function resolveSkillApproval(taskId: string, decision: 'approved' | 'rejected') {
    const msg = messages.value.find(
      (m) =>
        m.approvalStatus === 'pending' &&
        (m.raw as Record<string, unknown>)?.taskId === taskId,
    )
    if (msg) {
      msg.approvalStatus = decision
    }
  }

  function clearMessages() {
    messages.value = []
    isThinking.value = false
    conversationStatus.value = ''
  }

  return {
    messages,
    sortedMessages,
    connectionStatus,
    conversationStatus,
    isThinking,
    processAipMessage,
    addUserMessage,
    setConnectionStatus,
    clearMessages,
    resolvePermission,
    resolveSkillApproval,
  }
}
