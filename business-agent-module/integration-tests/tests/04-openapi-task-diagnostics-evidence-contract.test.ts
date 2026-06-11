import axios from 'axios';
import { beforeAll, describe, expect, test } from 'vitest';
import { BusinessAgentClient, createAuthenticatedClient, createClient } from '../src/api-client.js';
import { generateTestSuffix, TEST_CONFIG } from '../src/config.js';
import type { OpenApiTask } from '../src/types.js';

const LANGGRAPH_BIZ_BACKEND = 'LANGGRAPH_BIZ';
const FINAL_MARKER = 'OK_OPENAPI_DIAGNOSTICS_EVIDENCE_E2E';

interface MockDebugRecord {
  cursor?: string;
  responseSummary?: {
    toolCalls?: string[];
  };
}

describe.skipIf(!TEST_CONFIG.enableOpenApiDiagnosticsSmoke)(
  '04 - OpenAPI task diagnostics and evidence smoke',
  () => {
    let bootstrapClient: BusinessAgentClient;
    let rootClient: BusinessAgentClient;

    beforeAll(async () => {
      bootstrapClient = createClient();
      rootClient = await createAuthenticatedClient();
      await assertMockLlmReady();
    });

    test('runs real OpenAPI ask through BizWorker and exposes adjudication facts', async () => {
      const suffix = generateTestSuffix();
      const safeCorrelationSuffix = suffix.replace(/[^a-z0-9]/g, '');
      const tenantId = `tenant_oapi_diag_${suffix}`;
      const username = `oapi_diag_${suffix}`;
      const password = `E2ePass_${suffix}_123`;
      const ownerUserId = `owner_oapi_${suffix}`;
      const upstreamUserId = `upstream_oapi_${suffix}`;
      const workerId = `lgw_oapi_diag_${suffix}`;
      const agentId = `openapi_diag_agent_${suffix}`;
      const skillId = `openapi_diag_skill_${suffix}`;
      const traceId = `openapi-diagnostics-${suffix}`;
      const originalTaskId = `worldsimTask${safeCorrelationSuffix}`;
      const recoveryCorrelationKey = `worldsimRecovery${safeCorrelationSuffix}`;
      const idempotencyKey = `worldsimIdem${safeCorrelationSuffix}`;
      const attemptNumber = 2;

      await registerMockScript(traceId);

      const registeredUserId = await bootstrapClient.registerUser({
        tenantId,
        username,
        password,
        email: `${username}@example.invalid`,
        displayName: `OpenAPI Diagnostics E2E ${suffix}`,
        roles: 'TENANT_ADMIN'
      });
      expect(registeredUserId).toBeTruthy();

      const tenantClient = await createAuthenticatedClient(undefined, username, password);

      const langgraphWorker = await tenantClient.registerLanggraphWorker({
        workerId,
        name: `OpenAPI Diagnostics Worker ${suffix}`,
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
        capabilityDomain: 'openapi-diagnostics-e2e',
        auditTag: `openapi-diagnostics-e2e-${suffix}`
      });
      expect(provisioning.token).toBeTruthy();

      const app = await tenantClient.createClientApp({
        provisioningToken: provisioning.token,
        name: `OpenAPI Diagnostics E2E ${suffix}`,
        description: 'Created by business-agent-module OpenAPI diagnostics smoke',
        ownerUserId,
        capabilityDomain: 'openapi-diagnostics-e2e'
      });
      expect(app.clientAppId).toBeTruthy();

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

      const runtimeCredential = await tenantClient.issueRuntimeCredential(app.clientAppId, {
        description: `OpenAPI diagnostics runtime key ${suffix}`
      });
      expect(runtimeCredential.appKey).toBeTruthy();
      expect(runtimeCredential.secret).toBeTruthy();

      const controlCredential = await tenantClient.issueControlCredential(app.clientAppId, {
        description: `OpenAPI diagnostics control key ${suffix}`
      });
      expect(controlCredential.controlApiKey).toBeTruthy();

      const controlClient = createClient();
      controlClient.setClientAppControlKey(controlCredential.controlApiKey!);
      const bundle = await controlClient.syncAgentBundle({
        clientAppId: app.clientAppId,
        agentId,
        skillId,
        name: `OpenAPI Diagnostics Agent ${suffix}`,
        description: 'Facts-only diagnostics and evidence smoke agent',
        status: 'ENABLED',
        workerId,
        defaultModelConfigId: modelGrant.modelConfigId,
        markdownBody: [
          'Answer with the scripted final marker only.',
          `The first scripted cursor is next:${traceId}:001.`
        ].join('\n'),
        contextVisibility: 'passthrough',
        materialize: false
      });
      expect(bundle.agentId).toBe(agentId);
      expect(bundle.skillId).toBe(skillId);
      expect(bundle.workerId).toBe(workerId);
      expect(bundle.defaultModelConfigId).toBe(modelGrant.modelConfigId);

      const runtimeToken = await bootstrapClient.issueClientAppRuntimeToken(
        runtimeCredential.appKey!,
        runtimeCredential.secret!
      );
      expect(runtimeToken.accessToken).toBeTruthy();
      expect(runtimeToken.clientAppId).toBe(app.clientAppId);

      const submitted = await bootstrapClient.askOpenApiAgent(
        agentId,
        {
          message: [
            `Run the OpenAPI diagnostics smoke for trace ${traceId}.`,
            `The first scripted cursor is next:${traceId}:001.`,
            `Return ${FINAL_MARKER} ${traceId} as the final answer.`
          ].join('\n'),
          maxTurns: 2,
          modelConfigId: modelGrant.modelConfigId,
          metadata: {
            traceId,
            originalTaskId,
            recoveryCorrelationKey,
            attemptNumber,
            idempotencyKey
          },
          clientContext: {
            source: 'world-sim-smoke',
            traceId
          }
        },
        runtimeCredential.appKey!,
        runtimeToken.accessToken,
        upstreamUserId
      );
      expect(submitted.taskId).toBeTruthy();
      expect(submitted.agentId).toBe(agentId);
      expect(submitted.contextId).toMatch(/^bctx_\d{8}_[a-z0-9]+_[a-z0-9]+$/);
      const contextId = submitted.contextId!;

      const terminalTask = await waitForOpenApiTaskTerminal(
        bootstrapClient,
        agentId,
        submitted.taskId,
        runtimeCredential.appKey!,
        runtimeToken.accessToken,
        upstreamUserId
      );
      expect(terminalTask.status).toBe('COMPLETED');
      expect(terminalTask.result ?? '').toContain(`${FINAL_MARKER} ${traceId}`);
      expect(terminalTask.workerBackend).toBe(LANGGRAPH_BIZ_BACKEND);
      expect(terminalTask.providerType).toBe('langgraph-biz-worker');

      const diagnostics = await bootstrapClient.getOpenApiTaskDiagnostics(
        agentId,
        submitted.taskId,
        runtimeCredential.appKey!,
        runtimeToken.accessToken,
        upstreamUserId
      );
      expect(diagnostics).toMatchObject({
        taskId: submitted.taskId,
        agentId,
        contextId,
        status: 'COMPLETED',
        terminal: true,
        terminalStatus: 'COMPLETED',
        modelConfigId: modelGrant.modelConfigId,
        workerBackend: LANGGRAPH_BIZ_BACKEND,
        providerType: 'langgraph-biz-worker',
        safeWorkerRef: workerId
      });
      expect(diagnostics.messagesCount ?? 0).toBeGreaterThan(0);
      expect(diagnostics.cancelCapability).toMatchObject({
        cancelSupported: false,
        cancelMode: 'none'
      });
      expect(diagnostics.correlation).toMatchObject({
        originalTaskId,
        recoveryCorrelationKey,
        attemptNumber,
        idempotencyKey
      });

      const messages = await bootstrapClient.getOpenApiTaskMessages(
        agentId,
        submitted.taskId,
        { limit: 100, includeInternal: true },
        runtimeCredential.appKey!,
        runtimeToken.accessToken,
        upstreamUserId
      );
      expect(messages).toMatchObject({
        taskId: submitted.taskId,
        contextId,
        status: 'COMPLETED',
        terminal: true,
        terminalStatus: 'COMPLETED',
        workerBackend: LANGGRAPH_BIZ_BACKEND,
        providerType: 'langgraph-biz-worker'
      });
      expect(messages.messages.length).toBeGreaterThan(0);
      expect(messages.messages.some(message => message.eventKind === 'final_marker')).toBe(true);
      expect(messages.messages.some(message => (message.content ?? '').includes(FINAL_MARKER))).toBe(true);

      const evidence = await bootstrapClient.getOpenApiTaskEvidence(
        agentId,
        submitted.taskId,
        runtimeCredential.appKey!,
        runtimeToken.accessToken,
        upstreamUserId
      );
      expect(evidence).toMatchObject({
        taskId: submitted.taskId,
        agentId,
        contextId,
        status: 'COMPLETED',
        terminal: true,
        terminalStatus: 'COMPLETED'
      });
      expect(evidence.finalAnswer).toMatchObject({
        available: true
      });
      expect(evidence.finalAnswer?.summary ?? '').toContain(`${FINAL_MARKER} ${traceId}`);
      expect(evidence.reportRefs ?? []).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            type: 'frame_report',
            ref: expect.stringMatching(/^frame-report:\/\/.+\/frm_.+/),
            frameId: expect.stringMatching(/^frm_.+/)
          })
        ])
      );
      expect(evidence.artifactRefs ?? []).toEqual([]);

      const publicPayload = JSON.stringify({ diagnostics, evidence, messages });
      expect(publicPayload).not.toContain(runtimeCredential.secret!);
      expect(publicPayload).not.toContain(runtimeToken.accessToken);

      const records = await fetchMockDebugRecords(traceId);
      expect(records.some(record => record.cursor === `next:${traceId}:001`)).toBe(true);
      expect(records.every(record => (record.responseSummary?.toolCalls ?? []).length === 0)).toBe(true);
    }, 150000);
  }
);

