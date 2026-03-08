/**
 * 集成测试配置
 */
export const TEST_CONFIG = {
  baseURL: process.env.API_BASE_URL || 'http://localhost:8112',

  timeout: 30000,

  auth: {
    username: process.env.TEST_USERNAME || 'root',
    password: process.env.TEST_PASSWORD || 'root123'
  },

  autoCleanup: true,

  // 默认测试 agentId
  defaultAgentId: 'tutor-agent'
} as const;

export function generateTestSessionTitle(): string {
  return `test-session-${Date.now()}-${Math.random().toString(36).substring(7)}`;
}
