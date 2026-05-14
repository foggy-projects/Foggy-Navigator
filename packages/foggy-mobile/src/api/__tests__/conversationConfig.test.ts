import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockClient = {
  get: vi.fn(),
  patch: vi.fn(),
  post: vi.fn(),
}

vi.mock('../client', () => ({
  default: mockClient,
}))

describe('conversationConfig api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listConversationConfigs sends session ids in post body', async () => {
    const response = { data: [{ sessionId: 'session-1' }] }
    mockClient.post.mockResolvedValue(response)
    const { listConversationConfigs } = await import('../conversationConfig')

    const result = await listConversationConfigs(['session-1', 'session-2'])

    expect(mockClient.post).toHaveBeenCalledWith('/sessions/configs', {
      sessionIds: ['session-1', 'session-2'],
    })
    expect(result).toEqual(response.data)
  })

  it('updateConversationMilestone patches milestone id', async () => {
    const response = { data: { sessionId: 'session-1', milestoneId: 'ms-1' } }
    mockClient.patch.mockResolvedValue(response)
    const { updateConversationMilestone } = await import('../conversationConfig')

    const result = await updateConversationMilestone('session-1', 'ms-1')

    expect(mockClient.patch).toHaveBeenCalledWith('/sessions/session-1/config/milestone', {
      milestoneId: 'ms-1',
    })
    expect(result).toEqual(response.data)
  })
})
