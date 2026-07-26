import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  default: {
    post: mocks.post,
  },
}))

import { forwardSessionUnified } from '@/api/unifiedTask'

describe('forwardSessionUnified', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('suppresses the global error toast and preserves the backend error message', async () => {
    mocks.post.mockRejectedValue({
      response: {
        data: {
          msg: '源任务不属于源会话',
        },
      },
    })

    await expect(forwardSessionUnified({
      sourceSessionId: 'session-source',
      sourceMessageId: 'task-result-task-source',
      sourceTaskId: 'task-source',
    })).rejects.toThrow('源任务不属于源会话')

    expect(mocks.post).toHaveBeenCalledWith(
      '/session-relations/forward',
      expect.objectContaining({ sourceTaskId: 'task-source' }),
      expect.objectContaining({ suppressErrorMessage: true }),
    )
  })
})
