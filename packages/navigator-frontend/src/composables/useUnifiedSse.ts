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
const SUBSCRIPTION_RETRY_BASE_DELAY = 1000
const SUBSCRIPTION_RETRY_MAX_DELAY = 30000

// Local routing tables
const sessionListeners = new Map<string, Set<(msg: AgentMessage) => void>>()
const sessionSubscribedListeners = new Map<string, Set<() => void>>()
const subscribedCallbackEpochs = new WeakMap<() => void, Map<string, number>>()
const confirmedSubscriptionEpochs = new Map<string, number>()
const sessionSyncQueues = new Map<string, Promise<void>>()
const sessionSubscriptionRetries = new Map<string, {
  epoch: number
  attempt: number
  timer: ReturnType<typeof setTimeout> | null
}>()
const notificationListeners = new Set<(eventType: string, data: any) => void>()
let connectionEpoch = 0

export interface SessionSubscriptionOptions {
  /** Called after the backend confirms routing for this session, including reconnects. */
  onSubscribed?: () => void
}

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
        invalidateSubscriptionEpoch()
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
      invalidateSubscriptionEpoch()
      scheduleReconnect()
    }
  } catch (err: unknown) {
    if (manuallyClosed) return
    if (err instanceof DOMException && err.name === 'AbortError') return
    connected.value = false
    invalidateSubscriptionEpoch()
    scheduleReconnect()
  }
}

/** POST /api/v1/sse/subscribe with current token */
async function postSubscribe(sessionIds: string[]): Promise<boolean> {
  if (sessionIds.length === 0) return true
  const token = getToken()
  try {
    const response = await fetch('/api/v1/sse/subscribe', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ sessionIds }),
    })
    if (!response.ok) {
      console.error(`[UnifiedSse] subscribe failed with status ${response.status}`)
      return false
    }
    return true
  } catch (e) {
    console.error('[UnifiedSse] subscribe failed:', e)
    return false
  }
}

/** POST /api/v1/sse/unsubscribe with current token */
async function postUnsubscribe(sessionIds: string[]): Promise<boolean> {
  if (sessionIds.length === 0) return true
  const token = getToken()
  try {
    const response = await fetch('/api/v1/sse/unsubscribe', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ sessionIds }),
    })
    if (!response.ok) {
      console.error(`[UnifiedSse] unsubscribe failed with status ${response.status}`)
      return false
    }
    return true
  } catch (e) {
    console.error('[UnifiedSse] unsubscribe failed:', e)
    return false
  }
}

function notifySessionSubscribed(sessionId: string) {
  for (const callback of sessionSubscribedListeners.get(sessionId) ?? []) {
    notifySubscribedCallback(sessionId, callback, connectionEpoch)
  }
}

function notifySubscribedCallback(sessionId: string, callback: () => void, epoch: number) {
  if (connectionEpoch !== epoch) return
  if (confirmedSubscriptionEpochs.get(sessionId) !== epoch) return
  if (!sessionSubscribedListeners.get(sessionId)?.has(callback)) return
  let sessionEpochs = subscribedCallbackEpochs.get(callback)
  if (!sessionEpochs) {
    sessionEpochs = new Map()
    subscribedCallbackEpochs.set(callback, sessionEpochs)
  }
  if (sessionEpochs.get(sessionId) === epoch) return
  sessionEpochs.set(sessionId, epoch)
  try {
    callback()
  } catch (error) {
    console.error('[UnifiedSse] subscribed callback failed:', error)
  }
}

function notifySubscribedCallbackLater(sessionId: string, callback: () => void, epoch = connectionEpoch) {
  queueMicrotask(() => {
    notifySubscribedCallback(sessionId, callback, epoch)
  })
}

function invalidateSubscriptionEpoch() {
  connectionEpoch++
  confirmedSubscriptionEpochs.clear()
  clearAllSessionSubscriptionRetries()
}

function clearSessionSubscriptionRetry(sessionId: string) {
  const retry = sessionSubscriptionRetries.get(sessionId)
  if (retry?.timer != null) clearTimeout(retry.timer)
  sessionSubscriptionRetries.delete(sessionId)
}

function clearAllSessionSubscriptionRetries() {
  for (const sessionId of sessionSubscriptionRetries.keys()) {
    clearSessionSubscriptionRetry(sessionId)
  }
}

