import axios from 'axios';
import { beforeAll, describe, expect, test } from 'vitest';
import { createAuthenticatedClient, createClient, BusinessAgentClient } from '../src/api-client.js';
import { generateTestSuffix, TEST_CONFIG } from '../src/config.js';
import type { LanggraphTask } from '../src/types.js';

const LANGGRAPH_BIZ_BACKEND = 'LANGGRAPH_BIZ';
const SCRIPTED_MODEL = 'navigator-e2e-scripted';

describe.skipIf(!TEST_CONFIG.enableLanggraphWorkerSmoke)(
  '03 - LangGraph BizWorker mock LLM command smoke',
  () => {
    let bootstrapClient: BusinessAgentClient;
    let rootClient: BusinessAgentClient;

    beforeAll(async () => {
      bootstrapClient = createClient();
      rootClient = await createAuthenticatedClient();
      await assertMockLlmReady();
    });

    test('launches a Java Navi task into BizWorker and completes a scripted command tool loop', async () => {
      const suffix = generateTestSuffix();
      const tenantId = `tenant_lgw_e2e_${suffix}`;
      const username = `lgw_e2e_${suffix}`;
      const password = `E2ePass_${suffix}_123`;
      const ownerUserId = `owner_lgw_${suffix}`;
      const upstreamUserId = `upstream_lgw_${suffix}`;
      const workerId = `lgw_mock_${suffix}`;
      const workerPoolId = `bwp_lgw_mock_${suffix}`;
      const skillId = `java_navi_command_smoke_${suffix}`;
      const sessionId = `sess_lgw_mock_${suffix}`;
      const traceId = `java-navi-command-${suffix}`;

      const command = [
        'git --version',
        'curl --version | head -n 1',
        `printf 'next:${traceId}:002\\n'`
      ].join(' && ');

      await registerMockScript(traceId, command);

      const registeredUserId = await bootstrapClient.registerUser({
        tenantId,
        username,
        password,
        email: `${username}@example.invalid`,
        displayName: `LangGraph BizWorker E2E ${suffix}`,
        roles: 'TENANT_ADMIN'
      });
      expect(registeredUserId).toBeTruthy();

      const tenantClient = await createAuthenticatedClient(undefined, username, password);

      const langgraphWorker = await tenantClient.registerLanggraphWorker({
        workerId,
        name: `LangGraph BizWorker ${suffix}`,
        baseUrl: TEST_CONFIG.langgraphWorkerBaseURL,
        authToken: '',
        authMode: 'BEARER',
        status: 'ONLINE',
        hostname: 'e2e',
        workerVersion: 'e2e'
      });
      expect(langgraphWorker.workerId).toBe(workerId);

      const health = await tenantClient.healthCheckLanggraphWorker(workerId);
      expect(health.status).toBe('ONLINE');

      const identity = await rootClient.registerWorkerIdentity({
        workerId,
        workerBackend: LANGGRAPH_BIZ_BACKEND,
        baseUrl: TEST_CONFIG.langgraphWorkerBaseURL,
        version: 'e2e'
      });
      expect(identity.workerBackend).toBe(LANGGRAPH_BIZ_BACKEND);

      const provisioning = await tenantClient.issueProvisioningCredential({
        maxUses: 1,
        ownerUserId,
        capabilityDomain: 'langgraph-biz-worker-e2e',
        auditTag: `langgraph-biz-worker-e2e-${suffix}`
      });
      expect(provisioning.token).toBeTruthy();

      const app = await tenantClient.createClientApp({
        provisioningToken: provisioning.token,
        name: `LangGraph BizWorker E2E ${suffix}`,
        description: 'Created by business-agent-module integration tests',
        ownerUserId,
        capabilityDomain: 'langgraph-biz-worker-e2e'
      });
      expect(app.clientAppId).toBeTruthy();

      const pool = await tenantClient.createWorkerPool({
        poolId: workerPoolId,
        name: `LangGraph BizWorker Pool ${suffix}`,
        workerBackend: LANGGRAPH_BIZ_BACKEND,
        routingPolicy: 'ROUND_ROBIN'
      });
      expect(pool.workerBackend).toBe(LANGGRAPH_BIZ_BACKEND);
      await tenantClient.addWorkerPoolMember(workerPoolId, { workerId });

      const skill = await tenantClient.createSkill({
        skillId,
        name: `Java Navi Command Smoke ${suffix}`,
        description: 'Scripted command smoke skill for LangGraph BizWorker',
        status: 'ENABLED',
        markdownBody: [
          'Use the command tool once, then submit the final structured result.',
          `The first scripted cursor is next:${traceId}:001.`
        ].join('\n'),
        contextVisibility: 'passthrough'
      });
      expect(skill.skillId).toBe(skillId);

      await tenantClient.grantSkillToClientApp(app.clientAppId, {
        skillId,
        status: 'ENABLED'
      });
      await tenantClient.grantUpstreamUserAccess(app.clientAppId, {
        upstreamUserId,
        upstreamUserToken: `upstream-token-${suffix}`,
        status: 'ENABLED'
      });

      const modelGrant = await tenantClient.ensureE2eModelConfig(app.clientAppId, {
        standard: 'biz-worker',
        mockBaseUrl: TEST_CONFIG.mockBaseURL,
        setDefault: true
      });
      expect(modelGrant.modelConfigId).toBeTruthy();
      expect(modelGrant.isDefault).toBe(true);

      const created = await tenantClient.createTask({
        clientAppId: app.clientAppId,
        sessionId,
        upstreamUserId,
        skillId,
        workerPoolId,
        workdir: TEST_CONFIG.commandWorkdir,
        allowed_dirs: [TEST_CONFIG.commandWorkdir],
        allowed_tools: ['command']
      });
      expect(created.workerTaskId).toBeTruthy();
      expect(created.workerId).toBe(workerId);
      expect(created.workerProviderType).toBe('langgraph-biz-worker');

      const workerTask = await waitForLanggraphTask(
        tenantClient,
        created.workerTaskId!,
        created.navigatorEffectiveUserId
      );
      expect(workerTask.resultText).toBe('OK_JAVA_NAVI_COMMAND_MOCK_LLM_E2E');
      expect(JSON.parse(workerTask.structuredOutput ?? '{}')).toMatchObject({
        command_e2e: true,
        marker: 'OK_JAVA_NAVI_COMMAND_MOCK_LLM_E2E'
      });

      const businessTask = await tenantClient.getBusinessTask(created.taskId);
      expect(businessTask.workerTaskId).toBe(created.workerTaskId);

      const records = await fetchMockDebugRecords(traceId);
      expect(records.map(record => record.cursor)).toEqual([
        `next:${traceId}:001`,
        `next:${traceId}:002`
      ]);
      expect(records[0].responseSummary.toolCalls).toEqual(['command']);
      expect(records[1].responseSummary.toolCalls).toEqual(['submit_skill_result']);
    }, 120000);
  }
);

