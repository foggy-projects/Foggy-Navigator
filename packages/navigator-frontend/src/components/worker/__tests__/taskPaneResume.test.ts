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

  it('enables platform conversation rewind for completed Codex tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-1',
      sessionId: 'session-codex-1',
      workerId: 'worker-1',
      prompt: 'codex task',
      status: 'COMPLETED',
      providerType: 'codex-worker',
      model: 'codex-deep',
      codexThreadId: 'thread-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(true)
  })

  it('does not enable rewind while a Codex task is awaiting permission', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-2',
      sessionId: 'session-codex-2',
      workerId: 'worker-1',
      prompt: 'codex task',
      status: 'AWAITING_PERMISSION',
      providerType: 'codex-worker',
      model: 'codex-deep',
      codexThreadId: 'thread-2',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(false)
  })
})
