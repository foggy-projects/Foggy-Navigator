import { config } from '../config.js'
import type { TaskEntry, WorkerEvent } from '../models.js'
import { mapResponseItem, createResultEvent, createErrorEvent } from './event-mapper.js'
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
 * Run a Codex query and yield WorkerEvent objects via EventBroadcast
 *
 * This function:
 * 1. Creates or resumes a Codex SDK session
 * 2. Streams events through the SDK
 * 3. Maps each event to WorkerEvent format
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
  let totalUsage: { inputTokens: number; outputTokens: number } = { inputTokens: 0, outputTokens: 0 }
  let resolvedModel = model
  let resolvedThreadId = threadId

  try {
    // Dynamic import of the Codex SDK
    // The SDK package name may vary — try @openai/codex first
    let CodexSDK: any
    try {
      CodexSDK = await import('@openai/codex')
    } catch {
      throw new Error(
        'Codex SDK not available. Install @openai/codex: npm install @openai/codex'
      )
    }

    const effectiveApiKey = apiKey || config.openaiApiKey
    if (!effectiveApiKey) {
      throw new Error('No OpenAI API key configured. Set OPENAI_API_KEY or pass api_key in request.')
    }

    // Create Codex client
    const codex = new CodexSDK.CodexClient({
      apiKey: effectiveApiKey,
      model: model || 'codex-mini-latest',
    })

    // Create or resume a session
    const sessionOptions: Record<string, unknown> = {}
    if (cwd) sessionOptions.workingDirectory = cwd
    if (maxTurns) sessionOptions.maxTurns = maxTurns

    const session = threadId
      ? await codex.resumeSession(threadId, sessionOptions)
      : await codex.createSession(sessionOptions)

    resolvedThreadId = session.id || session.threadId || threadId
    entry.threadId = resolvedThreadId

    // Stream the query
    const stream = await session.query(prompt, {
      signal: abortController.signal,
    })

    for await (const item of stream) {
      if (abortController.signal.aborted) break

      numTurns++
      const seq = broadcast.nextSeq()

      // Map SDK event to WorkerEvent(s)
      const events = mapResponseItem(taskId, item, resolvedThreadId, seq)

      for (const event of events) {
        // Track assistant text for result
        if (event.type === 'assistant_text' && event.content) {
          lastAssistantText += event.content
        }

        broadcast.emit(event)
      }

      // Track usage if available
      if (item.usage) {
        totalUsage.inputTokens += item.usage.input_tokens || item.usage.inputTokens || 0
        totalUsage.outputTokens += item.usage.output_tokens || item.usage.outputTokens || 0
      }

      if (item.model) {
        resolvedModel = item.model
      }
    }

    // Send result event
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
