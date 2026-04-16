import type { DirectoryMilestone } from '@/api/types'

function parseMilestoneTime(value?: string): number | null {
  if (!value) return null
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? null : timestamp
}

function compareNullableNumberDesc(left: number | null, right: number | null): number {
  if (left == null && right == null) return 0
  if (left == null) return 1
  if (right == null) return -1
  return right - left
}

function compareNullableStringDesc(left?: string, right?: string): number {
  if (!left && !right) return 0
  if (!left) return 1
  if (!right) return -1
  return right.localeCompare(left, undefined, { sensitivity: 'base' })
}

export function compareMilestonesDefault(left: DirectoryMilestone, right: DirectoryMilestone): number {
  const startDiff = compareNullableNumberDesc(parseMilestoneTime(left.startAt), parseMilestoneTime(right.startAt))
  if (startDiff !== 0) return startDiff

  const nameDiff = compareNullableStringDesc(left.name, right.name)
  if (nameDiff !== 0) return nameDiff

  return compareNullableStringDesc(left.id, right.id)
}

export function sortMilestones(milestones: DirectoryMilestone[] | undefined): DirectoryMilestone[] {
  if (!milestones?.length) return []
  return [...milestones].sort(compareMilestonesDefault)
}
