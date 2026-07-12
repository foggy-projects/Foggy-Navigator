<template>
  <view class="task-detail-page">
    <view class="session-title-bar">
      <view v-if="editingTitle" class="title-edit-row">
        <input
          v-model="titleDraft"
          class="title-input"
          placeholder="输入会话标题"
          :maxlength="80"
          confirm-type="done"
          @confirm="saveTitle"
        />
        <button class="title-action" :loading="savingTitle" @tap="saveTitle">保存</button>
        <button class="title-action ghost" @tap="cancelEditTitle">取消</button>
      </view>
      <view v-else class="title-display-row">
        <text class="session-title">{{ sessionTitle }}</text>
        <text class="title-edit-btn" @tap="startEditTitle">编辑</text>
      </view>
    </view>

    <!-- 状态栏 -->
    <view v-if="taskStream.task.value" class="task-status-bar">
      <StatusBadge :status="taskStream.task.value.status" :show-label="true" />
      <InteractionBadge v-if="interactionState" :state="interactionState" />
      <ProviderBadge :provider-type="taskProviderType" />
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
      <view v-if="canResume && platformModels.length > 0" class="continuation-options">
        <view class="continuation-option" @tap="showModelConfigPicker">
          <text class="continuation-option-label">{{ selectedModelConfigLabel || '选择执行后端' }}</text>
        </view>
        <view class="continuation-option" @tap="showResumeModelPicker">
          <text class="continuation-option-label">{{ selectedModelLabel || '默认模型' }}</text>
        </view>
      </view>

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
            :enable-attachments="true"
            :attachments="attachments"
            @add-image="chooseAlbumImages"
            @take-photo="takePhoto"
            @add-file="chooseFiles"
            @remove-attachment="removeAttachment"
            @send="handleResume"
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
          :enable-attachments="true"
          :attachments="attachments"
          @add-image="chooseAlbumImages"
          @take-photo="takePhoto"
          @add-file="chooseFiles"
          @remove-attachment="removeAttachment"
          @send="handleResume"
        />
      </view>
    </view>
    <CodexModelPicker
      :visible="codexModelPickerVisible"
      :model-value="selectedModel"
      :options="modelOptions"
      @close="codexModelPickerVisible = false"
      @select="selectResumeModel"
    />
  </view>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { useTaskStream } from '@/composables/useTaskStream'
import { useInputMemory } from '@/composables/useInputMemory'
import { useSessionModelCache } from '@/composables/useSessionModelCache'
import { listConversationConfigs, updateConversationTitle } from '@/api/conversationConfig'
import { useAttachments, toImagesJson } from '@/composables/useAttachments'
import {
  createTaskUnified,
  getTaskUnified,
  cancelTaskUnified,
  resumeTaskUnified,
  respondToTaskUnified,
  reconnectTaskUnified,
  resyncTaskUnified,
} from '@/api/unifiedTask'
import { listModelConfigs } from '@/api/platform'
import type { ConversationConfig, DispatchTask, LlmModelConfig } from '@/api/types'
import StatusBadge from '@/components/StatusBadge.vue'
import InteractionBadge from '@/components/InteractionBadge.vue'
import ProviderBadge from '@/components/ProviderBadge.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'
import CodexModelPicker from '@/components/CodexModelPicker.vue'
import { formatDuration } from '@/utils/time'
import { canResumeTask, executeTaskContinuation } from '@/utils/taskContinuation'
import {
  isMobileSelectablePlatformModel,
  normalizeMobileCodexModel,
  resolveMobileModelOptions,
} from '@/utils/llmModelOptions'
import {
  inferTaskProviderType,
  isCodexBackend,
  providerTypeFromWorkerBackend,
  providerTypeShortLabel,
  requiresNewSessionForProvider,
  workerBackendFromProviderType,
} from '@/utils/workerBackend'

