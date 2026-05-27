import axios, { AxiosError, AxiosInstance } from 'axios';
import { TEST_CONFIG } from './config.js';
import type {
  BizWorkerIdentity,
  BizWorkerPool,
  BusinessAgentTask,
  ClientApp,
  ClientAppModelConfigGrant,
  ClientAppSkillGrant,
  ClientAppUpstreamUserGrant,
  CreateBusinessAgentTaskRequest,
  CreatedBusinessAgentTask,
  E2eModelConfigEnsureResult,
  IssuedCredential,
  LanggraphTask,
  LanggraphWorker,
  LoginResultDTO,
  RXResponse,
  Skill,
  UpstreamAdminCredentialClaim,
  UpstreamBootstrapRequestCreated,
  UpstreamTenantClientAppProvisioning
} from './types.js';

type RequestBody = Record<string, unknown>;

export class BusinessAgentClient {
  private client: AxiosInstance;

  constructor(baseURL: string = TEST_CONFIG.baseURL) {
    this.client = axios.create({
      baseURL,
      timeout: TEST_CONFIG.timeout,
      headers: {
        'Content-Type': 'application/json'
      }
    });

    this.client.interceptors.response.use(
      response => response,
      (error: AxiosError) => {
        const status = error.response?.status;
        const data = error.response?.data;
        const url = error.config?.url;
        console.error('API Error:', { url, status, data });
        const apiError: any = new Error(`API Error: ${error.message}`);
        apiError.response = { status, data };
        apiError.url = url;
        throw apiError;
      }
    );
  }

  async login(username: string, password: string): Promise<void> {
    const response = await this.client.post<RXResponse<LoginResultDTO>>(
      '/api/v1/auth/login',
      { username, password }
    );
    const token = response.data?.data?.token;
    if (!token) {
      throw new Error('Login failed: no token returned');
    }
    this.client.defaults.headers.common.Authorization = `Bearer ${token}`;
  }

  async registerUser(body: RequestBody): Promise<string> {
    const response = await this.client.post('/api/v1/auth/register', body);
    return unwrapData<string>(response.data);
  }

  async healthCheck(): Promise<boolean> {
    try {
      const health = await this.client.get('/actuator/health', {
        validateStatus: status => status < 500
      });
      if (health.status >= 200 && health.status < 300) {
        return true;
      }

      const authProbe = await this.client.get('/api/v1/auth/login', {
        validateStatus: status => status < 500
      });
      return authProbe.status !== 404;
    } catch {
      return false;
    }
  }

  async waitForReady(maxAttempts: number = 20, interval: number = 1000): Promise<void> {
    for (let i = 0; i < maxAttempts; i++) {
      if (await this.healthCheck()) {
        return;
      }
      await new Promise(resolve => setTimeout(resolve, interval));
    }
    throw new Error(`Service failed to become ready at ${TEST_CONFIG.baseURL}`);
  }

  async issueProvisioningCredential(body: RequestBody): Promise<IssuedCredential> {
    const response = await this.client.post(
      '/api/v1/admin/client-apps/provisioning-credentials',
      body
    );
    return unwrapData<IssuedCredential>(response.data);
  }

  setUpstreamAdminApiKey(apiKey: string): void {
    this.client.defaults.headers.common['X-Navi-Admin-Key'] = apiKey;
    delete this.client.defaults.headers.common.Authorization;
  }

  setClientAppControlKey(controlKey: string): void {
    this.client.defaults.headers.common['X-Client-App-Control-Key'] = controlKey;
    delete this.client.defaults.headers.common.Authorization;
    delete this.client.defaults.headers.common['X-Navi-Admin-Key'];
  }

  async requestUpstreamAdminKey(body: RequestBody): Promise<UpstreamBootstrapRequestCreated> {
    const response = await this.client.post(
      '/api/v1/upstream-bootstrap/admin-key-requests',
      body
    );
    return unwrapData<UpstreamBootstrapRequestCreated>(response.data);
  }

  async approveUpstreamAdminKeyRequest(requestCode: string, body: RequestBody): Promise<void> {
    await this.client.post(
      `/api/v1/admin/upstream-bootstrap-requests/${encodeURIComponent(requestCode)}/approve`,
      body
    );
  }

  async claimUpstreamAdminKey(requestCode: string, body: RequestBody): Promise<UpstreamAdminCredentialClaim> {
    const response = await this.client.post(
      `/api/v1/upstream-bootstrap/admin-key-requests/${encodeURIComponent(requestCode)}/claim`,
      body
    );
    return unwrapData<UpstreamAdminCredentialClaim>(response.data);
  }

  async ensureUpstreamTenantClientApp(body: RequestBody): Promise<UpstreamTenantClientAppProvisioning> {
    const response = await this.client.post(
      '/api/v1/admin/upstream-tenants/client-apps/ensure',
      body
    );
    return unwrapData<UpstreamTenantClientAppProvisioning>(response.data);
  }

  async createClientApp(body: RequestBody): Promise<ClientApp> {
    const response = await this.client.post('/api/v1/client-apps', body);
    return unwrapData<ClientApp>(response.data);
  }

