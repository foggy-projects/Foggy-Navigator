import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getFapAvailability: vi.fn(),
  getFapCatalog: vi.fn(),
  listFapConversations: vi.fn(),
  getFapConversation: vi.fn(),
  getFapEvents: vi.fn(),
  startFapConversation: vi.fn(),
  continueFapConversation: vi.fn(),
  cancelFapConversation: vi.fn(),
  reattachFapConversation: vi.fn(),
  getFapResources: vi.fn(),
  getFapRecovery: vi.fn(),
}))

vi.mock('@/api/workbenchFap', () => mocks)

import { useWorkbenchFap } from '@/composables/useWorkbenchFap'

const conversation = {
  conversationId: 'conversation-1',
  executionLane: 'FAP_V1',
  bindingStatus: 'ACTIVE',
  title: 'Focused task',
  workerProfileRef: 'worker-1',
  workspaceRef: 'workspace-1',
  allowDefaultModelConfig: true,
  definitiveTerminal: false,
  scopeReductions: [],
}

const Harness = defineComponent({
  setup(_props, { expose }) {
    const workbench = useWorkbenchFap()
    expose(workbench)
    return () => h('div')
  },
})

describe('useWorkbenchFap bounded polling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mocks.getFapAvailability.mockResolvedValue({
      packaged: true,
      enabled: true,
      eligible: true,
      executionLane: 'FAP_V1',
    })
    mocks.getFapCatalog.mockResolvedValue({ entries: [] })
    mocks.listFapConversations.mockResolvedValue([conversation])
    mocks.getFapConversation.mockResolvedValue(conversation)
    mocks.getFapEvents.mockResolvedValue({ events: [], nextAfterSeq: 0, hasMore: false })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('pauses after three consecutive failures instead of retrying forever', async () => {
    const wrapper = mount(Harness)
    await flushPromises()
    const workbench = wrapper.vm as unknown as ReturnType<typeof useWorkbenchFap>
    await workbench.selectConversation('conversation-1')
    expect(mocks.getFapConversation).toHaveBeenCalledTimes(1)

    mocks.getFapConversation.mockRejectedValue(new Error('runtime offline'))
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await vi.advanceTimersByTimeAsync(2_500)
      await flushPromises()
    }

    expect(workbench.pollingFailureCount as unknown).toBe(3)
    expect(workbench.pollingPaused as unknown).toBe(true)
    const callsAtPause = mocks.getFapConversation.mock.calls.length

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(mocks.getFapConversation).toHaveBeenCalledTimes(callsAtPause)

    wrapper.unmount()
  })
})
