import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockClient = {
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}

vi.mock('../client', () => ({
  default: mockClient,
}))

describe('unifiedTask api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('createTaskUnified posts form to /tasks', async () => {
    const response = { data: { taskId: 'task-1' } }
    mockClient.post.mockResolvedValue(response)
    const { createTaskUnified } = await import('../unifiedTask')

    const result = await createTaskUnified({
      workerId: 'worker-1',
      prompt: 'hello',
      directoryId: 'dir-1',
      model: 'sonnet',
    })

    expect(mockClient.post).toHaveBeenCalledWith('/tasks', {
      workerId: 'worker-1',
      prompt: 'hello',
      directoryId: 'dir-1',
      model: 'sonnet',
    })
    expect(result).toEqual(response.data)
  })

  it('resumeTaskUnified posts form to /tasks/resume', async () => {
    const response = { data: { taskId: 'task-2' } }
    mockClient.post.mockResolvedValue(response)
    const { resumeTaskUnified } = await import('../unifiedTask')

    const result = await resumeTaskUnified({
      workerId: 'worker-1',
      sessionId: 'session-1',
      prompt: 'resume',
    })

    expect(mockClient.post).toHaveBeenCalledWith('/tasks/resume', {
      workerId: 'worker-1',
      sessionId: 'session-1',
      prompt: 'resume',
    })
    expect(result).toEqual(response.data)
  })

  it('listTasksByDirPagedUnified forwards pagination params', async () => {
    const response = { data: { content: [], totalElements: 0 } }
    mockClient.get.mockResolvedValue(response)
    const { listTasksByDirPagedUnified } = await import('../unifiedTask')

    const result = await listTasksByDirPagedUnified('dir-1', 2, 20, 'ACTIVE')

    expect(mockClient.get).toHaveBeenCalledWith('/tasks/directory/dir-1/page', {
      params: { page: 2, size: 20, state: 'ACTIVE' },
    })
    expect(result).toEqual(response.data)
  })

  it('searchSessionsUnified omits empty optional params', async () => {
    const response = { data: { results: [], total: 0, page: 0, size: 20 } }
    mockClient.get.mockResolvedValue(response)
    const { searchSessionsUnified } = await import('../unifiedTask')

    const result = await searchSessionsUnified(undefined, 'worker-1', undefined, 1, 10)

    expect(mockClient.get).toHaveBeenCalledWith('/tasks/search', {
      params: {
        workerId: 'worker-1',
        page: 1,
        size: 10,
      },
    })
    expect(result).toEqual(response.data)
  })
})
