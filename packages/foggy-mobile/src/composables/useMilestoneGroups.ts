import type { ConversationGroup, DirectoryMilestone } from '@/api/types'

export interface MilestoneConversationSection {
  key: string
  label: string
  milestone?: DirectoryMilestone
  conversations: ConversationGroup[]
}

export function resolveConversationMilestone(
  group: ConversationGroup,
  milestones: DirectoryMilestone[],
): DirectoryMilestone | undefined {
  const milestoneId = group.config?.milestoneId
  if (!milestoneId) return undefined
  return milestones.find(milestone => milestone.id === milestoneId)
}

export function buildMilestoneSections(
  groups: ConversationGroup[],
  milestones: DirectoryMilestone[],
): MilestoneConversationSection[] {
  const milestoneOrder = new Map(
    milestones.map((milestone, index) => [milestone.id, index]),
  )
  const sections = new Map<string, MilestoneConversationSection>()

  for (const group of groups) {
    const milestone = resolveConversationMilestone(group, milestones)
    const key = milestone?.id || '__none__'
    const existing = sections.get(key)
    if (existing) {
      existing.conversations.push(group)
      continue
    }

    sections.set(key, {
      key,
      label: milestone?.name || '未设置里程碑',
      milestone,
      conversations: [group],
    })
  }

  return Array.from(sections.values()).sort((left, right) => {
    if (left.key === '__none__') return 1
    if (right.key === '__none__') return -1
    return (milestoneOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER)
      - (milestoneOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER)
  })
}
