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
          <text class="option-label">{{ selectedModelLabel || '默认模型' }}</text>
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
        <template v-if="useMilestoneGrouping">
          <view
            v-for="section in groupedDisplayGroups"
            :key="section.key"
            class="milestone-group"
          >
            <view class="milestone-group-header">
              <view class="milestone-group-main">
                <text class="milestone-group-title">{{ section.label }}</text>
                <text
                  v-if="section.milestone"
                  class="milestone-group-status"
                >{{ milestoneStatusLabel(section.milestone.status) }}</text>
                <text class="milestone-group-count">{{ section.conversations.length }} 个会话</text>
              </view>
              <text v-if="section.milestone?.docPath" class="milestone-group-doc">{{ section.milestone.docPath }}</text>
            </view>
            <SessionCard
              v-for="group in section.conversations"
              :key="group.sessionId"
              :group="group"
              :milestone="section.milestone"
              :model-configs="platformModels"
              @tap="openSession(group)"
              @longpress="showSessionActions(group)"
            />
          </view>
        </template>
        <template v-else>
          <SessionCard
            v-for="group in displayGroups"
            :key="group.sessionId"
            :group="group"
            :milestone="resolveMilestone(group)"
            :model-configs="platformModels"
            @tap="openSession(group)"
            @longpress="showSessionActions(group)"
          />
        </template>
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
import { listConversationConfigs, updateConversationMilestone, updateConversationPin, archiveConversation, holdConversation, unholdConversation, unarchiveConversation } from '@/api/conversationConfig'
import { listMilestones } from '@/api/claudeWorker'
import { listModelConfigs, listAgentModelOverrides } from '@/api/platform'
import type { DispatchTask, ConversationGroup, ConversationConfig, DirectoryMilestone, LlmModelConfig } from '@/api/types'
import { buildConversationGroups, getGroupInteractionState } from '@/composables/useConversationGroup'
import { buildMilestoneSections, resolveConversationMilestone } from '@/composables/useMilestoneGroups'
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
const milestoneOptions = ref<DirectoryMilestone[]>([])
const PAGE_SIZE = 40

// Platform models
const platformModels = ref<LlmModelConfig[]>([])
const selectedModelConfigId = ref('')
const selectedModelName = computed(() => {
  const m = platformModels.value.find(m => m.id === selectedModelConfigId.value)
  return m ? m.name : ''
})

// All known model options (value = what backend accepts, label = UI display)
//
// 源头真相（SSOT）：packages/navigator-frontend/src/utils/llmModelOptions.ts 中的
// ALL_MODEL_OPTIONS。由于 foggy-mobile 是独立的 uni-app 工程，未直接引用 navigator-frontend
// 的源码目录，所以在本文件保留一份"薄壁镜像"，但字段结构、value 集合必须与 PC SSOT 对齐。
//
// 1.0.4 alias-only 重构（见 docs/version-tracker/1.0.4-SNAPSHOT/04-codex-worker-gpt55-upgrade-and-model-alias-plan.md）：
// - Codex 切到 alias-only：前端只展示稳定 alias（codex-latest/fast/deep/mini）
// - Worker 在执行前把 alias 解析为真实模型（如 codex-latest → gpt-5.5）
// - 模型版本升级时仅改 Worker 配置（CODEX_MODEL_ALIASES），前端 / Java 后端无需任何改动
// - Claude / Gemini 命名风格相同：opus/sonnet/haiku、gemini-pro/flash/flash-lite
const ALL_MODELS: { value: string; label: string; backend: string }[] = [
  // Claude models
  { value: 'opus[1m]', label: 'Opus (1M)', backend: 'CLAUDE_CODE' },
  { value: 'opus', label: 'Opus', backend: 'CLAUDE_CODE' },
  { value: 'sonnet[1m]', label: 'Sonnet (1M)', backend: 'CLAUDE_CODE' },
  { value: 'sonnet', label: 'Sonnet', backend: 'CLAUDE_CODE' },
  { value: 'haiku', label: 'Haiku', backend: 'CLAUDE_CODE' },
  // Codex aliases (Worker 解析为真实模型；详见 Worker DEFAULT_CODEX_MODEL_ALIASES)
  { value: 'codex-latest', label: 'Codex Latest', backend: 'OPENAI_CODEX' },
  { value: 'codex-fast', label: 'Codex Fast', backend: 'OPENAI_CODEX' },
  { value: 'codex-deep', label: 'Codex Deep', backend: 'OPENAI_CODEX' },
  { value: 'codex-mini', label: 'Codex Mini', backend: 'OPENAI_CODEX' },
  // Gemini aliases（保持与 PC SSOT 对齐）
  { value: 'gemini-pro', label: 'Gemini Pro (Alias)', backend: 'GEMINI_CLI' },
  { value: 'gemini-flash', label: 'Gemini Flash (Alias)', backend: 'GEMINI_CLI' },
  { value: 'gemini-flash-lite', label: 'Gemini Flash Lite (Alias)', backend: 'GEMINI_CLI' },
]