const taskId = ref('')
const sessionId = ref('')
const aborting = ref(false)
const resumeInput = ref('')
const conversationConfig = ref<ConversationConfig | null>(null)
const platformModels = ref<LlmModelConfig[]>([])
const selectedModelConfigId = ref('')
const selectedModel = ref('')
const codexModelPickerVisible = ref(false)
const editingTitle = ref(false)
const titleDraft = ref('')
const savingTitle = ref(false)

// Model cache
const { initFromTask, setSessionModel, getSessionModel } = useSessionModelCache()
const { attachments, chooseAlbumImages, takePhoto, chooseFiles, removeAttachment, clearAttachments } = useAttachments()

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
  return canResumeTask(taskStream.task.value)
})

const interactionState = computed(() => conversationConfig.value?.interactionState)

const modelConfigLabel = computed(() => {
  const configId = taskStream.task.value?.modelConfigId
  if (!configId) return ''
  const cfg = platformModels.value.find(m => m.id === configId)
  return cfg ? cfg.name : ''
})

const taskModelConfig = computed(() => {
  const configId = taskStream.task.value?.modelConfigId
  return platformModels.value.find(model => model.id === configId)
})

const taskProviderType = computed(() => {
  return inferTaskProviderType(taskStream.task.value, taskModelConfig.value?.workerBackend)
})

const selectedModelConfig = computed(() => {
  return platformModels.value.find(model => model.id === selectedModelConfigId.value)
})

const selectedProviderType = computed(() => {
  return providerTypeFromWorkerBackend(selectedModelConfig.value?.workerBackend)
    || getSessionModel(sessionId.value)?.providerType
    || taskProviderType.value
})

const selectedModelConfigLabel = computed(() => {
  const config = selectedModelConfig.value
  if (!config) return ''
  const providerLabel = providerTypeShortLabel(providerTypeFromWorkerBackend(config.workerBackend))
  return providerLabel ? `${providerLabel} · ${config.name}` : config.name
})

const modelOptions = computed(() => selectedModelConfig.value
  ? resolveMobileModelOptions(selectedModelConfig.value)
  : [])

const selectedModelLabel = computed(() => {
  if (!selectedModel.value) return ''
  return modelOptions.value.find(option => option.value === selectedModel.value)?.label || selectedModel.value
})

const sessionTitle = computed(() => {
  return conversationConfig.value?.customTitle || taskStream.task.value?.prompt || 'Worker 会话'
})

