import { describe, expect, it } from 'vitest'
import { buildMilestoneSections, resolveConversationMilestone } from '@/composables/useMilestoneGroups'
import type { ConversationGroup, DirectoryMilestone } from '@/api/types'

function makeGroup(
  sessionId: string,
  milestoneId?: string,
): ConversationGroup {
  return {
    sessionId,
    latestTask: {
      taskId: `task-${sessionId}`,
      sessionId,
      workerId: 'worker-1',
      prompt: `prompt-${sessionId}`,
      status: 'COMPLETED',
      createdAt: '2026-04-11T00:00:00',
      updatedAt: '2026-04-11T00:00:00',
    },
    config: milestoneId ? {
      sessionId,
      pinned: false,
      authBound: false,
      milestoneId,
    } : {
      sessionId,
      pinned: false,
      authBound: false,
    },
    firstPrompt: `prompt-${sessionId}`,
    totalCost: 0,
    createdAt: '2026-04-11T00:00:00',
    updatedAt: '2026-04-11T00:00:00',
  }
}

describe('useMilestoneGroups', () => {
  const milestones: DirectoryMilestone[] = [
    { id: 'ms-1', name: 'v1.0.0', status: 'ACTIVE', docPath: 'docs/v1.0.0' },
    { id: 'ms-2', name: 'v1.1.0', status: 'PLANNED' },
  ]

  it('resolves conversation milestone from config.milestoneId', () => {
    const group = makeGroup('session-1', 'ms-2')

    expect(resolveConversationMilestone(group, milestones)?.name).toBe('v1.1.0')
  })

  it('groups conversations by milestone and keeps none last', () => {
    const groups = [
      makeGroup('session-none'),
      makeGroup('session-2', 'ms-2'),
      makeGroup('session-1', 'ms-1'),
    ]

    const sections = buildMilestoneSections(groups, milestones)

    expect(sections.map(section => section.label)).toEqual([
      'v1.0.0',
      'v1.1.0',
      '未设置里程碑',
    ])
    expect(sections[0]?.conversations.map(group => group.sessionId)).toEqual(['session-1'])
    expect(sections[2]?.conversations.map(group => group.sessionId)).toEqual(['session-none'])
  })
})
