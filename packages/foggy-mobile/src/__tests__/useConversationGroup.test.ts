import { describe, expect, it } from 'vitest'
import { buildConversationGroups } from '@/composables/useConversationGroup'
import type { DispatchTask } from '@/api/types'

function task(overrides: Partial<DispatchTask>): DispatchTask {
  return {
    taskId: 'task-1',
    sessionId: 'session-1',
    workerId: 'worker-1',
    prompt: 'latest prompt',
    status: 'COMPLETED',
    createdAt: '2026-05-16T10:00:00',
    updatedAt: '2026-05-16T10:00:00',
    ...overrides,
  }
}

describe('buildConversationGroups', () => {
  it('uses session summary fields from paged task results', () => {
    const groups = buildConversationGroups([
      task({
        sessionTaskCount: 3,
        sessionTotalCostUsd: 5.25,
        sessionInputTokens: 1200,
        sessionOutputTokens: 800,
        sessionFirstPrompt: 'first prompt',
        costUsd: 1,
        inputTokens: 100,
        outputTokens: 50,
      }),
    ], new Map())

    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({
      sessionId: 'session-1',
      firstPrompt: 'first prompt',
      taskCount: 3,
      totalCost: 5.25,
      totalInputTokens: 1200,
      totalOutputTokens: 800,
    })
  })
})