  async createWorkerPool(body: RequestBody): Promise<BizWorkerPool> {
    const response = await this.client.post('/api/v1/business-agent/worker-pools', body);
    return unwrapData<BizWorkerPool>(response.data);
  }

  async registerWorkerIdentity(body: RequestBody): Promise<BizWorkerIdentity> {
    const response = await this.client.post('/api/v1/business-agent/worker-identities', body);
    return unwrapData<BizWorkerIdentity>(response.data);
  }

  async addWorkerPoolMember(poolId: string, body: RequestBody): Promise<void> {
    const response = await this.client.post(
      `/api/v1/business-agent/worker-pools/${encodeURIComponent(poolId)}/members`,
      body
    );
    unwrapVoid(response.data);
  }

  async registerLanggraphWorker(body: RequestBody): Promise<LanggraphWorker> {
    const response = await this.client.post('/api/v1/langgraph-workers', body);
    return unwrapData<LanggraphWorker>(response.data);
  }

  async healthCheckLanggraphWorker(workerId: string): Promise<LanggraphWorker> {
    const response = await this.client.post(
      `/api/v1/langgraph-workers/${encodeURIComponent(workerId)}/health-check`
    );
    return unwrapData<LanggraphWorker>(response.data);
  }

  async createSkill(body: RequestBody): Promise<Skill> {
    const response = await this.client.post('/api/v1/business-agent/skills', body);
    return unwrapData<Skill>(response.data);
  }

  async grantSkillToClientApp(clientAppId: string, body: RequestBody): Promise<ClientAppSkillGrant> {
    const response = await this.client.post(
      `/api/v1/business-agent/client-apps/${clientAppId}/skill-grants`,
      body
    );
    return unwrapData<ClientAppSkillGrant>(response.data);
  }

  async grantUpstreamUserAccess(clientAppId: string, body: RequestBody): Promise<ClientAppUpstreamUserGrant> {
    const response = await this.client.post(
      `/api/v1/business-agent/client-apps/${clientAppId}/upstream-users`,
      body
    );
    return unwrapData<ClientAppUpstreamUserGrant>(response.data);
  }

  async ensureE2eModelConfig(clientAppId: string, body: RequestBody): Promise<E2eModelConfigEnsureResult> {
    const response = await this.client.post(
      `/api/v1/business-agent/client-apps/${clientAppId}/e2e-model-config/ensure`,
      body
    );
    return unwrapData<E2eModelConfigEnsureResult>(response.data);
  }

  async createClientAppModelConfig(clientAppId: string, body: RequestBody): Promise<ClientAppModelConfigGrant> {
    const response = await this.client.post(
      `/api/v1/client-apps/${encodeURIComponent(clientAppId)}/model-configs`,
      body
    );
    return unwrapData<ClientAppModelConfigGrant>(response.data);
  }

  async grantClientAppModelConfig(clientAppId: string, body: RequestBody): Promise<ClientAppModelConfigGrant> {
    const response = await this.client.post(
      `/api/v1/client-apps/${encodeURIComponent(clientAppId)}/model-config-grants`,
      body
    );
    return unwrapData<ClientAppModelConfigGrant>(response.data);
  }

  async createTask(body: CreateBusinessAgentTaskRequest): Promise<CreatedBusinessAgentTask> {
    const response = await this.client.post('/api/v1/business-agent/tasks', body);
    return unwrapData<CreatedBusinessAgentTask>(response.data);
  }

  async getBusinessTask(taskId: string): Promise<BusinessAgentTask> {
    const response = await this.client.get(
      `/api/v1/business-agent/tasks/${encodeURIComponent(taskId)}`
    );
    return unwrapData<BusinessAgentTask>(response.data);
  }

  async getLanggraphTask(taskId: string, userId: string): Promise<LanggraphTask> {
    const response = await this.client.get(
      `/api/v1/langgraph-tasks/${encodeURIComponent(taskId)}`,
      { params: { userId } }
    );
    return unwrapData<LanggraphTask>(response.data);
  }
}

export function createClient(baseURL?: string): BusinessAgentClient {
  return new BusinessAgentClient(baseURL);
}

export async function createAuthenticatedClient(
  baseURL?: string,
  username: string = TEST_CONFIG.auth.username,
  password: string = TEST_CONFIG.auth.password
): Promise<BusinessAgentClient> {
  const client = new BusinessAgentClient(baseURL);
  await client.login(username, password);
  return client;
}

function unwrapData<T>(payload: unknown): T {
  if (isRXResponse<T>(payload)) {
    if (payload.code !== 200) {
      throw new Error(`API returned code ${payload.code}: ${payload.msg ?? 'unknown error'}`);
    }
    if (payload.data === undefined || payload.data === null) {
      throw new Error('API returned empty data');
    }
    return payload.data;
  }
  return payload as T;
}

function unwrapVoid(payload: unknown): void {
  if (isRXResponse(payload) && payload.code !== 200) {
    throw new Error(`API returned code ${payload.code}: ${payload.msg ?? 'unknown error'}`);
  }
}

function isRXResponse<T>(payload: unknown): payload is RXResponse<T> {
  return Boolean(payload && typeof payload === 'object' && 'code' in payload);
}
