import { ref, computed, watch, type Ref, type ComputedRef } from 'vue'
import { listModelConfigs } from '@/api/platform'
import type { ForwardTargetMode } from '@/api/unifiedTask'
import type { ClaudeTask, LlmModelConfig, DirectoryMilestone } from '@/types'
import type { useClaudeWorker } from './useClaudeWorker'

// ── Types ──

export type ForwardSourceContext = {
  sourceSessionId: string
  sourceMessageId: string
  sourceContent: string
  sourceWorkerId?: string
  sourceDirectoryId?: string
  sourceMilestoneId?: string
  sourceTask: ClaudeTask
}

/** Lightweight conversation group — mirrors the one in ClaudeWorkerView */
export interface ConversationGroup {
  sessionId: string
  parentSessionId?: string
  claudeSessionId: string
  codexThreadId: string
  latestTask: ClaudeTask
  tasks: ClaudeTask[]
  totalCost: number
  firstPrompt: string
  config?: { milestoneId?: string; [key: string]: unknown }
}

export interface ForwardSessionDeps {
  workerState: ReturnType<typeof useClaudeWorker>
  directoryTasks: Ref<ClaudeTask[]>
  groupTasksToConversations: (tasks: ClaudeTask[]) => ConversationGroup[]
  resolveConversationMilestone: (conv: ConversationGroup) => DirectoryMilestone | undefined
  shortModel: (model: string) => string
  milestoneStatusLabel: (status: string) => string
  formatTime: (dateStr: string) => string
  ALL_MODELS: { value: string; label: string; backend: string }[]
}

// ── Helpers ──

function isSelectablePlatformModel(model: LlmModelConfig): boolean {
  return model.hasApiKey || model.workerBackend === 'OPENAI_CODEX'
}

function defaultForwardForm() {
  return {
    sourceSessionId: '',
    sourceMessageId: '',
    targetMode: 'NEW_SESSION' as ForwardTargetMode,
    targetSessionId: '',
    workerId: '',
    directoryId: '',
    cwd: '',
    prompt: '',
    model: '',
    modelConfigId: '',
    permissionMode: 'bypassPermissions',
    milestoneId: '',
  }
}

// ── Composable ──

