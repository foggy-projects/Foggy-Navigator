import {
  NATIVE_SUBTASK_FAILURE_CODE,
  type NativeSubtaskStatus,
  type NativeSubtaskUpdateData,
} from '../models.js'

export type AppServerNotification = {
  method: string
  params?: Record<string, unknown>
}

type StatusAuthority = 1 | 2 | 3

type TrackedSubtask = {
  data: NativeSubtaskUpdateData
  statusAuthority: StatusAuthority
}

const TERMINAL_STATUSES = new Set<NativeSubtaskStatus>(['completed', 'failed', 'interrupted'])

export class NativeSubtaskTracker {
  private rootThreadId?: string
  private rootTurnId?: string
  private readonly subtasks = new Map<string, TrackedSubtask>()
  private readonly now: () => Date

  constructor(rootThreadId?: string, now: () => Date = () => new Date()) {
    this.rootThreadId = rootThreadId
    this.now = now
  }

  setRootThreadId(threadId: string): void {
    this.rootThreadId = threadId
  }

  setRootTurnId(turnId: string): void {
    this.rootTurnId = turnId
  }

  handle(notification: AppServerNotification): NativeSubtaskUpdateData[] {
    switch (notification.method) {
      case 'thread/started':
        return this.handleThreadStarted(notification.params)
      case 'thread/status/changed':
        return this.handleThreadStatusChanged(notification.params)
      case 'turn/started':
        return this.handleTurn(notification.params, false)
      case 'turn/completed':
        return this.handleTurn(notification.params, true)
      case 'item/started':
      case 'item/completed':
        return this.handleItem(notification.method, notification.params)
      default:
        return []
    }
  }

  getSnapshot(subtaskId: string): NativeSubtaskUpdateData | undefined {
    const tracked = this.subtasks.get(subtaskId)
    return tracked ? { ...tracked.data } : undefined
  }

  private handleThreadStarted(params: Record<string, unknown> | undefined): NativeSubtaskUpdateData[] {
    const thread = asRecord(params?.thread)
    const threadId = readString(thread?.id)
    const parentThreadId = readString(thread?.parentThreadId)
    if (!threadId || !parentThreadId || threadId === this.rootThreadId) return []
    if (!this.isKnownThread(parentThreadId)) return []
    if (!this.subtasks.has(threadId)) return []

    const spawnSource = readThreadSpawnSource(thread?.source)
    const createdAt = secondsToIso(thread?.createdAt)
    const updatedAt = secondsToIso(thread?.updatedAt) || createdAt
    const status = mapThreadStatus(thread?.status) || 'pending'
    return this.merge(threadId, {
      parent_subtask_id: this.parentSubtaskId(parentThreadId),
      depth: readNumber(spawnSource?.depth) ?? this.derivedDepth(parentThreadId),
      label: sanitizeDisplayMetadata(thread?.agentNickname)
        || sanitizeDisplayMetadata(spawnSource?.agent_nickname),
      role: sanitizeDisplayMetadata(thread?.agentRole)
        || sanitizeDisplayMetadata(spawnSource?.agent_role),
      status,
      started_at: createdAt,
      updated_at: updatedAt,
    }, 1)
  }

  private handleThreadStatusChanged(params: Record<string, unknown> | undefined): NativeSubtaskUpdateData[] {
    const threadId = readString(params?.threadId)
    if (!threadId || threadId === this.rootThreadId || !this.subtasks.has(threadId)) return []
    const status = mapThreadStatus(params?.status)
    if (!status) return []
    return this.merge(threadId, {
      status,
      updated_at: this.now().toISOString(),
      completed_at: status === 'failed' ? this.now().toISOString() : undefined,
    }, 2)
  }

  private handleTurn(params: Record<string, unknown> | undefined, completed: boolean): NativeSubtaskUpdateData[] {
    const threadId = readString(params?.threadId)
    if (!threadId) return []
    const turn = asRecord(params?.turn)
    const turnId = readString(turn?.id)
    const startedAt = secondsToIso(turn?.startedAt)
    const completedAt = secondsToIso(turn?.completedAt)
    const status = completed ? mapTurnStatus(turn?.status) : 'running'
    if (threadId === this.rootThreadId) {
      if (this.rootTurnId && turnId !== this.rootTurnId) return []
      if (!completed) return []
      const childTerminalStatus: NativeSubtaskStatus = status === 'failed' ? 'failed' : 'interrupted'
      const updates: NativeSubtaskUpdateData[] = []
      for (const [subtaskId, tracked] of this.subtasks) {
        if (TERMINAL_STATUSES.has(tracked.data.status)) continue
        updates.push(...this.merge(subtaskId, {
          status: childTerminalStatus,
          updated_at: completedAt || this.now().toISOString(),
          completed_at: completedAt || this.now().toISOString(),
        }, 2))
      }
      return updates
    }
    if (!this.subtasks.has(threadId)) return []
    return this.merge(threadId, {
      status,
      started_at: startedAt,
      updated_at: completedAt || startedAt || this.now().toISOString(),
      completed_at: TERMINAL_STATUSES.has(status) ? (completedAt || this.now().toISOString()) : undefined,
    }, 3)
  }