// Dynamic model options based on selected platform model config
//
// 1.0.4 alias-only 兼容兜底（与 navigator-frontend resolveModelOptions 保持一致）：
// - OPENAI_CODEX backend 旧 availableModels 含真实模型（gpt-5.4 等）但无 alias 命中时，
//   退化为"不限制"，让用户在新 UI 里看到全部 alias，重新勾选保存即可完成迁移
const modelOptions = computed(() => {
  const cfg = platformModels.value.find(m => m.id === selectedModelConfigId.value)
  const backend = cfg?.workerBackend ?? 'CLAUDE_CODE'
  const backendModels = ALL_MODELS.filter(m => m.backend === backend)
  const allowed = cfg?.availableModels
  if (!allowed || allowed.length === 0) return backendModels
  const filtered = backendModels.filter(opt => allowed.includes(opt.value))
  if (backend === 'OPENAI_CODEX' && filtered.length === 0) {
    return backendModels
  }
  return filtered
})

const TURNS_OPTIONS = [10, 25, 50, 200, 999]
const PERMISSION_MODES = ['bypassPermissions', 'acceptEdits', 'default'] as const
const PERMISSION_LABELS: Record<string, string> = {
  bypassPermissions: '跳过权限',
  acceptEdits: '审批编辑',
  default: '全部审批',
}
const selectedModel = ref('')  // stores model value (e.g. 'opus[1m]'), NOT label
const selectedModelLabel = computed(() => {
  if (!selectedModel.value) return ''
  const opt = ALL_MODELS.find(m => m.value === selectedModel.value)
  return opt ? opt.label : selectedModel.value
})
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
const useMilestoneGrouping = computed(() => milestoneOptions.value.length > 0)

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
const groupedDisplayGroups = computed(() => buildMilestoneSections(displayGroups.value, milestoneOptions.value))

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
  loadMilestones()
  loadPlatformModels()
})

onShow(() => {
  if (directoryId.value) {
    loadSessions()
    loadMilestones()
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
      listModelConfigs(workerId.value || undefined),
      listAgentModelOverrides(),
    ])
    platformModels.value = models.filter(m => m.hasApiKey)
    // Auto-select claude-worker's agent-model override if exists
    const override = overrides.find(o => o.agentId === 'claude-worker')
    if (override && platformModels.value.some(m => m.id === override.modelConfigId)) {
      selectedModelConfigId.value = override.modelConfigId
    } else if (platformModels.value.length > 0) {
      selectedModelConfigId.value = platformModels.value[0]!.id
    } else {
      selectedModelConfigId.value = ''
    }
  } catch {
    // best-effort
  }
}

async function loadMilestones() {
  if (!directoryId.value) {
    milestoneOptions.value = []
    return
  }
  try {
    milestoneOptions.value = await listMilestones(directoryId.value)
  } catch (e) {
    milestoneOptions.value = []
    console.error('Failed to load milestones:', e)
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

function resolveMilestone(group: ConversationGroup): DirectoryMilestone | undefined {
  return resolveConversationMilestone(group, milestoneOptions.value)
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

  actions.push({
    label: '设置里程碑',
    handler: async () => {
      await showMilestonePicker(group)
    },
  })

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

async function showMilestonePicker(group: ConversationGroup) {
  if (milestoneOptions.value.length === 0) {
    await loadMilestones()
  }
  const options = milestoneOptions.value
  if (options.length === 0) {
    uni.showToast({ title: '当前目录暂无里程碑', icon: 'none' })
    return
  }

  const currentMilestoneId = group.config?.milestoneId
  const itemList = [
    currentMilestoneId ? '[当前] 未设置里程碑' : '未设置里程碑',
    ...options.map(milestone => formatMilestoneActionLabel(milestone, currentMilestoneId)),
  ]

  uni.showActionSheet({
    itemList,
    success: async (res) => {
      const nextMilestoneId = res.tapIndex === 0 ? undefined : options[res.tapIndex - 1]?.id
      const normalizedCurrent = currentMilestoneId || undefined
      if (nextMilestoneId === normalizedCurrent) return
      try {
        const cfg = await updateConversationMilestone(group.sessionId, nextMilestoneId)
        conversationConfigs.value.set(group.sessionId, cfg)
        rebuildGroups()
        uni.showToast({ title: '里程碑已更新', icon: 'success' })
      } catch {
        uni.showToast({ title: '更新失败', icon: 'error' })
      }
    },
  })
}

function formatMilestoneActionLabel(milestone: DirectoryMilestone, currentMilestoneId?: string): string {
  const currentPrefix = milestone.id === currentMilestoneId ? '[当前] ' : ''
  return `${currentPrefix}${milestone.name}（${milestoneStatusLabel(milestone.status)}）`
}

function milestoneStatusLabel(status?: string): string {
  switch (status) {
    case 'ACTIVE':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'ARCHIVED':
      return '已归档'
    case 'PLANNED':
      return '规划中'
    default:
      return status || '未知状态'
  }
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
  const options = modelOptions.value
  if (options.length === 0) {
    uni.showToast({ title: '暂无可用模型', icon: 'none' })
    return
  }
  const labels = options.map(m => m.label)
  uni.showActionSheet({
    itemList: labels,
    success: (res) => {
      selectedModel.value = options[res.tapIndex].value
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
.milestone-group {
  margin-bottom: 20rpx;
}
.milestone-group:last-child {
  margin-bottom: 0;
}
.milestone-group-header {
  padding: 0 4rpx 12rpx;
}
.milestone-group-main {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
}
.milestone-group-title {
  font-size: 28rpx;
  color: #303133;
  font-weight: 600;
  margin-right: 12rpx;
}
.milestone-group-status {
  font-size: 22rpx;
  color: #a05a07;
  background-color: #fff1d6;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  margin-right: 10rpx;
}
.milestone-group-count {
  font-size: 22rpx;
  color: #909399;
}
.milestone-group-doc {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #8c8c8c;
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
