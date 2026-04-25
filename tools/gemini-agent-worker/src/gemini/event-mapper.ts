import type { GeminiCliJsonEvent, WorkerEvent } from '../models.js'

function safeString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function safeNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function safeRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function firstString(record: Record<string, unknown>, keys: string[]): string | undefined {
  for (const key of keys) {
    const value = safeString(record[key])
    if (value) return value
  }
  return undefined
}

function firstNumber(record: Record<string, unknown>, keys: string[]): number | undefined {
  for (const key of keys) {
    const value = safeNumber(record[key])
    if (value !== undefined) return value
  }
  return undefined
}

function buildResultEvent(
  taskId: string,
  sessionId: string | undefined,
  payload: GeminiCliJsonEvent,
  nextSeq: () => number
): WorkerEvent {
  const stats = payload.stats
  const modelStats = stats?.models ? Object.entries(stats.models) : []
  const resolvedModel = firstString(payload, ['model']) || modelStats[0]?.[0]
  const inputTokens = firstNumber(payload as Record<string, unknown>, ['input_tokens', 'inputTokens'])
    ?? safeNumber(stats?.input_tokens)
    ?? safeNumber(stats?.input)
    ?? modelStats.reduce<number | undefined>((sum, [, value]) => {
      const tokens = safeNumber(value.input_tokens) ?? safeNumber(value.input)
      return tokens === undefined ? sum : (sum ?? 0) + tokens
    }, undefined)
  const outputTokens = firstNumber(payload as Record<string, unknown>, ['output_tokens', 'outputTokens'])
    ?? safeNumber(stats?.output_tokens)
    ?? modelStats.reduce<number | undefined>((sum, [, value]) => {
      const tokens = safeNumber(value.output_tokens)
      return tokens === undefined ? sum : (sum ?? 0) + tokens
    }, undefined)
  const durationMs = firstNumber(payload as Record<string, unknown>, ['duration_ms', 'durationMs'])
    ?? safeNumber(stats?.duration_ms)

  return {
    type: payload.status === 'error' ? 'error' : 'result',
    task_id: taskId,
    session_id: sessionId,
    result: payload.status === 'error' ? undefined : firstString(payload as Record<string, unknown>, ['content', 'text', 'message']),
    content: payload.status === 'error' ? undefined : firstString(payload as Record<string, unknown>, ['content', 'text', 'message']),
    error: payload.status === 'error' ? firstString(payload as Record<string, unknown>, ['error', 'message']) || 'Gemini CLI error' : undefined,
    model: resolvedModel,
    input_tokens: inputTokens,
    output_tokens: outputTokens,
    duration_ms: durationMs,
    seq: nextSeq(),
  }
}

function stringifyFallback(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  return JSON.stringify(value ?? '')
}

export function mapGeminiJsonToWorkerEvents(
  taskId: string,
  sessionId: string | undefined,
  payload: GeminiCliJsonEvent,
  nextSeq: () => number
): WorkerEvent[] {
  const events: WorkerEvent[] = []
  const record = payload as Record<string, unknown>
  const type = firstString(record, ['type', 'event', 'kind'])?.toLowerCase()
  const toolName = firstString(record, ['tool', 'tool_name', 'name'])
  const content = firstString(record, ['text', 'content', 'message'])
  const error = firstString(record, ['error', 'message'])
  const role = safeString(payload.role)?.toLowerCase()

  if (type === 'init') {
    return events
  }

  if (type === 'message' && role === 'assistant') {
    if (content) {
      events.push({
        type: 'assistant_text',
        task_id: taskId,
        session_id: sessionId,
        content,
        seq: nextSeq(),
      })
    }
    return events
  }

  if ((type === 'content' || type === 'message' || type === 'text' || type === 'assistant') && role !== 'user') {
    if (content) {
      events.push({
        type: 'assistant_text',
        task_id: taskId,
        session_id: sessionId,
        content,
        seq: nextSeq(),
      })
    }
    return events
  }

  if (type === 'tool_call' || type === 'tool_use') {
    events.push({
      type: 'tool_use',
      task_id: taskId,
      session_id: sessionId,
      tool: toolName || 'tool',
      input: payload.input || payload.parameters || record,
      tool_use_id: firstString(record, ['tool_use_id', 'tool_id', 'id']),
      seq: nextSeq(),
    })
    return events
  }

  if (type === 'tool_result' || type === 'tool_response') {
    const toolRecord = safeRecord(payload.result)
    const outputRecord = safeRecord(record.output)
    const toolOutput = firstString(record, ['output', 'content', 'text', 'message'])
    const resultValue = toolRecord ?? outputRecord ?? payload.result ?? payload.output ?? record
    events.push({
      type: 'tool_result',
      task_id: taskId,
      session_id: sessionId,
      tool: toolName
        || firstString(toolRecord || {}, ['tool', 'tool_name', 'name'])
        || firstString(outputRecord || {}, ['tool', 'tool_name', 'name'])
        || 'tool',
      output: toolOutput !== undefined ? toolOutput : stringifyFallback(resultValue),
      tool_use_id: firstString(record, ['tool_use_id', 'tool_id', 'id']),
      is_error: Boolean(payload.is_error ?? payload.status === 'error'),
      seq: nextSeq(),
    })
    return events
  }

  if (type === 'result' || type === 'completed' || type === 'final') {
    events.push(buildResultEvent(taskId, sessionId, payload, nextSeq))
    return events
  }

  if (type === 'error') {
    events.push({
      type: 'error',
      task_id: taskId,
      session_id: sessionId,
      error: error || 'Gemini CLI error',
      seq: nextSeq(),
    })
    return events
  }

  if (content && role !== 'user') {
    events.push({
      type: 'assistant_text',
      task_id: taskId,
      session_id: sessionId,
      content,
      seq: nextSeq(),
    })
  }

  return events
}
