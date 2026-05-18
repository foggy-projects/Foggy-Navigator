import { beforeAll, describe, expect, test } from 'vitest';
import { createAuthenticatedClient, createClient, BusinessAgentClient } from '../src/api-client.js';
import { generateTestSuffix } from '../src/config.js';

describe('02 - Upstream tenant ClientApp provisioning', () => {
  let bootstrapClient: BusinessAgentClient;

  beforeAll(async () => {
    bootstrapClient = createClient();
  });

  test('ensures a tenant ClientApp idempotently and rotates credentials on request', async () => {
    const suffix = generateTestSuffix();
    const authTenantId = `e2e_admin_${suffix}`;
    const username = `e2e_provisioner_${suffix}`;
    const password = `E2ePass_${suffix}_123`;
    const sourceSystem = 'tms-e2e';
    const sourceTenantId = `tenant-${suffix}`;
    const capabilityDomain = `tms-e2e-${suffix}`;
    const agentBundleCode = `tms-root-${suffix}`;
    const skillId = `tms.skill.${suffix}`;
    const workerPoolId = `biz-worker-${suffix}`;

    const registeredUserId = await bootstrapClient.registerUser({
      tenantId: authTenantId,
      username,
      password,
      email: `${username}@example.invalid`,
      displayName: `Upstream Tenant Provisioner ${suffix}`,
      roles: 'TENANT_ADMIN'
    });
    expect(registeredUserId).toBeTruthy();

    const client = await createAuthenticatedClient(undefined, username, password);

    const first = await client.ensureUpstreamTenantClientApp({
      sourceSystem,
      sourceTenantId,
      clientAppName: `TMS Tenant ${suffix}`,
      tenantName: `TMS Tenant ${suffix}`,
      capabilityDomain,
      agentBundleCode,
      skillId,
      workerPoolId
    });

    expect(first.created).toBe(true);
    expect(first.rotated).toBe(false);
    expect(first.navigatorTenantId).toBe(`nav_${sourceSystem}_${sourceTenantId}`);
    expect(first.clientAppId).toMatch(/^capp_/);
    expect(first.clientAppName).toBe(`TMS Tenant ${suffix}`);
    expect(first.capabilityDomain).toBe(capabilityDomain);
    expect(first.clientAppKey).toBeTruthy();
    expect(first.clientAppSecret).toBeTruthy();
    expect(first.controlApiKey).toBeTruthy();
    expect(first.rootAgentId).toBe(agentBundleCode);
    expect(first.skillId).toBe(skillId);
    expect(first.workerPoolId).toBe(workerPoolId);
    expect(first.bindingVersion).toBeTruthy();

    const second = await client.ensureUpstreamTenantClientApp({
      sourceSystem,
      sourceTenantId,
      clientAppName: `TMS Tenant ${suffix} Updated`,
      tenantName: `TMS Tenant ${suffix} Updated`,
      capabilityDomain,
      agentBundleCode,
      skillId,
      workerPoolId
    });

    expect(second.created).toBe(false);
    expect(second.rotated).toBe(false);
    expect(second.clientAppId).toBe(first.clientAppId);
    expect(second.clientAppName).toBe(`TMS Tenant ${suffix} Updated`);
    expect(second.clientAppKey).toBeFalsy();
    expect(second.clientAppSecret).toBeFalsy();
    expect(second.controlApiKey).toBeFalsy();
    expect(second.rootAgentId).toBe(agentBundleCode);
    expect(second.skillId).toBe(skillId);
    expect(second.workerPoolId).toBe(workerPoolId);

    const rotated = await client.ensureUpstreamTenantClientApp({
      sourceSystem,
      sourceTenantId,
      clientAppName: `TMS Tenant ${suffix} Updated`,
      tenantName: `TMS Tenant ${suffix} Updated`,
      capabilityDomain,
      agentBundleCode,
      skillId,
      workerPoolId,
      rotateCredentials: true
    });

    expect(rotated.created).toBe(false);
    expect(rotated.rotated).toBe(true);
    expect(rotated.clientAppId).toBe(first.clientAppId);
    expect(rotated.clientAppKey).toBeTruthy();
    expect(rotated.clientAppSecret).toBeTruthy();
    expect(rotated.controlApiKey).toBeTruthy();
  });
});
