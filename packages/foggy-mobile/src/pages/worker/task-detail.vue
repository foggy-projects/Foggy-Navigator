<template>
  <view class="task-detail-page">
    <!-- 状态栏 -->
    <view v-if="taskStream.task.value" class="task-status-bar">
      <StatusBadge :status="taskStream.task.value.status" :show-label="true" />
      <InteractionBadge v-if="interactionState" :state="interactionState" />
      <text v-if="taskStream.task.value.model" class="status-model">{{ taskStream.task.value.model }}</text>
      <text v-if="modelConfigLabel" class="status-config">{{ modelConfigLabel }}</text>
      <text v-if="taskStream.task.value.costUsd != null" class="status-cost">
        ${{ taskStream.task.value.costUsd.toFixed(4) }}
      </text>
      <text v-if="taskStream.task.value.durationMs != null" class="status-duration">
        {{ formatDuration(taskStream.task.value.durationMs) }}
      </text>
    </view>

    <!-- 连接状态横幅 -->
    <view v-if="connectionBannerStatus !== 'connected'" class="connection-banner" :class="connectionBannerStatus">
      <text class="connection-text">
        {{ connectionBannerStatus === 'connecting' ? '连接中...' : '连接断开' }}
      </text>
    </view>

    <!-- 加载更早消息 -->
    <view v-if="taskStream.hasMoreHistory.value" class="load-more-bar">
      <text
        v-if="!taskStream.loadingMore.value"
        class="load-more-text"
        @tap="taskStream.loadMoreHistory()"
      >加载更早消息</text>
      <text v-else class="load-more-text loading">加载中...</text>
    </view>

    <!-- 消息流 -->
    <view class="message-area">
      <MessageList
        :messages="sortedMessages"
        :is-thinking="taskStream.chatState.isThinking.value"
        @plan-respond="handlePlanRespond"
        @question-respond="handleQuestionRespond"
        @permission-respond="handlePermissionRespond"
      />
    </view>

    <!-- 底部操作 -->
    <view class="task-bottom">
      <!-- 运行中: 中止按钮 -->
      <view v-if="isRunning" class="abort-bar">
        <button class="abort-btn" :loading="aborting" @tap="handleAbort">
          中止任务
        </button>
      </view>

      <!-- 失败: 恢复操作按钮 -->
      <view v-else-if="isFailed" class="recovery-bar">
        <button class="recovery-btn" @tap="handleReconnect">
          重连
        </button>
        <button class="recovery-btn" @tap="handleResync">
          重同步
        </button>
        <view v-if="canResume" class="resume-section">
          <ChatInput
            v-model="resumeInput"
            placeholder="输入续对消息..."
            send-label="继续"
            :history-items="historyItems"
            @send="handleResume"
            @sent="onSent"
          />
        </view>
      </view>

      <!-- 已完成: 续对输入 -->
      <view v-else-if="canResume">
        <ChatInput
          v-model="resumeInput"
          placeholder="输入续对消息..."
          send-label="继续"
          :history-items="historyItems"
          @send="handleResume"
          @sent="onSent"
        />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { useTaskStream } from '@/composables/useTaskStream'
import { useInputMemory } from '@/composables/useInputMemory'
import { useSessionModelCache } from '@/composables/useSessionModelCache'
import { listConversationConfigs } from '@/api/conversationConfig'
import {
  getTaskUnified,
  cancelTaskUnified,
  resumeTaskUnified,
  respondToTaskUnified,
  reconnectTaskUnified,
  resyncTaskUnified,
} from '@/api/unifiedTask'
import { listModelConfigs } from '@/api/platform'
import type { ConversationConfig, LlmModelConfig } from '@/api/types'
import StatusBadge from '@/components/StatusBadge.vue'
import InteractionBadge from '@/components/InteractionBadge.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'
import { formatDuration } from '@/utils/time'

const taskId = ref('')
const sessionId = ref('')
const aborting = ref(false)
const resumeInput = ref('')
const conversationConfig = ref<ConversationConfig | null>(null)
const platformModels = ref<LlmModelConfig[]>([])

// Model cache
const { initFromTask, getSessionModel } = useSessionModelCache()

// Draft & history
const memoryScope = computed(() => sessionId.value ? 'pane-' + sessionId.value : '')
const { saveDraft, loadDraft, clearDraft, addToHistory, recentItems } = useInputMemory(memoryScope)

const historyItems = computed(() => recentItems(10))

watch(resumeInput, (val) => {
  saveDraft(val)
})

