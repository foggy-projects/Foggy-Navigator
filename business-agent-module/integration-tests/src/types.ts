export interface RXResponse<T = unknown> {
  code: number;
  msg?: string;
  data?: T;
  timestamp?: string;
  userTip?: string;
}

export interface LoginResultDTO {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: {
    id: string;
    username: string;
  };
}

export interface IssuedCredential {
  credentialId: string;
  clientAppId?: string;
  appKey?: string;
  secret?: string;
  token?: string;
  controlApiKey?: string;
  tenantId?: string;
  scopes?: string[];
  expiresAt?: string;
}

export interface UpstreamTenantClientAppProvisioning {
  navigatorTenantId: string;
  targetNavigatorTenantId?: string;
  clientAppId: string;
  clientAppName: string;
  capabilityDomain: string;
  clientAppCapabilityDomain?: string;
  upstreamSystemId?: string;
  sourceTenantId?: string;
  upstreamRef?: string;
  upstreamNamespace?: string;
  clientAppKey?: string;
  clientAppSecret?: string;
  controlApiKey?: string;
  agentCode?: string;
  rootAgentId?: string;
  modelConfigId?: string;
  skillId?: string;
  workerPoolId?: string;
  workerBackend?: string;
  physicalWorkerId?: string;
  directoryId?: string;
  bizWorkerBaseUrl?: string;
  bindingVersion: string;
  status?: string;
  errorCode?: string;
  message?: string;
  remediationHint?: string;
  credentialsReplayable?: boolean;
  created: boolean;
  rotated: boolean;
  activationReady?: boolean;
  blockers: string[];
  missingFields?: string[];
  requiredScopes?: string[];
  actualScopes?: string[];
  authorizedTenantIds?: string[];
}

export interface UpstreamBootstrapRequestCreated {
  requestCode: string;
  requestCodeSuffix: string;
  claimToken: string;
  status: string;
  requestExpiresAt?: string;
}

export interface UpstreamAdminCredentialClaim {
  credentialId: string;
  naviAdminApiKey: string;
  upstreamSystemId: string;
  authorizedTenantIds: string[];
  authorizedClientAppNamespace: string;
  scopes: string[];
  expiresAt?: string;
}

