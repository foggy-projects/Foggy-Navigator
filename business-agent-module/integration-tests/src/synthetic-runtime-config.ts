import { isIP } from 'node:net';

/**
 * Runtime-only projection consumed by the INT-001 child process.
 *
 * The disposable harness owns every bootstrap/control/admin credential.  This
 * module deliberately accepts only the narrow runtime tuple needed to exchange
 * an access token and exercise the Open API.  Do not add a convenience fallback
 * to the legacy integration-test environment here: doing so would make a typo
 * capable of targeting the shared 8112 development stack.
 */
export interface SyntheticRuntimeConfig {
  readonly runId: string;
  readonly naviBaseUrl: string;
  readonly a: SyntheticRuntimeFixture;
  /** Public target identifier only; no B ClientApp or credential is projected. */
  readonly sameTenantOtherClientAppAgentId: string;
  /** Public target identifier only; no C tenant, ClientApp, or credential is projected. */
  readonly crossTenantAgentId: string;
}

export interface SyntheticRuntimeFixture {
  readonly tenantId: string;
  readonly clientAppId: string;
  readonly agentId: string;
  readonly directoryId: string;
  readonly clientAppKey: string;
  readonly clientAppSecret: string;
  readonly upstreamUserId: string;
  readonly modelConfigId: string;
}

const OPT_IN_ENV = 'INT001_SYNTHETIC_UPSTREAM_HARNESS';
const REQUIRED_ENV = [
  OPT_IN_ENV,
  'INT001_RUN_ID',
  'INT001_NAVI_BASE_URL',
  'INT001_A_TENANT_ID',
  'INT001_A_CLIENT_APP_ID',
  'INT001_A_CLIENT_APP_KEY',
  'INT001_A_CLIENT_APP_SECRET',
  'INT001_A_AGENT_ID',
  'INT001_A_UPSTREAM_USER_ID',
  'INT001_A_MODEL_CONFIG_ID',
  'INT001_A_DIRECTORY_ID',
  'INT001_B_AGENT_ID',
  'INT001_C_AGENT_ID'
] as const;

/**
 * The parent audit runs exactly one probe per child process.  This value is
 * deliberately not part of the persisted runtime projection: it is an
 * unprivileged, parent-supplied execution selector that makes every deny
 * probe's Worker-ingress delta independently observable.
 */
const RUNTIME_PROBE_ENV = 'INT001_RUNTIME_PROBE';
const ALLOWED_INT001_ENV = new Set<string>(REQUIRED_ENV);
ALLOWED_INT001_ENV.add(RUNTIME_PROBE_ENV);

/**
 * The parent invokes the child with `env -i`; these are the only non-INT001
 * values it is allowed to project.  Do not add general shell, npm, Node, or
 * CI variables here: a new child dependency must be made explicit in the
 * parent allow-list and reviewed as a credential-boundary change.
 */
const ALLOWED_BASE_ENV = new Set(['PATH', 'HOME']);

/** Vite injects these fixed mode flags only inside the test child. */
const ALLOWED_VITE_RUNTIME_ENV = new Set([
  'BASE_URL',
  'DEV',
  'MODE',
  'PROD',
  'SSR'
]);

/**
 * Vitest injects these fixed runner-coordination flags only inside the test
 * child. They are not parent projections and the list is exact: do not add a
 * `VITEST_*` or general Node/npm wildcard.
 */
const ALLOWED_VITEST_RUNTIME_ENV = new Set([
  'NODE_ENV',
  'TEST',
  'VITEST',
  'VITEST_MODE',
  'VITEST_POOL_ID',
  'VITEST_WORKER_ID'
]);

const ALLOWED_CHILD_ENV = new Set([
  ...ALLOWED_BASE_ENV,
  ...ALLOWED_VITE_RUNTIME_ENV,
  ...ALLOWED_VITEST_RUNTIME_ENV,
  ...ALLOWED_INT001_ENV
]);
const RUN_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{2,47}$/;

export const INT001_PROBE_NAMES = [
  'positive',
  'deny-control',
  'deny-admin',
  'deny-same-client-app',
  'deny-cross-tenant',
  'deny-model-grant',
  'deny-directory',
  'deny-upstream-user'
] as const;

export type Int001ProbeName = (typeof INT001_PROBE_NAMES)[number];

export function int001ProbeTrace(runId: string, probe: Int001ProbeName): string {
  return `int001-${probe}-${runId}`;
}

export function int001ProbeCursor(runId: string, probe: Int001ProbeName): string {
  return `next:${int001ProbeTrace(runId, probe)}:001`;
}

export function int001StaticNoToolMarker(runId: string): string {
  return `INT001_STATIC_NO_TOOL_${runId}`;
}

