import { ref } from 'vue'
import { getToken } from '@/utils/auth'
import type { AgentMessage } from '@/types'

/**
 * Unified SSE — 整个浏览器 Tab 只维护 1 条 SSE 连接
 * 替代之前 per-session SSE + notification SSE 的多连接模式
 *
 * 事件分发:
 * - session_event  → sessionListeners (按 sessionId 路由)
 * - task_update / assistant_notification → notificationListeners
 */

// ---- module-level singleton state ----
const connected = ref(false)
let abortController: AbortController | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retryCount = 0
let manuallyClosed = false
const MAX_RETRIES = 10
const BASE_DELAY = 2000
const MAX_DELAY = 60000

// Local routing tables
const sessionListeners = new Map<string, Set<(msg: AgentMessage) => void>>()
const notificationListeners = new Set<(eventType: string, data: any) => void>()

// ---- SSE parsing ----
function parseSseEvents(
  chunk: string,
  state: { eventType: string; dataLines: string[] },
) {
  const lines = chunk.split('\n')
  for (const line of lines) {
    if (line.startsWith('event:')) {
      state.eventType = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      state.dataLines.push(line.slice(5).trim())
    } else if (line.trim() === '') {
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
    const parsed = JSON.parse(data)

    if (eventType === 'session_event') {
      // Route to session listeners by sessionId
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
      // Connection confirmation
      if (parsed.type === 'connected') {
        connected.value = true
        retryCount = 0
        // Re-subscribe all sessions after reconnect
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

async function doConnect() {
  abortController = new AbortController()
  const token = getToken()

  try {
    const response = await fetch('/api/v1/sse/unified', {
      headers: {
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
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
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done || manuallyClosed) break

      buffer += decoder.decode(value, { stream: true })
      const lastNewline = buffer.lastIndexOf('\n')
      if (lastNewline === -1) continue

      const complete = buffer.substring(0, lastNewline + 1)
      buffer = buffer.substring(lastNewline + 1)
      parseSseEvents(complete, state)
    }

    if (!manuallyClosed) {
      connected.value = false
      scheduleReconnect()
    }
  } catch (err: unknown) {
    if (manuallyClosed) return
    if (err instanceof DOMException && err.name === 'AbortError') return
    connected.value = false
    scheduleReconnect()
  }
}

/** POST /api/v1/sse/subscribe with current token */
async function postSubscribe(sessionIds: string[]) {
  if (sessionIds.length === 0) return
  const token = getToken()
  try {
    await fetch('/api/v1/sse/subscribe', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ sessionIds }),
    })
  } catch (e) {
    console.error('[UnifiedSse] subscribe failed:', e)
  }
}

/** POST /api/v1/sse/unsubscribe with current token */
async function postUnsubscribe(sessionIds: string[]) {
  if (sessionIds.length === 0) return
  const token = getToken()
  try {
    await fetch('/api/v1/sse/unsubscribe', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ sessionIds }),
    })
  } catch (e) {
    console.error('[UnifiedSse] unsubscribe failed:', e)
  }
}

/** Re-subscribe all locally tracked sessions (after reconnect) */
function resubscribeAll() {
  const sessionIds = [...sessionListeners.keys()]
  if (sessionIds.length > 0) {
    postSubscribe(sessionIds)
  }
}

// ---- public API ----

export function useUnifiedSse() {
  function connect() {
    if (abortController) return // already connected
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
    if (abortController) {
      abortController.abort()
      abortController = null
    }
  }

  /**
   * Subscribe to session events.
   * Registers a local callback AND tells the backend to route events for this session.
   * Returns an unsubscribe function.
   */
  function subscribeSession(
    sessionId: string,
    callback: (msg: AgentMessage) => void,
  ): () => void {
    let listeners = sessionListeners.get(sessionId)
    const isNew = !listeners || listeners.size === 0
    if (!listeners) {
      listeners = new Set()
      sessionListeners.set(sessionId, listeners)
    }
    listeners.add(callback)

    // Tell backend to start routing events for this session
    if (isNew) {
      postSubscribe([sessionId])
    }

    // Return unsubscribe function
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

  /**
   * Add a notification listener (task_update / assistant_notification).
   * Returns a remove function.
   */
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
