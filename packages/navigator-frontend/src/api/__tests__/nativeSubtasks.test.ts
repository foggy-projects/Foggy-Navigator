import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  default: { get: mocks.get },
}))

import { getNativeSubtasks } from '@/api/nativeSubtasks'

describe('nativeSubtasks api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('suppresses the global error message for snapshot capability probes', async () => {
    const snapshot = { taskId: 'task-1', subtasks: [] }
    mocks.get.mockResolvedValue({ code: 200, data: snapshot })

    await expect(getNativeSubtasks('task/1')).resolves.toEqual(snapshot)
    expect(mocks.get).toHaveBeenCalledWith(
      '/tasks/task%2F1/native-subtasks',
      { suppressErrorMessage: true },
    )
  })
})
