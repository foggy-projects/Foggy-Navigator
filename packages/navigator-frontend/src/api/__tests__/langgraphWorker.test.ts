import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  default: { post: mocks.post },
}))

import { approveTask } from '@/api/langgraphWorker'

describe('langgraphWorker api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('responds through the unified task route without caller-supplied reviewer identity', async () => {
    mocks.post.mockResolvedValue({ code: 200, data: null })

    await approveTask('task/1', {
      approvalResult: 'approved',
      comment: 'reviewed in Navigator',
    })

    expect(mocks.post).toHaveBeenCalledWith('/tasks/task%2F1/respond', {
      approvalResult: 'approved',
      comment: 'reviewed in Navigator',
    })
  })
})
