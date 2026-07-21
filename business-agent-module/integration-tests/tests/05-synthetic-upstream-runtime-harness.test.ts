import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import {
  int001ProbeCursor,
  int001ProbeTrace,
  int001StaticNoToolMarker,
  loadSyntheticRuntimeConfig,
  loadSyntheticRuntimeProbe,
  type Int001ProbeName,
  type SyntheticRuntimeConfig
} from '../src/synthetic-runtime-config.js';

/**
 * This file is intentionally standalone.  It must never import the legacy
 * integration-test config/client/setup because those modules default to the
 * shared 8112 stack and root credentials.
 */
const runtimeHarnessEnabled = process.env.INT001_SYNTHETIC_UPSTREAM_HARNESS === 'true';
let config: SyntheticRuntimeConfig;
let client: AxiosInstance;
const selectedProbe = runtimeHarnessEnabled ? loadSyntheticRuntimeProbe() : undefined;

// The default synthetic config suite is a static boundary check and must not
// require a disposable stack. Only the audit parent supplies this explicit
// opt-in under env -i; once enabled, the strict loader still rejects every
// undeclared inherited variable before any request can be sent.
if (runtimeHarnessEnabled) {
  config = loadSyntheticRuntimeConfig();
  client = axios.create({
    baseURL: config.naviBaseUrl,
    timeout: 30_000,
    validateStatus: () => true,
    headers: {
      'Content-Type': 'application/json'
    }
  });
}

let runtimeAccessToken = '';
// The parent reads this single fixed-enum result from its private child log.
// Never place a URL, response value, task identifier, prompt, or exception
// message in it: the root audit receipt may retain only the phase/class below.
let childResultStatus: 'PASS' | 'FAIL' = 'FAIL';
let childPhase: Int001ChildPhase = 'RUNTIME_TOKEN';
let childFailureClass: Int001ChildFailureClass = 'RUNTIME_TOKEN_EXCHANGE';

