import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  default: { get: mocks.get, post: mocks.post },
}))

import {
  continueFapConversation,
  getFapAvailability,
  getFapEvents,
  startFapConversation,
} from '@/api/workbenchFap'

describe('workbenchFap api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses a silent capability probe so stable builds can omit the canary module', async () => {
    const availability = { packaged: true, enabled: true, eligible: true, executionLane: 'FAP_V1' }
    mocks.get.mockResolvedValue({ code: 200, data: availability })

    await expect(getFapAvailability({ suppressErrorMessage: true })).resolves.toEqual(availability)
    expect(mocks.get).toHaveBeenCalledWith(
      '/workbench/fap/availability',
      { suppressErrorMessage: true },
    )
  })

  it('keeps START and CONTINUE on separate FAP-only endpoints', async () => {
    const conversation = { conversationId: 'conversation/1', executionLane: 'FAP_V1' }
    mocks.post.mockResolvedValue({ code: 200, data: conversation })
    const start = {
      requestId: 'start-1',
      workerProfileRef: 'worker-1',
      workspaceRef: 'workspace-1',
      allowDefaultModelConfig: true,
      prompt: 'start',
    }

    await startFapConversation(start)
    await continueFapConversation('conversation/1', {
      requestId: 'continue-1',
      prompt: 'continue',
    })

    expect(mocks.post).toHaveBeenNthCalledWith(1, '/workbench/fap/conversations', start)
    expect(mocks.post).toHaveBeenNthCalledWith(
      2,
      '/workbench/fap/conversations/conversation%2F1/tasks',
      { requestId: 'continue-1', prompt: 'continue' },
    )
  })

  it('supports silent bounded event polling without selecting a legacy stream', async () => {
    const page = { events: [], nextAfterSeq: 8, hasMore: false }
    mocks.get.mockResolvedValue({ code: 200, data: page })

    await expect(getFapEvents('conversation/1', 7, 100, {
      suppressErrorMessage: true,
    })).resolves.toEqual(page)

    expect(mocks.get).toHaveBeenCalledWith(
      '/workbench/fap/conversations/conversation%2F1/events',
      {
        params: { afterSeq: 7, limit: 100 },
        suppressErrorMessage: true,
      },
    )
  })
})
