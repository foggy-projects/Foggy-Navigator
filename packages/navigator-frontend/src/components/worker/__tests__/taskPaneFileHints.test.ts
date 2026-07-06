import { describe, expect, it, vi } from 'vitest'
import { loadTaskFileHints } from '../taskPaneFileHints'
import type { SessionFileHintsResponse } from '@/types/sessionFileHints'

describe('taskPaneFileHints', () => {
  it('returns null response when refresh fails so stale rows can be cleared', async () => {
    let currentResponse: SessionFileHintsResponse | null = {
      taskId: 'task-1',
      files: [{
        filePath: 'D:/repo/src/app.ts',
        cwdRelativePath: 'src/app.ts',
        pathScope: 'inside_cwd',
        openableInFileBrowser: true,
        changeKinds: ['update'],
        sourceTools: ['file_change'],
        confidence: 'high',
        toolUseIds: ['patch-1'],
        taskIds: ['task-1'],
        firstSeenAt: '2026-06-29T00:00:00.000Z',
        lastSeenAt: '2026-06-29T00:00:00.000Z',
        seenCount: 1,
      }],
      total: 1,
    }
    const fetcher = vi.fn().mockRejectedValue(new Error('worker unavailable'))

    const result = await loadTaskFileHints('task-1', fetcher)
    currentResponse = result.response

    expect(fetcher).toHaveBeenCalledWith('task-1')
    expect(currentResponse).toBeNull()
    expect(result.error).toBeInstanceOf(Error)
  })

  it('does not call the API when task id is missing', async () => {
    const fetcher = vi.fn()

    const result = await loadTaskFileHints(undefined, fetcher)

    expect(fetcher).not.toHaveBeenCalled()
    expect(result.response).toBeNull()
    expect(result.error).toBeUndefined()
  })
})