describe('05 - synthetic upstream runtime harness', () => {
  if (!runtimeHarnessEnabled || selectedProbe === undefined) {
    test.skip('requires the explicit INT001 synthetic runtime audit opt-in', () => {});
    return;
  }

  const positiveOnly = selectedProbe === 'positive' ? test : test.skip;
  const denyControlOnly = selectedProbe === 'deny-control' ? test : test.skip;
  const denyAdminOnly = selectedProbe === 'deny-admin' ? test : test.skip;
  const denySameClientAppOnly = selectedProbe === 'deny-same-client-app' ? test : test.skip;
  const denyCrossTenantOnly = selectedProbe === 'deny-cross-tenant' ? test : test.skip;
  const denyModelGrantOnly = selectedProbe === 'deny-model-grant' ? test : test.skip;
  const denyDirectoryOnly = selectedProbe === 'deny-directory' ? test : test.skip;
  const denyUpstreamUserOnly = selectedProbe === 'deny-upstream-user' ? test : test.skip;

  beforeAll(async () => {
    enterChildPhase('RUNTIME_TOKEN', 'RUNTIME_TOKEN_EXCHANGE');
    const response = await client.post(
      '/api/v1/open/client-apps/runtime-token',
      null,
      {
        headers: {
          'X-Client-App-Key': config.a.clientAppKey,
          'X-Client-App-Secret': config.a.clientAppSecret
        }
      }
    );
    const token = requireSuccessData<RuntimeTokenPayload>(response, 'runtime token exchange');
    expect(token.clientAppId === config.a.clientAppId).toBe(true);
    runtimeAccessToken = requireText(token.accessToken, 'runtime access token');
  });

  positiveOnly('proves A readiness and owner-smoke facts through the runtime lane', async () => {
    enterChildPhase('POSITIVE_READINESS', 'READINESS_CONTRACT');
    const response = await preflight(config.a.agentId, validPreflightBody(config));
    const readiness = requireSuccessData<ReadinessPayload>(response, 'A preflight');

    expect(readiness.overallStatus).toBe('OK');
    expect(readiness.clientAppId === config.a.clientAppId).toBe(true);
    expect(readiness.agentId === config.a.agentId).toBe(true);
    expect(readiness.effectiveModelConfigId === config.a.modelConfigId).toBe(true);
    // `owner-smoke` in the upstream CLI is a local profile check plus this
    // exact preflight payload.  The disposable fixture is deliberately Biz
    // only, so prove the resolved execution role rather than accepting any
    // non-empty Worker backend or a Codex/Pool-shaped fallback.
    expect(readiness.effectiveWorkerBackend).toBe('LANGGRAPH_BIZ');
    expect(readiness.effectiveDirectoryId === config.a.directoryId).toBe(true);
    expect(hasText(readiness.effectivePhysicalWorkerId)).toBe(true);
    expect(hasExecutionWorkerRole(readiness, 'biz', 'BIZ_WORKER_IDENTITY')).toBe(true);
  });

  positiveOnly('runs the static no-tool ask and verifies terminal diagnostics', async () => {
    // A failure before the response is safely classified as an ask
    // submission error; no generic catch-all is allowed into the root receipt.
    enterChildPhase('POSITIVE_ASK', 'ASK_SUBMISSION_ERROR');
    const probe: Int001ProbeName = 'positive';
    const marker = int001StaticNoToolMarker(config.runId);
    let response: AxiosResponse<unknown>;
    try {
      response = await ask(config.a.agentId, {
        message: probeMessage(config, probe, marker),
        maxTurns: 1,
        modelConfigId: config.a.modelConfigId,
        directoryId: config.a.directoryId,
        metadata: {
          traceId: int001ProbeTrace(config.runId, probe)
        }
      });
    } catch {
      // Keep the root receipt diagnostic-only. The private child log may have
      // runner detail, but it must never cross the fixed-enum boundary.
      enterChildPhase('POSITIVE_ASK', 'ASK_SUBMISSION_ERROR');
      throw new Error('positive ask submission failed');
    }
    if (!isRxSuccess(response) || envelopeData(response.data) === undefined) {
      enterChildPhase('POSITIVE_ASK', 'ASK_RX_REJECTED');
      throw new Error('positive ask did not return a successful RX payload');
    }
    const submitted = envelopeData(response.data) as OpenApiTaskPayload;
    if (!hasText(submitted.taskId)) {
      enterChildPhase('POSITIVE_ASK', 'ASK_TASK_ID_MISSING');
      throw new Error('positive ask did not return a task id');
    }
    const taskId = submitted.taskId.trim();

    enterChildPhase('POSITIVE_TASK_TERMINAL', 'TASK_TERMINAL');
    const terminal = await waitForTerminalTask(config.a.agentId, taskId);
    expect(terminal.status).toBe('COMPLETED');
    expect(requireText(terminal.result, 'positive task result')).toBe(marker);

    enterChildPhase('POSITIVE_DIAGNOSTICS', 'DIAGNOSTICS_CONTRACT');
    const diagnosticsResponse = await client.get(
      `/api/v1/open/agents/${encodeURIComponent(config.a.agentId)}`
        + `/tasks/${encodeURIComponent(taskId)}/diagnostics`,
      { headers: runtimeHeaders() }
    );
    const diagnostics = requireSuccessData<DiagnosticsPayload>(
      diagnosticsResponse,
      'positive task diagnostics'
    );
    expect(diagnostics.taskId === taskId).toBe(true);
    expect(diagnostics.status).toBe('COMPLETED');
    expect(diagnostics.terminal).toBe(true);
    expect(diagnostics.modelConfigId === config.a.modelConfigId).toBe(true);
    expect(hasText(diagnostics.effectiveWorkerBackend ?? diagnostics.workerBackend)).toBe(true);
    markChildPass();
  });

  denyControlOnly('fails closed when the runtime lane attempts a control mutation', async () => {
    enterChildPhase('DENY_CONTROL', 'DENY_CONTRACT');
    await assertDeniedMutation(
      'deny-control',
      client.post(
        `/api/v1/client-apps/${encodeURIComponent(config.a.clientAppId)}/model-config-grants`,
        {
          // A is already required to have this enabled grant for the positive
          // path.  A control-plane bypass would therefore be a successful,
          // idempotent POST rather than an unrelated form-validation failure.
          modelConfigId: config.a.modelConfigId,
          isDefault: false,
          grantScope: 'APP'
        },
        { headers: runtimeHeaders() }
      )
    );
    markChildPass();
  });

  denyAdminOnly('fails closed when the runtime lane attempts an admin mutation', async () => {
    enterChildPhase('DENY_ADMIN', 'DENY_CONTRACT');
    await assertDeniedMutation(
      'deny-admin',
      client.post(
        '/api/v1/admin/upstream-tenants/client-apps/ensure',
        validAdminEnsureProbeBody(config),
        { headers: runtimeHeaders() }
      )
    );
    markChildPass();
  });

  denySameClientAppOnly('fails closed for a same-tenant, different-ClientApp Agent', async () => {
    enterChildPhase('DENY_SAME_CLIENT_APP', 'DENY_CONTRACT');
    await assertDeniedReadiness(
      'deny-same-client-app',
      preflight(config.sameTenantOtherClientAppAgentId, validPreflightBody(config)),
      'ROOT_AGENT_BINDING'
    );
    await assertDeniedAsk(
      'deny-same-client-app',
      ask(config.sameTenantOtherClientAppAgentId, deniedAskBody(config, 'deny-same-client-app'))
    );
    markChildPass();
  });

  denyCrossTenantOnly('fails closed for a cross-tenant Agent', async () => {
    enterChildPhase('DENY_CROSS_TENANT', 'DENY_CONTRACT');
    await assertDeniedReadiness(
      'deny-cross-tenant',
      preflight(config.crossTenantAgentId, validPreflightBody(config)),
      'ROOT_AGENT_BINDING'
    );
    await assertDeniedAsk(
      'deny-cross-tenant',
      ask(config.crossTenantAgentId, deniedAskBody(config, 'deny-cross-tenant'))
    );
    markChildPass();
  });

  denyModelGrantOnly('fails closed for a missing model grant before task dispatch', async () => {
    enterChildPhase('DENY_MODEL_GRANT', 'DENY_CONTRACT');
    await assertDeniedReadiness(
      'deny-model-grant',
      preflight(config.a.agentId, {
        ...validPreflightBody(config),
        modelConfigId: `int001-missing-model-${config.runId}`
      }),
      'MODEL_CONFIG_GRANT'
    );
    markChildPass();
  });

  denyDirectoryOnly('fails closed for an unavailable directory before task dispatch', async () => {
    enterChildPhase('DENY_DIRECTORY', 'DENY_CONTRACT');
    await assertDeniedReadiness(
      'deny-directory',
      preflight(config.a.agentId, {
        ...validPreflightBody(config),
        directoryId: `int001-missing-directory-${config.runId}`
      }),
      'WORKSPACE_RESOURCE'
    );
    markChildPass();
  });

  denyUpstreamUserOnly('fails closed for an ungranted upstream user before task dispatch', async () => {
    enterChildPhase('DENY_UPSTREAM_USER', 'DENY_CONTRACT');
    // The runtime principal is carried by the header.  Keep the body and
    // header aligned so this probes the real upstream-user grant boundary,
    // rather than only a request-body consistency branch.
    const ungrantedUpstreamUserId = `int001-ungranted-user-${config.runId}`;
    await assertDeniedReadiness(
      'deny-upstream-user',
      preflight(
        config.a.agentId,
        {
          ...validPreflightBody(config),
          upstreamUserId: ungrantedUpstreamUserId
        },
        ungrantedUpstreamUserId
      ),
      'UPSTREAM_USER_GRANT'
    );
    markChildPass();
  });

  afterAll(() => {
    console.info(
      `INT001_CHILD_RESULT runId=${config.runId}`
        + ` probe=${selectedProbe}`
        + ` status=${childResultStatus}`
        + ` phase=${childPhase}`
        + ` failureClass=${childFailureClass}`
    );
  });
});

