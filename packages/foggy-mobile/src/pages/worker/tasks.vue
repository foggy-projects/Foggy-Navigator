<template>
  <view class="tasks-page">
    <!-- 创建会话输入栏 -->
    <view class="task-input-bar">
      <view class="project-name-row">
        <text class="project-name">{{ projectName }}</text>
      </view>
      <view class="input-row">
        <view
          v-if="historyItems.length > 0"
          class="history-btn"
          @tap="showHistory"
        >
          <text class="history-icon">&#x23F1;</text>
        </view>
        <textarea
          v-model="promptInput"
          class="task-textarea"
          placeholder="输入任务描述..."
          :auto-height="true"
          :maxlength="-1"
        />
        <button
          class="run-btn"
          :disabled="!promptInput.trim() || creating"
          :loading="creating"
          @tap="handleCreateTask"
        >
          运行
        </button>
      </view>
      <!-- 模型 / 轮次 选择行 -->
      <view class="option-row">
        <view v-if="platformModels.length > 0" class="option-tag option-tag--api" @tap="showApiModelPicker">
          <text class="option-label">{{ selectedModelName ? 'API: ' + selectedModelName : 'API 凭证' }}</text>
          <text v-if="selectedModelConfigId" class="option-clear" @tap.stop="selectedModelConfigId = ''">&#x2715;</text>
        </view>
        <view class="option-tag" @tap="showModelPicker">
          <text class="option-label">{{ selectedModel || '默认模型' }}</text>
          <text v-if="selectedModel" class="option-clear" @tap.stop="selectedModel = ''">&#x2715;</text>
        </view>
        <view class="option-tag" @tap="showTurnsPicker">
          <text class="option-label">{{ selectedTurns ? selectedTurns + ' 轮' : '默认轮次' }}</text>
          <text v-if="selectedTurns" class="option-clear" @tap.stop="selectedTurns = 0">&#x2715;</text>
        </view>
        <view class="option-tag" @tap="showPermissionPicker">
          <text class="option-label">{{ PERMISSION_LABELS[selectedPermission] }}</text>
        </view>
      </view>
    </view>

    <!-- 过滤栏 -->
    <view class="filter-bar">
      <view class="filter-group">
        <text
          v-for="opt in STATE_FILTERS"
          :key="opt.value"
          :class="['filter-tag', opt.cls || '', { active: stateFilter === opt.value }]"
          @tap="setStateFilter(opt.value)"
        >{{ opt.label }}</text>
      </view>
      <view class="filter-group">
        <text
          v-for="opt in SOURCE_FILTERS"
          :key="opt.value"
          :class="['filter-tag', { active: sourceFilter === opt.value }]"
          @tap="setSourceFilter(opt.value)"
        >{{ opt.label }}</text>
      </view>
    </view>

    <!-- 会话列表 (使用原生页面滚动) -->
    <view class="task-list">
      <view v-if="loading && displayGroups.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>
      <template v-else-if="displayGroups.length > 0">
        <SessionCard
          v-for="group in displayGroups"
          :key="group.sessionId"
          :group="group"
          :model-configs="platformModels"
          @tap="openSession(group)"
          @longpress="showSessionActions(group)"
        />
        <view v-if="hasMore" class="load-more">
          <text class="load-more-text" @tap="loadMore">加载更多</text>
        </view>
      </template>
      <EmptyState
        v-else-if="!loading"
        icon="&#x1F4CB;"
        title="暂无会话"
        :description="stateFilter === 'ALL' ? '输入任务描述并点击运行' : '当前筛选条件下暂无会话'"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { onLoad, onShow, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { createTaskUnified, listTasksByDirPagedUnified, deleteTaskUnified } from '@/api/unifiedTask'
import { listConversationConfigs, updateConversationPin, archiveConversation, holdConversation, unholdConversation, unarchiveConversation } from '@/api/conversationConfig'
import { listModelConfigs, listAgentModelOverrides } from '@/api/platform'
import type { DispatchTask, ConversationGroup, ConversationConfig, LlmModelConfig } from '@/api/types'
import { buildConversationGroups, getGroupInteractionState } from '@/composables/useConversationGroup'
import { useInputMemory } from '@/composables/useInputMemory'
import SessionCard from '@/components/SessionCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const workerId = ref('')
const directoryId = ref('')
const projectName = ref('')
const promptInput = ref('')
const creating = ref(false)
const loading = ref(false)

// Session-driven data
const tasks = ref<DispatchTask[]>([])
const conversationConfigs = ref<Map<string, ConversationConfig>>(new Map())
const groups = ref<ConversationGroup[]>([])
const page = ref(0)
const totalSessions = ref(0)
const PAGE_SIZE = 20

// Platform models
const platformModels = ref<LlmModelConfig[]>([])
const selectedModelConfigId = ref('')
const selectedModelName = computed(() => {
  const m = platformModels.value.find(m => m.id === selectedModelConfigId.value)
  return m ? m.name : ''
})

// Model / turns
const MODEL_OPTIONS = ['Sonnet 4', 'Opus 4', 'Haiku 4', 'Sonnet 3.5']
const TURNS_OPTIONS = [10, 25, 50, 200, 999]
const PERMISSION_MODES = ['bypassPermissions', 'acceptEdits', 'default'] as const
const PERMISSION_LABELS: Record<string, string> = {
  bypassPermissions: '跳过权限',
  acceptEdits: '审批编辑',
  default: '全部审批',
}
const selectedModel = ref('')
const selectedTurns = ref(0)
const selectedPermission = ref<string>('bypassPermissions')

// Filter state
const STATE_FILTERS = [
  { label: '活跃', value: 'ACTIVE', cls: 'filter-tag--active' },
  { label: '待回复', value: 'AWAITING_REPLY', cls: 'filter-tag--awaiting' },
  { label: '处理中', value: 'PROCESSING', cls: 'filter-tag--processing' },
  { label: '已搁置', value: 'ON_HOLD', cls: '' },
  { label: '已归档', value: 'ARCHIVED', cls: '' },
  { label: '全部', value: 'ALL', cls: '' },
]
const SOURCE_FILTERS = [
  { label: '平台', value: 'PLATFORM' },
  { label: '同步', value: 'SYNCED' },
  { label: '全部来源', value: 'ALL' },
]
const stateFilter = ref('ACTIVE')
const sourceFilter = ref('PLATFORM')

// Active interaction states for client-side filtering
const ACTIVE_STATES = new Set(['PROCESSING', 'AWAITING_REPLY'])

// Draft & history
const memoryScope = computed(() => directoryId.value ? 'task-' + directoryId.value : '')
const { saveDraft, loadDraft, clearDraft, addToHistory, recentItems } = useInputMemory(memoryScope)

const historyItems = computed(() => recentItems(10))
const hasMore = computed(() => (page.value + 1) * PAGE_SIZE < totalSessions.value)

// Combined filter: server-side state + client-side source + client-side active
const displayGroups = computed(() => {
  let list = groups.value

  // Client-side "ACTIVE" filter: only show active sessions
  if (stateFilter.value === 'ACTIVE') {
    list = list.filter(g => {
      const state = getGroupInteractionState(g)
      return state ? ACTIVE_STATES.has(state) : false
    })
  }

  // Client-side source filter
  if (sourceFilter.value !== 'ALL') {
    list = list.filter(g => {
      const isPlatform = g.latestTask.source === 'PLATFORM' || g.latestTask.source == null
      return sourceFilter.value === 'PLATFORM' ? isPlatform : !isPlatform
    })
  }

  return list
})

function currentStateParam(): string | undefined {
  // ACTIVE and ALL: fetch all from server, filter client-side
  if (stateFilter.value === 'ALL' || stateFilter.value === 'ACTIVE') return undefined
  return stateFilter.value
}

function setStateFilter(val: string) {
  stateFilter.value = val
  loadSessions()
}

function setSourceFilter(val: string) {
  sourceFilter.value = val
}

// Auto-save draft on input change
watch(promptInput, (val) => {
  saveDraft(val)
})

onLoad((options) => {
  workerId.value = options?.workerId || ''
  directoryId.value = options?.directoryId || ''
  projectName.value = decodeURIComponent(options?.projectName || '')
  // Restore draft
  const draft = loadDraft()
  if (draft) promptInput.value = draft
  loadSessions()
  loadPlatformModels()
})

onShow(() => {
  if (directoryId.value) {
    loadSessions()
  }
})

// 原生下拉刷新
onPullDownRefresh(async () => {
  await loadSessions()
  uni.stopPullDownRefresh()
})

// 原生触底加载更多
onReachBottom(() => {
  loadMore()
})

async function loadPlatformModels() {
  try {
    const [models, overrides] = await Promise.all([
      listModelConfigs(),
      listAgentModelOverrides(),
    ])
    platformModels.value = models.filter(m => m.hasApiKey)
    // Auto-select claude-worker's agent-model override if exists
    const override = overrides.find(o => o.agentId === 'claude-worker')
    if (override && platformModels.value.some(m => m.id === override.modelConfigId)) {
      selectedModelConfigId.value = override.modelConfigId
    } else if (platformModels.value.length > 0) {
      selectedModelConfigId.value = platformModels.value[0]!.id
    }
  } catch {
    // best-effort
  }
}

async function loadSessions() {
  if (!directoryId.value) return
  loading.value = true
  try {
    const result = await listTasksByDirPagedUnified(directoryId.value, 0, PAGE_SIZE, currentStateParam())
    tasks.value = result.content
    totalSessions.value = result.totalSessions
    page.value = 0

    // Batch-load conversation configs
    await loadConfigs(tasks.value)

    // Build conversation groups
    rebuildGroups()
  } catch (e) {
    console.error('Failed to load sessions:', e)
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loading.value) return
  loading.value = true
  try {
    const nextPage = page.value + 1
    const result = await listTasksByDirPagedUnified(directoryId.value, nextPage, PAGE_SIZE, currentStateParam())
    tasks.value.push(...result.content)
    page.value = nextPage
    totalSessions.value = result.totalSessions

    // Load configs for new tasks
    await loadConfigs(result.content)

    // Rebuild groups
    rebuildGroups()
  } catch (e) {
    console.error('Failed to load more sessions:', e)
  } finally {
    loading.value = false
  }
}

async function loadConfigs(taskList: DispatchTask[]) {
  const sessionIds = [...new Set(taskList.map(t => t.sessionId).filter(Boolean))]
  const newIds = sessionIds.filter(id => !conversationConfigs.value.has(id))
  if (newIds.length === 0) return
  try {
    const configs = await listConversationConfigs(newIds)
    for (const cfg of configs) {
      conversationConfigs.value.set(cfg.sessionId, cfg)
    }
  } catch {
    // best-effort: configs are optional
  }
}

function rebuildGroups() {
  groups.value = buildConversationGroups(tasks.value, conversationConfigs.value)
}

async function handleCreateTask() {
  if (!promptInput.value.trim() || !workerId.value) return
  creating.value = true
  try {
    const form: Parameters<typeof createTaskUnified>[0] = {
      workerId: workerId.value,
      prompt: promptInput.value.trim(),
      directoryId: directoryId.value,
    }
    if (selectedModelConfigId.value) form.modelConfigId = selectedModelConfigId.value
    if (selectedModel.value) form.model = selectedModel.value
    if (selectedTurns.value) form.maxTurns = selectedTurns.value
    if (selectedPermission.value) form.permissionMode = selectedPermission.value

    const task = await createTaskUnified(form)
    addToHistory(promptInput.value.trim())
    clearDraft()
    promptInput.value = ''
    // Navigate to session detail
    uni.navigateTo({
      url: `/pages/worker/task-detail?taskId=${task.taskId}&sessionId=${task.sessionId}`,
    })
  } catch (e) {
    console.error('Failed to create task:', e)
    uni.showToast({ title: '创建任务失败', icon: 'error' })
  } finally {
    creating.value = false
  }
}

function openSession(group: ConversationGroup) {
  uni.navigateTo({
    url: `/pages/worker/task-detail?taskId=${group.latestTask.taskId}&sessionId=${group.sessionId}`,
  })
}

function showSessionActions(group: ConversationGroup) {
  const isPinned = group.config?.pinned ?? false
  const interactionState = group.config?.interactionState
  const isArchived = interactionState === 'ARCHIVED'
  const isOnHold = interactionState === 'ON_HOLD'

  const actions: { label: string; handler: () => Promise<void> }[] = [
    {
      label: isPinned ? '取消置顶' : '置顶',
      handler: async () => {
        try {
          const cfg = await updateConversationPin(group.sessionId, !isPinned)
          conversationConfigs.value.set(group.sessionId, cfg)
          rebuildGroups()
        } catch { uni.showToast({ title: '操作失败', icon: 'error' }) }
      },
    },
  ]

  if (isArchived) {
    actions.push({
      label: '取消归档',
      handler: async () => {
        try {
          const cfg = await unarchiveConversation(group.sessionId)
          conversationConfigs.value.set(group.sessionId, cfg)
          rebuildGroups()
        } catch { uni.showToast({ title: '操作失败', icon: 'error' }) }
      },
    })
  } else {
    if (isOnHold) {
      actions.push({
        label: '取消搁置',
        handler: async () => {
          try {
            const cfg = await unholdConversation(group.sessionId)
            conversationConfigs.value.set(group.sessionId, cfg)
            rebuildGroups()
          } catch { uni.showToast({ title: '操作失败', icon: 'error' }) }
        },
      })
    } else {
      actions.push({
        label: '搁置',
        handler: async () => {
          try {
            const cfg = await holdConversation(group.sessionId)
            conversationConfigs.value.set(group.sessionId, cfg)
            rebuildGroups()
          } catch { uni.showToast({ title: '操作失败', icon: 'error' }) }
        },
      })
    }
    actions.push({
      label: '归档',
      handler: async () => {
        try {
          const cfg = await archiveConversation(group.sessionId)
          conversationConfigs.value.set(group.sessionId, cfg)
          rebuildGroups()
        } catch { uni.showToast({ title: '操作失败', icon: 'error' }) }
      },
    })
  }

  actions.push({
    label: '删除最近任务',
    handler: async () => {
      uni.showModal({
        title: '确认删除',
        content: '确定删除此会话的最近任务？',
        success: async (modalRes) => {
          if (modalRes.confirm) {
            try {
              await deleteTaskUnified(group.latestTask.taskId)
              // Reload to reflect changes
              await loadSessions()
              uni.showToast({ title: '已删除', icon: 'success' })
            } catch {
              uni.showToast({ title: '删除失败', icon: 'error' })
            }
          }
        },
      })
    },
  })

  uni.showActionSheet({
    itemList: actions.map(a => a.label),
    success: (res) => {
      actions[res.tapIndex].handler()
    },
  })
}

function showHistory() {
  const items = historyItems.value
  if (items.length === 0) return
  const truncated = items.map(s => s.length > 40 ? s.slice(0, 40) + '...' : s)
  uni.showActionSheet({
    itemList: truncated,
    success: (res) => {
      promptInput.value = items[res.tapIndex]
    },
  })
}

function showApiModelPicker() {
  const names = platformModels.value.map(m => m.name)
  uni.showActionSheet({
    itemList: names,
    success: (res) => {
      selectedModelConfigId.value = platformModels.value[res.tapIndex].id
    },
  })
}

function showModelPicker() {
  uni.showActionSheet({
    itemList: MODEL_OPTIONS,
    success: (res) => {
      selectedModel.value = MODEL_OPTIONS[res.tapIndex]
    },
  })
}

function showTurnsPicker() {
  const labels = TURNS_OPTIONS.map(n => n + ' 轮')
  uni.showActionSheet({
    itemList: labels,
    success: (res) => {
      selectedTurns.value = TURNS_OPTIONS[res.tapIndex]
    },
  })
}

function showPermissionPicker() {
  const labels = PERMISSION_MODES.map(m => PERMISSION_LABELS[m])
  uni.showActionSheet({
    itemList: labels,
    success: (res) => {
      selectedPermission.value = PERMISSION_MODES[res.tapIndex]
    },
  })
}
</script>

<style scoped>
.tasks-page {
  background-color: #f5f5f5;
  min-height: 100vh;
}
.task-input-bar {
  padding: 20rpx 24rpx;
  background-color: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.project-name-row {
  margin-bottom: 16rpx;
}
.project-name {
  font-size: 30rpx;
  color: #303133;
  font-weight: 600;
}
.input-row {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
}
.history-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-right: 16rpx;
}
.history-icon {
  font-size: 36rpx;
}
.task-textarea {
  flex: 1;
  min-height: 64rpx;
  max-height: 200rpx;
  padding: 16rpx 24rpx;
  font-size: 28rpx;
  background-color: #f5f5f5;
  border-radius: 16rpx;
  line-height: 1.5;
  margin-right: 16rpx;
}
.run-btn {
  width: 120rpx;
  height: 68rpx;
  font-size: 28rpx;
  background-color: #667eea;
  color: #ffffff;
  border-radius: 16rpx;
  border: none;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.run-btn[disabled] {
  opacity: 0.5;
}
.option-row {
  display: flex;
  flex-direction: row;
  margin-top: 16rpx;
  flex-wrap: wrap;
}
.option-tag {
  display: flex;
  flex-direction: row;
  align-items: center;
  padding: 8rpx 20rpx;
  background-color: #f0f2f5;
  border-radius: 24rpx;
  margin-right: 16rpx;
  margin-bottom: 8rpx;
}
.option-tag--api {
  background-color: #e8f5e9;
}
.option-tag--api .option-label {
  color: #2e7d32;
}
.option-label {
  font-size: 24rpx;
  color: #606266;
  margin-right: 8rpx;
}
.option-clear {
  font-size: 22rpx;
  color: #909399;
  padding: 0 4rpx;
}
/* 过滤栏 */
.filter-bar {
  padding: 16rpx 24rpx;
  background-color: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.filter-group {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  margin-bottom: 8rpx;
}
.filter-group:last-child {
  margin-bottom: 0;
}
.filter-tag {
  font-size: 24rpx;
  color: #909399;
  padding: 6rpx 20rpx;
  border-radius: 24rpx;
  background-color: transparent;
  margin-right: 8rpx;
  margin-bottom: 4rpx;
}
.filter-tag.active {
  color: #667eea;
  background-color: #ecf0ff;
  font-weight: 600;
}
.filter-tag--active.active {
  color: #67c23a;
  background-color: #f0f9eb;
}
.filter-tag--awaiting.active {
  color: #e6a23c;
  background-color: #fdf6ec;
}
.filter-tag--processing.active {
  color: #409eff;
  background-color: #ecf5ff;
}
/* 会话列表 */
.task-list {
  padding: 20rpx 24rpx;
}
.loading-wrap {
  padding: 48rpx;
  text-align: center;
}
.loading-text {
  font-size: 28rpx;
  color: #909399;
}
.load-more {
  padding: 24rpx;
  text-align: center;
}
.load-more-text {
  font-size: 26rpx;
  color: #667eea;
}
</style>
