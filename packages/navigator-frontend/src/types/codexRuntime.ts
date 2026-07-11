export type CodexRuntimeRoutingPolicy =
  | 'DARK'
  | 'ULTRA_CANARY'
  | 'ULTRA_DEFAULT'
  | 'ALL_CANARY'
  | 'ALL_DEFAULT'
  | 'DRAINING'

export type CodexRuntimeReadinessStatus =
  | 'PENDING'
  | 'READY'
  | 'INCOMPATIBLE'
  | 'UNREACHABLE'
  | string

export interface CodexRuntime {
  runtimeId: string
  revision: number
  workerId: string
  runtimeType: string
  endpointConfigured?: boolean
  endpointDisplay?: string
  instanceId?: string
  enabled: boolean
  routingPolicy: CodexRuntimeRoutingPolicy
  rolloutPercentage: number
  priority: number
  routingEpoch: number
  readinessStatus: CodexRuntimeReadinessStatus
  readinessMessage?: string
  contractVersion?: string
  cliVersion?: string
  schemaDigest?: string
  expectedCliVersion?: string
  expectedSchemaDigest?: string
  capabilityFresh?: boolean
  supportsUltra?: boolean
  lastCapabilityAt?: string
  archived?: boolean
  archivedAt?: string
  createdAt: string
  updatedAt: string
}

export interface CodexRuntimeAvailability {
  appServerManaged: boolean
  ultraAvailable: boolean
  blockReason:
    | 'CODEX_ULTRA_RUNTIME_UNAVAILABLE'
    | 'CODEX_RUNTIME_MODEL_ALIAS_CONFLICT'
    | null
}

export type CodexRuntimeRateLimitState =
  | 'AVAILABLE'
  | 'LIMIT_REACHED'
  | 'STALE'
  | 'UNSUPPORTED'
  | 'UNKNOWN'

export interface CodexRuntimeRateLimitWindow {
  usedPercent: number
  windowDurationMins: number | null
  /** Provider reset timestamp in epoch seconds. */
  resetsAt: number | null
}

export interface CodexRuntimeRateLimit {
  limitId: string | null
  limitName: string | null
  primary: CodexRuntimeRateLimitWindow | null
  secondary: CodexRuntimeRateLimitWindow | null
  rateLimitReachedType: string | null
}

export interface CodexRuntimeRateLimits {
  contractVersion: number
  runtimeId: string
  runtimeRevision: number
  instanceId: string
  scope: string
  state: CodexRuntimeRateLimitState
  observedAtEpochMs: number | null
  stale: boolean
  limits: CodexRuntimeRateLimit[]
  errorCode: string | null
}

export interface RegisterCodexRuntimeRequest {
  runtimeId: string
  workerId: string
  runtimeType?: 'APP_SERVER'
  endpointUrl: string
  authToken?: string
  instanceId?: string
  enabled?: boolean
  routingPolicy?: CodexRuntimeRoutingPolicy
  rolloutPercentage?: number
  priority?: number
  routingEpoch?: number
}

export interface UpdateCodexRuntimeRoutingRequest {
  enabled?: boolean
  routingPolicy?: CodexRuntimeRoutingPolicy
  rolloutPercentage?: number
  priority?: number
  expectedRoutingEpoch: number
}

export interface UpdateCodexRuntimeLifecycleRequest {
  expectedRoutingEpoch: number
}
