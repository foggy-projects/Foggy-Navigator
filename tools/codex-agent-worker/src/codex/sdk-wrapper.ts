import { Codex } from '@openai/codex-sdk'
import type { CodexOptions, ThreadOptions, ModelReasoningEffort, ThreadItem } from '@openai/codex-sdk'
import { config } from '../config.js'
import type { TaskEntry, WorkerEvent } from '../models.js'
import { createResultEvent, createErrorEvent } from './event-mapper.js'
import { EventBroadcast } from '../persistence/event-store.js'

/**
 * Global task registry — tracks all active and recently completed tasks
 */
export const taskRegistry = new Map<string, TaskEntry>()

/**
 * Event broadcasts per task — subscribers receive real-time events
 */
export const taskBroadcasts = new Map<string, EventBroadcast>()

/**
 * 解析 model:reasoning_level 后缀
 * 如 "gpt-5.4:high" → { model: "gpt-5.4", reasoningLevel: "high" }
 * 如 "gpt-5.4-mini" → { model: "gpt-5.4-mini", reasoningLevel: undefined }
 *
 * SDK ModelReasoningEffort: "minimal" | "low" | "medium" | "high" | "xhigh"
 * 前端传 "extra-high" → 映射为 "xhigh"
 */
export function parseModelString(rawModel: string): { model: string; reasoningLevel?: ModelReasoningEffort } {
  const colonIdx = rawModel.indexOf(':')
  if (colonIdx <= 0) return { model: rawModel }

  const model = rawModel.substring(0, colonIdx)
  let level = rawModel.substring(colonIdx + 1)

  // 映射前端命名到 SDK 枚举
  if (level === 'extra-high') level = 'xhigh'

  const valid: ModelReasoningEffort[] = ['minimal', 'low', 'medium', 'high', 'xhigh']
  if (valid.includes(level as ModelReasoningEffort)) {
    return { model, reasoningLevel: level as ModelReasoningEffort }
  }
  return { model }
}

export function shouldAbortBeforeTurnStart(completedTurns: number, maxTurns: number | undefined): boolean {
  return maxTurns !== undefined && completedTurns >= maxTurns
}

function createEventWithSeq(
  broadcast: EventBroadcast,
  event: Omit<WorkerEvent, 'seq'>
): WorkerEvent {
  return {
    ...event,
    seq: broadcast.nextSeq(),
  }
}

function emitWorkerEvent(
  broadcast: EventBroadcast,
  event: Omit<WorkerEvent, 'seq'>
): WorkerEvent {
  const eventWithSeq = createEventWithSeq(broadcast, event)
  broadcast.emit(eventWithSeq)
  return eventWithSeq
}

function createToolUseEvent(
  taskId: string,
  threadId: string | undefined,
  tool: string,
  input: Record<string, unknown> | undefined,
  toolUseId: string
): Omit<WorkerEvent, 'seq'> {
  return {
    type: 'tool_use',
    task_id: taskId,
    session_id: threadId,
    tool,
    input,
    tool_use_id: toolUseId,
  }
}

/**
 * 将 Codex SDK ThreadItem 映射为 WorkerEvent
 */
export function mapThreadItemToEvents(
  taskId: string,
  item: ThreadItem,
  threadId: string | undefined,
  nextSeq: () => number,
  startedToolUses: ReadonlySet<string> = new Set()
): WorkerEvent[] {
  const events: WorkerEvent[] = []

  switch (item.type) {
    case 'agent_message':
      if (item.text) {
        events.push({
          type: 'assistant_text',
          task_id: taskId,
          session_id: threadId,
          content: item.text,
          seq: nextSeq(),
        })
      }
      break

    case 'command_execution':
      if (!startedToolUses.has(item.id)) {
        events.push({
          ...createToolUseEvent(taskId, threadId, 'command_execution', { command: item.command }, item.id),
          seq: nextSeq(),
        })
      }
      // 如果已完成，输出结果
      if (item.status === 'completed' || item.status === 'failed') {
        events.push({
          type: 'tool_result',
          task_id: taskId,
          session_id: threadId,
          tool: 'command_execution',
          output: item.aggregated_output || '',
          tool_use_id: item.id,
          is_error: item.status === 'failed',
          seq: nextSeq(),
        })
      }
      break

    case 'file_change':
      events.push({
        type: 'tool_use',
        task_id: taskId,
        session_id: threadId,
        tool: 'file_change',
        input: { changes: item.changes },
        tool_use_id: item.id,
        seq: nextSeq(),
      })
      break

    case 'mcp_tool_call':
      if (!startedToolUses.has(item.id)) {
        events.push({
          ...createToolUseEvent(
            taskId,
            threadId,
            `${item.server}:${item.tool}`,
            item.arguments as Record<string, unknown>,
            item.id
          ),
          seq: nextSeq(),
        })
      }
      if (item.status === 'completed' || item.status === 'failed') {
        events.push({
          type: 'tool_result',
          task_id: taskId,
          session_id: threadId,
          tool: `${item.server}:${item.tool}`,
          output: item.result ? JSON.stringify(item.result) : (item.error?.message || ''),
          tool_use_id: item.id,
          is_error: item.status === 'failed',
          seq: nextSeq(),
        })
      }
      break

    case 'reasoning':
      // 推理摘要作为 assistant_text 发送
      if (item.text) {
        events.push({
          type: 'assistant_text',
          task_id: taskId,
          session_id: threadId,
          content: item.text,
          subtype: 'reasoning',
          seq: nextSeq(),
        })
      }
      break

    case 'error':
      events.push({
        type: 'error',
        task_id: taskId,
        session_id: threadId,
        error: item.message,
        seq: nextSeq(),
      })
      break
  }

  return events
}

