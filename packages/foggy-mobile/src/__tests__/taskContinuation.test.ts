import { describe, expect, it } from 'vitest'
import { vi } from 'vitest'
import {
  canResumeTask,
  executeTaskContinuation,
  getTaskContinuationRef,
} from '@/utils/taskContinuation'

describe('taskContinuation', () => {
  it('allows resume for completed claude sessions', () => {
    expect(canResumeTask({
      status: 'COMPLETED',
      sessionId: 'session-1',
    } as any)).toBe(true)
  })

  it('allows resume for aborted tasks shown as awaiting reply', () => {
    expect(canResumeTask({
      status: 'ABORTED',
      sessionId: 'session-2',
    } as any)).toBe(true)
  })

  it('uses the platform session for all providers', () => {
    expect(canResumeTask({
      status: 'FAILED',
      sessionId: 'session-3',
    } as any)).toBe(true)
    expect(getTaskContinuationRef({
      status: 'FAILED',
      sessionId: 'session-3',
    } as any)).toBe('session-3')
  })

  it('rejects running tasks and tasks without continuation refs', () => {
    expect(canResumeTask({
      status: 'RUNNING',
      sessionId: 'session-4',
    } as any)).toBe(false)
    expect(canResumeTask({
      status: 'COMPLETED',
    } as any)).toBe(false)
  })

  it('creates a new session and never resumes after cross-provider confirmation', async () => {
    const confirmNewSession = vi.fn().mockResolvedValue(true)
    const createNewSession = vi.fn().mockResolvedValue({ taskId: 'task-new' })
    const resumeSession = vi.fn()

    await expect(executeTaskContinuation({
      requiresNewSession: true,
      confirmNewSession,
      createNewSession,
      resumeSession,
    })).resolves.toEqual({ mode: 'created', task: { taskId: 'task-new' } })
    expect(confirmNewSession).toHaveBeenCalledOnce()
    expect(createNewSession).toHaveBeenCalledOnce()
    expect(resumeSession).not.toHaveBeenCalled()
  })

  it('does not create or resume when cross-provider confirmation is cancelled', async () => {
    const confirmNewSession = vi.fn().mockResolvedValue(false)
    const createNewSession = vi.fn()
    const resumeSession = vi.fn()

    await expect(executeTaskContinuation({
      requiresNewSession: true,
      confirmNewSession,
      createNewSession,
      resumeSession,
    })).resolves.toEqual({ mode: 'cancelled' })
    expect(createNewSession).not.toHaveBeenCalled()
    expect(resumeSession).not.toHaveBeenCalled()
  })

  it('resumes the same provider without showing the cross-provider confirmation', async () => {
    const confirmNewSession = vi.fn()
    const createNewSession = vi.fn()
    const resumeSession = vi.fn().mockResolvedValue({ taskId: 'task-resumed' })

    await expect(executeTaskContinuation({
      requiresNewSession: false,
      confirmNewSession,
      createNewSession,
      resumeSession,
    })).resolves.toEqual({ mode: 'resumed', task: { taskId: 'task-resumed' } })
    expect(confirmNewSession).not.toHaveBeenCalled()
    expect(createNewSession).not.toHaveBeenCalled()
    expect(resumeSession).toHaveBeenCalledOnce()
  })
})
