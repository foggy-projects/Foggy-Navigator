import { describe, expect, it } from 'vitest'
import { canEnableRewind, canShowContinuationInput } from '../taskPaneResume'

describe('taskPaneResume', () => {
  it('allows continuation for provider-agnostic sessions', () => {
    expect(canShowContinuationInput({
      sessionId: 'session-1',
      status: 'COMPLETED',
    })).toBe(true)
  })

  it('does not allow continuation before the platform session exists', () => {
    expect(canShowContinuationInput({
      status: 'COMPLETED',
    })).toBe(false)
  })

  it('does not enable Claude Code rewind for LangGraph Biz tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-langgraph-1',
      sessionId: 'session-langgraph-1',
      workerId: 'worker-1',
      prompt: 'biz task',
      status: 'COMPLETED',
      providerType: 'langgraph-biz-worker',
      model: 'biz-default',
      claudeSessionId: 'worker-session-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(false)
  })

  it('keeps Claude Code rewind enabled for completed Claude tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-claude-1',
      sessionId: 'session-claude-1',
      workerId: 'worker-1',
      prompt: 'claude task',
      status: 'COMPLETED',
      providerType: 'claude-worker',
      model: 'sonnet',
      claudeSessionId: 'claude-session-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(true)
  })
})
