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
  createdAt: string
  updatedAt: string
}

export interface RegisterCodexRuntimeRequest {
  runtimeId: string
  workerId: string
  runtimeType?: 'APP_SERVER'
  endpointUrl: string
  authToken: string
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
