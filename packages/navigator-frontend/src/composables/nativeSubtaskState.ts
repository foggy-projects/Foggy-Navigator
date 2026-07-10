import {
  NATIVE_SUBTASK_FAILURE_CODE,
  type NativeSubtaskMessageCode,
  type NativeSubtask,
  type NativeSubtaskRow,
  type NativeSubtaskSnapshot,
  type NativeSubtaskUpdate,
  type NativeSubtaskWire,
} from '@/types/nativeSubtasks'

export type NormalizedNativeSubtaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'INTERRUPTED'
  | 'BLOCKED'
  | 'UNKNOWN'

export interface NativeSubtaskState {
  taskId: string | null
  byId: Record<string, NativeSubtask>
  lastEventSeq: number
}

export type NativeSubtaskAction =
  | { type: 'RESET'; taskId: string | null }
  | { type: 'SNAPSHOT'; snapshot: NativeSubtaskSnapshot }
  | { type: 'UPDATE'; update: NativeSubtaskUpdate }

export function createNativeSubtaskState(taskId: string | null = null): NativeSubtaskState {
  return { taskId, byId: {}, lastEventSeq: 0 }
}

export function reduceNativeSubtasks(
  state: NativeSubtaskState,
  action: NativeSubtaskAction,
): NativeSubtaskState {
  if (action.type === 'RESET') {
    return createNativeSubtaskState(action.taskId)
  }

  if (action.type === 'UPDATE') {
    if (!state.taskId || action.update.taskId !== state.taskId) return state
    return mergeSubtask(state, action.update.subtask, action.update.lastEventSeq)
  }

  if (!state.taskId || action.snapshot.taskId !== state.taskId) return state
  return action.snapshot.subtasks.reduce(
    (next, subtask) => mergeSubtask(next, subtask, normalizeSeq(subtask.lastEventSeq)),
    state,
  )
}

export function selectNativeSubtasks(state: NativeSubtaskState): NativeSubtask[] {
  return Object.values(state.byId)
}

export function normalizeNativeSubtaskStatus(status?: string): NormalizedNativeSubtaskStatus {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'PENDING' || normalized === 'QUEUED') return 'PENDING'
  if (normalized === 'RUNNING' || normalized === 'IN_PROGRESS') return 'RUNNING'
  if (normalized === 'COMPLETED' || normalized === 'SUCCEEDED') return 'COMPLETED'
  if (normalized === 'FAILED' || normalized === 'ERROR') return 'FAILED'
  if (normalized === 'BLOCKED') return 'BLOCKED'
  if (['INTERRUPTED', 'CANCELED', 'CANCELLED', 'ABORTED'].includes(normalized)) return 'INTERRUPTED'
  return 'UNKNOWN'
}

export function normalizeNativeSubtaskMessage(
  _message: unknown,
  status: NormalizedNativeSubtaskStatus,
): NativeSubtaskMessageCode | undefined {
  if (status !== 'FAILED' && status !== 'BLOCKED') return undefined
  // Treat all failed-provider messages alike. Raw text may contain prompts,
  // filesystem paths, tool output, or credentials and must never reach the UI.
  return NATIVE_SUBTASK_FAILURE_CODE
}

/** Order parent/child items and cap indentation so malformed depth cannot break a Pane. */
export function buildNativeSubtaskRows(
  subtasks: NativeSubtask[],
  maxDisplayDepth = 3,
): NativeSubtaskRow[] {
  const byId = new Map(subtasks.map((item) => [item.subtaskId, item]))
  const children = new Map<string, NativeSubtask[]>()
  const roots: NativeSubtask[] = []

  for (const item of subtasks) {
    if (item.parentSubtaskId && item.parentSubtaskId !== item.subtaskId && byId.has(item.parentSubtaskId)) {
      const siblings = children.get(item.parentSubtaskId) ?? []
      siblings.push(item)
      children.set(item.parentSubtaskId, siblings)
    } else {
      roots.push(item)
    }
  }

  const rows: NativeSubtaskRow[] = []
  const visited = new Set<string>()
  const visit = (item: NativeSubtask, derivedDepth: number) => {
    if (visited.has(item.subtaskId)) return
    visited.add(item.subtaskId)
    const requestedDepth = Number.isFinite(item.depth) ? Math.max(0, item.depth) : derivedDepth
    rows.push({
      subtask: item,
      displayDepth: Math.min(maxDisplayDepth, Math.max(derivedDepth, requestedDepth)),
    })
    for (const child of children.get(item.subtaskId) ?? []) {
      visit(child, derivedDepth + 1)
    }
  }

  for (const root of roots) visit(root, 0)
  // Cycles have no root; append them once with bounded server-provided depth.
  for (const item of subtasks) visit(item, 0)
  return rows
}

