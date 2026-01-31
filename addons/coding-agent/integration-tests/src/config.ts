/**
 * 集成测试配置
 */
export const TEST_CONFIG = {
  // API 基础 URL
  baseURL: process.env.API_BASE_URL || 'http://localhost:8112',

  // 超时配置
  timeout: 30000,
  retries: 2,

  // 测试标志
  skipDockerTests: process.env.SKIP_DOCKER_TESTS === 'true',
  skipOpenHandsTests: process.env.SKIP_OPENHANDS_TESTS === 'true',

  // 资源清理
  autoCleanup: true,
  cleanupTimeout: 5000,

  // 认证配置
  auth: {
    username: process.env.TEST_USERNAME || 'root',
    password: process.env.TEST_PASSWORD || 'root123'
  },

  // 测试数据
  testRepo: {
    gitRepoUrl: 'https://github.com/test/semantic-layer.git',
    branchName: 'main'
  }
} as const;

/**
 * 生成唯一的测试用户 ID
 */
export function generateTestUserId(): string {
  return `test-user-${Date.now()}-${Math.random().toString(36).substring(7)}`;
}

/**
 * 生成唯一的测试项目 ID
 */
export function generateTestProjectId(): string {
  return `test-project-${Date.now()}-${Math.random().toString(36).substring(7)}`;
}