/**
 * Run a Codex query and yield WorkerEvent objects via EventBroadcast
 *
 * Uses @openai/codex-sdk:
 * 1. Creates Codex instance → startThread() or resumeThread()
 * 2. Calls runStreamed() to get async event stream
 * 3. Maps each ThreadEvent to WorkerEvent format
 * 4. Broadcasts events to all subscribers
 */
export async function runQuery(
  taskId: string,
  prompt: string,
  cwd: string | undefined,
  threadId: string | undefined,
  model: string | undefined,
  maxTurns: number | undefined,
  apiKey: string | undefined
): Promise<void> {
  const broadcast = new EventBroadcast(taskId)
  taskBroadcasts.set(taskId, broadcast)

  const abortController = new AbortController()

  const entry: TaskEntry = {
    taskId,
    status: 'running',
    abortController,
    threadId,
    startedAt: Date.now(),
  }
  taskRegistry.set(taskId, entry)

  const startTime = Date.now()
  let numTurns = 0
  let lastAssistantText = ''
  let totalUsage = { inputTokens: 0, outputTokens: 0 }
  let resolvedThreadId = threadId
  let terminalEventSent = false
  let terminalFailureMessage: string | undefined
  let abortReason = 'Task aborted'
  const startedToolUses = new Set<string>()
  const maxTurnLimit = maxTurns !== undefined && Number.isInteger(maxTurns) && maxTurns > 0
    ? maxTurns
    : undefined

  // 解析 model:reasoning_level
  const rawModel = model || 'gpt-5.4-mini'
  const { model: effectiveModel, reasoningLevel } = parseModelString(rawModel)
  let resolvedModel = effectiveModel

  try {
    // 创建 Codex 实例
    // 支持两种认证模式：
    // 1. API Key 模式：传入 apiKey
    // 2. 订阅模式：不传 apiKey，SDK 通过 Codex CLI 自动读取 ~/.codex/auth.json
    const effectiveApiKey = apiKey || config.openaiApiKey || undefined
    const codexOptions: CodexOptions = {}
    if (effectiveApiKey) {
      codexOptions.apiKey = effectiveApiKey
    }

    const codex = new Codex(codexOptions)

    // 创建或恢复 Thread
    const threadOptions: ThreadOptions = {
      model: effectiveModel,
      skipGitRepoCheck: true,
      sandboxMode: 'danger-full-access',
    }
    if (cwd) threadOptions.workingDirectory = cwd
    if (reasoningLevel) threadOptions.modelReasoningEffort = reasoningLevel

    const thread = threadId
      ? codex.resumeThread(threadId, threadOptions)
      : codex.startThread(threadOptions)

    // 流式执行
    const { events } = await thread.runStreamed(prompt, {
      signal: abortController.signal,
    })

    for await (const event of events) {
      if (abortController.signal.aborted) break

      switch (event.type) {
        case 'thread.started':
          resolvedThreadId = event.thread_id
          entry.threadId = resolvedThreadId
          break

        case 'turn.started':
          if (shouldAbortBeforeTurnStart(numTurns, maxTurnLimit)) {
            abortReason = `Task aborted: max_turns limit reached (${maxTurnLimit})`
            abortController.abort(abortReason)
          }
          break

        case 'item.completed': {
          const workerEvents = mapThreadItemToEvents(
            taskId,
            event.item,
            resolvedThreadId,
            () => broadcast.nextSeq(),
            startedToolUses
          )

          for (const we of workerEvents) {
            if (we.type === 'assistant_text' && we.content && we.subtype !== 'reasoning') {
              lastAssistantText += we.content
            }
            broadcast.emit(we)
          }
          break
        }

        case 'item.started':
        case 'item.updated': {
          if (event.type === 'item.started') {
            if (event.item.type === 'command_execution') {
              startedToolUses.add(event.item.id)
              emitWorkerEvent(
                broadcast,
                createToolUseEvent(taskId, resolvedThreadId, 'command_execution', { command: event.item.command }, event.item.id)
              )
            } else if (event.item.type === 'mcp_tool_call') {
              startedToolUses.add(event.item.id)
              emitWorkerEvent(
                broadcast,
                createToolUseEvent(
                  taskId,
                  resolvedThreadId,
                  `${event.item.server}:${event.item.tool}`,
                  event.item.arguments as Record<string, unknown>,
                  event.item.id
                )
              )
            }
          }
          break
        }

        case 'turn.completed':
          numTurns++
          if (event.usage) {
            totalUsage.inputTokens += event.usage.input_tokens || 0
            totalUsage.outputTokens += event.usage.output_tokens || 0
          }
          break

        case 'turn.failed':
          terminalFailureMessage = event.error.message
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, terminalFailureMessage, broadcast.nextSeq()
          ))
          terminalEventSent = true
          break

        case 'error':
          terminalFailureMessage = event.message
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, terminalFailureMessage, broadcast.nextSeq()
          ))
          terminalEventSent = true
          break
      }

      if (terminalFailureMessage || abortController.signal.aborted) {
        break
      }
    }

    if (terminalFailureMessage) {
      entry.status = 'failed'
      entry.completedAt = Date.now()
      return
    }

    if (abortController.signal.aborted) {
      entry.status = 'aborted'
      entry.completedAt = Date.now()
      if (!terminalEventSent) {
        broadcast.emit(createErrorEvent(
          taskId,
          resolvedThreadId,
          abortReason,
          broadcast.nextSeq()
        ))
      }
      return
    }

    // 发送结果事件
    const durationMs = Date.now() - startTime
    const resultEvent = createResultEvent(
      taskId, resolvedThreadId, lastAssistantText || undefined,
      totalUsage, resolvedModel, durationMs, numTurns, broadcast.nextSeq()
    )
    broadcast.emit(resultEvent)

    entry.status = 'completed'
    entry.completedAt = Date.now()

  } catch (error: any) {
    if (abortController.signal.aborted) {
      entry.status = 'aborted'
      if (!terminalEventSent) {
        const reason = typeof abortController.signal.reason === 'string'
          ? abortController.signal.reason
          : abortReason
        const abortEvent = createErrorEvent(taskId, resolvedThreadId, reason, broadcast.nextSeq())
        broadcast.emit(abortEvent)
      }
    } else {
      entry.status = 'failed'
      const errorMsg = error?.message || String(error)
      if (!terminalEventSent) {
        const errorEvent = createErrorEvent(taskId, resolvedThreadId, errorMsg, broadcast.nextSeq())
        broadcast.emit(errorEvent)
      }
    }
    entry.completedAt = Date.now()
  } finally {
    broadcast.close()
  }
}

/**
 * Abort a running task
 */
export function abortTask(taskId: string): boolean {
  const entry = taskRegistry.get(taskId)
  if (!entry || entry.status !== 'running') return false

  entry.abortController?.abort()
  entry.status = 'aborted'
  entry.completedAt = Date.now()

  return true
}

/**
 * Get task status
 */
export function getTaskStatus(taskId: string): TaskEntry | undefined {
  return taskRegistry.get(taskId)
}

/**
 * Clean up old completed tasks (keep last 100)
 */
export function cleanupOldTasks(): void {
  const MAX_COMPLETED = 100
  const completed = Array.from(taskRegistry.entries())
    .filter(([, e]) => e.status !== 'running')
    .sort((a, b) => (b[1].completedAt || 0) - (a[1].completedAt || 0))

  if (completed.length > MAX_COMPLETED) {
    for (const [id] of completed.slice(MAX_COMPLETED)) {
      const broadcast = taskBroadcasts.get(id)
      if (broadcast) {
        broadcast.cleanup()
      } else {
        new EventBroadcast(id).cleanup()
      }
      taskRegistry.delete(id)
      taskBroadcasts.delete(id)
    }
  }
}