export function parseNativeSubtaskUpdate(payload: unknown): NativeSubtaskUpdate | null {
  const outer = asRecord(payload)
  if (!outer) return null
  const wrapped = asRecord(outer.data)
  const source = wrapped ? { ...outer, ...wrapped } : outer
  const subtask = asRecord(source.subtask)
    ?? (wrapped && stringValue(wrapped.subtaskId) ? wrapped : null)
  const taskId = stringValue(source.taskId)
  const subtaskId = stringValue(subtask?.subtaskId)
  const lastEventSeq = normalizeSeq(source.lastEventSeq, -1)
  if (!taskId || !subtask || !subtaskId || lastEventSeq < 0) return null

  return {
    taskId,
    lastEventSeq,
    subtask: normalizeWireSubtask(subtask, subtaskId),
  }
}

function mergeSubtask(
  state: NativeSubtaskState,
  wire: NativeSubtaskWire,
  fallbackSeq: number,
): NativeSubtaskState {
  if (!wire.subtaskId) return state
  const previous = state.byId[wire.subtaskId]
  const incomingSeq = normalizeSeq(wire.lastEventSeq, fallbackSeq)
  if (previous && incomingSeq < previous.lastEventSeq) return state

  const defined = Object.fromEntries(
    Object.entries(wire).filter(([key, value]) => key !== 'message' && value !== undefined),
  ) as Partial<NativeSubtask>
  const status = normalizeNativeSubtaskStatus(wire.status || previous?.status)
  const merged: NativeSubtask = {
    ...(previous ?? {
      subtaskId: wire.subtaskId,
      status: wire.status || 'PENDING',
      depth: 0,
      lastEventSeq: 0,
    }),
    ...defined,
    status,
    message: normalizeNativeSubtaskMessage(wire.message, status),
    depth: finiteNonNegative(wire.depth, previous?.depth ?? 0),
    lastEventSeq: incomingSeq,
  }
  return {
    taskId: state.taskId,
    byId: { ...state.byId, [wire.subtaskId]: merged },
    lastEventSeq: Math.max(state.lastEventSeq, incomingSeq),
  }
}

function normalizeWireSubtask(source: Record<string, unknown>, subtaskId: string): NativeSubtaskWire {
  const status = stringValue(source.status) || 'PENDING'
  return {
    subtaskId,
    parentSubtaskId: stringValue(source.parentSubtaskId),
    depth: optionalFiniteNonNegative(source.depth),
    label: stringValue(source.label),
    role: stringValue(source.role),
    status,
    activity: stringValue(source.activity),
    message: normalizeNativeSubtaskMessage(
      source.message,
      normalizeNativeSubtaskStatus(status),
    ),
    startedAt: timestampValue(source.startedAt),
    updatedAt: timestampValue(source.updatedAt),
    completedAt: timestampValue(source.completedAt),
    durationMs: optionalFiniteNonNegative(source.durationMs),
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function timestampValue(value: unknown): string | number | undefined {
  return typeof value === 'string' || typeof value === 'number' ? value : undefined
}

function finiteNonNegative(value: unknown, fallback?: number): number {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : (fallback ?? 0)
}

function optionalFiniteNonNegative(value: unknown): number | undefined {
  if (value == null || value === '') return undefined
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined
}

function normalizeSeq(value: unknown, fallback = 0): number {
  return Math.floor(finiteNonNegative(value, fallback))
}
