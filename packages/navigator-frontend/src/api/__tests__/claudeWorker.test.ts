import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockUnifiedTaskApi = vi.hoisted(() => ({
  cancelTaskUnified: vi.fn(),
  getTaskUnified: vi.fn(),
}))

vi.mock('../unifiedTask', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../unifiedTask')>()
  return {
    ...actual,
    cancelTaskUnified: mockUnifiedTaskApi.cancelTaskUnified,
    getTaskUnified: mockUnifiedTaskApi.getTaskUnified,
  }
})

import { abortTask } from '../claudeWorker'

describe('claudeWorker abortTask', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns the authoritative task state after the cancel request is acknowledged', async () => {
    mockUnifiedTaskApi.cancelTaskUnified.mockResolvedValue(undefined)
    mockUnifiedTaskApi.getTaskUnified.mockResolvedValue({
      taskId: 'task-1',
      status: 'CANCEL_REQUESTED',
      errorMessage: 'TERMINATION_UNCONFIRMED',
    })

    const result = await abortTask('task-1')

    expect(mockUnifiedTaskApi.cancelTaskUnified).toHaveBeenCalledWith('task-1')
    expect(mockUnifiedTaskApi.getTaskUnified).toHaveBeenCalledWith('task-1')
    expect(result).toMatchObject({
      taskId: 'task-1',
      status: 'CANCEL_REQUESTED',
      errorMessage: 'TERMINATION_UNCONFIRMED',
    })
  })
})
