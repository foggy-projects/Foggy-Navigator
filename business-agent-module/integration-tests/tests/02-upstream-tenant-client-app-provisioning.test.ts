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
    const workerBackend = 'LANGGRAPH_BIZ';
    const physicalWorkerId = `physical-worker-${suffix}`;
    const directoryId = `dir-tms-${suffix}`;
    const bizWorkerBaseUrl = 'http://127.0.0.1:3061';
    const upstreamRef = `TMS-${suffix}`;

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
      scopes: ['CLIENT_APP_MANAGE', 'CLIENT_APP_CONTROL_KEY_ISSUE', 'WORKER_POOL_MANAGE']
    });

    const claimed = await bootstrapClient.claimUpstreamAdminKey(adminKeyRequest.requestCode, {
      claimToken: adminKeyRequest.claimToken
    });
    expect(claimed.naviAdminApiKey).toBeTruthy();

    const client = createClient();
    client.setUpstreamAdminApiKey(claimed.naviAdminApiKey);

    const inspected = await client.inspectCurrentUpstreamAdminCredential();
    expect(inspected.credentialId).toBe(claimed.credentialId);
    expect(inspected.principalId).toBeTruthy();
    expect(inspected.upstreamSystemId).toBe(sourceSystem);
    expect(inspected.authorizedTenantIds).toContain(navigatorTenantId);
    expect(inspected.authorizedClientAppNamespace).toBe(sourceSystem);
    expect(inspected.scopes).toContain('CLIENT_APP_MANAGE');
    expect(inspected.scopes).toContain('CLIENT_APP_CONTROL_KEY_ISSUE');
    expect(inspected.scopes).toContain('WORKER_POOL_MANAGE');
    expect(inspected.status).toBe('ACTIVE');

    const workerIdentity = await client.registerUpstreamWorkerIdentity({
      workerId: physicalWorkerId,
      workerBackend,
      baseUrl: bizWorkerBaseUrl,
      version: `e2e-${suffix}`
    });
    expect(workerIdentity.workerId).toBe(physicalWorkerId);
    expect(workerIdentity.ownerType).toBe('UPSTREAM_SYSTEM');
    expect(workerIdentity.ownerId).toBe(sourceSystem);
    expect(workerIdentity.workerBackend).toBe(workerBackend);
    expect(workerIdentity.status).toBe('ENABLED');
    expect(workerIdentity.healthStatus).toBe('HEALTHY');

    const provisioningPayload = {
      sourceSystem,
      sourceTenantId,
      clientAppName: `TMS Tenant ${suffix}`,
      tenantName: `TMS Tenant ${suffix}`,
      capabilityDomain,
      upstreamRef,
      agentBundleCode,
      skillId,
      workerPoolId,
      workerBackend,
      physicalWorkerId,
      directoryId,
      bizWorkerBaseUrl
    };

    const first = await client.ensureUpstreamTenantClientApp(provisioningPayload);

    expect(first.created).toBe(true);
    expect(first.rotated).toBe(false);
    expect(first.status).toBe('READY');
    expect(first.credentialsReplayable).toBe(true);
    expect(first.navigatorTenantId).toBe(navigatorTenantId);
    expect(first.targetNavigatorTenantId).toBe(navigatorTenantId);
    expect(first.clientAppId).toMatch(/^capp_/);
    expect(first.clientAppName).toBe(`TMS Tenant ${suffix}`);
    expect(first.capabilityDomain).toBe(capabilityDomain);
    expect(first.clientAppCapabilityDomain).toBe(capabilityDomain);
    expect(first.upstreamSystemId).toBe(sourceSystem);
    expect(first.sourceTenantId).toBe(sourceTenantId);
    expect(first.upstreamRef).toBe(upstreamRef);
    expect(first.upstreamNamespace).toBe(sourceSystem);
    expect(first.clientAppKey).toBeTruthy();
    expect(first.clientAppSecret).toBeTruthy();
    expect(first.controlApiKey).toBeTruthy();
    expect(first.agentCode).toBe(agentBundleCode);
    expect(first.rootAgentId).toBe(agentBundleCode);
    expect(first.skillId).toBe(skillId);
    expect(first.workerPoolId).toBe(workerPoolId);
    expect(first.workerBackend).toBe(workerBackend);
    expect(first.physicalWorkerId).toBe(physicalWorkerId);
    expect(first.directoryId).toBe(directoryId);
    expect(first.bizWorkerBaseUrl).toBe(bizWorkerBaseUrl);
    expect(first.bindingVersion).toBeTruthy();
    expect(first.modelConfigId).toBeFalsy();
    expect(first.activationReady).toBe(false);
    expect(first.errorCode).toBe('WORKSPACE_RESOURCE');
    expect(first.missingFields).toContain('modelConfigId');
    expect(first.missingFields).toContain('directoryId');
    expect(first.requiredScopes).toContain('CLIENT_APP_MANAGE');
    expect(first.requiredScopes).toContain('CLIENT_APP_CONTROL_KEY_ISSUE');
    expect(first.actualScopes).toContain('CLIENT_APP_MANAGE');
    expect(first.actualScopes).toContain('CLIENT_APP_CONTROL_KEY_ISSUE');
    expect(first.authorizedTenantIds).toContain(navigatorTenantId);
    expect(first.blockers.some(item => item.includes('modelConfigId is missing'))).toBe(true);
    expect(first.blockers.some(item => item.includes(`working directory not found: ${directoryId}`))).toBe(true);

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
      ...provisioningPayload,
      clientAppName: `TMS Tenant ${suffix} Updated`,
      tenantName: `TMS Tenant ${suffix} Updated`
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
    expect(second.agentCode).toBe(agentBundleCode);
    expect(second.rootAgentId).toBe(agentBundleCode);
    expect(second.skillId).toBe(skillId);
    expect(second.workerPoolId).toBe(workerPoolId);
    expect(second.workerBackend).toBe(workerBackend);
    expect(second.physicalWorkerId).toBe(physicalWorkerId);
    expect(second.directoryId).toBe(directoryId);
    expect(second.bizWorkerBaseUrl).toBe(bizWorkerBaseUrl);
    expect(second.modelConfigId).toBe(modelGrant.modelConfigId);
    expect(second.activationReady).toBe(false);
    expect(second.missingFields).toContain('runtimeCredential.clientAppKey');
    expect(second.missingFields).toContain('runtimeCredential.clientAppSecret');
    expect(second.missingFields).toContain('controlCredential.controlApiKey');
    expect(second.missingFields).not.toContain('modelConfigId');
    expect(second.missingFields).not.toContain('workerBackend');
    expect(second.missingFields).not.toContain('physicalWorkerId');
    expect(second.missingFields).toContain('directoryId');
    expect(second.blockers.some(item => item.includes('modelConfigId'))).toBe(false);
    expect(second.blockers.some(item => item.includes('defaultModelConfigId'))).toBe(false);
    expect(second.blockers.some(item => item.includes(`working directory not found: ${directoryId}`))).toBe(true);

    const rotated = await client.ensureUpstreamTenantClientApp({
      ...provisioningPayload,
      clientAppName: `TMS Tenant ${suffix} Updated`,
      tenantName: `TMS Tenant ${suffix} Updated`,
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
    expect(rotated.activationReady).toBe(false);
    expect(rotated.errorCode).toBe('WORKSPACE_RESOURCE');
    expect(rotated.missingFields).toEqual(['directoryId']);
    expect(rotated.blockers.some(item => item.includes(`working directory not found: ${directoryId}`))).toBe(true);
    expect(rotated.remediationHint).toContain(directoryId);
    expect(rotated.remediationHint).toContain(navigatorTenantId);
    expect(rotated.upstreamRef).toBe(upstreamRef);
    expect(rotated.workerBackend).toBe(workerBackend);
    expect(rotated.physicalWorkerId).toBe(physicalWorkerId);
    expect(rotated.directoryId).toBe(directoryId);
    expect(rotated.bizWorkerBaseUrl).toBe(bizWorkerBaseUrl);
  });
});
