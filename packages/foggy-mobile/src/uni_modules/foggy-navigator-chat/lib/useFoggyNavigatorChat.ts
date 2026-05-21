import { computed, getCurrentInstance, onBeforeUnmount, ref, type ComputedRef, type Ref } from 'vue'
import { createUniNavigatorBffClient } from './client'
import {
  createFoggyMessageIngestor,
  nextNavigatorMessageId,
  sanitizeNavigatorText,
  sessionMessagesToOpenMessages,
} from './normalizer'
import type {
  FoggyNavigatorChatClient,
  FoggyNavigatorChatConfig,
  FoggyNavigatorChatMessage,
  FoggyNavigatorSendOptions,
  FoggyNavigatorSessionMessagesPage,
  FoggyNavigatorSessionPage,
  FoggyNavigatorTaskMessagesPage,
  FoggyNavigatorTaskStatus,
  PaginationOptions,
} from './types'

export interface UseFoggyNavigatorChatOptions {
  client?: FoggyNavigatorChatClient
}

export interface UseFoggyNavigatorChat {
  messages: ComputedRef<FoggyNavigatorChatMessage[]>
  isLoading: Ref<boolean>
  taskStatus: Ref<FoggyNavigatorTaskStatus | null>
  contextId: Ref<string | null>
  progressText: Ref<string>
  error: Ref<string | null>
  networkRetryCount: Ref<number>
  send: (content: string, options?: FoggyNavigatorSendOptions) => Promise<void>
  cancel: () => Promise<void>
  clear: () => void
  listSessions: (options?: PaginationOptions) => Promise<FoggyNavigatorSessionPage>
  getSessionMessages: (
    contextId: string,
    options?: PaginationOptions,
  ) => Promise<FoggyNavigatorSessionMessagesPage>
  loadSession: (
    contextId: string,
    options?: PaginationOptions,
  ) => Promise<FoggyNavigatorSessionMessagesPage>
  deleteSession: (contextId: string) => Promise<void>
}

