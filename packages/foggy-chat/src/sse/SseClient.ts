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
}

export interface SseController {
  close(): void
  getEventSource(): EventSource | null
}

export function createSseClient<TRaw>(options: SseClientOptions<TRaw>): SseController {
  const maxRetries = options.maxRetries ?? 5
  const baseDelay = options.baseRetryDelayMs ?? 1000
  const maxDelay = options.maxRetryDelayMs ?? 30000

  let retryCount = 0
  let manuallyClosed = false
  let currentEs: EventSource | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function connect() {
    const es = new EventSource(options.url)
    currentEs = es

    // Fix: use addEventListener for named events (backend sends .name("event"))
    es.addEventListener(options.eventName ?? 'event', (e: MessageEvent) => {
      const raw: TRaw = JSON.parse(e.data)
      // 先调用原始事件回调（用于处理特殊事件如 ROUTE_REQUEST）
      options.onRawEvent?.(raw)
      // 然后通过 adapter 转换并处理消息
      const msgs = options.adapter.convert(raw, options.sessionId)
      if (msgs.length > 0) options.onMessage(msgs)
    })

    // Initial connection message (unnamed event)
    es.onmessage = (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)
        if (data.type === 'connected') {
          retryCount = 0 // 连接成功，重置重连计数
          options.onConnected?.()
        }
      } catch {
        // ignore non-JSON unnamed messages
      }
    }

    es.onerror = () => {
      if (manuallyClosed) return

      // readyState === CLOSED 表示连接已关闭，需要重连
      if (es.readyState === EventSource.CLOSED) {
        es.close()
        currentEs = null

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
          // 超过最大重连次数，放弃
          options.onError?.(new Event('error'))
        }
      }
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
      if (currentEs) {
        currentEs.close()
        currentEs = null
      }
    },
    getEventSource() {
      return currentEs
    },
  }
}
