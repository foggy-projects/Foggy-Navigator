import type { EventAdapter } from '../types/adapter'
import type { AipMessage } from '../types/aip'

export interface SseClientOptions<TRaw> {
  url: string
  eventName?: string
  adapter: EventAdapter<TRaw>
  sessionId: string
  onMessage: (messages: AipMessage[]) => void
  onConnected?: () => void
  onError?: (error: Event) => void
  /** 原始事件回调，用于处理 adapter 不转换的特殊事件（如 ROUTE_REQUEST） */
  onRawEvent?: (raw: TRaw) => void
  /** 最大重连次数，默认 5 */
  maxRetries?: number
  /** 基础重连延迟（ms），默认 1000 */
  baseRetryDelayMs?: number
  /** 最大重连延迟（ms），默认 30000 */
  maxRetryDelayMs?: number
  /** 重连中回调 */
  onReconnecting?: (attempt: number) => void
  /** 超过最大重连次数回调 */
  onMaxRetriesExceeded?: () => void
  /** 自定义请求头（用于 Authorization 等） */
  headers?: Record<string, string>
}

export interface SseController {
  close(): void
  getEventSource(): EventSource | null
}

/**
 * 基于 fetch + ReadableStream 的 SSE 客户端
 * 支持自定义 headers（解决 EventSource 无法传递 Authorization header 的限制）
 */
export function createSseClient<TRaw>(options: SseClientOptions<TRaw>): SseController {
  const maxRetries = options.maxRetries ?? 5
  const baseDelay = options.baseRetryDelayMs ?? 1000
  const maxDelay = options.maxRetryDelayMs ?? 30000
  const targetEvent = options.eventName ?? 'event'

  let retryCount = 0
  let manuallyClosed = false
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let abortController: AbortController | null = null

  function scheduleReconnect() {
    if (manuallyClosed) return
    if (retryCount < maxRetries) {
      const delay = Math.min(baseDelay * Math.pow(2, retryCount), maxDelay)
      retryCount++
      options.onReconnecting?.(retryCount)
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (!manuallyClosed) {
          connect()
        }
      }, delay)
    } else {
      options.onMaxRetriesExceeded?.()
      options.onError?.(new Event('error'))
    }
  }

  /**
   * 解析 SSE 文本流，处理 event/data/id 字段
   */
  function parseSseChunk(chunk: string, state: { eventType: string; dataLines: string[] }) {
    const lines = chunk.split('\n')
    for (const line of lines) {
      if (line.startsWith('event:')) {
        state.eventType = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        state.dataLines.push(line.slice(5).trim())
      } else if (line.startsWith('id:')) {
        // SSE id field - ignored for now
      } else if (line.trim() === '') {
        // 空行表示事件结束，分发事件
        if (state.dataLines.length > 0) {
          const data = state.dataLines.join('\n')
          dispatchEvent(state.eventType, data)
          state.dataLines = []
          state.eventType = ''
        }
      }
    }
  }

  function dispatchEvent(eventType: string, data: string) {
    try {
      if (eventType === targetEvent) {
        const raw: TRaw = JSON.parse(data)
        options.onRawEvent?.(raw)
        const msgs = options.adapter.convert(raw, options.sessionId)
        if (msgs.length > 0) options.onMessage(msgs)
      } else if (eventType === '' || eventType === 'message') {
        // 默认事件（连接确认等）
        const parsed = JSON.parse(data)
        if (parsed.type === 'connected') {
          retryCount = 0
          options.onConnected?.()
        }
      }
    } catch {
      // ignore non-JSON or parse errors
    }
  }

  async function connect() {
    abortController = new AbortController()

    try {
      const response = await fetch(options.url, {
        headers: {
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
          ...(options.headers ?? {}),
        },
        signal: abortController.signal,
      })

      if (!response.ok || !response.body) {
        scheduleReconnect()
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      const state = { eventType: '', dataLines: [] as string[] }
      // Buffer for incomplete lines across chunks
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done || manuallyClosed) break

        buffer += decoder.decode(value, { stream: true })

        // Process complete events (delimited by double newline)
        // But we need to handle partial chunks, so process line-by-line
        // keeping any incomplete last line in the buffer
        const lastNewline = buffer.lastIndexOf('\n')
        if (lastNewline === -1) continue // no complete line yet

        const complete = buffer.substring(0, lastNewline + 1)
        buffer = buffer.substring(lastNewline + 1)
        parseSseChunk(complete, state)
      }

      // Stream ended normally
      if (!manuallyClosed) {
        scheduleReconnect()
      }
    } catch (err: unknown) {
      if (manuallyClosed) return
      if (err instanceof DOMException && err.name === 'AbortError') return
      scheduleReconnect()
    }
  }

  connect()

  return {
    close() {
      manuallyClosed = true
      if (retryTimer != null) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (abortController) {
        abortController.abort()
        abortController = null
      }
    },
    // Kept for API compatibility; returns null since we no longer use EventSource
    getEventSource() {
      return null
    },
  }
}