const taskStream = useTaskStream(() => {
  // Refresh task status on completion
  if (taskId.value) {
    getTaskUnified(taskId.value).then((t) => {
      if (t) taskStream.task.value = t
    })
  }
})

const sortedMessages = computed(() => taskStream.chatState.sortedMessages.value)

const isRunning = computed(() => {
  const status = taskStream.task.value?.status
  return status === 'RUNNING' || status === 'PENDING' || status === 'AWAITING_PERMISSION'
})

const isFailed = computed(() => {
  return taskStream.task.value?.status === 'FAILED'
})

const connectionBannerStatus = computed(() => taskStream.chatState.connectionStatus.value)

const canResume = computed(() => {
  const status = taskStream.task.value?.status
  return (status === 'COMPLETED' || status === 'FAILED') && !!taskStream.task.value?.claudeSessionId
})

const interactionState = computed(() => conversationConfig.value?.interactionState)

const modelConfigLabel = computed(() => {
  const configId = taskStream.task.value?.modelConfigId
  if (!configId) return ''
  const cfg = platformModels.value.find(m => m.id === configId)
  return cfg ? cfg.name : ''
})

onLoad(async (options) => {
  taskId.value = options?.taskId || ''
  sessionId.value = options?.sessionId || ''

  // Restore draft
  const draft = loadDraft()
  if (draft) resumeInput.value = draft

  // Load platform models for config name display
  loadPlatformModelsQuiet()

  if (taskId.value) {
    try {
      const task = await getTaskUnified(taskId.value)
      if (!task) return
      taskStream.task.value = task
      sessionId.value = task.sessionId

      // Init model cache from this task
      initFromTask(task)

      // Load conversation config
      loadConversationConfig(task.sessionId)

      if (task.sessionId) {
        await taskStream.connect(task.sessionId)
      }
    } catch (e) {
      console.error('Failed to load task:', e)
    }
  }
})

onUnload(() => {
  taskStream.disconnect()
})

async function loadPlatformModelsQuiet() {
  try {
    platformModels.value = await listModelConfigs()
  } catch {
    // best-effort
  }
}

async function loadConversationConfig(sid: string) {
  try {
    const configs = await listConversationConfigs([sid])
    if (configs.length > 0) {
      conversationConfig.value = configs[0]
    }
  } catch {
    // best-effort
  }
}

async function handleAbort() {
  if (!taskId.value) return
  aborting.value = true
  try {
    await cancelTaskUnified(taskId.value)
    if (taskStream.task.value) {
      taskStream.task.value.status = 'ABORTED'
    }
    uni.showToast({ title: '已中止', icon: 'success' })
  } catch (e) {
    console.error('Failed to abort task:', e)
    uni.showToast({ title: '中止失败', icon: 'error' })
  } finally {
    aborting.value = false
  }
}

async function handleResume(prompt: string) {
  const task = taskStream.task.value
  if (!task?.claudeSessionId) return

  // Get cached model selection for this session
  const cached = getSessionModel(task.sessionId)

  try {
    const newTask = await resumeTaskUnified({
      workerId: task.workerId,
      prompt,
      directoryId: task.directoryId,
      sessionId: task.sessionId,
      model: cached?.model || task.model,
      modelConfigId: cached?.modelConfigId || task.modelConfigId,
    })
    taskStream.resumeInPlace(newTask)
    taskId.value = newTask.taskId
    sessionId.value = newTask.sessionId
    // Update model cache
    initFromTask(newTask)

    // Sync URL to new taskId (H5 only, via history.replaceState)
    // #ifdef H5
    try {
      const newQuery = `taskId=${newTask.taskId}&sessionId=${newTask.sessionId}`
      const currentPath = window.location.pathname
      const newUrl = `${currentPath}?${newQuery}${window.location.hash}`
      window.history.replaceState(null, '', newUrl)
    } catch {
      // best-effort: URL sync is non-critical
    }
    // #endif
  } catch (e) {
    console.error('Failed to resume task:', e)
  }
}

async function handleReconnect() {
  if (!taskId.value) return
  try {
    await reconnectTaskUnified(taskId.value)
    uni.showToast({ title: '已重连', icon: 'success' })
    await taskStream.syncTaskStatus()
  } catch (e) {
    console.error('Failed to reconnect:', e)
    uni.showToast({ title: '重连失败', icon: 'error' })
  }
}