async function assertMockLlmReady(): Promise<void> {
  const response = await axios.get(`${TEST_CONFIG.mockAdminBaseURL}/admin/health`, {
    timeout: TEST_CONFIG.timeout
  });
  expect(response.data?.status).toBe('ok');
}

async function registerMockScript(traceId: string): Promise<void> {
  await axios.post(
    `${TEST_CONFIG.mockAdminBaseURL}/__e2e/scripts`,
    {
      traceId,
      scenarioId: 'openapi-diagnostics-evidence-e2e',
      turns: [
        {
          cursor: `next:${traceId}:001`,
          response: {
            content: `${FINAL_MARKER} ${traceId}`
          }
        }
      ]
    },
    { timeout: TEST_CONFIG.timeout }
  );
}

async function waitForOpenApiTaskTerminal(
  client: BusinessAgentClient,
  agentId: string,
  taskId: string,
  appKey: string,
  accessToken: string,
  upstreamUserId: string
): Promise<OpenApiTask> {
  const deadline = Date.now() + 120000;
  let lastTask: OpenApiTask | undefined;
  while (Date.now() < deadline) {
    lastTask = await client.getOpenApiTask(agentId, taskId, appKey, accessToken, upstreamUserId);
    if (lastTask.status === 'COMPLETED') {
      return lastTask;
    }
    if (['FAILED', 'CANCELLED'].includes(lastTask.status)) {
      throw new Error(
        `OpenAPI task ${taskId} ended with ${lastTask.status}: ${lastTask.failureSummary ?? lastTask.errorMessage ?? ''}`
      );
    }
    await sleep(1000);
  }
  throw new Error(`Timed out waiting for OpenAPI task ${taskId}; last status=${lastTask?.status ?? 'unknown'}`);
}

async function fetchMockDebugRecords(traceId: string): Promise<MockDebugRecord[]> {
  const response = await axios.get(`${TEST_CONFIG.mockAdminBaseURL}/__debug/requests`, {
    params: { traceId },
    timeout: TEST_CONFIG.timeout
  });
  return Array.isArray(response.data) ? response.data : [];
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
