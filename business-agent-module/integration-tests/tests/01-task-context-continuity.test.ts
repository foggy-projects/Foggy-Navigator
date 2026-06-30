import { beforeAll, describe, expect, test } from 'vitest';
import { createAuthenticatedClient, createClient, BusinessAgentClient } from '../src/api-client.js';
import { generateTestSuffix, TEST_CONFIG } from '../src/config.js';
import type { CreatedBusinessAgentTask } from '../src/types.js';

describe('01 - Business Agent task context continuity', () => {
  const TASK_CREATION_BACKEND = 'CLAUDE_CODE';
  let bootstrapClient: BusinessAgentClient;

  beforeAll(async () => {
    bootstrapClient = createClient();
  });

  test('creates consecutive tasks with the same business context id', async () => {
    const suffix = generateTestSuffix();
    const tenantId = `e2e_tenant_${suffix}`;
    const username = `e2e_ba_${suffix}`;
    const password = `E2ePass_${suffix}_123`;
    const ownerUserId = `e2e_owner_${suffix}`;
    const upstreamUserId = `e2e_user_${suffix}`;
    const agentId = `e2eagent_${suffix}`;
    const skillId = `e2eskill_${suffix}`;
    const workerPoolId = `e2epool_${suffix}`;
    const sessionId = `e2esession_${suffix}`;

    const registeredUserId = await bootstrapClient.registerUser({
      tenantId,
      username,
      password,
      email: `${username}@example.invalid`,
      displayName: `Business Agent E2E ${suffix}`,
      roles: 'TENANT_ADMIN'
    });
    expect(registeredUserId).toBeTruthy();

    const client = await createAuthenticatedClient(undefined, username, password);

    const provisioning = await client.issueProvisioningCredential({
      maxUses: 1,
      ownerUserId,
      capabilityDomain: 'business-agent-e2e',
      auditTag: `business-agent-e2e-${suffix}`
    });
    expect(provisioning.token).toBeTruthy();

    const app = await client.createClientApp({
      provisioningToken: provisioning.token,
      name: `Business Agent E2E ${suffix}`,
      description: 'Created by business-agent-module integration tests',
      ownerUserId,
      capabilityDomain: 'business-agent-e2e'
    });
    expect(app.clientAppId).toBeTruthy();
    expect(app.tenantId).toBe(tenantId);

    const pool = await client.createWorkerPool({
      poolId: workerPoolId,
      name: `Business Agent E2E Pool ${suffix}`,
      workerBackend: TASK_CREATION_BACKEND,
      routingPolicy: 'ROUND_ROBIN'
    });
    expect(pool.poolId).toBe(workerPoolId);
    expect(pool.workerBackend).toBe(TASK_CREATION_BACKEND);

    const skill = await client.createSkill({
      skillId,
      name: `Business Agent E2E Skill ${suffix}`,
      description: 'Minimal skill for task context continuity checks',
      status: 'ENABLED',
      markdownBody: 'You are a deterministic assistant used by integration tests.',
      contextVisibility: 'passthrough'
    });
    expect(skill.skillId).toBe(skillId);

    const skillGrant = await client.grantSkillToClientApp(app.clientAppId, {
      skillId,
      status: 'ENABLED'
    });
    expect(skillGrant.status).toBe('ENABLED');

    const userGrant = await client.grantUpstreamUserAccess(app.clientAppId, {
      upstreamUserId,
      upstreamUserToken: `upstream-token-${suffix}`,
      status: 'ENABLED'
    });
    expect(userGrant.status).toBe('ENABLED');

    const modelGrant = await client.createClientAppModelConfig(app.clientAppId, {
      name: `Business Agent E2E Model ${suffix}`,
      category: 'GENERAL',
      baseUrl: TEST_CONFIG.mockBaseURL,
      modelName: `business-agent-e2e-${suffix}`,
      apiKey: `sk-e2e-${suffix}`,
      workerBackend: TASK_CREATION_BACKEND,
      availableModels: [`business-agent-e2e-${suffix}`],
      setDefault: true
    });
    expect(modelGrant.modelConfigId).toBeTruthy();
    expect(modelGrant.isDefault).toBe(true);
    expect(modelGrant.workerBackend).toBe(TASK_CREATION_BACKEND);

    const agent = await client.syncAgentBundle({
      clientAppId: app.clientAppId,
      agentId,
      skillId,
      name: `Business Agent E2E Agent ${suffix}`,
      description: 'Agent bundle for task context continuity checks',
      status: 'ENABLED',
      workerId: workerPoolId,
      defaultModelConfigId: modelGrant.modelConfigId,
      markdownBody: 'You are a deterministic assistant used by integration tests.',
      contextVisibility: 'passthrough',
      materialize: false
    });
    expect(agent.agentId).toBe(agentId);
    expect(agent.skillId).toBe(skillId);
    expect(agent.workerId).toBe(workerPoolId);
    expect(agent.defaultModelConfigId).toBe(modelGrant.modelConfigId);

    const firstTask = await createTask(client, sessionId, app.clientAppId, upstreamUserId, agentId);
    expect(firstTask.contextId).toBeTruthy();
    expect(firstTask.contextId).toMatch(/^bctx_/);
    expect(firstTask.sessionId).toBe(sessionId);
    expect(firstTask.clientAppId).toBe(app.clientAppId);
    expect(firstTask.agentId).toBe(agentId);
    expect(firstTask.skillId).toBe(skillId);
    expect(firstTask.workerPoolId).toBe(workerPoolId);

    const secondTask = await createTask(
      client,
      sessionId,
      app.clientAppId,
      upstreamUserId,
      agentId,
      firstTask.contextId
    );
    expect(secondTask.contextId).toBe(firstTask.contextId);
    expect(secondTask.sessionId).toBe(sessionId);
    expect(secondTask.taskId).not.toBe(firstTask.taskId);
  });

  async function createTask(
    client: BusinessAgentClient,
    sessionId: string,
    clientAppId: string,
    upstreamUserId: string,
    agentId: string,
    contextId?: string
  ): Promise<CreatedBusinessAgentTask> {
    return client.createTask({
      clientAppId,
      sessionId,
      contextId,
      upstreamUserId,
      agentId,
      clientContextJson: JSON.stringify({
        source: 'business-agent-module/integration-tests',
        contextId: contextId ?? null
      })
    });
  }
});
