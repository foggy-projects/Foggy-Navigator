import { ref } from 'vue'
import { getToken } from '@/utils/auth'
import { getSseBaseUrl, getApiBaseUrl } from '@/utils/config'
import { createFetchSseTransport } from '@/sse/fetchSseTransport'
// #ifdef MP-WEIXIN
import { createWxSseTransport } from '@/sse/wxSseTransport'
// #endif
import type { SseTransport, SseTransportOptions } from '@/sse/types'

/**
 * Mobile Unified SSE — 整个 App 只维护 1 条 SSE 连接
 * 替代之前 per-session SSE 的多连接模式
 */

function createTransport(opts: SseTransportOptions): SseTransport {
  // #ifdef MP-WEIXIN
  return createWxSseTransport(opts)
  // #endif

  // #ifndef MP-WEIXIN
  return createFetchSseTransport(opts)
  // #endif
}

// ---- module-level singleton state ----
const connected = ref(false)
let transport: SseTransport | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retryCount = 0
let manuallyClosed = false
const MAX_RETRIES = 10
const BASE_DELAY = 2000
const MAX_DELAY = 60000

// Local routing tables
type SessionCallback = (msg: any) => void
const sessionListeners = new Map<string, Set<SessionCallback>>()
const notificationListeners = new Set<(eventType: string, data: any) => void>()

// ---- event dispatch ----
function dispatchEvent(eventType: string, data: string) {
  try {
    const parsed = JSON.parse(data)

    if (eventType === 'session_event') {
      const sessionId = parsed.sessionId as string
      if (sessionId) {
        const listeners = sessionListeners.get(sessionId)
        if (listeners) {
          for (const cb of listeners) {
            cb(parsed)
          }
        }
      }
    } else if (eventType === 'task_update' || eventType === 'assistant_notification') {
      for (const cb of notificationListeners) {
        cb(eventType, parsed)
      }
    } else if (eventType === 'heartbeat') {
      // keep-alive — no-op
    } else if (!eventType || eventType === 'message') {
      if (parsed.type === 'connected') {
        connected.value = true
        retryCount = 0
        resubscribeAll()
      }
    }
  } catch {
    // ignore non-JSON
  }
}

// ---- connection management ----
function scheduleReconnect() {
  if (manuallyClosed) return
  if (retryCount < MAX_RETRIES) {
    const delay = Math.min(BASE_DELAY * Math.pow(2, retryCount), MAX_DELAY)
    retryCount++
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (!manuallyClosed) doConnect()
    }, delay)
  } else {
    connected.value = false
  }
}

function doConnect() {
  const token = getToken()
  const sseUrl = `${getSseBaseUrl()}/sse/unified`

  transport = createTransport({
    url: sseUrl,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    onEvent: dispatchEvent,
    onError: () => {
      if (manuallyClosed) return
      connected.value = false
    },
    onClose: () => {
      if (!manuallyClosed) {
        connected.value = false
        scheduleReconnect()
      }
    },
  })
}

async function postSubscribe(sessionIds: string[]) {
  if (sessionIds.length === 0) return
  const token = getToken()
  try {
    await uni.request({
      url: `${getApiBaseUrl()}/sse/subscribe`,
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      data: { sessionIds },
    })
  } catch (e) {
    console.error('[UnifiedSse] subscribe failed:', e)
  }
}

async function postUnsubscribe(sessionIds: string[]) {
  if (sessionIds.length === 0) return
  const token = getToken()
  try {
    await uni.request({
      url: `${getApiBaseUrl()}/sse/unsubscribe`,
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      data: { sessionIds },
    })
  } catch (e) {
    console.error('[UnifiedSse] unsubscribe failed:', e)
  }
}

function resubscribeAll() {
  const sessionIds = [...sessionListeners.keys()]
  if (sessionIds.length > 0) {
    postSubscribe(sessionIds)
  }
}

// ---- public API ----
export function useUnifiedSse() {
  function connect() {
    if (transport) return
    manuallyClosed = false
    retryCount = 0
    doConnect()
  }

  function disconnect() {
    manuallyClosed = true
    connected.value = false
    if (retryTimer != null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    if (transport) {
      transport.close()
      transport = null
    }
  }

  function subscribeSession(
    sessionId: string,
    callback: SessionCallback,
  ): () => void {
    // 自动建立 SSE 长连接（幂等，重复调用无副作用）
    if (!transport) {
      manuallyClosed = false
      retryCount = 0
      doConnect()
    }

    let listeners = sessionListeners.get(sessionId)
    const isNew = !listeners || listeners.size === 0
    if (!listeners) {
      listeners = new Set()
      sessionListeners.set(sessionId, listeners)
    }
    listeners.add(callback)

    if (isNew) {
      postSubscribe([sessionId])
    }

    return () => {
      const set = sessionListeners.get(sessionId)
      if (set) {
        set.delete(callback)
        if (set.size === 0) {
          sessionListeners.delete(sessionId)
          postUnsubscribe([sessionId])
        }
      }
    }
  }

  function addNotificationListener(
    callback: (eventType: string, data: any) => void,
  ): () => void {
    notificationListeners.add(callback)
    return () => {
      notificationListeners.delete(callback)
    }
  }

  return {
    connected,
    connect,
    disconnect,
    subscribeSession,
    addNotificationListener,
  }
}
