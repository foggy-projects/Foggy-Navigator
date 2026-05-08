import { describe, expect, it } from 'vitest'
import {
  inferTaskWorkerBackend,
  isClaudeCodeTask,
  providerTypeFromWorkerBackend,
  providerTypeLabel,
  taskSessionRefLabel,
} from '@/utils/workerBackend'
import type { ClaudeTask } from '@/types'

function createTask(overrides: Partial<ClaudeTask>): ClaudeTask {
  return {
    taskId: 'task-1',
    sessionId: 'session-1',
    workerId: 'worker-1',
    prompt: 'test',
    status: 'COMPLETED',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

describe('workerBackend utils', () => {
  it('maps LangGraph worker backend to provider type', () => {
    expect(providerTypeFromWorkerBackend('LANGGRAPH_BIZ')).toBe('langgraph-biz-worker')
  })

  it('uses providerType as the strongest task backend signal', () => {
    const task = createTask({
      providerType: 'langgraph-biz-worker',
      claudeSessionId: 'worker-session-1',
      model: 'sonnet',
    })

    expect(inferTaskWorkerBackend(task)).toBe('LANGGRAPH_BIZ')
    expect(isClaudeCodeTask(task)).toBe(false)
    expect(taskSessionRefLabel(task)).toBe('Worker Session ID')
  })

  it('does not treat LangGraph-like model aliases as Claude Code just because a session ref exists', () => {
    const task = createTask({
      claudeSessionId: 'worker-session-1',
      model: 'biz-default',
    })

    expect(inferTaskWorkerBackend(task)).toBe('LANGGRAPH_BIZ')
    expect(isClaudeCodeTask(task)).toBe(false)
  })

  it('keeps legacy Claude tasks with only claudeSessionId compatible', () => {
    const task = createTask({
      claudeSessionId: 'claude-session-1',
    })

    expect(inferTaskWorkerBackend(task)).toBe('CLAUDE_CODE')
    expect(isClaudeCodeTask(task)).toBe(true)
    expect(taskSessionRefLabel(task)).toBe('Claude Session ID')
  })

  it('labels all current provider types', () => {
    expect(providerTypeLabel('claude-worker')).toBe('Claude Worker')
    expect(providerTypeLabel('codex-worker')).toBe('Codex Worker')
    expect(providerTypeLabel('gemini-worker')).toBe('Gemini Worker')
    expect(providerTypeLabel('langgraph-biz-worker')).toBe('LangGraph Biz Worker')
  })
})
