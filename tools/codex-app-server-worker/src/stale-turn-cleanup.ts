/**
 * Safe result projection for an explicitly authorized cleanup of a native
 * app-server turn that outlived its logical Navigator task.  It deliberately
 * carries no provider state, thread content, or runtime identity.
 */
export type StaleTurnCleanupStatus = 'cleaned' | 'already_terminal'

/** The only native turn states this capability understands. */
export type StaleTurnStatus = 'inProgress' | 'completed' | 'failed' | 'interrupted'

export function isTerminalStaleTurnStatus(status: StaleTurnStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'interrupted'
}

export type StaleTurnCleanupResult = {
  status: StaleTurnCleanupStatus
}

export type StaleTurnCleanupErrorCode =
  | 'STALE_TURN_CLEANUP_TASK_NOT_TERMINAL'
  | 'STALE_TURN_CLEANUP_BINDING_MISSING'
  | 'STALE_TURN_CLEANUP_LANE_AFFINITY_MISMATCH'
  | 'STALE_TURN_CLEANUP_THREAD_AFFINITY_MISMATCH'
  | 'STALE_TURN_CLEANUP_TURN_NOT_FOUND'
  | 'STALE_TURN_CLEANUP_TURN_STATUS_UNKNOWN'
  | 'STALE_TURN_CLEANUP_RUNTIME_UNAVAILABLE'
  | 'STALE_TURN_CLEANUP_READ_UNAVAILABLE'
  | 'STALE_TURN_CLEANUP_INTERRUPT_UNAVAILABLE'
  | 'STALE_TURN_CLEANUP_REREAD_TIMEOUT'

/**
 * Stable public failures for the cleanup capability.  The status is part of
 * the capability contract: binding/protocol ambiguity is a conflict, while a
 * same-lane runtime that cannot be observed is temporarily unavailable.
 */
export class StaleTurnCleanupError extends Error {
  constructor(
    readonly code: StaleTurnCleanupErrorCode,
    readonly httpStatus: 409 | 503,
  ) {
    super(code)
    this.name = 'StaleTurnCleanupError'
  }
}