export interface UpstreamAdminCredential {
  credentialId: string;
  principalId?: string;
  credentialKeyPrefix?: string;
  credentialKeySuffix?: string;
  upstreamSystemId: string;
  authorizedTenantIds: string[];
  authorizedClientAppNamespace: string;
  scopes: string[];
  status: string;
  expiresAt?: string;
  revokedAt?: string;
  lastUsedAt?: string;
  sourceRequestId?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ClientApp {
  clientAppId: string;
  tenantId: string;
  name: string;
  status: string;
}

export interface ClientAppRuntimeAccessToken {
  tokenId: string;
  tenantId: string;
  clientAppId: string;
  credentialId: string;
  appKey: string;
  accessToken: string;
  tokenType?: string;
  expiresInSeconds?: number;
  expiresAt?: string;
}

export interface BusinessAgentBundle {
  tenantId: string;
  clientAppId: string;
  agentId: string;
  skillId: string;
  ownerType?: string;
  ownerId?: string;
  name: string;
  description?: string;
  agentType?: string;
  workerId?: string;
  defaultDirectoryId?: string;
  agentProfile?: string;
  defaultModelConfigId?: string;
  defaultModel?: string;
  enabled?: boolean;
  createdAt?: string;
  updatedAt?: string;
  skillBundle?: SkillBundle;
}

export interface SkillBundle {
  tenantId?: string;
  clientAppId?: string;
  skillId: string;
  name?: string;
  description?: string;
  status?: string;
  markdownBody?: string;
  contextVisibility?: string;
}

export interface BizWorkerPool {
  poolId: string;
  tenantId: string;
  name: string;
  workerBackend: string;
  ownerType?: string;
  ownerId?: string;
  capabilitiesJson?: string;
  labelsJson?: string;
  status: string;
  healthStatus: string;
}

export interface BizWorkerIdentity {
  workerId: string;
  workerBackend: string;
  baseUrl: string;
  ownerType?: string;
  ownerId?: string;
  capabilitiesJson?: string;
  labelsJson?: string;
  version?: string;
  status: string;
  healthStatus: string;
}

export interface LanggraphWorker {
  workerId: string;
  name: string;
  baseUrl: string;
  authMode?: string;
  status: string;
  hostname?: string;
  workerVersion?: string;
}

export interface LanggraphTask {
  taskId: string;
  sessionId: string;
  workerId: string;
  agentId: string;
  userId: string;
  prompt: string;
  status: string;
  model?: string;
  modelConfigId?: string;
  contextId?: string;
  resultText?: string;
  structuredOutput?: string;
  errorMessage?: string;
  durationMs?: number;
}

export interface Skill {
  skillId: string;
  tenantId: string;
  name: string;
  status: string;
}

export interface ClientAppSkillGrant {
  grantId: string;
  clientAppId: string;
  skillId: string;
  status: string;
}

export interface ClientAppUpstreamUserGrant {
  grantId: string;
  clientAppId: string;
  upstreamUserId: string;
  status: string;
}

export interface E2eModelConfigEnsureResult {
  clientAppId: string;
  standard: string;
  mockBaseUrl: string;
  modelConfigId: string;
  modelConfigName: string;
  modelCreated: boolean;
  modelUpdated: boolean;
  grantId: number;
  grantCreated: boolean;
  grantStatus: string;
  isDefault: boolean;
}

export interface ClientAppModelConfigGrant {
  id: number;
  clientAppId: string;
  tenantId: string;
  modelConfigId: string;
  modelConfigName?: string;
  workerBackend?: string;
  status: string;
  isDefault: boolean;
  grantScope?: string;
}

export interface CreateBusinessAgentTaskRequest {
  clientAppId: string;
  sessionId: string;
  contextId?: string;
  upstreamUserId: string;
  agentId?: string;
  skillId?: string;
  workerPoolId?: string;
  requestedModelConfigId?: string;
  modelVariant?: string;
  resumeFromTaskId?: string;
  clientContextJson?: string;
  workdir?: string;
  allowed_dirs?: string[];
  allowed_tools?: string[];
}

export interface CreatedBusinessAgentTask {
  taskId: string;
  sessionId: string;
  contextId: string;
  tenantId: string;
  clientAppId: string;
  upstreamUserId: string;
  navigatorEffectiveUserId: string;
  skillId: string;
  workerPoolId: string;
  workerTaskId?: string;
  workerSessionId?: string;
  workerId?: string;
  workerProviderType?: string;
  modelConfigId: string;
  requestedModelConfigId?: string;
  status: string;
  taskScopedToken: string;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessAgentTask {
  taskId: string;
  sessionId: string;
  contextId?: string;
  tenantId: string;
  clientAppId: string;
  upstreamUserId: string;
  navigatorEffectiveUserId: string;
  skillId: string;
  workerPoolId: string;
  workerTaskId?: string;
  workerSessionId?: string;
  workerId?: string;
  workerProviderType?: string;
  modelConfigId: string;
  requestedModelConfigId?: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface OpenApiAskRequest {
  message?: string;
  question?: string;
  contextId?: string;
  maxTurns?: number;
  modelConfigId?: string;
  modelVariant?: string;
  systemPrompt?: string;
  firstMsg?: string;
  metadata?: Record<string, unknown>;
  clientContext?: Record<string, unknown>;
  attachments?: Array<Record<string, unknown>>;
  workdir?: string;
  allowed_dirs?: string[];
  allowed_tools?: string[];
}

export interface OpenApiTask {
  taskId: string;
  agentId?: string;
  status: string;
  contextId?: string;
  workerTaskId?: string;
  providerTaskId?: string;
  lastAckedSeq?: number;
  modelConfigId?: string;
  modelConfigSource?: string;
  workerBackend?: string;
  providerType?: string;
  taskSource?: string;
  workerSource?: string;
  backendSource?: string;
  failureStage?: string;
  failureSummary?: string;
  result?: string;
  errorMessage?: string;
  durationMs?: number;
  costUsd?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface OpenTaskDiagnostics {
  taskId: string;
  agentId: string;
  contextId?: string;
  status: string;
  terminal: boolean;
  terminalStatus?: string;
  submittedAt?: string;
  workerStartedAt?: string;
  lastObservedAt?: string;
  messagesCount?: number;
  workerTaskId?: string;
  providerTaskId?: string;
  lastAckedSeq?: number;
  modelConfigId?: string;
  modelConfigSource?: string;
  workerBackend?: string;
  providerType?: string;
  taskSource?: string;
  workerSource?: string;
  backendSource?: string;
  safeWorkerRef?: string;
  failureStage?: string;
  failureSummary?: string;
  cancelCapability?: OpenTaskCancelCapability;
  correlation?: OpenTaskCorrelation;
  createdAt?: string;
  updatedAt?: string;
}

export interface OpenTaskCorrelation {
  originalTaskId?: string;
  recoveryCorrelationKey?: string;
  attemptNumber?: number;
  idempotencyKey?: string;
}

export interface OpenTaskCancelCapability {
  cancelSupported: boolean;
  cancelMode?: string;
  cleanupSupported?: boolean;
  backendLimitations?: string[];
}

export interface OpenTaskEvidence {
  taskId: string;
  agentId: string;
  contextId?: string;
  status: string;
  terminal: boolean;
  terminalStatus?: string;
  finalAnswer?: OpenTaskFinalAnswer;
  structuredOutput?: OpenTaskStructuredOutput;
  reportRefs?: OpenTaskReportRef[];
  artifactRefs?: OpenTaskArtifactRef[];
}

export interface OpenTaskFinalAnswer {
  available: boolean;
  summary?: string;
  messageId?: string;
  source?: string;
  createdAt?: string;
}

export interface OpenTaskStructuredOutput {
  available: boolean;
  content?: unknown;
  source?: string;
  messageId?: string;
}

export interface OpenTaskReportRef {
  refId?: string;
  ref?: string;
  frameId?: string;
  type?: string;
  title?: string;
  summary?: string;
  createdAt?: string;
  metadata?: Record<string, unknown>;
}

export interface OpenTaskArtifactRef {
  refId?: string;
  type?: string;
  title?: string;
  path?: string;
  mimeType?: string;
  sizeBytes?: number;
  metadata?: Record<string, unknown>;
}

export interface OpenTaskMessagesResponse {
  taskId: string;
  contextId?: string;
  workerTaskId?: string;
  providerTaskId?: string;
  lastAckedSeq?: number;
  modelConfigId?: string;
  modelConfigSource?: string;
  workerBackend?: string;
  providerType?: string;
  taskSource?: string;
  workerSource?: string;
  backendSource?: string;
  failureStage?: string;
  failureSummary?: string;
  messages: OpenSessionMessage[];
  status: string;
  terminal: boolean;
  terminalStatus?: string;
  nextCursor?: string;
  hasMore: boolean;
}

export interface OpenSessionMessage {
  messageId?: string;
  contextId?: string;
  taskId?: string;
  role?: string;
  type?: string;
  eventKind?: string;
  progressType?: string;
  content?: string;
  status?: string;
  terminal?: boolean;
  terminalStatus?: string;
  metadata?: Record<string, unknown>;
  attachments?: Array<Record<string, unknown>>;
  reportRefs?: OpenTaskReportRef[];
  artifactRefs?: OpenTaskArtifactRef[];
  createdAt?: string;
}

