import { describe, expect, test } from 'vitest';
import {
  loadSyntheticRuntimeConfig,
  loadSyntheticRuntimeProbe
} from '../src/synthetic-runtime-config.js';

/**
 * These tests deliberately provide a synthetic ProcessEnv rather than loading
 * the process environment. They prove the child boundary without inheriting a
 * developer shell, legacy E2E variables, or any real credential.
 */
describe('05 - synthetic runtime environment boundary', () => {
  test('accepts only the documented runtime projection', () => {
    const config = loadSyntheticRuntimeConfig(validEnvironment());

    expect(config.runId).toBe('int001-unit-run');
    expect(config.naviBaseUrl).toBe('http://127.0.0.1:19112');
    expect(config.a.clientAppId).toBe('client-app-a');
    expect(config.sameTenantOtherClientAppAgentId).toBe('agent-b');
    expect(config.crossTenantAgentId).toBe('agent-c');
  });

  test.each([
    'FOO',
    'JAVA_TOOL_OPTIONS',
    'API_BASE_URL',
    'NAVI_ADMIN_API_KEY',
    'BIZ_AGENT_E2E_MOCK_BASE_URL',
    'INT001_B_CLIENT_APP_SECRET',
    'VITEST_UNDECLARED'
  ])('rejects undeclared inherited variable %s', name => {
    const env = validEnvironment();
    env[name] = 'not-projected';

    expect(() => loadSyntheticRuntimeConfig(env)).toThrow(
      `${name} is not allowed in the synthetic runtime child`
    );
  });

  test('accepts each documented Vite/Vitest process-local exemption', () => {
    const env = validEnvironment();
    env.BASE_URL = '/';
    env.DEV = 'true';
    env.MODE = 'test';
    env.NODE_ENV = 'test';
    env.PROD = 'false';
    env.SSR = 'true';
    env.TEST = 'true';
    env.VITEST = 'true';
    env.VITEST_MODE = 'TEST';
    env.VITEST_POOL_ID = '1';
    env.VITEST_WORKER_ID = '1';

    expect(loadSyntheticRuntimeConfig(env).a.agentId).toBe('agent-a');
  });

  test('requires PATH and HOME alongside the INT001 projection', () => {
    const noPath = validEnvironment();
    delete noPath.PATH;
    expect(() => loadSyntheticRuntimeConfig(noPath)).toThrow('PATH is required');

    const noHome = validEnvironment();
    delete noHome.HOME;
    expect(() => loadSyntheticRuntimeConfig(noHome)).toThrow('HOME is required');
  });

  test('accepts only a registered parent-selected runtime probe', () => {
    const env = validEnvironment();
    env.INT001_RUNTIME_PROBE = 'deny-directory';

    expect(loadSyntheticRuntimeConfig(env).a.agentId).toBe('agent-a');
    expect(loadSyntheticRuntimeProbe(env)).toBe('deny-directory');

    env.INT001_RUNTIME_PROBE = 'all-denies';
    expect(() => loadSyntheticRuntimeProbe(env)).toThrow(
      'INT001_RUNTIME_PROBE must be a registered synthetic probe'
    );
  });
});

function validEnvironment(): NodeJS.ProcessEnv {
  return {
    PATH: '/usr/bin:/bin',
    HOME: '/tmp/int001-unit-home',
    INT001_SYNTHETIC_UPSTREAM_HARNESS: 'true',
    INT001_RUN_ID: 'int001-unit-run',
    INT001_NAVI_BASE_URL: 'http://127.0.0.1:19112',
    INT001_A_TENANT_ID: 'tenant-a',
    INT001_A_CLIENT_APP_ID: 'client-app-a',
    INT001_A_CLIENT_APP_KEY: 'key-fixture-a',
    INT001_A_CLIENT_APP_SECRET: 'secret-fixture-a',
    INT001_A_AGENT_ID: 'agent-a',
    INT001_A_UPSTREAM_USER_ID: 'upstream-user-a',
    INT001_A_MODEL_CONFIG_ID: 'model-a',
    INT001_A_DIRECTORY_ID: 'directory-a',
    INT001_B_AGENT_ID: 'agent-b',
    INT001_C_AGENT_ID: 'agent-c'
  };
}