export function useForwardSession(deps: ForwardSessionDeps) {
  const { workerState, directoryTasks, groupTasksToConversations, resolveConversationMilestone, shortModel, milestoneStatusLabel, formatTime, ALL_MODELS } = deps

  // --- State ---
  const showForwardDialog = ref(false)
  const forwardSubmitting = ref(false)
  const forwardPlatformModels = ref<LlmModelConfig[]>([])
  const forwardSource = ref<ForwardSourceContext | null>(null)
  const forwardForm = ref(defaultForwardForm())
  let loadForwardModelConfigSeq = 0

  // --- Functions ---

  function resetForwardDialog() {
    forwardSource.value = null
    forwardPlatformModels.value = []
    forwardForm.value = defaultForwardForm()
  }

  async function loadForwardModelConfigs(workerId: string, preferredId?: string) {
    const seq = ++loadForwardModelConfigSeq
    if (!workerId) {
      forwardPlatformModels.value = []
      forwardForm.value.modelConfigId = ''
      return
    }
    try {
      const models = await listModelConfigs(workerId)
      if (seq !== loadForwardModelConfigSeq) return
      forwardPlatformModels.value = models.filter(isSelectablePlatformModel)
      const sourceConfigId = forwardSource.value?.sourceTask.modelConfigId
      const directoryDefaultId = forwardSelectedDirectory.value?.defaultModelConfigId
      const nextConfigId = [
        preferredId,
        directoryDefaultId,
        sourceConfigId,
        forwardPlatformModels.value[0]?.id,
      ].find((configId) => !!configId && forwardPlatformModels.value.some((model) => model.id === configId)) || ''
      forwardForm.value.modelConfigId = nextConfigId
    } catch {
      if (seq !== loadForwardModelConfigSeq) return
      forwardPlatformModels.value = []
      forwardForm.value.modelConfigId = ''
    }
  }

  /**
   * Initialize the forward dialog with source context.
   * Call this from handlePaneForward in ClaudeWorkerView.
   */
  async function openForwardDialog(opts: {
    task: ClaudeTask
    messageId: string
    sourceContent: string
    selectedWorkerId: string
    defaultModel: string
    defaultModelConfigId: string
    defaultPermissionMode: string
  }) {
    const { task, messageId, sourceContent, selectedWorkerId: fallbackWorkerId, defaultModel, defaultModelConfigId, defaultPermissionMode } = opts
    const sourceMilestoneId = workerState.conversationConfigs.value.get(task.sessionId)?.milestoneId || ''
    const initialWorkerId = task.workerId || fallbackWorkerId || ''
    const initialDirectoryId = task.directoryId || ''

    forwardSource.value = {
      sourceSessionId: task.sessionId,
      sourceMessageId: messageId,
      sourceContent,
      sourceWorkerId: task.workerId,
      sourceDirectoryId: task.directoryId,
      sourceMilestoneId,
      sourceTask: task,
    }
    forwardForm.value = {
      sourceSessionId: task.sessionId,
      sourceMessageId: messageId,
      targetMode: 'NEW_SESSION',
      targetSessionId: '',
      workerId: initialWorkerId,
      directoryId: initialDirectoryId,
      cwd: task.cwd || '',
      prompt: sourceContent,
      model: task.model || defaultModel || '',
      modelConfigId: task.modelConfigId || defaultModelConfigId || '',
      permissionMode: defaultPermissionMode || 'bypassPermissions',
      milestoneId: initialDirectoryId ? sourceMilestoneId : '',
    }

    if (initialWorkerId) {
      try {
        await workerState.loadDirectories(initialWorkerId)
      } catch {
        // best-effort
      }
      await loadForwardModelConfigs(initialWorkerId, forwardForm.value.modelConfigId || undefined)
    } else {
      forwardPlatformModels.value = []
    }

    showForwardDialog.value = true
  }

  /**
   * Submit the forward request via API.
   * Returns result containing the new task, or throws on error.
   */
  async function submitForward() {
    if (!forwardSource.value) throw new Error('缺少源会话信息')
    const prompt = forwardForm.value.prompt.trim()
    if (!prompt) throw new Error('请输入转发内容')
    if (forwardForm.value.targetMode === 'NEW_SESSION' && !forwardForm.value.workerId) {
      throw new Error('请选择目标 Worker')
    }
    if (forwardForm.value.targetMode === 'EXISTING_SESSION' && !forwardForm.value.targetSessionId) {
      throw new Error('请选择目标会话')
    }

    forwardSubmitting.value = true
    try {
      const sourceSessionId = forwardSource.value.sourceSessionId
      const targetMode = forwardForm.value.targetMode
      const result = await workerState.forwardSession({
        sourceSessionId,
        sourceMessageId: forwardSource.value.sourceMessageId,
        targetMode,
        targetSessionId: targetMode === 'EXISTING_SESSION'
          ? forwardForm.value.targetSessionId
          : undefined,
        workerId: targetMode === 'NEW_SESSION' ? forwardForm.value.workerId : undefined,
        directoryId: targetMode === 'NEW_SESSION' ? (forwardForm.value.directoryId || undefined) : undefined,
        cwd: targetMode === 'NEW_SESSION' ? (forwardForm.value.cwd.trim() || undefined) : undefined,
        prompt,
        model: targetMode === 'NEW_SESSION' ? (forwardForm.value.model || undefined) : undefined,
        modelConfigId: targetMode === 'NEW_SESSION' ? (forwardForm.value.modelConfigId || undefined) : undefined,
        permissionMode: targetMode === 'NEW_SESSION' ? (forwardForm.value.permissionMode || undefined) : undefined,
        milestoneId: targetMode === 'NEW_SESSION' && forwardForm.value.directoryId
          ? (forwardForm.value.milestoneId || undefined)
          : undefined,
      })

      showForwardDialog.value = false
      resetForwardDialog()
      return { result, sourceSessionId, targetMode }
    } catch (e) {
      throw e
    } finally {
      forwardSubmitting.value = false
    }
  }

  function forwardConversationOptionLabel(conv: ConversationGroup): string {
    const milestone = resolveConversationMilestone(conv)
    const milestoneText = milestone ? ` · ${milestone.name}` : ''
    return `${conv.firstPrompt || '未命名会话'} · ${formatTime(conv.latestTask.createdAt)}${milestoneText}`
  }

  function dirNameById(dirId?: string): string {
    if (!dirId) return '-'
    const dir = workerState.directories.value.find(d => d.directoryId === dirId)
    return dir?.projectName || dirId.substring(0, 8)
  }

  function providerTypeLabel(providerType?: string): string {
    if (providerType === 'claude-worker') return 'Claude Worker'
    if (providerType === 'codex-worker') return 'Codex Worker'
    return providerType || '-'
  }

  // --- Computed ---

  const forwardSourcePreview = computed(() => forwardSource.value?.sourceContent?.trim() || '')
  const forwardDialogTitle = computed(() =>
    forwardForm.value.targetMode === 'EXISTING_SESSION' ? '转发到已有会话' : '转发为新会话',
  )
  const forwardConversationPool = computed(() => {
    const taskMap = new Map<string, ClaudeTask>()
    for (const task of workerState.tasks.value) {
      taskMap.set(task.taskId, task)
    }
    for (const task of directoryTasks.value) {
      taskMap.set(task.taskId, task)
    }
    return groupTasksToConversations([...taskMap.values()])
  })
  const forwardExistingTargets = computed(() => {
    const sourceSessionId = forwardSource.value?.sourceSessionId
    if (!sourceSessionId) return [] as ConversationGroup[]
    return forwardConversationPool.value
      .filter((conv) => conv.parentSessionId === sourceSessionId)
      .sort((a, b) => new Date(b.latestTask.createdAt).getTime() - new Date(a.latestTask.createdAt).getTime())
  })
  const forwardSelectedExistingConversation = computed(() =>
    forwardExistingTargets.value.find((conv) => conv.sessionId === forwardForm.value.targetSessionId) || null,
  )
  const forwardDirectories = computed(() => {
    if (!forwardForm.value.workerId) return []
    return workerState.directories.value.filter((dir) => dir.workerId === forwardForm.value.workerId)
  })
  const forwardSelectedDirectory = computed(() =>
    workerState.directories.value.find((dir) => dir.directoryId === forwardForm.value.directoryId),
  )
  const forwardMilestoneOptions = computed(() => forwardSelectedDirectory.value?.milestones || [])
  const forwardSelectedModelConfig = computed(() =>
    forwardPlatformModels.value.find((model) => model.id === forwardForm.value.modelConfigId) || null,
  )
  const forwardModelOptions = computed(() => {
    const backend = forwardSelectedModelConfig.value?.workerBackend ?? 'CLAUDE_CODE'
    const backendModels = ALL_MODELS.filter((model) => model.backend === backend)
    const allowed = forwardSelectedModelConfig.value?.availableModels
    if (!allowed || allowed.length === 0) return backendModels
    return backendModels.filter((model) => allowed.includes(model.value))
  })
  const forwardExistingWorkerName = computed(() => {
    const workerId = forwardSelectedExistingConversation.value?.latestTask.workerId
    if (!workerId) return '-'
    return workerState.workers.value.find((worker) => worker.workerId === workerId)?.name || workerId
  })
  const forwardExistingDirectoryName = computed(() => {
    const directoryId = forwardSelectedExistingConversation.value?.latestTask.directoryId
    return directoryId ? dirNameById(directoryId) : '-'
  })
  const forwardExistingProviderLabel = computed(() => {
    const providerType = forwardSelectedExistingConversation.value?.latestTask.providerType
    return providerType ? providerTypeLabel(providerType) : '-'
  })
  const forwardExistingModelConfigLabel = computed(() => {
    const task = forwardSelectedExistingConversation.value?.latestTask
    if (!task?.modelConfigId) return '-'
    const modelConfig = forwardPlatformModels.value.find((item) => item.id === task.modelConfigId)
    return modelConfig ? `${modelConfig.name} (${task.modelConfigId})` : task.modelConfigId
  })
  const forwardExistingModelLabel = computed(() => {
    const model = forwardSelectedExistingConversation.value?.latestTask.model
    return model ? shortModel(model) : '-'
  })
  const forwardExistingMilestoneLabel = computed(() => {
    const conv = forwardSelectedExistingConversation.value
    if (!conv) return '-'
    const milestone = resolveConversationMilestone(conv)
    return milestone ? `${milestone.name} (${milestoneStatusLabel(milestone.status)})` : '未设置'
  })
  const forwardSubmitButtonText = computed(() =>
    forwardForm.value.targetMode === 'EXISTING_SESSION' ? '转发追加' : '转发创建',
  )
  const forwardSubmitDisabled = computed(() => {
    if (!forwardForm.value.prompt.trim()) return true
    if (forwardForm.value.targetMode === 'EXISTING_SESSION') {
      return !forwardForm.value.targetSessionId
    }
    return !forwardForm.value.workerId
  })

  // --- Watchers ---

  watch(() => showForwardDialog.value, (visible) => {
    if (!visible && !forwardSubmitting.value) {
      resetForwardDialog()
    }
  })

  watch(() => forwardForm.value.targetMode, (mode) => {
    if (mode === 'EXISTING_SESSION') {
      if (!forwardExistingTargets.value.some((conv) => conv.sessionId === forwardForm.value.targetSessionId)) {
        forwardForm.value.targetSessionId = forwardExistingTargets.value[0]?.sessionId || ''
      }
      return
    }
    forwardForm.value.targetSessionId = ''
  })

  watch(forwardExistingTargets, (targets) => {
    if (forwardForm.value.targetMode !== 'EXISTING_SESSION') {
      return
    }
    if (!targets.some((conv) => conv.sessionId === forwardForm.value.targetSessionId)) {
      forwardForm.value.targetSessionId = targets[0]?.sessionId || ''
    }
  })

  watch(() => forwardForm.value.targetSessionId, async (sessionId) => {
    if (forwardForm.value.targetMode !== 'EXISTING_SESSION') {
      return
    }
    const target = forwardExistingTargets.value.find((conv) => conv.sessionId === sessionId)
    if (!target?.latestTask.workerId) {
      forwardPlatformModels.value = []
      return
    }
    try {
      await workerState.loadDirectories(target.latestTask.workerId)
    } catch {
      // best-effort
    }
    await loadForwardModelConfigs(target.latestTask.workerId, target.latestTask.modelConfigId || undefined)
  })

  watch(() => forwardForm.value.workerId, async (workerId) => {
    if (forwardForm.value.targetMode !== 'NEW_SESSION') {
      return
    }
    if (!workerId) {
      forwardPlatformModels.value = []
      forwardForm.value.directoryId = ''
      forwardForm.value.modelConfigId = ''
      return
    }
    try {
      await workerState.loadDirectories(workerId)
    } catch {
      // best-effort
    }
    const selectedDir = workerState.directories.value.find((dir) => dir.directoryId === forwardForm.value.directoryId)
    if (selectedDir && selectedDir.workerId !== workerId) {
      forwardForm.value.directoryId = ''
    }
    await loadForwardModelConfigs(workerId, forwardForm.value.modelConfigId || undefined)
  })

  watch(() => forwardForm.value.directoryId, (directoryId) => {
    if (forwardForm.value.targetMode !== 'NEW_SESSION') {
      return
    }
    const dir = workerState.directories.value.find((item) => item.directoryId === directoryId)
    if (!dir) {
      forwardForm.value.milestoneId = ''
      return
    }
    if (forwardForm.value.workerId !== dir.workerId) {
      forwardForm.value.workerId = dir.workerId
    }
    forwardForm.value.cwd = dir.path
    if (dir.defaultModelConfigId && forwardPlatformModels.value.some((model) => model.id === dir.defaultModelConfigId)) {
      forwardForm.value.modelConfigId = dir.defaultModelConfigId
    }
    const source = forwardSource.value
    const milestoneIds = new Set((dir.milestones || []).map((milestone) => milestone.id))
    if (source?.sourceDirectoryId === directoryId && source.sourceMilestoneId && milestoneIds.has(source.sourceMilestoneId)) {
      forwardForm.value.milestoneId = source.sourceMilestoneId
      return
    }
    if (!forwardForm.value.milestoneId || !milestoneIds.has(forwardForm.value.milestoneId)) {
      forwardForm.value.milestoneId = ''
    }
  })

  watch(forwardModelOptions, (options) => {
    if (forwardForm.value.targetMode !== 'NEW_SESSION') {
      return
    }
    if (options.length === 0) {
      forwardForm.value.model = ''
      return
    }
    if (!options.some((option) => option.value === forwardForm.value.model)) {
      forwardForm.value.model = options[0]!.value
    }
  })

  // --- Return ---

  return {
    // Dialog state
    showForwardDialog,
    forwardSubmitting,
    forwardForm,
    forwardSource,
    forwardPlatformModels,
    // Computed for template
    forwardSourcePreview,
    forwardDialogTitle,
    forwardConversationPool,
    forwardExistingTargets,
    forwardSelectedExistingConversation,
    forwardDirectories,
    forwardSelectedDirectory,
    forwardMilestoneOptions,
    forwardSelectedModelConfig,
    forwardModelOptions,
    forwardExistingWorkerName,
    forwardExistingDirectoryName,
    forwardExistingProviderLabel,
    forwardExistingModelConfigLabel,
    forwardExistingModelLabel,
    forwardExistingMilestoneLabel,
    forwardSubmitButtonText,
    forwardSubmitDisabled,
    // Methods
    openForwardDialog,
    submitForward,
    resetForwardDialog,
    forwardConversationOptionLabel,
  }
}
