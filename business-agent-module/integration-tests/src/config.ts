export const TEST_CONFIG = {
  baseURL: process.env.API_BASE_URL || 'http://localhost:8112',
  timeout: Number(process.env.API_TIMEOUT_MS || 30000),

  auth: {
    username: process.env.TEST_USERNAME || 'root',
    password: process.env.TEST_PASSWORD || 'root123'
  },

  mockBaseURL: process.env.BIZ_AGENT_E2E_MOCK_BASE_URL || 'http://127.0.0.1:3066/v1',
  mockAdminBaseURL: mockAdminBaseURL(process.env.BIZ_AGENT_E2E_MOCK_BASE_URL || 'http://127.0.0.1:3066/v1'),
  workerBackend: process.env.BIZ_AGENT_E2E_WORKER_BACKEND || 'NAVI_E2E_NO_WORKER',
  langgraphWorkerBaseURL: process.env.BIZ_AGENT_E2E_LANGGRAPH_WORKER_BASE_URL || 'http://localhost:3065',
  enableLanggraphWorkerSmoke: process.env.BIZ_AGENT_E2E_LANGGRAPH_WORKER_SMOKE === 'true',
  enableOpenApiDiagnosticsSmoke: process.env.BIZ_AGENT_E2E_OPENAPI_DIAGNOSTICS_SMOKE === 'true',
  commandWorkdir: process.env.BIZ_AGENT_E2E_COMMAND_WORKDIR || '/tmp'
} as const;

export function generateTestSuffix(): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 8);
  return `${timestamp}_${random}`;
}

function mockAdminBaseURL(baseURL: string): string {
  return baseURL.replace(/\/v1\/?$/, '').replace(/\/$/, '');
}

