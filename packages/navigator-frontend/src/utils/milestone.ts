import type { DirectoryMilestone } from '@/types'

export type MilestoneSortBy = 'startAt' | 'endAt' | 'name' | 'status'
export type MilestoneSortDir = 'asc' | 'desc'

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

export function compareMilestones(
  left: DirectoryMilestone,
  right: DirectoryMilestone,
  sortBy: MilestoneSortBy = 'startAt',
  sortDir: MilestoneSortDir = 'desc',
): number {
  let result = 0

  switch (sortBy) {
    case 'name':
      result = compareNullableStringDesc(left.name, right.name)
      break
    case 'status':
      result = compareNullableStringDesc(left.status, right.status)
      break
    case 'endAt':
      result = compareNullableNumberDesc(parseMilestoneTime(left.endAt), parseMilestoneTime(right.endAt))
      break
    case 'startAt':
    default:
      result = compareMilestonesDefault(left, right)
      break
  }

  if (result === 0) {
    result = compareMilestonesDefault(left, right)
  }

  return sortDir === 'asc' ? -result : result
}

export function sortMilestones(
  milestones: DirectoryMilestone[] | undefined,
  sortBy: MilestoneSortBy = 'startAt',
  sortDir: MilestoneSortDir = 'desc',
): DirectoryMilestone[] {
  if (!milestones?.length) return []
  return [...milestones].sort((left, right) => compareMilestones(left, right, sortBy, sortDir))
}
