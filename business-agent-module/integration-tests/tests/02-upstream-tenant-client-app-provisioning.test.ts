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
    const sourceSystem = 'tms-e2e';
    const sourceTenantId = `tenant-${suffix}`;
    const navigatorTenantId = `nav_${sourceSystem}_${sourceTenantId}`;
    const capabilityDomain = `tms-e2e-${suffix}`;
    const agentBundleCode = `tms-root-${suffix}`;
    const skillId = `tms.skill.${suffix}`;
    const workerPoolId = `biz-worker-${suffix}`;

    const adminKeyRequest = await bootstrapClient.requestUpstreamAdminKey({
      upstreamSystemId: sourceSystem,
      requestedTenantId: navigatorTenantId,
      multiTenant: true,
      reason: `e2e tenant client app provisioning ${suffix}`,
      applicantLabel: `e2e-${suffix}`
    });
    expect(adminKeyRequest.requestCode).toBeTruthy();
    expect(adminKeyRequest.claimToken).toBeTruthy();

    const adminClient = await createAuthenticatedClient();
    await adminClient.approveUpstreamAdminKeyRequest(adminKeyRequest.requestCode, {
      authorizedTenantIds: [navigatorTenantId],
      authorizedClientAppNamespace: sourceSystem,
      scopes: ['CLIENT_APP_MANAGE', 'CLIENT_APP_CONTROL_KEY_ISSUE']
    });

    const claimed = await bootstrapClient.claimUpstreamAdminKey(adminKeyRequest.requestCode, {
      claimToken: adminKeyRequest.claimToken
    });
    expect(claimed.naviAdminApiKey).toBeTruthy();

    const client = createClient();
    client.setUpstreamAdminApiKey(claimed.naviAdminApiKey);

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
    expect(first.status).toBe('READY');
    expect(first.credentialsReplayable).toBe(true);
    expect(first.navigatorTenantId).toBe(navigatorTenantId);
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
    expect(first.modelConfigId).toBeFalsy();
    expect(first.blockers.some(item => item.includes('modelConfigId is missing'))).toBe(true);

    if (!first.controlApiKey) {
      throw new Error('ClientApp control key is required for model config provisioning');
    }
    const controlClient = createClient();
    controlClient.setClientAppControlKey(first.controlApiKey);
    const modelGrant = await controlClient.createClientAppModelConfig(first.clientAppId, {
      name: `TMS E2E Model ${suffix}`,
      category: 'GENERAL',
      baseUrl: 'https://llm.example.invalid/v1',
      modelName: `tms-e2e-model-${suffix}`,
      apiKey: `sk-test-${suffix}`,
      setDefault: true,
      availableModels: [`tms-e2e-model-${suffix}`]
    });

    expect(modelGrant.clientAppId).toBe(first.clientAppId);
    expect(modelGrant.modelConfigId).toBeTruthy();
    expect(modelGrant.status).toBe('ENABLED');
    expect(modelGrant.isDefault).toBe(true);

    const repeatedGrant = await controlClient.grantClientAppModelConfig(first.clientAppId, {
      modelConfigId: modelGrant.modelConfigId,
      isDefault: true
    });
    expect(repeatedGrant.id).toBe(modelGrant.id);
    expect(repeatedGrant.modelConfigId).toBe(modelGrant.modelConfigId);
    expect(repeatedGrant.status).toBe('ENABLED');
    expect(repeatedGrant.isDefault).toBe(true);

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
    expect(second.status).toBe('CREDENTIALS_NOT_REPLAYABLE');
    expect(second.errorCode).toBe('CREDENTIALS_NOT_REPLAYABLE');
    expect(second.credentialsReplayable).toBe(false);
    expect(second.clientAppId).toBe(first.clientAppId);
    expect(second.clientAppName).toBe(`TMS Tenant ${suffix} Updated`);
    expect(second.clientAppKey).toBeFalsy();
    expect(second.clientAppSecret).toBeFalsy();
    expect(second.controlApiKey).toBeFalsy();
    expect(second.rootAgentId).toBe(agentBundleCode);
    expect(second.skillId).toBe(skillId);
    expect(second.workerPoolId).toBe(workerPoolId);
    expect(second.modelConfigId).toBe(modelGrant.modelConfigId);
    expect(second.blockers.some(item => item.includes('modelConfigId'))).toBe(false);
    expect(second.blockers.some(item => item.includes('defaultModelConfigId'))).toBe(false);

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
    expect(rotated.status).toBe('READY');
    expect(rotated.credentialsReplayable).toBe(true);
    expect(rotated.clientAppId).toBe(first.clientAppId);
    expect(rotated.clientAppKey).toBeTruthy();
    expect(rotated.clientAppSecret).toBeTruthy();
    expect(rotated.controlApiKey).toBeTruthy();
    expect(rotated.modelConfigId).toBe(modelGrant.modelConfigId);
  });
});
