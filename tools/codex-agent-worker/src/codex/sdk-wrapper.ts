import { Codex } from '@openai/codex-sdk'
import type { CodexOptions, ThreadOptions, ModelReasoningEffort, ThreadEvent, ThreadItem } from '@openai/codex-sdk'
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
function parseModelString(rawModel: string): { model: string; reasoningLevel?: ModelReasoningEffort } {
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

/**
 * 将 Codex SDK ThreadItem 映射为 WorkerEvent
 */
function mapThreadItemToEvents(taskId: string, item: ThreadItem, threadId: string | undefined, seq: number): WorkerEvent[] {
  const events: WorkerEvent[] = []

  switch (item.type) {
    case 'agent_message':
      if (item.text) {
        events.push({
          type: 'assistant_text',
          task_id: taskId,
          session_id: threadId,
          content: item.text,
          seq,
        })
      }
      break

    case 'command_execution':
      // 命令开始
      events.push({
        type: 'tool_use',
        task_id: taskId,
        session_id: threadId,
        tool: 'command_execution',
        input: { command: item.command },
        tool_use_id: item.id,
        seq,
      })
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
          seq: seq + 1,
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
        seq,
      })
      break

    case 'mcp_tool_call':
      events.push({
        type: 'tool_use',
        task_id: taskId,
        session_id: threadId,
        tool: `${item.server}:${item.tool}`,
        input: item.arguments as Record<string, unknown>,
        tool_use_id: item.id,
        seq,
      })
      if (item.status === 'completed' || item.status === 'failed') {
        events.push({
          type: 'tool_result',
          task_id: taskId,
          session_id: threadId,
          tool: `${item.server}:${item.tool}`,
          output: item.result ? JSON.stringify(item.result) : (item.error?.message || ''),
          tool_use_id: item.id,
          is_error: item.status === 'failed',
          seq: seq + 1,
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
          seq,
        })
      }
      break

    case 'error':
      events.push({
        type: 'error',
        task_id: taskId,
        session_id: threadId,
        error: item.message,
        seq,
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

        case 'item.completed': {
          numTurns++
          const seq = broadcast.nextSeq()
          const workerEvents = mapThreadItemToEvents(taskId, event.item, resolvedThreadId, seq)

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
          // 对于命令执行，在 started/updated 时也发送事件以显示进度
          if (event.item.type === 'command_execution' && event.type === 'item.started') {
            const seq = broadcast.nextSeq()
            broadcast.emit({
              type: 'tool_use',
              task_id: taskId,
              session_id: resolvedThreadId,
              tool: 'command_execution',
              input: { command: event.item.command },
              tool_use_id: event.item.id,
              seq,
            })
          }
          break
        }

        case 'turn.completed':
          if (event.usage) {
            totalUsage.inputTokens += event.usage.input_tokens || 0
            totalUsage.outputTokens += event.usage.output_tokens || 0
          }
          break

        case 'turn.failed':
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, event.error.message, broadcast.nextSeq()
          ))
          break

        case 'error':
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, event.message, broadcast.nextSeq()
          ))
          break
      }
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
    } else {
      entry.status = 'failed'
      const errorMsg = error?.message || String(error)
      const errorEvent = createErrorEvent(taskId, resolvedThreadId, errorMsg, broadcast.nextSeq())
      broadcast.emit(errorEvent)
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

  const broadcast = taskBroadcasts.get(taskId)
  if (broadcast) {
    broadcast.close()
  }

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
      taskRegistry.delete(id)
      taskBroadcasts.delete(id)
    }
  }
}
