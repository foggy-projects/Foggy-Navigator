import { ref, onUnmounted } from 'vue'
import { ElNotification } from 'element-plus'
import { useUnifiedSse } from '@/composables/useUnifiedSse'

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

function handleEvent(eventType: string, data: any) {
  if (eventType === 'assistant_notification') {
    handleAssistantNotification(data)
  } else if (eventType === 'task_update') {
    if (data.type === 'monitoring_alert') {
      handleMonitoringAlert(data)
    } else {
      window.dispatchEvent(new CustomEvent('task-update', { detail: data }))
    }
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

let removeListener: (() => void) | null = null

export function useNotifications() {
  const { connected, connect: connectSse, addNotificationListener } = useUnifiedSse()

  function connect() {
    // Ensure the unified SSE is connected
    connectSse()
    // Register notification listener (idempotent — only adds once)
    if (!removeListener) {
      removeListener = addNotificationListener(handleEvent)
    }
  }

  function disconnect() {
    if (removeListener) {
      removeListener()
      removeListener = null
    }
    // Don't disconnect the unified SSE — other consumers may need it
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