onLoad(async (options) => {
  taskId.value = options?.taskId || ''
  sessionId.value = options?.sessionId || ''

  // Restore draft
  const draft = loadDraft()
  if (draft) resumeInput.value = draft

  if (taskId.value) {
    try {
      const task = await getTaskUnified(taskId.value)
      if (!task) return
      taskStream.task.value = task
      sessionId.value = task.sessionId
      await loadPlatformModelsQuiet(task)

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

async function loadPlatformModelsQuiet(task: DispatchTask) {
  try {
    platformModels.value = (await listModelConfigs(task.workerId)).filter(isMobileSelectablePlatformModel)
    const sourceProviderType = inferTaskProviderType(task)
    const sourceBackend = workerBackendFromProviderType(sourceProviderType)
    const initialConfig = platformModels.value.find(model => model.id === task.modelConfigId)
      || platformModels.value.find(model => model.workerBackend === sourceBackend)
    if (initialConfig) {
      applyModelConfig(initialConfig.id, task.model)
    } else {
      selectedModelConfigId.value = task.modelConfigId || ''
      selectedModel.value = task.model || ''
    }
  } catch {
    // best-effort
  }
}

function applyModelConfig(modelConfigId: string, preferredModel?: string) {
  const config = platformModels.value.find(model => model.id === modelConfigId)
  if (!config) return
  const options = resolveMobileModelOptions(config)
  const normalizedPreferred = isCodexBackend(config.workerBackend)
    ? normalizeMobileCodexModel(preferredModel)
    : preferredModel
  selectedModelConfigId.value = config.id
  selectedModel.value = normalizedPreferred && options.some(option => option.value === normalizedPreferred)
    ? normalizedPreferred
    : options[0]?.value || ''
  if (sessionId.value) {
    setSessionModel(
      sessionId.value,
      selectedModelConfigId.value,
      selectedModel.value,
      providerTypeFromWorkerBackend(config.workerBackend),
    )
  }
}

function showModelConfigPicker() {
  if (platformModels.value.length === 0) {
    uni.showToast({ title: '暂无可用模型配置', icon: 'none' })
    return
  }
  const itemList = platformModels.value.map((model) => {
    const providerLabel = providerTypeShortLabel(providerTypeFromWorkerBackend(model.workerBackend))
    return providerLabel ? `${providerLabel} · ${model.name}` : model.name
  })
  uni.showActionSheet({
    itemList,
    success: (res) => {
      const config = platformModels.value[res.tapIndex]
      if (config) applyModelConfig(config.id, selectedModel.value)
    },
  })
}

function showResumeModelPicker() {
  const options = modelOptions.value
  if (options.length === 0) {
    uni.showToast({ title: '暂无可用模型', icon: 'none' })
    return
  }
  if (isCodexBackend(selectedModelConfig.value?.workerBackend)) {
    codexModelPickerVisible.value = true
    return
  }
  uni.showActionSheet({
    itemList: options.map(option => option.label),
    success: (res) => {
      const option = options[res.tapIndex]
      if (option) selectResumeModel(option.value)
    },
  })
}

function selectResumeModel(model: string) {
  selectedModel.value = model
  codexModelPickerVisible.value = false
  if (sessionId.value) {
    setSessionModel(
      sessionId.value,
      selectedModelConfigId.value,
      selectedModel.value,
      selectedProviderType.value,
    )
  }
}

function confirmCreateNewSession(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '创建新会话',
      content: '当前模型使用不同的执行后端，无法续接原生会话。是否创建新会话？',
      confirmText: '创建新会话',
      cancelText: '取消',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
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

function startEditTitle() {
  titleDraft.value = conversationConfig.value?.customTitle || taskStream.task.value?.prompt || ''
  editingTitle.value = true
}

function cancelEditTitle() {
  editingTitle.value = false
  titleDraft.value = ''
}

async function saveTitle() {
  const title = titleDraft.value.trim()
  const sid = taskStream.task.value?.sessionId || sessionId.value
  if (!sid) return
  if (!title) {
    uni.showToast({ title: '标题不能为空', icon: 'none' })
    return
  }
  savingTitle.value = true
  try {
    conversationConfig.value = await updateConversationTitle(sid, title)
    editingTitle.value = false
    uni.showToast({ title: '标题已更新', icon: 'success' })
  } catch (e) {
    console.error('Failed to update title:', e)
    uni.showToast({ title: '更新失败', icon: 'error' })
  } finally {
    savingTitle.value = false
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
  if (!task || !canResumeTask(task)) return

  // Get cached model selection for this session
  const cached = getSessionModel(task.sessionId)
  const modelConfigId = selectedModelConfigId.value || cached?.modelConfigId || task.modelConfigId
  const model = selectedModel.value || cached?.model || task.model
  const targetConfig = platformModels.value.find(config => config.id === modelConfigId)
  const targetProviderType = providerTypeFromWorkerBackend(targetConfig?.workerBackend)
    || cached?.providerType
    || taskProviderType.value
  const shouldCreateNewSession = requiresNewSessionForProvider(
    taskProviderType.value,
    targetConfig?.workerBackend || workerBackendFromProviderType(targetProviderType),
  )
  const imagesJson = toImagesJson(attachments.value)
  const chatImages = attachments.value
    .filter(att => att.isImage && att.previewUrl)
    .map(att => ({ name: att.name, url: att.previewUrl }))

  try {
    const continuation = await executeTaskContinuation({
      requiresNewSession: shouldCreateNewSession,
      confirmNewSession: confirmCreateNewSession,
      createNewSession: () => createTaskUnified({
        workerId: task.workerId,
        prompt,
        cwd: task.cwd,
        directoryId: task.directoryId,
        model,
        modelConfigId,
        providerType: targetProviderType,
        images: imagesJson,
      }),
      resumeSession: () => resumeTaskUnified({
        workerId: task.workerId,
        prompt,
        cwd: task.cwd,
        directoryId: task.directoryId,
        sessionId: task.sessionId,
        model,
        modelConfigId,
        providerType: targetProviderType,
        images: imagesJson,
      }),
    })
    if (continuation.mode === 'cancelled') {
      resumeInput.value = prompt
      return
    }

    const newTask = continuation.task
    if (continuation.mode === 'created') {
      clearAttachments()
      onSent(prompt)
      uni.showToast({ title: '已创建新会话', icon: 'success' })
      uni.redirectTo({
        url: `/pages/worker/task-detail?taskId=${newTask.taskId}&sessionId=${newTask.sessionId}`,
      })
      return
    }

    taskStream.resumeInPlace(newTask, chatImages)
    taskId.value = newTask.taskId
    sessionId.value = newTask.sessionId
    clearAttachments()
    // Update model cache
    initFromTask(newTask)
    setSessionModel(
      newTask.sessionId,
      newTask.modelConfigId || modelConfigId || '',
      newTask.model || model || '',
      newTask.providerType || targetProviderType,
    )
    onSent(prompt)

    // Sync URL to new taskId (H5 only, via history.replaceState)
    // uni-app H5 uses hash routing: #/pages/worker/task-detail?taskId=xxx&sessionId=xxx
    // We must update the hash part, NOT the pathname query string
    // #ifdef H5
    try {
      const hashBase = window.location.hash.split('?')[0] // e.g. "#/pages/worker/task-detail"
      const newHash = `${hashBase}?taskId=${newTask.taskId}&sessionId=${newTask.sessionId}`
      const newUrl = `${window.location.pathname}${window.location.search}${newHash}`
      window.history.replaceState(null, '', newUrl)
    } catch {
      // best-effort: URL sync is non-critical
    }
    // #endif
  } catch (e) {
    console.error('Failed to resume task:', e)
    resumeInput.value = prompt
    uni.showToast({ title: '继续任务失败', icon: 'error' })
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
    taskStream.chatState.resolvePermission(
      permissionId, decision === 'allow' ? 'approved' : 'denied',
    )
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
    taskStream.chatState.resolvePermission(
      permissionId, decision === 'allow' ? 'approved' : 'denied',
    )
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
.session-title-bar {
  background: #ffffff;
  padding: 16rpx 24rpx;
  border-bottom: 2rpx solid #f0f0f0;
}
.title-display-row,
.title-edit-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  min-height: 64rpx;
}
.session-title {
  flex: 1;
  min-width: 0;
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title-edit-btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 8rpx 18rpx;
  border-radius: 10rpx;
  background: #ecf5ff;
  color: #409eff;
  font-size: 24rpx;
}
.title-input {
  flex: 1;
  min-width: 0;
  height: 64rpx;
  padding: 0 18rpx;
  border-radius: 12rpx;
  background: #f5f7fa;
  color: #303133;
  font-size: 28rpx;
  box-sizing: border-box;
}
.title-action {
  flex-shrink: 0;
  height: 64rpx;
  margin-left: 12rpx;
  padding: 0 20rpx;
  border-radius: 12rpx;
  background: #409eff;
  color: #ffffff;
  font-size: 24rpx;
  line-height: 64rpx;
}
.title-action.ghost {
  background: #f0f2f5;
  color: #606266;
}
.task-status-bar {
  display: flex;
  flex-direction: row;
  align-items: center;
  padding: 16rpx 24rpx;
  background-color: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
  flex-wrap: wrap;
  gap: 8rpx;
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
.continuation-options {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx;
  padding: 14rpx 24rpx;
  background: #ffffff;
  border-top: 2rpx solid #f0f0f0;
}
.continuation-option {
  flex: 1 1 260rpx;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  padding: 8rpx 16rpx;
  background: #f5f7fa;
  border: 1rpx solid #e4e7ed;
  border-radius: 8rpx;
}
.continuation-option-label {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 22rpx;
  color: #606266;
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
