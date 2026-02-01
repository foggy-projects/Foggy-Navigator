import 'dotenv/config';
import { beforeAll, afterAll } from 'vitest';
import { createClient, CodingAgentClient } from '../src/api-client.js';
import { TEST_CONFIG } from '../src/config.js';

/**
 * 全局测试设置
 */

let isServiceReady = false;
let sharedClient: CodingAgentClient | null = null;

beforeAll(async () => {
  console.log('=== Integration Tests Setup ===');
  console.log('API Base URL:', TEST_CONFIG.baseURL);
  console.log('Skip Docker Tests:', TEST_CONFIG.skipDockerTests);
  console.log('Skip OpenHands Tests:', TEST_CONFIG.skipOpenHandsTests);

  // 检查服务是否就绪
  const client = createClient();

  try {
    console.log('Checking if service is ready...');
    await client.waitForReady(10, 2000);
    isServiceReady = true;
    console.log('✓ Service is ready');
  } catch (error) {
    console.error('✗ Service is not ready');
    console.error('Please make sure the Spring Boot application is running at:', TEST_CONFIG.baseURL);
    process.exit(1);
  }

  // 登录获取 JWT token
  try {
    await client.login(TEST_CONFIG.auth.username, TEST_CONFIG.auth.password);
    sharedClient = client;
  } catch (error) {
    console.error('✗ Login failed');
    process.exit(1);
  }

  console.log('=== Setup Complete ===\n');
}, 30000);

afterAll(async () => {
  console.log('\n=== Integration Tests Cleanup ===');
  console.log('All tests completed');
});

export { isServiceReady, sharedClient };
