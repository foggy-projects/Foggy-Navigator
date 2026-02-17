import type { UniSseClientOptions, UniSseController, SseTransport, SseTransportOptions } from './types'
import { createFetchSseTransport } from './fetchSseTransport'
// #ifdef MP-WEIXIN
import { createWxSseTransport } from './wxSseTransport'
// #endif

function createTransport(opts: SseTransportOptions): SseTransport {
  // #ifdef MP-WEIXIN
  return createWxSseTransport(opts)
  // #endif

  // #ifndef MP-WEIXIN
  return createFetchSseTransport(opts)
  // #endif
}

/**
 * 跨平台 SSE 客户端
 * H5: fetch + ReadableStream
 * 微信小程序: wx.request + onChunkReceived
 */
export function createUniSseClient<TRaw>(options: UniSseClientOptions<TRaw>): UniSseController {
  const maxRetries = options.maxRetries ?? 5
  const baseDelay = options.baseRetryDelayMs ?? 1000
  const maxDelay = options.maxRetryDelayMs ?? 30000
  const targetEvent = options.eventName ?? 'event'

  let retryCount = 0
  let manuallyClosed = false
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let transport: SseTransport | null = null

  function scheduleReconnect() {
    if (manuallyClosed) return
    if (retryCount < maxRetries) {
      const delay = Math.min(baseDelay * Math.pow(2, retryCount), maxDelay)
      retryCount++
      options.onReconnecting?.(retryCount)
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (!manuallyClosed) connect()
      }, delay)
    } else {
      options.onMaxRetriesExceeded?.()
      options.onError?.(new Error('Max retries exceeded'))
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

  function connect() {
    transport = createTransport({
      url: options.url,
      headers: options.headers,
      onEvent: dispatchEvent,
      onError: (error) => {
        if (manuallyClosed) return
        options.onError?.(error)
      },
      onClose: () => {
        if (!manuallyClosed) scheduleReconnect()
      },
    })
  }

  connect()

  return {
    close() {
      manuallyClosed = true
      if (retryTimer != null) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (transport) {
        transport.close()
        transport = null
      }
    },
  }
}