  private handleItem(
    method: 'item/started' | 'item/completed',
    params: Record<string, unknown> | undefined
  ): NativeSubtaskUpdateData[] {
    const itemParams = params || {}
    const item = asRecord(itemParams.item)
    if (!item) return []
    if (item.type === 'subAgentActivity') {
      return this.handleSubAgentActivity(method, itemParams, item)
    }
    if (item.type === 'collabAgentToolCall') {
      return this.handleCollabToolCall(method, itemParams, item)
    }
    return []
  }

  private handleSubAgentActivity(
    method: 'item/started' | 'item/completed',
    params: Record<string, unknown>,
    item: Record<string, unknown>
  ): NativeSubtaskUpdateData[] {
    const threadId = readString(item.agentThreadId)
    if (!threadId || threadId === this.rootThreadId) return []
    const senderThreadId = readString(params.threadId)
    if (!this.isKnownThread(senderThreadId)) return []
    if (senderThreadId === this.rootThreadId && !this.matchesRootTurn(params.turnId)) return []
    const activity = readActivity(item.kind)
    if (!activity) return []
    const eventTime = itemLifecycleTime(method, params) || this.now().toISOString()
    return this.merge(threadId, {
      parent_subtask_id: this.parentSubtaskId(senderThreadId),
      depth: this.derivedDepth(senderThreadId),
      label: labelFromAgentPath(readString(item.agentPath)),
      status: activity === 'interrupted' ? 'interrupted' : 'running',
      activity,
      started_at: activity === 'started' ? eventTime : undefined,
      updated_at: eventTime,
      completed_at: activity === 'interrupted' ? eventTime : undefined,
    }, 2)
  }

  private handleCollabToolCall(
    method: 'item/started' | 'item/completed',
    params: Record<string, unknown>,
    item: Record<string, unknown>
  ): NativeSubtaskUpdateData[] {
    const senderThreadId = readString(item.senderThreadId) || readString(params.threadId)
    if (!this.isKnownThread(senderThreadId)) return []
    if (senderThreadId === this.rootThreadId && !this.matchesRootTurn(params.turnId)) return []
    const receiverIds = readStringArray(item.receiverThreadIds)
    const agentStates = asRecord(item.agentsStates)
    const allIds = new Set([...receiverIds, ...Object.keys(agentStates || {})])
    const eventTime = itemLifecycleTime(method, params) || this.now().toISOString()
    const updates: NativeSubtaskUpdateData[] = []

    for (const threadId of allIds) {
      if (!threadId || threadId === this.rootThreadId) continue
      const state = asRecord(agentStates?.[threadId])
      const status = mapCollabStatus(state?.status) || 'pending'
      updates.push(...this.merge(threadId, {
        parent_subtask_id: this.parentSubtaskId(senderThreadId),
        depth: this.derivedDepth(senderThreadId),
        status,
        updated_at: eventTime,
        completed_at: TERMINAL_STATUSES.has(status) ? eventTime : undefined,
      }, 2))
    }
    return updates
  }

  private merge(
    subtaskId: string,
    update: Partial<NativeSubtaskUpdateData> & Pick<NativeSubtaskUpdateData, 'status'>,
    authority: StatusAuthority
  ): NativeSubtaskUpdateData[] {
    const existing = this.subtasks.get(subtaskId)
    const before = existing ? JSON.stringify(existing.data) : undefined
    const previousStatus = existing?.data.status
    const previousAuthority = existing?.statusAuthority ?? 1
    const mayUpdateStatus = !existing || shouldReplaceStatus(
      previousStatus!,
      update.status,
      previousAuthority,
      authority
    )

    const nextStatus = mayUpdateStatus ? update.status : previousStatus!
    // Provider errors may contain prompts, paths, credentials, or tool output. The
    // native-subtask contract exposes only a stable failure code at this boundary.
    const data = compactUndefined({
      ...(existing?.data || {}),
      ...update,
      contract_version: 1 as const,
      subtask_id: subtaskId,
      status: nextStatus,
      message: nextStatus === 'failed' ? NATIVE_SUBTASK_FAILURE_CODE : undefined,
      updated_at: update.updated_at || existing?.data.updated_at || this.now().toISOString(),
    }) as NativeSubtaskUpdateData
    if (!mayUpdateStatus) {
      if (existing?.data.completed_at !== undefined) data.completed_at = existing.data.completed_at
      else delete data.completed_at
    }

    const statusAuthority = mayUpdateStatus
      ? (previousStatus === nextStatus ? Math.max(previousAuthority, authority) as StatusAuthority : authority)
      : previousAuthority
    this.subtasks.set(subtaskId, { data, statusAuthority })
    return before === JSON.stringify(data) ? [] : [{ ...data }]
  }

