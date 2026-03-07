import type { WorkerEvent } from '../models.js'

/**
 * Map Codex SDK response item to WorkerEvent(s)
 *
 * Codex SDK events:
 *   - item with type "message" (agent response text)
 *   - item with type "function_call" (tool invocation)
 *   - item with type "function_call_output" (tool result)
 *   - completion event with usage stats
 *
 * Output must match Claude Worker's WorkerEvent JSON format exactly.
 */

/**
 * Map a Codex SDK response item to one or more WorkerEvents
 */
export function mapResponseItem(
  taskId: string,
  item: any,
  threadId: string | undefined,
  seq: number
): WorkerEvent[] {
  const events: WorkerEvent[] = []

  if (!item) return events

  const type = item.type

  if (type === 'message') {
    // Agent text message
    const content = extractMessageContent(item)
    if (content) {
      events.push({
        type: 'assistant_text',
        task_id: taskId,
        session_id: threadId,
        content,
        seq,
      })
    }
  } else if (type === 'function_call') {
    // Tool invocation
    events.push({
      type: 'tool_use',
      task_id: taskId,
      session_id: threadId,
      tool: item.name || item.function?.name || 'unknown',
      input: parseToolInput(item.arguments || item.function?.arguments),
      tool_use_id: item.call_id || item.id,
      seq,
    })
  } else if (type === 'function_call_output') {
    // Tool result
    events.push({
      type: 'tool_result',
      task_id: taskId,
      session_id: threadId,
      tool: item.name || 'unknown',
      output: typeof item.output === 'string' ? item.output : JSON.stringify(item.output),
      tool_use_id: item.call_id || item.id,
      is_error: item.status === 'error' || false,
      seq,
    })
  }

  return events
}

/**
 * Create a result event (sent at completion)
 */
export function createResultEvent(
  taskId: string,
  threadId: string | undefined,
  resultText: string | undefined,
  usage: { inputTokens?: number; outputTokens?: number; totalTokens?: number } | undefined,
  model: string | undefined,
  durationMs: number,
  numTurns: number,
  seq: number
): WorkerEvent {
  return {
    type: 'result',
    task_id: taskId,
    session_id: threadId,
    content: resultText || '',
    result: resultText || '',
    cost_usd: estimateCost(usage, model),
    duration_ms: durationMs,
    input_tokens: usage?.inputTokens,
    output_tokens: usage?.outputTokens,
    num_turns: numTurns,
    model: model,
    seq,
  }
}

/**
 * Create an error event
 */
export function createErrorEvent(
  taskId: string,
  threadId: string | undefined,
  error: string,
  seq: number
): WorkerEvent {
  return {
    type: 'error',
    task_id: taskId,
    session_id: threadId,
    error,
    seq,
  }
}

// --- Helpers ---

function extractMessageContent(item: any): string | undefined {
  if (typeof item.content === 'string') return item.content
  if (Array.isArray(item.content)) {
    return item.content
      .map((part: any) => {
        if (typeof part === 'string') return part
        if (part.type === 'output_text' || part.type === 'text') return part.text
        return ''
      })
      .filter(Boolean)
      .join('')
  }
  if (item.text) return item.text
  return undefined
}

function parseToolInput(args: any): Record<string, unknown> | undefined {
  if (!args) return undefined
  if (typeof args === 'string') {
    try {
      return JSON.parse(args)
    } catch {
      return { raw: args }
    }
  }
  return args as Record<string, unknown>
}

/**
 * Rough cost estimation based on model and token usage
 * (Actual costs may vary, this is for tracking purposes)
 */
function estimateCost(
  usage: { inputTokens?: number; outputTokens?: number } | undefined,
  model: string | undefined
): number | undefined {
  if (!usage) return undefined

  const input = usage.inputTokens || 0
  const output = usage.outputTokens || 0

  // Default pricing (GPT-4.1 tier)
  let inputPricePer1M = 2.0   // $2 per 1M input tokens
  let outputPricePer1M = 8.0  // $8 per 1M output tokens

  if (model) {
    const m = model.toLowerCase()
    if (m.includes('o3') || m.includes('o4-mini')) {
      inputPricePer1M = 1.1
      outputPricePer1M = 4.4
    } else if (m.includes('gpt-4.1-mini')) {
      inputPricePer1M = 0.4
      outputPricePer1M = 1.6
    } else if (m.includes('gpt-4.1-nano')) {
      inputPricePer1M = 0.1
      outputPricePer1M = 0.4
    }
  }

  return (input * inputPricePer1M + output * outputPricePer1M) / 1_000_000
}