function scheduleSessionSubscriptionRetry(sessionId: string, epoch: number) {
  if (epoch !== connectionEpoch || !sessionListeners.has(sessionId)) return

  const existing = sessionSubscriptionRetries.get(sessionId)
  if (existing?.epoch === epoch && existing.timer != null) return
  if (existing && existing.epoch !== epoch) clearSessionSubscriptionRetry(sessionId)

  const attempt = existing?.epoch === epoch ? existing.attempt : 0
  const delay = Math.min(
    SUBSCRIPTION_RETRY_BASE_DELAY * Math.pow(2, attempt),
    SUBSCRIPTION_RETRY_MAX_DELAY,
  )
  const retry = {
    epoch,
    attempt: attempt + 1,
    timer: null as ReturnType<typeof setTimeout> | null,
  }
  retry.timer = setTimeout(() => {
    const current = sessionSubscriptionRetries.get(sessionId)
    if (current !== retry) return
    current.timer = null
    if (epoch !== connectionEpoch || !sessionListeners.has(sessionId)) {
      clearSessionSubscriptionRetry(sessionId)
      return
    }
    void enqueueSessionSync(sessionId)
  }, delay)
  sessionSubscriptionRetries.set(sessionId, retry)
}

async function syncSessionSubscription(sessionId: string): Promise<void> {
  if (sessionListeners.has(sessionId)) {
    const requestEpoch = connectionEpoch
    if (confirmedSubscriptionEpochs.get(sessionId) === requestEpoch) {
      clearSessionSubscriptionRetry(sessionId)
      return
    }
    const success = await postSubscribe([sessionId])
    if (success && requestEpoch === connectionEpoch && sessionListeners.has(sessionId)) {
      clearSessionSubscriptionRetry(sessionId)
      confirmedSubscriptionEpochs.set(sessionId, requestEpoch)
      notifySessionSubscribed(sessionId)
    } else if (!success) {
      scheduleSessionSubscriptionRetry(sessionId, requestEpoch)
    }
    return
  }

  clearSessionSubscriptionRetry(sessionId)
  await postUnsubscribe([sessionId])
  confirmedSubscriptionEpochs.delete(sessionId)
}

function enqueueSessionSync(sessionId: string): Promise<void> {
  const previous = sessionSyncQueues.get(sessionId) ?? Promise.resolve()
  const next = previous
    .catch(() => undefined)
    .then(() => syncSessionSubscription(sessionId))
  sessionSyncQueues.set(sessionId, next)
  void next.then(
    () => { if (sessionSyncQueues.get(sessionId) === next) sessionSyncQueues.delete(sessionId) },
    () => { if (sessionSyncQueues.get(sessionId) === next) sessionSyncQueues.delete(sessionId) },
  )
  return next
}

/** Re-subscribe all locally tracked sessions (after reconnect). */
function resubscribeAll() {
  for (const sessionId of sessionListeners.keys()) {
    void enqueueSessionSync(sessionId)
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
    invalidateSubscriptionEpoch()
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
    options: SessionSubscriptionOptions = {},
  ): () => void {
    let listeners = sessionListeners.get(sessionId)
    const isNew = !listeners || listeners.size === 0
    if (!listeners) {
      listeners = new Set()
      sessionListeners.set(sessionId, listeners)
    }
    listeners.add(callback)
    if (options.onSubscribed) {
      let subscribedListeners = sessionSubscribedListeners.get(sessionId)
      if (!subscribedListeners) {
        subscribedListeners = new Set()
        sessionSubscribedListeners.set(sessionId, subscribedListeners)
      }
      subscribedListeners.add(options.onSubscribed)
    }

    // Tell backend to start routing events for this session
    if (isNew || confirmedSubscriptionEpochs.get(sessionId) !== connectionEpoch
        || sessionSyncQueues.has(sessionId)) {
      const sync = enqueueSessionSync(sessionId)
      if (options.onSubscribed) {
        void sync.then(() => notifySubscribedCallbackLater(sessionId, options.onSubscribed!))
      }
    } else if (options.onSubscribed) {
      notifySubscribedCallbackLater(sessionId, options.onSubscribed)
    }

    // Return unsubscribe function
    return () => {
      const set = sessionListeners.get(sessionId)
      if (set) {
        set.delete(callback)
        if (options.onSubscribed) {
          const subscribedListeners = sessionSubscribedListeners.get(sessionId)
          subscribedListeners?.delete(options.onSubscribed)
          if (subscribedListeners?.size === 0) sessionSubscribedListeners.delete(sessionId)
        }
        if (set.size === 0) {
          sessionListeners.delete(sessionId)
          clearSessionSubscriptionRetry(sessionId)
          void enqueueSessionSync(sessionId)
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