  private parentSubtaskId(parentThreadId: string | undefined): string | undefined {
    return parentThreadId && parentThreadId !== this.rootThreadId ? parentThreadId : undefined
  }

  private derivedDepth(parentThreadId: string | undefined): number | undefined {
    if (!parentThreadId) return undefined
    if (parentThreadId === this.rootThreadId) return 1
    const parent = this.subtasks.get(parentThreadId)?.data
    return parent?.depth === undefined ? undefined : parent.depth + 1
  }

  private isKnownThread(threadId: string | undefined): boolean {
    return Boolean(threadId && (threadId === this.rootThreadId || this.subtasks.has(threadId)))
  }

  private matchesRootTurn(turnId: unknown): boolean {
    return Boolean(this.rootTurnId && readString(turnId) === this.rootTurnId)
  }
}

function mapThreadStatus(value: unknown): NativeSubtaskStatus | undefined {
  const type = readString(asRecord(value)?.type)
  if (type === 'active') return 'running'
  if (type === 'systemError') return 'failed'
  if (type === 'idle') return 'pending'
  return undefined
}

function shouldReplaceStatus(
  previous: NativeSubtaskStatus,
  next: NativeSubtaskStatus,
  previousAuthority: StatusAuthority,
  nextAuthority: StatusAuthority
): boolean {
  if (previous === next) return true
  if (TERMINAL_STATUSES.has(previous)) {
    if (next === 'running') return nextAuthority >= previousAuthority
    return TERMINAL_STATUSES.has(next) && nextAuthority >= previousAuthority
  }
  if (TERMINAL_STATUSES.has(next)) return true
  if (nextAuthority > previousAuthority) return true
  if (nextAuthority < previousAuthority) return false
  return !(previous === 'running' && next === 'pending')
}

function mapTurnStatus(value: unknown): NativeSubtaskStatus {
  switch (value) {
    case 'completed': return 'completed'
    case 'failed': return 'failed'
    case 'interrupted': return 'interrupted'
    default: return 'running'
  }
}

function mapCollabStatus(value: unknown): NativeSubtaskStatus | undefined {
  switch (value) {
    case 'pendingInit': return 'pending'
    case 'running': return 'running'
    case 'completed': return 'completed'
    case 'interrupted': return 'interrupted'
    case 'errored':
    case 'notFound': return 'failed'
    case 'shutdown': return 'interrupted'
    default: return undefined
  }
}

function readThreadSpawnSource(sourceValue: unknown): Record<string, unknown> | undefined {
  const source = asRecord(sourceValue)
  const subAgent = asRecord(source?.subAgent) || asRecord(source?.subagent)
  return asRecord(subAgent?.thread_spawn)
}

function readActivity(value: unknown): NativeSubtaskUpdateData['activity'] | undefined {
  return value === 'started' || value === 'interacted' || value === 'interrupted' ? value : undefined
}

function labelFromAgentPath(agentPath: string | undefined): string | undefined {
  if (!agentPath) return undefined
  const label = agentPath.split(/[\\/]+/).filter(Boolean).at(-1)
  return sanitizeDisplayMetadata(label)
}

const SAFE_DISPLAY_METADATA = /^[\p{L}\p{N}][\p{L}\p{N} ._-]*$/u
const SENSITIVE_DISPLAY_METADATA = /(?:\b(?:bearer|basic)\s+|(?:^|[^a-z0-9])(?:token|secret|password|credential|api[_ -]?key)(?:$|[^a-z0-9])|\bsk-[a-z0-9_-]+|(?:https?|file|ssh|ftp):\/\/|www\.|[a-z]:[\\/]|[\\/]|~[\\/])/i

function sanitizeDisplayMetadata(value: unknown): string | undefined {
  const candidate = readString(value)
  if (!candidate || candidate.length > 64) return undefined
  if (/[\u0000-\u001f\u007f]/.test(candidate)) return undefined
  if (!SAFE_DISPLAY_METADATA.test(candidate) || SENSITIVE_DISPLAY_METADATA.test(candidate)) return undefined
  return candidate
}

function itemLifecycleTime(method: string, params: Record<string, unknown>): string | undefined {
  return method === 'item/started'
    ? millisecondsToIso(params.startedAtMs)
    : millisecondsToIso(params.completedAtMs)
}

function secondsToIso(value: unknown): string | undefined {
  const seconds = readNumber(value)
  return seconds === undefined ? undefined : new Date(seconds * 1000).toISOString()
}

function millisecondsToIso(value: unknown): string | undefined {
  const milliseconds = readNumber(value)
  return milliseconds === undefined ? undefined : new Date(milliseconds).toISOString()
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function readNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function readStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && Boolean(item)) : []
}

function compactUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}