function enterChildPhase(phase: Int001ChildPhase, failureClass: Int001ChildFailureClass): void {
  childResultStatus = 'FAIL';
  childPhase = phase;
  childFailureClass = failureClass;
}

function markChildPass(): void {
  childResultStatus = 'PASS';
  childPhase = 'COMPLETE';
  childFailureClass = 'NONE';
}

async function preflight(
  agentId: string,
  body: ReadinessRequest,
  upstreamUserId = config.a.upstreamUserId
): Promise<AxiosResponse<unknown>> {
  return client.post(
    `/api/v1/open/agents/${encodeURIComponent(agentId)}/preflight`,
    body,
    { headers: runtimeHeaders(upstreamUserId) }
  );
}

async function ask(agentId: string, body: AskRequest): Promise<AxiosResponse<unknown>> {
  return client.post(
    `/api/v1/open/agents/${encodeURIComponent(agentId)}/ask`,
    body,
    { headers: runtimeHeaders() }
  );
}

function runtimeHeaders(upstreamUserId = config.a.upstreamUserId): Record<string, string> {
  if (!hasText(runtimeAccessToken)) {
    throw new Error('runtime access token was not issued');
  }
  return {
    'X-Client-App-Key': config.a.clientAppKey,
    'X-Client-App-Access-Token': runtimeAccessToken,
    'X-Upstream-User-Id': upstreamUserId
  };
}

