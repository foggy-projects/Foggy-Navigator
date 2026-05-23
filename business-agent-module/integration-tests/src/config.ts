export const TEST_CONFIG = {
  baseURL: process.env.API_BASE_URL || 'http://localhost:8112',
  timeout: Number(process.env.API_TIMEOUT_MS || 30000),

  auth: {
    username: process.env.TEST_USERNAME || 'root',
    password: process.env.TEST_PASSWORD || 'root123'
  },

  mockBaseURL: process.env.BIZ_AGENT_E2E_MOCK_BASE_URL || 'http://localhost:18080/v1',
  workerBackend: process.env.BIZ_AGENT_E2E_WORKER_BACKEND || 'NAVI_E2E_NO_WORKER'
} as const;

export function generateTestSuffix(): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 8);
  return `${timestamp}_${random}`;
}

