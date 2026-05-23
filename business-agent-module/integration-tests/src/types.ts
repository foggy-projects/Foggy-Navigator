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
  token?: string;
  controlApiKey?: string;
  tenantId?: string;
  scopes?: string[];
  expiresAt?: string;
}

export interface UpstreamTenantClientAppProvisioning {
  navigatorTenantId: string;
  clientAppId: string;
  clientAppName: string;
  capabilityDomain: string;
  clientAppKey?: string;
  clientAppSecret?: string;
  controlApiKey?: string;
  rootAgentId?: string;
  modelConfigId?: string;
  skillId?: string;
  workerPoolId?: string;
  bindingVersion: string;
  status?: string;
  errorCode?: string;
  message?: string;
  credentialsReplayable?: boolean;
  created: boolean;
  rotated: boolean;
  blockers: string[];
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

export interface ClientApp {
  clientAppId: string;
  tenantId: string;
  name: string;
  status: string;
}

export interface BizWorkerPool {
  poolId: string;
  tenantId: string;
  name: string;
  workerBackend: string;
  status: string;
  healthStatus: string;
}

export interface BizWorkerIdentity {
  workerId: string;
  workerBackend: string;
  baseUrl: string;
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
  skillId: string;
  workerPoolId: string;
  requestedModelConfigId?: string;
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

