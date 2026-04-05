/**
 * Conversation Group composable — groups DispatchTask[] by sessionId
 * into ConversationGroup[] for session-driven list display.
 */
import type { DispatchTask, ConversationConfig, ConversationGroup } from '@/api/types'

/**
 * Build conversation groups from a flat task list + optional config map.
 * - Groups tasks by sessionId
 * - Picks latest task per session (by updatedAt)
 * - Merges ConversationConfig metadata
 * - Sorts: pinned first, then by latest activity (newest first)
 */
export function buildConversationGroups(
  tasks: DispatchTask[],
  configs: Map<string, ConversationConfig>,
): ConversationGroup[] {
  // Group tasks by sessionId
  const sessionMap = new Map<string, DispatchTask[]>()
  for (const task of tasks) {
    if (!task.sessionId) continue
    const existing = sessionMap.get(task.sessionId)
    if (existing) {
      existing.push(task)
    } else {
      sessionMap.set(task.sessionId, [task])
    }
  }

  // Build groups
  const groups: ConversationGroup[] = []
  for (const [sessionId, sessionTasks] of sessionMap) {
    // Sort by updatedAt descending to find latest
    sessionTasks.sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    const latestTask = sessionTasks[0]
    // First prompt: earliest task's prompt
    const earliestTask = sessionTasks[sessionTasks.length - 1]
    const config = configs.get(sessionId)

    groups.push({
      sessionId,
      latestTask,
      config,
      firstPrompt: earliestTask.prompt || '',
      totalCost: sessionTasks.reduce((sum, t) => sum + (t.costUsd || 0), 0),
      createdAt: earliestTask.createdAt,
      updatedAt: latestTask.updatedAt,
    })
  }

  // Sort: pinned first, then by updatedAt descending
  groups.sort((a, b) => {
    const aPinned = a.config?.pinned ? 1 : 0
    const bPinned = b.config?.pinned ? 1 : 0
    if (aPinned !== bPinned) return bPinned - aPinned
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  })

  return groups
}

/**
 * Get the display title for a conversation group.
 * Priority: customTitle > first prompt (truncated)
 */
export function getGroupTitle(group: ConversationGroup, maxLen = 60): string {
  if (group.config?.customTitle) return group.config.customTitle
  const prompt = group.firstPrompt
  if (prompt.length <= maxLen) return prompt
  return prompt.slice(0, maxLen) + '...'
}

/**
 * Get the effective interactionState for a conversation group.
 * Priority: config.interactionState > derived from latest task status
 */
export function getGroupInteractionState(group: ConversationGroup): string | undefined {
  if (group.config?.interactionState) return group.config.interactionState
  // Derive from latest task status
  const status = group.latestTask.status
  if (status === 'RUNNING' || status === 'PENDING') return 'PROCESSING'
  if (status === 'AWAITING_PERMISSION') return 'AWAITING_REPLY'
  return undefined
}