function validPreflightBody(runtime: SyntheticRuntimeConfig): ReadinessRequest {
  return {
    upstreamUserId: runtime.a.upstreamUserId,
    modelConfigId: runtime.a.modelConfigId,
    directoryId: runtime.a.directoryId,
    context: {
      skillId: runtime.a.agentId
    }
  };
}

function deniedAskBody(runtime: SyntheticRuntimeConfig, probe: Int001ProbeName): AskRequest {
  return {
    message: probeMessage(runtime, probe),
    maxTurns: 1,
    modelConfigId: runtime.a.modelConfigId,
    directoryId: runtime.a.directoryId,
    metadata: {
      traceId: int001ProbeTrace(runtime.runId, probe)
    }
  };
}

/**
 * This is intentionally a structurally valid ensure request.  The endpoint
 * authenticates before provisioning today, but a malformed `{}` body could
 * otherwise turn a future auth-order regression into a validation-only pass.
 * All values are disposable, run-scoped identifiers; no credential is placed
 * in the body.
 */
function validAdminEnsureProbeBody(runtime: SyntheticRuntimeConfig): JsonRecord {
  return {
    sourceSystem: `int001-admin-${runtime.runId}`,
    sourceTenantId: `deny-admin-${runtime.runId}`,
    clientAppName: `INT001 admin denial probe ${runtime.runId}`,
    capabilityDomain: `int001-admin-${runtime.runId}`,
    rotateCredentials: false
  };
}

function probeMessage(runtime: SyntheticRuntimeConfig, probe: Int001ProbeName, marker?: string): string {
  const cursor = int001ProbeCursor(runtime.runId, probe);
  return marker
    ? `INT001 static no-tool probe. ${marker}. ${cursor}`
    : `INT001 deny probe. ${cursor}`;
}

async function waitForTerminalTask(agentId: string, taskId: string): Promise<OpenApiTaskPayload> {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const response = await client.get(
      `/api/v1/open/agents/${encodeURIComponent(agentId)}/tasks/${encodeURIComponent(taskId)}`,
      { headers: runtimeHeaders() }
    );
    const task = requireSuccessData<OpenApiTaskPayload>(response, 'positive task poll');
    if (task.status === 'COMPLETED') {
      return task;
    }
    if (task.status === 'FAILED' || task.status === 'CANCELLED') {
      throw new Error('positive task reached a terminal failure state');
    }
    await sleep(500);
  }
  throw new Error('positive task did not reach a terminal state before the deadline');
}

async function assertDeniedMutation(
  probe: Extract<Int001ProbeName, 'deny-control' | 'deny-admin'>,
  responsePromise: Promise<AxiosResponse<unknown>>
): Promise<void> {
  const response = await responsePromise;
  assertNoTaskCreated(response);
  // Both endpoints require a distinct credential header (control or admin).
  // A runtime access token is deliberately insufficient and must be rejected
  // before form processing or any provisioning/model mutation.
  expect(response.status).toBe(401);
  expect(isRxSuccess(response)).toBe(false);
}

async function assertDeniedReadiness(
  probe: Exclude<Int001ProbeName, 'positive' | 'deny-control' | 'deny-admin'>,
  responsePromise: Promise<AxiosResponse<unknown>>,
  expectedFailedCheck: string
): Promise<void> {
  const response = await responsePromise;
  assertNoTaskCreated(response);
  const readiness = requireSuccessData<ReadinessPayload>(response, `${probe} preflight`);
  expect(readiness.overallStatus).toBe('FAIL');
  expect(hasFailedReadinessCheck(readiness, expectedFailedCheck)).toBe(true);
}

async function assertDeniedAsk(
  probe: Extract<Int001ProbeName, 'deny-same-client-app' | 'deny-cross-tenant'>,
  responsePromise: Promise<AxiosResponse<unknown>>
): Promise<void> {
  const response = await responsePromise;
  assertNoTaskCreated(response);
  // ask routes turn a failed root-agent visibility check into RX.failB.  This
  // must happen before a task is created or a Worker can be selected.
  expect(response.status).toBe(400);
  expect(isRxSuccess(response)).toBe(false);
}

function assertNoTaskCreated(response: AxiosResponse<unknown>): void {
  expect(hasText(findTaskId(response.data))).toBe(false);
}