/**
 * Loads a projection supplied by the disposable parent harness.  Error text is
 * intentionally restricted to variable names and safe classifications so a
 * failing child cannot echo a credential to CI logs.
 */
export function loadSyntheticRuntimeConfig(
  env: NodeJS.ProcessEnv = process.env
): SyntheticRuntimeConfig {
  if (env[OPT_IN_ENV] !== 'true') {
    throw new Error(`${OPT_IN_ENV}=true is required for the synthetic upstream runtime suite`);
  }

  rejectUnsafeInheritedEnvironment(env);

  for (const name of ALLOWED_BASE_ENV) {
    requireNonBlank(env, name);
  }

  for (const name of REQUIRED_ENV) {
    requireNonBlank(env, name);
  }

  const runId = requireNonBlank(env, 'INT001_RUN_ID');
  if (!RUN_ID_PATTERN.test(runId)) {
    throw new Error('INT001_RUN_ID must be a 3-48 character safe run identifier');
  }

  const naviBaseUrl = requireLoopbackNon8112Url(env, 'INT001_NAVI_BASE_URL');
  const a: SyntheticRuntimeFixture = {
    tenantId: requireNonBlank(env, 'INT001_A_TENANT_ID'),
    clientAppId: requireNonBlank(env, 'INT001_A_CLIENT_APP_ID'),
    clientAppKey: requireNonBlank(env, 'INT001_A_CLIENT_APP_KEY'),
    clientAppSecret: requireNonBlank(env, 'INT001_A_CLIENT_APP_SECRET'),
    agentId: requireNonBlank(env, 'INT001_A_AGENT_ID'),
    upstreamUserId: requireNonBlank(env, 'INT001_A_UPSTREAM_USER_ID'),
    modelConfigId: requireNonBlank(env, 'INT001_A_MODEL_CONFIG_ID'),
    directoryId: requireNonBlank(env, 'INT001_A_DIRECTORY_ID')
  };
  const sameTenantOtherClientAppAgentId = requireNonBlank(env, 'INT001_B_AGENT_ID');
  const crossTenantAgentId = requireNonBlank(env, 'INT001_C_AGENT_ID');
  requireDistinct('Agent fixture identifiers', [
    a.agentId,
    sameTenantOtherClientAppAgentId,
    crossTenantAgentId
  ]);

  return Object.freeze({
    runId,
    naviBaseUrl,
    a: Object.freeze(a),
    sameTenantOtherClientAppAgentId,
    crossTenantAgentId
  });
}

/**
 * Reads the parent-selected, single runtime probe without accepting an
 * arbitrary test-name or command-line pattern from the environment.
 */
export function loadSyntheticRuntimeProbe(
  env: NodeJS.ProcessEnv = process.env
): Int001ProbeName {
  const raw = requireNonBlank(env, RUNTIME_PROBE_ENV);
  if (!INT001_PROBE_NAMES.includes(raw as Int001ProbeName)) {
    throw new Error(`${RUNTIME_PROBE_ENV} must be a registered synthetic probe`);
  }
  return raw as Int001ProbeName;
}

function rejectUnsafeInheritedEnvironment(env: NodeJS.ProcessEnv): void {
  for (const [name, value] of Object.entries(env)) {
    if (value !== undefined && !ALLOWED_CHILD_ENV.has(name)) {
      throw new Error(`${name} is not allowed in the synthetic runtime child`);
    }
  }
}

function requireLoopbackNon8112Url(env: NodeJS.ProcessEnv, name: string): string {
  const raw = requireNonBlank(env, name);
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error(`${name} must be an absolute loopback URL`);
  }
  if (parsed.protocol !== 'http:' || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must be a plain http loopback URL without credentials, query, or fragment`);
  }
  if (parsed.pathname !== '/' && parsed.pathname !== '') {
    throw new Error(`${name} must not contain a path`);
  }
  if (!parsed.port || parsed.port === '8112') {
    throw new Error(`${name} must use an explicit disposable port other than 8112`);
  }
  const port = Number(parsed.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`${name} has an invalid port`);
  }
  if (!isLoopbackHost(parsed.hostname)) {
    throw new Error(`${name} must target a loopback host`);
  }
  return parsed.toString().replace(/\/$/, '');
}

function isLoopbackHost(hostname: string): boolean {
  const normalized = hostname.replace(/^\[(.*)]$/, '$1').toLowerCase();
  if (normalized === 'localhost' || normalized === '::1') {
    return true;
  }
  return isIP(normalized) === 4 && normalized.startsWith('127.');
}

function requireNonBlank(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} is required`);
  }
  return value.trim();
}

function requireDistinct(label: string, values: string[]): void {
  if (new Set(values).size !== values.length) {
    throw new Error(`${label} must be distinct`);
  }
}
