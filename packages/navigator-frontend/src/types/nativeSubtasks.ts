export const NATIVE_SUBTASK_UPDATE_TYPE = 'NATIVE_SUBTASK_UPDATE' as const
export const NATIVE_SUBTASK_FAILURE_CODE = 'NATIVE_SUBTASK_FAILED' as const
export type NativeSubtaskMessageCode = typeof NATIVE_SUBTASK_FAILURE_CODE

export type NativeSubtaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'INTERRUPTED'
  | 'CANCELED'
  | 'BLOCKED'
  | (string & {})

/** Wire representation shared by the snapshot API and AgentMessage payload. */
export interface NativeSubtaskWire {
  subtaskId: string
  parentSubtaskId?: string
  depth?: number
  label?: string
  role?: string
  status: NativeSubtaskStatus
  activity?: string
  message?: string
  startedAt?: string | number
  updatedAt?: string | number
  completedAt?: string | number
  durationMs?: number
  lastEventSeq?: number
}

/** Normalized item stored independently from @foggy/chat messages. */
export interface NativeSubtask extends Omit<NativeSubtaskWire, 'message'> {
  depth: number
  lastEventSeq: number
  message?: NativeSubtaskMessageCode
}

export interface NativeSubtaskSnapshot {
  taskId: string
  subtasks: NativeSubtaskWire[]
}

export interface NativeSubtaskUpdate {
  taskId: string
  lastEventSeq: number
  subtask: NativeSubtaskWire
}

export interface NativeSubtaskAgentMessage {
  type: typeof NATIVE_SUBTASK_UPDATE_TYPE
  payload: NativeSubtaskUpdate | { data: NativeSubtaskUpdate }
}

export interface NativeSubtaskRow {
  subtask: NativeSubtask
  displayDepth: number
}
