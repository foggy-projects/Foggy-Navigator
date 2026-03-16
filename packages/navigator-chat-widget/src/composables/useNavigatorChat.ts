import { ref, computed, type Ref, type ComputedRef, onUnmounted } from 'vue'
import { NavigatorApi } from '../api/navigatorApi'
import type { ChatMessage, NavigatorChatConfig, TaskStatus } from '../types'

let _seq = 0
function nextId(): string {
  return `msg-${Date.now()}-${++_seq}`
}

export interface UseNavigatorChat {
  /** 所有消息（按时间排序） */
  messages: ComputedRef<ChatMessage[]>
  /** 是否正在等待 AI 响应 */
  isLoading: Ref<boolean>
  /** 当前任务状态 */
  taskStatus: Ref<TaskStatus | null>
  /** 当前多轮会话 contextId */
  contextId: Ref<string | null>
  /** 错误信息 */
  error: Ref<string | null>
  /** 发送消息 */
  send: (content: string) => Promise<void>
  /** 取消当前任务 */
  cancel: () => Promise<void>
  /** 清空对话（重新开始新会话） */
  clear: () => void
}

/**
 * Navigator 对话 composable（核心逻辑）
 *
 * 封装了 ask → poll → 展示 的完整循环，支持多轮对话。
 *
 * @example
 * ```vue
 * <script setup>
 * import { useNavigatorChat } from '@foggy/navigator-chat-widget'
 *
 * const chat = useNavigatorChat({
 *   baseUrl: '/api/proxy/navigator',  // 通过自己后端代理
 *   agentId: 'xxx',
 * })
 * </script>
 * ```
 */
export function useNavigatorChat(config: NavigatorChatConfig): UseNavigatorChat {
  const api = new NavigatorApi(config)
  const pollInterval = config.pollInterval ?? 2000
  const timeout = config.timeout ?? 300_000

  const rawMessages = ref<ChatMessage[]>([])
  const isLoading = ref(false)
  const taskStatus = ref<TaskStatus | null>(null)
  const contextId = ref<string | null>(null)
  const error = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let currentTaskId: string | null = null
  let pollStartTime = 0

  const messages = computed(() =>
    [...rawMessages.value].sort((a, b) => a.timestamp - b.timestamp)
  )

  onUnmounted(() => {
    stopPolling()
  })

  async function send(content: string): Promise<void> {
    if (isLoading.value) return
    error.value = null

    // 添加用户消息
    rawMessages.value.push({
      id: nextId(),
      role: 'user',
      content,
      timestamp: Date.now(),
    })

    isLoading.value = true
    taskStatus.value = 'SUBMITTED'

    try {
      const task = await api.ask(content, contextId.value ?? undefined)
      currentTaskId = task.taskId
      contextId.value = task.contextId
      taskStatus.value = task.status

      // 开始轮询
      pollStartTime = Date.now()
      startPolling(task.taskId)
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      error.value = msg
      isLoading.value = false
      taskStatus.value = null
      rawMessages.value.push({
        id: nextId(),
        role: 'system',
        content: `发送失败: ${msg}`,
        timestamp: Date.now(),
        error: msg,
      })
    }
  }

  function startPolling(taskId: string) {
    stopPolling()
    pollTimer = setTimeout(() => doPoll(taskId), pollInterval)
  }

  async function doPoll(taskId: string) {
    if (currentTaskId !== taskId) return // 已切换到其他任务

    try {
      const task = await api.getTask(taskId)
      taskStatus.value = task.status

      if (isTerminal(task.status)) {
        // 任务完成
        stopPolling()
        isLoading.value = false
        currentTaskId = null

        if (task.status === 'COMPLETED' && task.result) {
          rawMessages.value.push({
            id: nextId(),
            role: 'assistant',
            content: task.result,
            timestamp: Date.now(),
            taskId: task.taskId,
            status: task.status,
            durationMs: task.durationMs ?? undefined,
            costUsd: task.costUsd ?? undefined,
          })
        } else if (task.status === 'FAILED') {
          const errMsg = task.errorMessage || '任务执行失败'
          error.value = errMsg
          rawMessages.value.push({
            id: nextId(),
            role: 'system',
            content: errMsg,
            timestamp: Date.now(),
            taskId: task.taskId,
            status: task.status,
            error: errMsg,
          })
        } else if (task.status === 'CANCELED') {
          rawMessages.value.push({
            id: nextId(),
            role: 'system',
            content: '任务已取消',
            timestamp: Date.now(),
            taskId: task.taskId,
            status: task.status,
          })
        }
        return
      }

      // 检查超时
      if (Date.now() - pollStartTime > timeout) {
        stopPolling()
        isLoading.value = false
        error.value = '等待超时'
        rawMessages.value.push({
          id: nextId(),
          role: 'system',
          content: `等待响应超时（${Math.round(timeout / 1000)}秒）`,
          timestamp: Date.now(),
          error: 'timeout',
        })
        return
      }

      // 继续轮询
      pollTimer = setTimeout(() => doPoll(taskId), pollInterval)
    } catch (e: unknown) {
      // 网络错误，重试
      pollTimer = setTimeout(() => doPoll(taskId), pollInterval * 2)
    }
  }

  async function cancel(): Promise<void> {
    if (!currentTaskId) return
    try {
      await api.cancelTask(currentTaskId)
    } catch {
      // ignore
    }
    stopPolling()
    isLoading.value = false
    taskStatus.value = 'CANCELED'
    currentTaskId = null
  }

  function clear() {
    stopPolling()
    rawMessages.value = []
    isLoading.value = false
    taskStatus.value = null
    contextId.value = null
    error.value = null
    currentTaskId = null
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function isTerminal(status: TaskStatus): boolean {
    return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELED'
  }

  return {
    messages,
    isLoading,
    taskStatus,
    contextId,
    error,
    send,
    cancel,
    clear,
  }
}