async function handleResync() {
  if (!taskId.value) return
  try {
    await resyncTaskUnified(taskId.value)
    uni.showToast({ title: '已重同步', icon: 'success' })
    await taskStream.syncTaskStatus()
    // Reload messages after resync
    if (sessionId.value) {
      await taskStream.connect(sessionId.value)
    }
  } catch (e) {
    console.error('Failed to resync:', e)
    uni.showToast({ title: '重同步失败', icon: 'error' })
  }
}

async function handlePlanRespond(permissionId: string, decision: string, denyMessage?: string, planAction?: string) {
  if (!taskId.value) return
  try {
    await respondToTaskUnified(taskId.value, {
      permissionId,
      decision,
      denyMessage: denyMessage || (decision === 'deny' ? 'Plan rejected by user' : undefined),
      planAction,
    })
    taskStream.chatState.resolvePermission(permissionId, decision === 'allow' ? 'approved' : 'denied')
    if (decision === 'allow' && taskStream.task.value) {
      taskStream.task.value.status = 'RUNNING'
    }
  } catch (e) {
    console.error('Failed to respond to plan review:', e)
    uni.showToast({ title: '响应失败', icon: 'error' })
  }
}

async function handleQuestionRespond(permissionId: string, answers: Record<string, string>) {
  if (!taskId.value) return
  try {
    await respondToTaskUnified(taskId.value, {
      permissionId,
      decision: 'allow',
      answers,
    })
    taskStream.chatState.resolvePermission(permissionId, 'approved')
    if (taskStream.task.value) {
      taskStream.task.value.status = 'RUNNING'
    }
  } catch (e) {
    console.error('Failed to respond to question:', e)
    uni.showToast({ title: '响应失败', icon: 'error' })
  }
}

async function handlePermissionRespond(permissionId: string, decision: string, scope: string) {
  if (!taskId.value) return
  try {
    await respondToTaskUnified(taskId.value, {
      permissionId,
      decision,
      scope,
    })
    taskStream.chatState.resolvePermission(permissionId, decision === 'allow' ? 'approved' : 'denied')
    if (decision === 'allow' && taskStream.task.value) {
      taskStream.task.value.status = 'RUNNING'
    }
  } catch (e) {
    console.error('Failed to respond to permission:', e)
    uni.showToast({ title: '响应失败', icon: 'error' })
  }
}

function onSent(content: string) {
  addToHistory(content)
  clearDraft()
  resumeInput.value = ''
}
</script>

<style scoped>
.task-detail-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--window-top, 0px));
  background: #f5f5f5;
}
.task-status-bar {
  display: flex;
  flex-direction: row;
  align-items: center;
  padding: 16rpx 24rpx;
  background-color: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
  flex-wrap: wrap;
}
.status-model {
  font-size: 24rpx;
  color: #909399;
  background-color: #f0f0f0;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  margin-right: 16rpx;
}
.status-config {
  font-size: 22rpx;
  color: #2e7d32;
  background-color: #e8f5e9;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  margin-right: 16rpx;
}
.status-cost {
  font-size: 24rpx;
  color: #e6a23c;
  margin-right: 16rpx;
}
.status-duration {
  font-size: 24rpx;
  color: #909399;
  margin-right: 16rpx;
}
.message-area {
  flex: 1;
  overflow: hidden;
}
.task-bottom {
  flex-shrink: 0;
}
.abort-bar {
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  border-top: 2rpx solid #f0f0f0;
}
.abort-btn {
  width: 100%;
  height: 80rpx;
  background: #f56c6c;
  color: #ffffff;
  font-size: 30rpx;
  border-radius: 16rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.recovery-bar {
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  border-top: 2rpx solid #f0f0f0;
}
.recovery-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 68rpx;
  padding: 0 32rpx;
  font-size: 28rpx;
  background-color: #ecf5ff;
  color: #409eff;
  border-radius: 12rpx;
  border: none;
  margin-right: 16rpx;
  margin-bottom: 16rpx;
}
.resume-section {
  margin-top: 16rpx;
}
.connection-banner {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  padding: 12rpx 24rpx;
  font-size: 24rpx;
}
.connection-text {
  margin-left: 16rpx;
  font-size: 24rpx;
}
.connection-banner.connecting {
  background: #fdf6ec;
  color: #e6a23c;
}
.connection-banner.disconnected {
  background: #fef0f0;
  color: #f56c6c;
}
.load-more-bar {
  padding: 16rpx 24rpx;
  text-align: center;
  background-color: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.load-more-text {
  font-size: 26rpx;
  color: #667eea;
}
.load-more-text.loading {
  color: #909399;
}
</style>