export function useFoggyNavigatorChat(
  config: FoggyNavigatorChatConfig,
  options: UseFoggyNavigatorChatOptions = {},
): UseFoggyNavigatorChat {
  const client = options.client ?? createUniNavigatorBffClient(config)
  const pollInterval = config.pollInterval ?? 3000
  const timeout = config.timeout ?? 300_000
  const ingestor = createFoggyMessageIngestor({
    mode: config.mode,
    showToolCalls: config.showToolCalls,
    showToolResults: config.showToolResults,
    showRuntimeEvents: config.showRuntimeEvents,
  })

  const rawMessages = ref<FoggyNavigatorChatMessage[]>([])
  const isLoading = ref(false)
  const taskStatus = ref<FoggyNavigatorTaskStatus | null>(null)
  const contextId = ref<string | null>(null)
  const progressText = ref('正在理解问题...')
  const error = ref<string | null>(null)
  const networkRetryCount = ref(0)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let currentTaskId: string | null = null
  let nextCursor: string | null = null
  let pollStartTime = 0

  const messages = computed(() =>
    [...rawMessages.value].sort((a, b) => a.timestamp - b.timestamp),
  )

  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      stopPolling()
    })
  }

  async function send(content: string, sendOptions: FoggyNavigatorSendOptions = {}): Promise<void> {
    if (isLoading.value) return
    const question = content.trim()
    if (!question) return

    error.value = null
    networkRetryCount.value = 0
    appendLocalMessage({
      id: nextNavigatorMessageId('fnc-user'),
      role: 'user',
      content: sanitizeNavigatorText(question),
      timestamp: Date.now(),
      attachments: sendOptions.attachments,
    })

    isLoading.value = true
    taskStatus.value = 'SUBMITTED'
    progressText.value = '正在理解问题...'

    try {
      const task = await client.ask(question, contextId.value ?? undefined, sendOptions)
      currentTaskId = task.taskId
      contextId.value = task.contextId ?? contextId.value
      taskStatus.value = task.status
      nextCursor = task.nextCursor ?? null
      pollStartTime = Date.now()
      ingestor.ingestInitialTask(task)
      syncMessages()

      if (task.terminal || isTerminal(task.status)) {
        finishTask(task.status, task.terminalStatus ?? null)
      } else {
        schedulePoll(task.taskId, pollInterval)
      }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      error.value = safeFailureMessage(message)
      isLoading.value = false
      taskStatus.value = null
      appendLocalMessage({
        id: nextNavigatorMessageId('fnc-send-error'),
        role: 'system',
        content: `发送失败: ${error.value}`,
        timestamp: Date.now(),
        error: error.value,
      })
    }
  }

  async function doPoll(taskId: string): Promise<void> {
    if (currentTaskId !== taskId) return

    try {
      const page = await client.getTaskMessages(taskId, nextCursor, 50)
      ingestTaskMessages(page)
      networkRetryCount.value = 0

      const terminalMessage = page.messages.find((message) => message.terminal)
      if (page.terminal || terminalMessage) {
        finishTask(
          page.status ?? taskStatus.value,
          terminalMessage?.terminalStatus ?? page.terminalStatus ?? null,
        )
        return
      }

      if (Date.now() - pollStartTime > timeout) {
        stopPolling()
        isLoading.value = false
        progressText.value = '任务仍在后台处理'
        appendLocalMessage({
          id: nextNavigatorMessageId('fnc-timeout'),
          role: 'system',
          content: `连接等待已超过 ${Math.round(timeout / 1000)} 秒，任务仍可能在后台处理。`,
          timestamp: Date.now(),
          taskId,
          status: taskStatus.value ?? undefined,
          process: true,
          processKind: 'network',
          transient: true,
        })
        return
      }

      schedulePoll(taskId, pollInterval)
    } catch {
      networkRetryCount.value += 1
      progressText.value = networkRetryCount.value > 1
        ? `网络波动，正在重试 ${networkRetryCount.value}`
        : '网络波动，正在重试'

      if (Date.now() - pollStartTime > timeout) {
        stopPolling()
        isLoading.value = false
        appendLocalMessage({
          id: nextNavigatorMessageId('fnc-network-timeout'),
          role: 'system',
          content: '网络连接不稳定，任务可能仍在后台处理，请稍后从历史会话继续查看。',
          timestamp: Date.now(),
          taskId,
          status: taskStatus.value ?? undefined,
          process: true,
          processKind: 'network',
          transient: true,
        })
        return
      }

      schedulePoll(taskId, Math.min(pollInterval * Math.max(2, networkRetryCount.value), 15_000))
    }
  }

  function ingestTaskMessages(page: FoggyNavigatorTaskMessagesPage) {
    if (page.contextId) contextId.value = page.contextId
    if (page.status) taskStatus.value = page.status
    ingestor.ingestOpenMessages(page.messages ?? [], page.taskId, page.status)
    nextCursor = page.nextCursor ?? nextCursor
    syncMessages()
  }

  async function cancel(): Promise<void> {
    if (!currentTaskId) return
    const taskId = currentTaskId
    try {
      await client.cancelTask?.(taskId)
    } finally {
      stopPolling()
      isLoading.value = false
      currentTaskId = null
      nextCursor = null
      taskStatus.value = 'CANCELED'
      progressText.value = '已取消'
      appendLocalMessage({
        id: nextNavigatorMessageId('fnc-cancel'),
        role: 'system',
        content: '已取消当前任务。',
        timestamp: Date.now(),
        taskId,
        status: 'CANCELED',
        process: true,
        processKind: 'system',
        transient: true,
      })
    }
  }

  function clear() {
    stopPolling()
    ingestor.clear()
    rawMessages.value = []
    isLoading.value = false
    taskStatus.value = null
    contextId.value = null
    progressText.value = '正在理解问题...'
    error.value = null
    networkRetryCount.value = 0
    currentTaskId = null
    nextCursor = null
  }

  async function listSessions(options: PaginationOptions = {}): Promise<FoggyNavigatorSessionPage> {
    if (!client.listSessions) {
      return { sessions: [], hasMore: false, nextCursor: null }
    }
    return client.listSessions(options)
  }

  async function getSessionMessages(
    targetContextId: string,
    options: PaginationOptions = {},
  ): Promise<FoggyNavigatorSessionMessagesPage> {
    if (!client.getSessionMessages) {
      return { contextId: targetContextId, messages: [], hasMore: false, nextCursor: null }
    }
    return client.getSessionMessages(targetContextId, options)
  }

  async function loadSession(
    targetContextId: string,
    options: PaginationOptions = {},
  ): Promise<FoggyNavigatorSessionMessagesPage> {
    stopPolling()
    ingestor.clear()
    rawMessages.value = []
    contextId.value = targetContextId
    isLoading.value = true
    error.value = null
    try {
      const page = await getSessionMessages(targetContextId, options)
      contextId.value = page.contextId ?? targetContextId
      ingestor.ingestOpenMessages(
        sessionMessagesToOpenMessages(page.messages ?? []),
        'history',
        undefined,
        { includeUserMessages: true },
      )
      syncMessages()
      return page
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      error.value = safeFailureMessage(message)
      throw cause
    } finally {
      isLoading.value = false
    }
  }

  async function deleteSession(targetContextId: string): Promise<void> {
    await client.deleteSession?.(targetContextId)
    if (contextId.value === targetContextId) clear()
  }

  function finishTask(status?: FoggyNavigatorTaskStatus | null, terminalStatus?: string | null) {
    stopPolling()
    isLoading.value = false
    currentTaskId = null
    nextCursor = null
    if (status) taskStatus.value = status
    else if (terminalStatus) taskStatus.value = terminalStatus
    progressText.value = status === 'FAILED' || terminalStatus === 'FAILED' ? '任务执行失败' : '处理完成'
  }

  function schedulePoll(taskId: string, delay: number) {
    stopPolling()
    pollTimer = setTimeout(() => {
      void doPoll(taskId)
    }, delay)
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function syncMessages() {
    rawMessages.value = ingestor.getMessages()
  }

  function appendLocalMessage(message: FoggyNavigatorChatMessage) {
    ingestor.append(message)
    syncMessages()
  }

  return {
    messages,
    isLoading,
    taskStatus,
    contextId,
    progressText,
    error,
    networkRetryCount,
    send,
    cancel,
    clear,
    listSessions,
    getSessionMessages,
    loadSession,
    deleteSession,
  }
}

function isTerminal(status?: FoggyNavigatorTaskStatus | null): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELED'
}

function safeFailureMessage(content: string): string {
  if (/task_scoped_token|adapterConfigJson|manifestJson|client_app_secret|runtime credential|authorization|worker gateway|gateway url|secret/i.test(content)) {
    return '操作执行失败，请稍后重试或联系管理员。'
  }
  return content
}