function isRxSuccess(response: AxiosResponse<unknown>): boolean {
  return response.status >= 200
    && response.status < 300
    && isRecord(response.data)
    && response.data.code === 200;
}

function requireSuccessData<T extends JsonRecord>(
  response: AxiosResponse<unknown>,
  classification: string
): T {
  if (!isRxSuccess(response)) {
    throw new Error(`${classification} did not return a successful RX response`);
  }
  const data = envelopeData(response.data);
  if (data === undefined) {
    throw new Error(`${classification} did not return an object payload`);
  }
  return data as T;
}

function envelopeData(body: unknown): JsonRecord | undefined {
  if (!isRecord(body) || !isRecord(body.data)) {
    return undefined;
  }
  return body.data;
}

function findTaskId(body: unknown): string | undefined {
  const data = envelopeData(body);
  if (data && typeof data.taskId === 'string') {
    return data.taskId;
  }
  if (isRecord(body) && typeof body.taskId === 'string') {
    return body.taskId;
  }
  return undefined;
}

function hasExecutionWorkerRole(
  readiness: ReadinessPayload,
  expectedRole: string,
  expectedSource: string
): boolean {
  return Array.isArray(readiness.physicalWorkerDiagnostics)
    && readiness.physicalWorkerDiagnostics.some(diagnostic => diagnostic.role === expectedRole
      && diagnostic.source === expectedSource
      && diagnostic.executionWorker === true);
}

function hasFailedReadinessCheck(readiness: ReadinessPayload, expectedCode: string): boolean {
  return Array.isArray(readiness.checks)
    && readiness.checks.some(check => check.code === expectedCode && check.status === 'FAIL');
}

function requireText(value: unknown, classification: string): string {
  if (!hasText(value)) {
    throw new Error(`${classification} is absent`);
  }
  return value.trim();
}

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

type JsonRecord = Record<string, unknown>;

type Int001ChildPhase =
  | 'RUNTIME_TOKEN'
  | 'POSITIVE_READINESS'
  | 'POSITIVE_ASK'
  | 'POSITIVE_TASK_TERMINAL'
  | 'POSITIVE_DIAGNOSTICS'
  | 'DENY_CONTROL'
  | 'DENY_ADMIN'
  | 'DENY_SAME_CLIENT_APP'
  | 'DENY_CROSS_TENANT'
  | 'DENY_MODEL_GRANT'
  | 'DENY_DIRECTORY'
  | 'DENY_UPSTREAM_USER'
  | 'COMPLETE';

type Int001ChildFailureClass =
  | 'RUNTIME_TOKEN_EXCHANGE'
  | 'READINESS_CONTRACT'
  | 'ASK_RX_REJECTED'
  | 'ASK_TASK_ID_MISSING'
  | 'ASK_SUBMISSION_ERROR'
  | 'TASK_TERMINAL'
  | 'DIAGNOSTICS_CONTRACT'
  | 'DENY_CONTRACT'
  | 'NONE';

interface RuntimeTokenPayload extends JsonRecord {
  clientAppId?: unknown;
  accessToken?: unknown;
}

interface ReadinessRequest extends JsonRecord {
  upstreamUserId: string;
  modelConfigId: string;
  directoryId: string;
  context: JsonRecord;
}

interface AskRequest extends JsonRecord {
  message: string;
  maxTurns: number;
  modelConfigId: string;
  directoryId: string;
  metadata: JsonRecord;
}

interface ReadinessCheck extends JsonRecord {
  code?: unknown;
  status?: unknown;
  message?: unknown;
}

interface PhysicalWorkerDiagnostic extends JsonRecord {
  role?: unknown;
  source?: unknown;
  executionWorker?: unknown;
}

interface ReadinessPayload extends JsonRecord {
  overallStatus?: unknown;
  clientAppId?: unknown;
  agentId?: unknown;
  effectiveModelConfigId?: unknown;
  effectiveWorkerBackend?: unknown;
  effectiveDirectoryId?: unknown;
  effectivePhysicalWorkerId?: unknown;
  physicalWorkerDiagnostics?: PhysicalWorkerDiagnostic[];
  checks?: ReadinessCheck[];
}

interface OpenApiTaskPayload extends JsonRecord {
  taskId?: unknown;
  status?: unknown;
  result?: unknown;
}

interface DiagnosticsPayload extends JsonRecord {
  taskId?: unknown;
  status?: unknown;
  terminal?: unknown;
  modelConfigId?: unknown;
  workerBackend?: unknown;
  effectiveWorkerBackend?: unknown;
}
