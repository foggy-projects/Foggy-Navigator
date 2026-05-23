import 'dotenv/config';
import { beforeAll } from 'vitest';
import { createClient } from '../src/api-client.js';
import { TEST_CONFIG } from '../src/config.js';

beforeAll(async () => {
  console.log('=== Business Agent Integration Tests Setup ===');
  console.log('API Base URL:', TEST_CONFIG.baseURL);

  const client = createClient();
  try {
    await client.waitForReady(10, 2000);
  } catch (error) {
    console.error('Service is not ready. Start the Spring Boot application at:', TEST_CONFIG.baseURL);
    throw error;
  }

  console.log('Service is ready');
}, 60000);
