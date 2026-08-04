import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import {
  cancelFapConversation,
  continueFapConversation,
  getFapAvailability,
  getFapCatalog,
  getFapConversation,
  getFapEvents,
  getFapRecovery,
  getFapResources,
  listFapConversations,
  reattachFapConversation,
  startFapConversation,
  type FapAvailability,
  type FapCatalogEntry,
  type FapCatalogResourceType,
  type FapContinueConversationForm,
  type FapConversation,
  type FapEvent,
  type FapProviderOptions,
  type FapRecoveryView,
  type FapResourceRef,
  type FapStartConversationForm,
} from '@/api/workbenchFap'

const POLL_INTERVAL_MS = 2_500
const MAX_CONSECUTIVE_POLL_FAILURES = 3

type AvailabilityState = 'loading' | 'eligible' | 'disabled' | 'not-packaged' | 'not-eligible'

export function newFapRequestId(operation: string): string {
  const suffix = typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `workbench-${operation}-${suffix}`
}

export function codexProviderOptions(reasoningEffort?: string): FapProviderOptions | undefined {
  if (!reasoningEffort) return undefined
  return {
    namespace: 'foggy.codex',
    version: '1',
    payload: { reasoningEffort },
  }
}

export function useWorkbenchFap() {
  const availability = ref<FapAvailability>()
  const availabilityState = ref<AvailabilityState>('loading')
  const conversations = ref<FapConversation[]>([])
  const selectedConversation = ref<FapConversation>()
  const events = ref<FapEvent[]>([])
  const resources = ref<FapResourceRef[]>([])
  const recovery = ref<FapRecoveryView>()
  const workerProfiles = ref<FapCatalogEntry[]>([])
  const workspaces = ref<FapCatalogEntry[]>([])
  const modelConfigs = ref<FapCatalogEntry[]>([])

  const initializing = ref(false)
  const initializationError = ref<string>()
  const catalogLoading = ref(false)
  const conversationsLoading = ref(false)
  const selectedLoading = ref(false)
  const commandPending = ref(false)
  const evidenceLoading = ref(false)
  const pollingPaused = ref(false)
  const pollingFailureCount = ref(0)
  const lastPollingError = ref<string>()

  let initialized = false
  let pageActive = false
  let refreshInFlight = false
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let selectedEpoch = 0
  let nextAfterSeq = 0
  let eventPageHasMore = false

  const isEligible = computed(() => availabilityState.value === 'eligible')
  const isTaskRunning = computed(() => {
    const value = selectedConversation.value
    return value?.bindingStatus === 'ACTIVE' && value.definitiveTerminal !== true
  })
  const canContinue = computed(() => {
    const value = selectedConversation.value
    return value?.bindingStatus === 'ACTIVE' && value.definitiveTerminal === true
  })
  const canCancel = computed(() => isTaskRunning.value && !commandPending.value)

  function classifyAvailability(value: FapAvailability): AvailabilityState {
    if (!value.packaged) return 'not-packaged'
    if (!value.enabled) return 'disabled'
    if (!value.eligible) return 'not-eligible'
    return 'eligible'
  }

  async function initialize(): Promise<void> {
    if (initialized || initializing.value) return
    initializing.value = true
    initializationError.value = undefined
    try {
      try {
        availability.value = await getFapAvailability({ suppressErrorMessage: true })
        availabilityState.value = classifyAvailability(availability.value)
      } catch {
        availabilityState.value = 'not-packaged'
        return
      }
      if (!isEligible.value) return
      try {
        await Promise.all([refreshConversations(), loadCatalog()])
        initialized = true
      } catch (error) {
        initializationError.value = errorMessage(error)
      }
    } finally {
      initializing.value = false
    }
  }

  async function loadCatalog(): Promise<void> {
    if (!isEligible.value || catalogLoading.value) return
    catalogLoading.value = true
    try {
      const [workers, directories, models] = await Promise.all([
        catalog('WORKER_PROFILE'),
        catalog('WORKSPACE'),
        catalog('MODEL_CONFIG'),
      ])
      workerProfiles.value = workers
      workspaces.value = directories
      modelConfigs.value = models
    } finally {
      catalogLoading.value = false
    }
  }

  async function catalog(resourceType: FapCatalogResourceType): Promise<FapCatalogEntry[]> {
    const page = await getFapCatalog(resourceType)
    return (page.entries ?? []).filter((entry) => entry.resourceType === resourceType)
  }

  async function refreshConversations(): Promise<void> {
    if (!isEligible.value || conversationsLoading.value) return
    conversationsLoading.value = true
    try {
      conversations.value = await listFapConversations()
      const selectedId = selectedConversation.value?.conversationId
      if (selectedId) {
        const local = conversations.value.find((item) => item.conversationId === selectedId)
        if (local) selectedConversation.value = { ...selectedConversation.value, ...local }
      }
    } finally {
      conversationsLoading.value = false
    }
  }

  async function selectConversation(conversationId: string): Promise<void> {
    stopPolling()
    selectedEpoch += 1
    selectedConversation.value = conversations.value.find(
      (item) => item.conversationId === conversationId,
    )
    events.value = []
    resources.value = []
    recovery.value = undefined
    nextAfterSeq = 0
    eventPageHasMore = false
    resetPollingFailures()
    await refreshSelected(true)
  }

  async function refreshSelected(manual = false): Promise<void> {
    const conversationId = selectedConversation.value?.conversationId
    if (!conversationId || refreshInFlight) return
    const epoch = selectedEpoch
    refreshInFlight = true
    selectedLoading.value = manual
    try {
      const [latest, eventPage] = await Promise.all([
        getFapConversation(conversationId, { suppressErrorMessage: !manual }),
        getFapEvents(conversationId, nextAfterSeq, 100, { suppressErrorMessage: !manual }),
      ])
      if (epoch !== selectedEpoch || selectedConversation.value?.conversationId !== conversationId) return
      selectedConversation.value = latest
      upsertConversation(latest)
      appendEvents(eventPage.events ?? [])
      nextAfterSeq = eventPage.nextAfterSeq
        ?? Math.max(nextAfterSeq, ...events.value.map((event) => event.eventSeq))
      eventPageHasMore = eventPage.hasMore === true
      resetPollingFailures()
    } catch (error) {
      if (epoch !== selectedEpoch) return
      pollingFailureCount.value += 1
      lastPollingError.value = errorMessage(error)
      if (pollingFailureCount.value >= MAX_CONSECUTIVE_POLL_FAILURES) {
        pollingPaused.value = true
      }
      if (manual) throw error
    } finally {
      refreshInFlight = false
      selectedLoading.value = false
      schedulePolling()
    }
  }

  async function startConversation(input: {
    title?: string
    workerProfileRef: string
    workspaceRef: string
    modelConfigRef?: string
    allowDefaultModelConfig: boolean
    prompt: string
    reasoningEffort?: string
  }): Promise<FapConversation> {
    commandPending.value = true
    try {
      const form: FapStartConversationForm = {
        requestId: newFapRequestId('start'),
        title: input.title,
        workerProfileRef: input.workerProfileRef,
        workspaceRef: input.workspaceRef,
        modelConfigRef: input.modelConfigRef,
        allowDefaultModelConfig: input.allowDefaultModelConfig,
        prompt: input.prompt,
        providerOptions: codexProviderOptions(input.reasoningEffort),
      }
      const conversation = await startFapConversation(form)
      upsertConversation(conversation)
      await selectConversation(conversation.conversationId)
      return conversation
    } finally {
      commandPending.value = false
    }
  }

  async function continueConversation(prompt: string, reasoningEffort?: string): Promise<void> {
    const conversationId = requiredConversationId()
    commandPending.value = true
    try {
      const form: FapContinueConversationForm = {
        requestId: newFapRequestId('continue'),
        prompt,
        providerOptions: codexProviderOptions(reasoningEffort),
      }
      const conversation = await continueFapConversation(conversationId, form)
      selectedConversation.value = conversation
      upsertConversation(conversation)
      resetPollingFailures()
      schedulePolling(0)
    } finally {
      commandPending.value = false
    }
  }

  async function cancelCurrent(): Promise<void> {
    const conversationId = requiredConversationId()
    commandPending.value = true
    try {
      await cancelFapConversation(conversationId, {
        requestId: newFapRequestId('cancel'),
        reasonCode: 'USER_REQUESTED',
        message: 'Cancelled from personal FAP Workbench canary',
      })
      resetPollingFailures()
      schedulePolling(0)
    } finally {
      commandPending.value = false
    }
  }

  async function reattachCurrent(): Promise<void> {
    const conversationId = requiredConversationId()
    commandPending.value = true
    try {
      await reattachFapConversation(conversationId, {
        requestId: newFapRequestId('reattach'),
        reasonCode: 'USER_REQUESTED',
      })
      resetPollingFailures()
      await refreshSelected(true)
    } finally {
      commandPending.value = false
    }
  }

  async function loadEvidence(): Promise<void> {
    const conversationId = requiredConversationId()
    evidenceLoading.value = true
    try {
      const [resourcePage, recoveryView] = await Promise.all([
        getFapResources(conversationId),
        getFapRecovery(conversationId),
      ])
      resources.value = resourcePage.items ?? resourcePage.resources ?? []
      recovery.value = recoveryView
    } finally {
      evidenceLoading.value = false
    }
  }

  function appendEvents(incoming: FapEvent[]): void {
    const merged = new Map<string, FapEvent>()
    for (const event of events.value) merged.set(event.eventId || String(event.eventSeq), event)
    for (const event of incoming) merged.set(event.eventId || String(event.eventSeq), event)
    events.value = [...merged.values()].sort((left, right) => left.eventSeq - right.eventSeq)
  }

  function upsertConversation(conversation: FapConversation): void {
    const remaining = conversations.value.filter(
      (item) => item.conversationId !== conversation.conversationId,
    )
    conversations.value = [conversation, ...remaining]
  }

  function resetPollingFailures(): void {
    pollingFailureCount.value = 0
    pollingPaused.value = false
    lastPollingError.value = undefined
  }

  function resumePolling(): void {
    resetPollingFailures()
    schedulePolling(0)
  }

  function shouldPoll(): boolean {
    const value = selectedConversation.value
    return pageActive
      && isEligible.value
      && value?.bindingStatus === 'ACTIVE'
      && !pollingPaused.value
      && (eventPageHasMore || value.definitiveTerminal !== true)
  }

  function schedulePolling(delay = POLL_INTERVAL_MS): void {
    stopPolling()
    if (!shouldPoll()) return
    pollTimer = setTimeout(() => {
      pollTimer = undefined
      void refreshSelected(false)
    }, delay)
  }

  function stopPolling(): void {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = undefined
  }

  function activate(): void {
    pageActive = true
    schedulePolling()
  }

  function deactivate(): void {
    pageActive = false
    stopPolling()
  }

  function requiredConversationId(): string {
    const conversationId = selectedConversation.value?.conversationId
    if (!conversationId) throw new Error('请先选择一个 FAP 会话')
    return conversationId
  }

  onMounted(() => {
    pageActive = true
    void initialize()
  })
  onActivated(activate)
  onDeactivated(deactivate)
  onUnmounted(deactivate)

  return {
    availability,
    availabilityState,
    conversations,
    selectedConversation,
    events,
    resources,
    recovery,
    workerProfiles,
    workspaces,
    modelConfigs,
    initializing,
    initializationError,
    catalogLoading,
    conversationsLoading,
    selectedLoading,
    commandPending,
    evidenceLoading,
    pollingPaused,
    pollingFailureCount,
    lastPollingError,
    isEligible,
    isTaskRunning,
    canContinue,
    canCancel,
    initialize,
    loadCatalog,
    refreshConversations,
    selectConversation,
    refreshSelected,
    startConversation,
    continueConversation,
    cancelCurrent,
    reattachCurrent,
    loadEvidence,
    resumePolling,
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return 'FAP 轮询失败'
}
