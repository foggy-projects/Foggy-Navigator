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
  created: boolean;
  rotated: boolean;
  blockers: string[];
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

