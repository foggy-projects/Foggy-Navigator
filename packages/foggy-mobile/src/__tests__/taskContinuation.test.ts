import { describe, expect, it } from 'vitest'
import { canResumeTask, getTaskContinuationRef } from '@/utils/taskContinuation'

describe('taskContinuation', () => {
  it('allows resume for completed claude sessions', () => {
    expect(canResumeTask({
      status: 'COMPLETED',
      claudeSessionId: 'claude-1',
    } as any)).toBe(true)
  })

  it('allows resume for aborted tasks shown as awaiting reply', () => {
    expect(canResumeTask({
      status: 'ABORTED',
      claudeSessionId: 'claude-2',
    } as any)).toBe(true)
  })

  it('allows resume for codex-backed sessions', () => {
    expect(canResumeTask({
      status: 'FAILED',
      codexThreadId: 'thread-1',
    } as any)).toBe(true)
    expect(getTaskContinuationRef({
      status: 'FAILED',
      codexThreadId: 'thread-1',
    } as any)).toBe('thread-1')
  })

  it('rejects running tasks and tasks without continuation refs', () => {
    expect(canResumeTask({
      status: 'RUNNING',
      claudeSessionId: 'claude-3',
    } as any)).toBe(false)
    expect(canResumeTask({
      status: 'COMPLETED',
    } as any)).toBe(false)
  })
})
