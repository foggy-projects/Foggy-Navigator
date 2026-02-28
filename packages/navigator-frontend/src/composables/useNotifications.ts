import { ref, onUnmounted } from 'vue'
import { ElNotification } from 'element-plus'
import { getToken } from '@/utils/auth'

export interface AssistantNotification {
  id: string
  title: string
  body: string
  severity: 'info' | 'success' | 'warning' | 'error'
  relatedTaskIds: string[]
  suggestions: Array<{ action: string; description: string }>
  timestamp: number
  read: boolean
}

const notifications = ref<AssistantNotification[]>([])
const unreadCount = ref(0)
const connected = ref(false)

let abortController: AbortController | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retryCount = 0
let manuallyClosed = false
const MAX_RETRIES = 10
const BASE_DELAY = 2000
const MAX_DELAY = 60000

function parseSseEvents(chunk: string, state: { eventType: string; dataLines: string[] }) {
  const lines = chunk.split('\n')
  for (const line of lines) {
    if (line.startsWith('event:')) {
      state.eventType = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      state.dataLines.push(line.slice(5).trim())
    } else if (line.trim() === '') {
      if (state.dataLines.length > 0) {
        const data = state.dataLines.join('\n')
        handleEvent(state.eventType, data)
        state.dataLines = []
        state.eventType = ''
      }
    }
  }
}

function handleEvent(eventType: string, data: string) {
  try {
    const parsed = JSON.parse(data)

    if (eventType === 'assistant_notification') {
      handleAssistantNotification(parsed)
    } else if (eventType === 'task_update') {
      if (parsed.type === 'monitoring_alert') {
        handleMonitoringAlert(parsed)
      } else {
        window.dispatchEvent(new CustomEvent('task-update', { detail: parsed }))
      }
    } else if (eventType === 'heartbeat') {
      // heartbeat - connection alive
    } else if (!eventType || eventType === 'message') {
      if (parsed.type === 'connected') {
        connected.value = true
        retryCount = 0
        window.dispatchEvent(new CustomEvent('notification-reconnected'))
      }
    }
  } catch {
    // ignore non-JSON
  }
}

function handleAssistantNotification(data: any) {
  // data 可能是包含 parts 的 A2aMessage，或者解析后的通知 JSON
  let notif: any = null

  if (data.parts && Array.isArray(data.parts)) {
    // A2aMessage 格式：从 data part 中提取 notification
    for (const part of data.parts) {
      if (part.type === 'data' && part.data?.notification) {
        notif = part.data
        break
      }
      if (part.type === 'text') {
        // 原始文本回退
        notif = { notification: { title: 'Task Update', body: part.text, severity: 'info' } }
        break
      }
    }
  } else if (data.notification) {
    notif = data
  }

  if (!notif?.notification) return

  const notification: AssistantNotification = {
    id: `notif-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    title: notif.notification.title || 'Task Update',
    body: notif.notification.body || '',
    severity: notif.notification.severity || 'info',
    relatedTaskIds: notif.notification.relatedTaskIds || [],
    suggestions: notif.suggestions || [],
    timestamp: Date.now(),
    read: false,
  }

  notifications.value.unshift(notification)
  unreadCount.value++

  // 保留最近 50 条通知
  if (notifications.value.length > 50) {
    notifications.value = notifications.value.slice(0, 50)
  }

  // 浏览器通知（仅页面不可见时）
  if (document.hidden && Notification.permission === 'granted') {
    new Notification(notification.title, {
      body: notification.body,
      tag: notification.id,
    })
  }
}

function handleMonitoringAlert(data: any) {
  const level = (data.level || 'ERROR').toUpperCase()
  const service = data.service || 'unknown'
  const message = data.message || '(no details)'

  const notifType = level === 'ERROR' ? 'error' : 'warning'
  ElNotification({
    title: `[${service}] ${level}`,
    message: message.length > 120 ? message.substring(0, 120) + '...' : message,
    type: notifType,
    duration: 8000,
    position: 'top-right',
  })

  // Also add to notification list for history
  const notification: AssistantNotification = {
    id: `monitor-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    title: `[${service}] ${level}`,
    body: message,
    severity: notifType,
    relatedTaskIds: [],
    suggestions: [],
    timestamp: Date.now(),
    read: false,
  }
  notifications.value.unshift(notification)
  unreadCount.value++
  if (notifications.value.length > 50) {
    notifications.value = notifications.value.slice(0, 50)
  }
}

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
    const response = await fetch('/api/v1/notifications/stream', {
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

    if (!manuallyClosed) scheduleReconnect()
  } catch (err: unknown) {
    if (manuallyClosed) return
    if (err instanceof DOMException && err.name === 'AbortError') return
    scheduleReconnect()
  }
}

export function useNotifications() {
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

  function markAllRead() {
    notifications.value.forEach((n) => (n.read = true))
    unreadCount.value = 0
  }

  function requestPermission() {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission()
    }
  }

  onUnmounted(() => {
    // Don't disconnect on unmount - notifications are global
  })

  return {
    notifications,
    unreadCount,
    connected,
    connect,
    disconnect,
    markAllRead,
    requestPermission,
  }
}