async function assertMockLlmReady(): Promise<void> {
  await axios.get(`${TEST_CONFIG.mockAdminBaseURL}/admin/health`, {
    timeout: TEST_CONFIG.timeout
  });
}

async function registerMockScript(traceId: string, command: string): Promise<void> {
  await axios.post(
    `${TEST_CONFIG.mockAdminBaseURL}/__e2e/scripts`,
    {
      traceId,
      scenarioId: 'java-navi-command-mock-llm-e2e',
      turns: [
        {
          cursor: `next:${traceId}:001`,
          response: {
            tool_calls: [
              {
                id: `call_command_${traceId}`,
                type: 'function',
                function: {
                  name: 'command',
                  arguments: JSON.stringify({
                    command,
                    workdir: '.',
                    timeout_seconds: 10,
                    max_output_chars: 4000
                  })
                }
              }
            ]
          }
        },
        {
          cursor: `next:${traceId}:002`,
          response: {
            tool_calls: [
              {
                id: `call_submit_${traceId}`,
                type: 'function',
                function: {
                  name: 'submit_skill_result',
                  arguments: JSON.stringify({
                    summary: 'OK_JAVA_NAVI_COMMAND_MOCK_LLM_E2E',
                    structured_output: {
                      command_e2e: true,
                      marker: 'OK_JAVA_NAVI_COMMAND_MOCK_LLM_E2E'
                    }
                  })
                }
              }
            ]
          }
        }
      ]
    },
    { timeout: TEST_CONFIG.timeout }
  );
}

async function waitForLanggraphTask(
  client: BusinessAgentClient,
  taskId: string,
  userId: string
): Promise<LanggraphTask> {
  const deadline = Date.now() + 90000;
  let lastTask: LanggraphTask | undefined;
  while (Date.now() < deadline) {
    lastTask = await client.getLanggraphTask(taskId, userId);
    if (lastTask.status === 'COMPLETED') {
      return lastTask;
    }
    if (['FAILED', 'CANCELLED'].includes(lastTask.status)) {
      throw new Error(`LangGraph task ${taskId} ended with ${lastTask.status}: ${lastTask.errorMessage ?? ''}`);
    }
    await sleep(1000);
  }
  throw new Error(`Timed out waiting for LangGraph task ${taskId}; last status=${lastTask?.status ?? 'unknown'}`);
}

async function fetchMockDebugRecords(traceId: string): Promise<Array<any>> {
  const response = await axios.get(`${TEST_CONFIG.mockAdminBaseURL}/__debug/requests`, {
    params: { traceId },
    timeout: TEST_CONFIG.timeout
  });
  return response.data.filter((record: any) => record.request?.tools);
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
