import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type CanarySoakProfile = 'production' | 'local-smoke'

export interface GateThresholds {
  minTerminalTasks: number
  minObservationHours: number
  minPoolRotations: number
  minSuccessRate: number
  maxInternalErrorRate: number
  maxAffinityMismatches: number
  maxPrivacyLeakages: number
}

export interface CanarySoakConfigFile {
  profile: CanarySoakProfile
  navigator: {
    baseUrl: string
    tasksPath?: string
    tokenEnv: string
    runtimeId: string
    runtimeRevision?: number
    routingEpoch?: number
    workerId?: string
    model?: string
    providerType?: string
    cohortMarkerEnv?: string
  }
  workers: Array<{ healthUrl: string }>
  stateFile: string
  sampleIntervalSeconds?: number
  maxSampleGapSeconds?: number
  requestTimeoutSeconds?: number
  privacyMarkerEnvNames?: string[]
  thresholds?: Partial<GateThresholds>
}

export interface CanarySoakConfig {
  profile: CanarySoakProfile
  navigatorBaseUrl: string
  navigatorTasksPath: string
  navigatorTokenEnv: string
  runtimeId: string
  runtimeRevision?: number
  routingEpoch?: number
  workerId?: string
  model?: string
  providerType?: string
  cohortMarkerEnv?: string
  workerHealthUrls: string[]
  stateFile: string
  sampleIntervalMs: number
  maxSampleGapMs: number
  requestTimeoutMs: number
  privacyMarkerEnvNames: string[]
  thresholds: GateThresholds
  fingerprint: string
}

export type TerminalTaskStatus = 'completed' | 'failed' | 'aborted'

export interface SanitizedTerminalTask {
  status: TerminalTaskStatus
  internal_error: boolean
  recovery_unknown: boolean
  affinity_mismatch: boolean
  privacy_leakage: boolean
  observed_at: string
}

export type AffinityViolationReason = 'missing_runtime_instance' | 'unknown_runtime_instance'

export interface SanitizedAffinityViolation {
  reason: AffinityViolationReason
  observed_at: string
}

export interface SanitizedWorkerSample {
  instance_digest: string
  created_counter: number
  reused_counter: number
  retired_counter: number
  rejected_counter: number
  acquire_timeouts_counter: number
  rotations: number
  crashes_counter: number
  crashes: number
  rejections: number
  acquire_timeouts: number
  instances: number
  busy: number
  idle: number
  creating: number
  queued: number
  lanes: number
  draining: boolean
  sampled_at: string
}

export interface CanarySoakState {
  schema_version: 3
  profile: CanarySoakProfile
  evidence_class: 'PRODUCTION_CANDIDATE' | 'NON_PRODUCTION_SMOKE'
  config_fingerprint: string
  window_started_at: string
  last_attempt_at?: string
  last_sample_at?: string
  next_due_at: string
  continuity_resets: number
  completed_cycles: number
  incomplete_cycles: number
  navigator_poll_failures: number
  worker_poll_failures: number
  health_privacy_leakages: number
  last_cycle_complete: boolean
  terminal_tasks: Record<string, SanitizedTerminalTask>
  affinity_violations: Record<string, SanitizedAffinityViolation>
  worker_samples: Record<string, SanitizedWorkerSample>
}

export interface GateResult {
  passed: boolean
  productionEvidenceEligible: boolean
  terminalTasks: number
  completedTasks: number
  failedTasks: number
  abortedTasks: number
  internalErrors: number
  recoveryUnknown: number
  affinityMismatches: number
  privacyLeakages: number
  poolRotations: number
  poolCrashes: number
  poolRejections: number
  poolAcquireTimeouts: number
  observationHours: number
  successRate: number
  internalErrorRate: number
  checkpointFresh: boolean
  sampleAgeSeconds: number | null
  checks: Record<string, boolean>
}

export interface GateEvaluationContext {
  now: Date
  maxSampleGapMs: number
}

export interface SampleResult {
  state: CanarySoakState
  due: boolean
  cycleComplete: boolean
  errorCodes: string[]
}

export interface CanarySoakDependencies {
  fetch?: typeof fetch
  now?: () => Date
  env?: NodeJS.ProcessEnv
}

export class CanarySoakError extends Error {
  constructor(readonly code: string) {
    super(code)
    this.name = 'CanarySoakError'
  }
}

export const PRODUCTION_THRESHOLDS: Readonly<GateThresholds> = Object.freeze({
  minTerminalTasks: 50,
  minObservationHours: 72,
  minPoolRotations: 2,
  minSuccessRate: 0.98,
  maxInternalErrorRate: 0.01,
  maxAffinityMismatches: 0,
  maxPrivacyLeakages: 0,
})

const LOCAL_SMOKE_THRESHOLDS: Readonly<GateThresholds> = Object.freeze({
  minTerminalTasks: 2,
  minObservationHours: 0,
  minPoolRotations: 0,
  minSuccessRate: 0,
  maxInternalErrorRate: 1,
  maxAffinityMismatches: 0,
  maxPrivacyLeakages: 0,
})

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'ABORTED'])
const INTERNAL_ERROR = /(?:APP_SERVER_|CODEX_)/i
const RECOVERY_UNKNOWN = /APP_SERVER_RECOVERY_UNKNOWN/i
const AFFINITY_ERROR = /AFFINITY/i
const STATE_FORBIDDEN_KEY = /(?:^|_)(?:token|secret|password|authorization|prompt|result|error_message|endpoint|url|marker)(?:_|$)/i
const MAX_WORKERS = 16
const PRODUCTION_PROVIDER_TYPE = 'codex-app-server-worker'
const PRODUCTION_ULTRA_MODELS = new Set([
  'codex-ultra',
  'codex-latest:ultra',
  'codex-terra:ultra',
  'gpt-5.6-sol:ultra',
  'gpt-5.6-terra:ultra',
])
const MAX_NEXT_DUE_FUTURE_MS = 24 * 60 * 60 * 1_000

interface StateLease {
  leasePath: string
  leaseId: string
}

interface StateLeaseOwner {
  leaseId: string
  hostname: string
  pid: number
}

interface CycleHealthContext {
  instanceIds: Set<string>
  instanceDigests: Set<string>
  runtimeRevision?: number
}

export async function loadCanarySoakConfig(configPath: string): Promise<CanarySoakConfig> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await fs.readFile(configPath, 'utf8'))
  } catch {
    throw new CanarySoakError('CANARY_CONFIG_READ_FAILED')
  }
  return resolveCanarySoakConfig(parsed, path.dirname(path.resolve(configPath)))
}

export function resolveCanarySoakConfig(raw: unknown, baseDirectory = process.cwd()): CanarySoakConfig {
  const value = asRecord(raw, 'CANARY_CONFIG_INVALID')
  assertOnlyKeys(value, [
    'profile', 'navigator', 'workers', 'stateFile', 'sampleIntervalSeconds',
    'maxSampleGapSeconds', 'requestTimeoutSeconds', 'privacyMarkerEnvNames', 'thresholds',
  ], 'CANARY_CONFIG_FIELD_UNKNOWN')
  rejectEmbeddedSecrets(value)
  const profile = requiredProfile(value.profile)
  const navigator = asRecord(value.navigator, 'CANARY_CONFIG_NAVIGATOR_INVALID')
  assertOnlyKeys(
    navigator,
    [
      'baseUrl', 'tasksPath', 'tokenEnv', 'runtimeId', 'runtimeRevision', 'routingEpoch',
      'workerId', 'model', 'providerType', 'cohortMarkerEnv',
    ],
    'CANARY_CONFIG_NAVIGATOR_FIELD_UNKNOWN',
  )
  const workers = requiredArray(value.workers, 'CANARY_CONFIG_WORKERS_INVALID')
  if (workers.length < 1 || workers.length > MAX_WORKERS) {
    throw new CanarySoakError('CANARY_CONFIG_WORKERS_INVALID')
  }
  const navigatorBaseUrl = normalizedHttpUrl(navigator.baseUrl, false)
  const navigatorTasksPath = optionalString(navigator.tasksPath) || '/api/v1/codex-tasks'
  validateTasksPath(navigatorTasksPath, navigatorBaseUrl)
  const navigatorTokenEnv = environmentName(navigator.tokenEnv, 'CANARY_CONFIG_TOKEN_ENV_INVALID')
  const runtimeId = boundedString(navigator.runtimeId, 128, 'CANARY_CONFIG_RUNTIME_ID_INVALID')
  const runtimeRevision = optionalInteger(
    navigator.runtimeRevision,
    1,
    Number.MAX_SAFE_INTEGER,
    'CANARY_CONFIG_RUNTIME_REVISION_INVALID',
  )
  const routingEpoch = optionalInteger(
    navigator.routingEpoch,
    0,
    Number.MAX_SAFE_INTEGER,
    'CANARY_CONFIG_ROUTING_EPOCH_INVALID',
  )
  const workerId = optionalBoundedString(navigator.workerId, 128, 'CANARY_CONFIG_WORKER_ID_INVALID')
  const model = optionalBoundedString(navigator.model, 128, 'CANARY_CONFIG_MODEL_INVALID')
  const providerType = optionalBoundedString(navigator.providerType, 64, 'CANARY_CONFIG_PROVIDER_TYPE_INVALID')
  const cohortMarkerEnv = navigator.cohortMarkerEnv === undefined
    ? undefined
    : environmentName(navigator.cohortMarkerEnv, 'CANARY_CONFIG_COHORT_MARKER_ENV_INVALID')
  const workerHealthUrls = workers.map((worker) => {
    const record = asRecord(worker, 'CANARY_CONFIG_WORKER_INVALID')
    assertOnlyKeys(record, ['healthUrl'], 'CANARY_CONFIG_WORKER_FIELD_UNKNOWN')
    return normalizedHttpUrl(record.healthUrl, true)
  })
  if (profile === 'production') {
    if (runtimeRevision === undefined || routingEpoch === undefined || !workerId || !model || !cohortMarkerEnv) {
      throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_COHORT_INCOMPLETE')
    }
    if (!isUltraModel(model)) throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_MODEL_NOT_ULTRA')
    if (providerType !== PRODUCTION_PROVIDER_TYPE) {
      throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_PROVIDER_INVALID')
    }
    assertProductionTransport(navigatorBaseUrl)
    workerHealthUrls.forEach(assertProductionTransport)
  }

  const stateFileValue = boundedString(value.stateFile, 1_024, 'CANARY_CONFIG_STATE_FILE_INVALID')
  const stateFile = path.isAbsolute(stateFileValue)
    ? path.normalize(stateFileValue)
    : path.resolve(baseDirectory, stateFileValue)
  const sampleIntervalSeconds = integer(value.sampleIntervalSeconds, 60, 1, 86_400, 'CANARY_CONFIG_INTERVAL_INVALID')
  const maxSampleGapSeconds = integer(
    value.maxSampleGapSeconds,
    profile === 'production' ? 600 : 3_600,
    sampleIntervalSeconds,
    86_400,
    'CANARY_CONFIG_SAMPLE_GAP_INVALID',
  )
  if (profile === 'production' && maxSampleGapSeconds > 900) {
    throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_SAMPLE_GAP_TOO_LARGE')
  }
  const requestTimeoutSeconds = integer(value.requestTimeoutSeconds, 20, 1, 120, 'CANARY_CONFIG_TIMEOUT_INVALID')
  const privacyMarkerEnvNames = optionalStringArray(value.privacyMarkerEnvNames)
    .map(name => environmentName(name, 'CANARY_CONFIG_MARKER_ENV_INVALID'))
  if (profile === 'production' && privacyMarkerEnvNames.length === 0) {
    throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_MARKERS_REQUIRED')
  }
  const thresholds = resolveGateThresholds(profile, value.thresholds)
  const fingerprintPayload = {
    profile,
    navigator_base: navigatorBaseUrl,
    navigator_path: navigatorTasksPath,
    navigator_token_env: navigatorTokenEnv,
    runtime_id: runtimeId,
    runtime_revision: runtimeRevision ?? null,
    routing_epoch: routingEpoch ?? null,
    worker_id: workerId || null,
    model: model || null,
    provider_type: providerType || null,
    cohort_marker_env: cohortMarkerEnv || null,
    workers: workerHealthUrls,
    sample_interval_seconds: sampleIntervalSeconds,
    max_sample_gap_seconds: maxSampleGapSeconds,
    request_timeout_seconds: requestTimeoutSeconds,
    privacy_marker_env_names: privacyMarkerEnvNames,
    thresholds,
  }
  return {
    profile,
    navigatorBaseUrl,
    navigatorTasksPath,
    navigatorTokenEnv,
    runtimeId,
    runtimeRevision,
    routingEpoch,
    workerId,
    model,
    providerType,
    cohortMarkerEnv,
    workerHealthUrls,
    stateFile,
    sampleIntervalMs: sampleIntervalSeconds * 1_000,
    maxSampleGapMs: maxSampleGapSeconds * 1_000,
    requestTimeoutMs: requestTimeoutSeconds * 1_000,
    privacyMarkerEnvNames,
    thresholds,
    fingerprint: digest(canonicalJson(fingerprintPayload)),
  }
}

export function resolveGateThresholds(
  profile: CanarySoakProfile,
  rawOverrides: unknown,
): GateThresholds {
  const baseline = profile === 'production' ? PRODUCTION_THRESHOLDS : LOCAL_SMOKE_THRESHOLDS
  if (rawOverrides === undefined) return { ...baseline }
  const overrides = asRecord(rawOverrides, 'CANARY_CONFIG_THRESHOLDS_INVALID')
  assertOnlyKeys(overrides, Object.keys(baseline), 'CANARY_CONFIG_THRESHOLD_UNKNOWN')
  const resolved = { ...baseline }
  for (const key of Object.keys(overrides)) {
    const raw = overrides[key]
    const isRate = key.endsWith('Rate')
    const number = numeric(raw, 0, isRate ? 1 : Number.MAX_SAFE_INTEGER, 'CANARY_CONFIG_THRESHOLD_INVALID')
    const integerGate = key !== 'minObservationHours' && !isRate
    if (integerGate && !Number.isInteger(number)) throw new CanarySoakError('CANARY_CONFIG_THRESHOLD_INVALID')
    const minimumGate = key.startsWith('min')
    if (profile === 'production') {
      const productionValue = PRODUCTION_THRESHOLDS[key as keyof GateThresholds]
      if ((minimumGate && number < productionValue) || (!minimumGate && number > productionValue)) {
        throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_THRESHOLD_WEAKENED')
      }
    }
    resolved[key as keyof GateThresholds] = number
  }
  return resolved
}

export function createInitialState(config: CanarySoakConfig, now: Date, continuityResets = 0): CanarySoakState {
  const iso = now.toISOString()
  return {
    schema_version: 3,
    profile: config.profile,
    evidence_class: config.profile === 'production' ? 'PRODUCTION_CANDIDATE' : 'NON_PRODUCTION_SMOKE',
    config_fingerprint: config.fingerprint,
    window_started_at: iso,
    next_due_at: iso,
    continuity_resets: continuityResets,
    completed_cycles: 0,
    incomplete_cycles: 0,
    navigator_poll_failures: 0,
    worker_poll_failures: 0,
    health_privacy_leakages: 0,
    last_cycle_complete: false,
    terminal_tasks: {},
    affinity_violations: {},
    worker_samples: {},
  }
}

export async function readCanarySoakState(
  config: CanarySoakConfig,
  now = new Date(),
): Promise<CanarySoakState | undefined> {
  let raw: unknown
  try {
    raw = JSON.parse(await fs.readFile(config.stateFile, 'utf8'))
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined
    throw new CanarySoakError('CANARY_STATE_READ_FAILED')
  }
  let state: CanarySoakState
  try {
    state = validateState(raw, now)
  } catch (error) {
    if (error instanceof CanarySoakError
      && ['CANARY_STATE_INVALID', 'CANARY_STATE_VERSION_UNSUPPORTED'].includes(error.code)) {
      throw new CanarySoakError('CANARY_STATE_RESET_REQUIRED')
    }
    throw error
  }
  if (state.profile !== config.profile || state.config_fingerprint !== config.fingerprint) {
    throw new CanarySoakError('CANARY_STATE_RESET_REQUIRED')
  }
  return state
}

export function isSampleDue(state: CanarySoakState | undefined, now: Date): boolean {
  if (!state) return true
  const dueAt = Date.parse(state.next_due_at)
  return !Number.isFinite(dueAt) || now.getTime() >= dueAt
}

export async function sampleCanarySoak(
  config: CanarySoakConfig,
  existingState?: CanarySoakState,
  dependencies: CanarySoakDependencies = {},
): Promise<SampleResult> {
  const now = dependencies.now?.() || new Date()
  const lease = await acquireStateLease(config.stateFile, now)
  try {
    const persistedState = await readCanarySoakState(config, now)
    let state = persistedState || existingState || createInitialState(config, now)
    validateState(state, now)
    if (state.profile !== config.profile || state.config_fingerprint !== config.fingerprint) {
      throw new CanarySoakError('CANARY_STATE_RESET_REQUIRED')
    }
    if (!isSampleDue(state, now)) {
      return { state, due: false, cycleComplete: state.last_cycle_complete, errorCodes: [] }
    }
    const lastSuccess = state.last_sample_at ? Date.parse(state.last_sample_at) : undefined
    const continuityReference = lastSuccess !== undefined && Number.isFinite(lastSuccess)
      ? lastSuccess
      : Date.parse(state.window_started_at)
    if (Number.isFinite(continuityReference) && now.getTime() - continuityReference > config.maxSampleGapMs) {
      state = createInitialState(config, now, state.continuity_resets + 1)
    }
    const fetchImpl = dependencies.fetch || fetch
    const env = dependencies.env || process.env
    const token = requiredEnvironmentSecret(env, config.navigatorTokenEnv, 'CANARY_NAVIGATOR_TOKEN_MISSING')
    const markers = config.privacyMarkerEnvNames.map(name =>
      requiredEnvironmentSecret(env, name, 'CANARY_PRIVACY_MARKER_MISSING'))
    const cohortMarker = config.cohortMarkerEnv
      ? requiredEnvironmentSecret(env, config.cohortMarkerEnv, 'CANARY_COHORT_MARKER_MISSING')
      : undefined
    const nextState = cloneState(state)
    const iso = now.toISOString()
    nextState.last_attempt_at = iso
    nextState.next_due_at = new Date(now.getTime() + config.sampleIntervalMs).toISOString()
    const errorCodes: string[] = []

    let tasks: Record<string, unknown>[] | undefined
    try {
      tasks = await fetchNavigatorTasks(config, token, fetchImpl)
    } catch (error) {
      nextState.navigator_poll_failures++
      errorCodes.push(stableErrorCode(error, 'CANARY_NAVIGATOR_POLL_FAILED'))
    }

    let workersComplete = true
    const healthContext: CycleHealthContext = { instanceIds: new Set(), instanceDigests: new Set() }
    for (const healthUrl of config.workerHealthUrls) {
      try {
        const health = await fetchJson(
          healthUrl,
          {},
          config.requestTimeoutMs,
          fetchImpl,
          'CANARY_WORKER_HEALTH_FAILED',
        )
        incorporateWorkerHealth(nextState, health, markers, now, config, healthContext)
      } catch (error) {
        workersComplete = false
        nextState.worker_poll_failures++
        errorCodes.push(stableErrorCode(error, 'CANARY_WORKER_HEALTH_FAILED'))
      }
    }
    const navigatorComplete = tasks !== undefined
    const cycleComplete = navigatorComplete && workersComplete
    if (tasks !== undefined && workersComplete) {
      incorporateTasks(nextState, config, tasks, markers, cohortMarker, healthContext.instanceIds, now)
    }
    nextState.last_cycle_complete = cycleComplete
    if (cycleComplete) {
      nextState.completed_cycles++
      nextState.last_sample_at = iso
    } else {
      nextState.incomplete_cycles++
    }
    const forbiddenValues = [token, ...markers, ...(cohortMarker ? [cohortMarker] : [])]
    assertSanitizedState(nextState, forbiddenValues)
    await writeAtomicCanarySoakState(config.stateFile, nextState, forbiddenValues, lease, now)
    return { state: nextState, due: true, cycleComplete, errorCodes: [...new Set(errorCodes)] }
  } finally {
    await releaseStateLease(lease)
  }
}

export function evaluateCanarySoakGate(
  state: CanarySoakState,
  thresholds: GateThresholds,
  context?: GateEvaluationContext,
): GateResult {
  const tasks = Object.values(state.terminal_tasks)
  const terminalTasks = tasks.length
  const completedTasks = tasks.filter(task => task.status === 'completed').length
  const failedTasks = tasks.filter(task => task.status === 'failed').length
  const abortedTasks = tasks.filter(task => task.status === 'aborted').length
  const internalErrors = tasks.filter(task => task.internal_error).length
  const recoveryUnknown = tasks.filter(task => task.recovery_unknown).length
  const affinityMismatches = tasks.filter(task => task.affinity_mismatch).length
    + Object.keys(state.affinity_violations).length
  const privacyLeakages = tasks.filter(task => task.privacy_leakage).length + state.health_privacy_leakages
  const poolRotations = Object.values(state.worker_samples).reduce((sum, sample) => sum + sample.rotations, 0)
  const poolCrashes = Object.values(state.worker_samples).reduce((sum, sample) => sum + sample.crashes, 0)
  const poolRejections = Object.values(state.worker_samples).reduce((sum, sample) => sum + sample.rejections, 0)
  const poolAcquireTimeouts = Object.values(state.worker_samples)
    .reduce((sum, sample) => sum + sample.acquire_timeouts, 0)
  const end = state.last_sample_at ? Date.parse(state.last_sample_at) : Date.parse(state.window_started_at)
  const start = Date.parse(state.window_started_at)
  const observationHours = Math.max(0, end - start) / 3_600_000
  const successRate = terminalTasks === 0 ? 0 : completedTasks / terminalTasks
  const internalErrorRate = terminalTasks === 0 ? 0 : internalErrors / terminalTasks
  const lastSample = state.last_sample_at ? Date.parse(state.last_sample_at) : Number.NaN
  const sampleAgeSeconds = context && Number.isFinite(lastSample)
    ? Math.max(0, context.now.getTime() - lastSample) / 1_000
    : null
  const checkpointFresh = context
    ? sampleAgeSeconds !== null && sampleAgeSeconds * 1_000 <= context.maxSampleGapMs
    : true
  const checks: Record<string, boolean> = {
    checkpoint_fresh: checkpointFresh,
    has_complete_sample: state.completed_cycles > 0 && state.last_cycle_complete,
    terminal_tasks: terminalTasks >= thresholds.minTerminalTasks,
    observation_window: observationHours >= thresholds.minObservationHours,
    pool_rotations: poolRotations >= thresholds.minPoolRotations,
    success_rate: successRate >= thresholds.minSuccessRate,
    internal_error_rate: internalErrorRate <= thresholds.maxInternalErrorRate,
    recovery_unknown: recoveryUnknown === 0,
    affinity_mismatch: affinityMismatches <= thresholds.maxAffinityMismatches,
    privacy_leakage: privacyLeakages <= thresholds.maxPrivacyLeakages,
  }
  if (state.profile === 'production') checks.external_production_evidence = false
  return {
    passed: Object.values(checks).every(Boolean),
    productionEvidenceEligible: false,
    terminalTasks,
    completedTasks,
    failedTasks,
    abortedTasks,
    internalErrors,
    recoveryUnknown,
    affinityMismatches,
    privacyLeakages,
    poolRotations,
    poolCrashes,
    poolRejections,
    poolAcquireTimeouts,
    observationHours,
    successRate,
    internalErrorRate,
    checkpointFresh,
    sampleAgeSeconds,
    checks,
  }
}

export function renderCanarySoakReport(
  state: CanarySoakState,
  thresholds: GateThresholds,
  context?: GateEvaluationContext,
): string {
  const gate = evaluateCanarySoakGate(state, thresholds, context)
  const heading = state.profile === 'production'
    ? 'PRODUCTION CANARY/SOAK EVIDENCE REPORT'
    : '!!! NON-PRODUCTION LOCAL SMOKE - CANNOT BE USED AS PRODUCTION EVIDENCE !!!'
  const checks = Object.entries(gate.checks)
    .map(([name, passed]) => `  ${name}: ${passed ? 'PASS' : 'PENDING'}`)
    .join('\n')
  return [
    heading,
    `gate: ${gate.passed ? 'PASS' : 'PENDING'}`,
    `production_evidence_eligible: ${gate.productionEvidenceEligible}`,
    'external_production_evidence: false',
    `window_started_at: ${state.window_started_at}`,
    `last_successful_sample_at: ${state.last_sample_at || 'none'}`,
    `checkpoint_age_seconds: ${gate.sampleAgeSeconds === null ? 'unknown' : gate.sampleAgeSeconds.toFixed(0)}`,
    `continuity_resets: ${state.continuity_resets}`,
    `terminal_tasks: ${gate.terminalTasks}/${thresholds.minTerminalTasks}`,
    `completed_failed_aborted: ${gate.completedTasks}/${gate.failedTasks}/${gate.abortedTasks}`,
    `observation_hours: ${gate.observationHours.toFixed(2)}/${thresholds.minObservationHours}`,
    `pool_rotations: ${gate.poolRotations}/${thresholds.minPoolRotations}`,
    `pool_crashes_rejections_acquire_timeouts: ${gate.poolCrashes}/${gate.poolRejections}/${gate.poolAcquireTimeouts}`,
    `success_rate: ${(gate.successRate * 100).toFixed(2)}%/${(thresholds.minSuccessRate * 100).toFixed(2)}%`,
    `internal_errors: ${gate.internalErrors} (${(gate.internalErrorRate * 100).toFixed(2)}%)`,
    `recovery_unknown: ${gate.recoveryUnknown}`,
    `affinity_mismatches: ${gate.affinityMismatches}`,
    `privacy_marker_leakages: ${gate.privacyLeakages}`,
    `poll_failures_navigator_worker: ${state.navigator_poll_failures}/${state.worker_poll_failures}`,
    'checks:',
    checks,
  ].join('\n')
}

export async function writeAtomicCanarySoakState(
  stateFile: string,
  state: CanarySoakState,
  forbiddenValues: string[] = [],
  existingLease?: StateLease,
  now = new Date(),
): Promise<void> {
  validateState(state, now)
  const lease = existingLease || await acquireStateLease(stateFile, now)
  try {
    await assertStateLeaseOwned(lease)
    assertSanitizedState(state, forbiddenValues)
    const directory = path.dirname(stateFile)
    await fs.mkdir(directory, { recursive: true })
    const temporary = path.join(
      directory,
      `.${path.basename(stateFile)}.${process.pid}.${crypto.randomBytes(8).toString('hex')}.tmp`,
    )
    let handle: fs.FileHandle | undefined
    try {
      handle = await fs.open(temporary, 'wx', 0o600)
      await handle.writeFile(`${JSON.stringify(state, null, 2)}\n`, 'utf8')
      await handle.sync()
      await handle.close()
      handle = undefined
      await fs.rename(temporary, stateFile)
      if (process.platform !== 'win32') {
        const directoryHandle = await fs.open(directory, 'r')
        try {
          await directoryHandle.sync()
        } finally {
          await directoryHandle.close()
        }
      }
    } catch {
      await handle?.close().catch(() => undefined)
      await fs.rm(temporary, { force: true }).catch(() => undefined)
      throw new CanarySoakError('CANARY_STATE_WRITE_FAILED')
    }
  } finally {
    if (!existingLease) await releaseStateLease(lease)
  }
}

export async function resetCanarySoakState(
  config: CanarySoakConfig,
  now = new Date(),
): Promise<CanarySoakState> {
  const lease = await acquireStateLease(config.stateFile, now)
  try {
    const state = createInitialState(config, now)
    await writeAtomicCanarySoakState(config.stateFile, state, [], lease, now)
    return state
  } finally {
    await releaseStateLease(lease)
  }
}

export function assertSanitizedState(state: unknown, forbiddenValues: string[] = []): void {
  visitState(state, forbiddenValues.filter(value => value.length > 0))
}

function visitState(value: unknown, forbiddenValues: string[], key?: string): void {
  if (key && STATE_FORBIDDEN_KEY.test(key)) throw new CanarySoakError('CANARY_STATE_UNSAFE_FIELD')
  if (typeof value === 'string') {
    if (forbiddenValues.some(secret => value.includes(secret))) {
      throw new CanarySoakError('CANARY_STATE_SECRET_DETECTED')
    }
    return
  }
  if (Array.isArray(value)) {
    value.forEach(item => visitState(item, forbiddenValues))
    return
  }
  if (value && typeof value === 'object') {
    Object.entries(value).forEach(([childKey, child]) => visitState(child, forbiddenValues, childKey))
  }
}

async function fetchNavigatorTasks(
  config: CanarySoakConfig,
  token: string,
  fetchImpl: typeof fetch,
): Promise<Record<string, unknown>[]> {
  const url = new URL(config.navigatorTasksPath, `${config.navigatorBaseUrl}/`)
  if (url.origin !== new URL(config.navigatorBaseUrl).origin) {
    throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
  }
  if (config.workerId) url.searchParams.set('workerId', config.workerId)
  const payload = await fetchJson(
    url.toString(),
    { Authorization: `Bearer ${token}` },
    config.requestTimeoutMs,
    fetchImpl,
    'CANARY_NAVIGATOR_POLL_FAILED',
  )
  const envelope = asOptionalRecord(payload)
  if (envelope && typeof envelope.code === 'number' && envelope.code !== 200) {
    throw new CanarySoakError('CANARY_NAVIGATOR_ENVELOPE_FAILED')
  }
  const tasks = Array.isArray(payload) ? payload : envelope?.data
  if (!Array.isArray(tasks)) throw new CanarySoakError('CANARY_NAVIGATOR_RESPONSE_INVALID')
  return tasks.map(task => asRecord(task, 'CANARY_NAVIGATOR_TASK_INVALID'))
}

async function fetchJson(
  url: string,
  headers: Record<string, string>,
  timeoutMs: number,
  fetchImpl: typeof fetch,
  errorCode: string,
): Promise<unknown> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  timer.unref()
  try {
    const response = await fetchImpl(url, { method: 'GET', headers, signal: controller.signal })
    if (!response.ok) throw new CanarySoakError(errorCode)
    return await response.json()
  } catch (error) {
    if (error instanceof CanarySoakError) throw error
    throw new CanarySoakError(errorCode)
  } finally {
    clearTimeout(timer)
  }
}

function incorporateTasks(
  state: CanarySoakState,
  config: CanarySoakConfig,
  tasks: Record<string, unknown>[],
  markers: string[],
  cohortMarker: string | undefined,
  healthyInstanceIds: Set<string>,
  now: Date,
): void {
  const windowStart = Date.parse(state.window_started_at)
  for (const task of tasks) {
    const status = optionalString(task.status).toUpperCase()
    if (!TERMINAL_STATUSES.has(status)) continue
    if (optionalString(task.runtimeId) !== config.runtimeId) continue
    if (config.workerId && optionalString(task.workerId) !== config.workerId) continue
    if (config.runtimeRevision !== undefined && task.runtimeRevision !== config.runtimeRevision) continue
    if (config.routingEpoch !== undefined && task.routingEpoch !== config.routingEpoch) continue
    if (config.model && optionalString(task.model) !== config.model) continue
    if (config.providerType && optionalString(task.providerType) !== config.providerType) continue
    if (config.profile === 'production' && optionalString(task.runtimeType) !== 'APP_SERVER') continue
    if (cohortMarker && !optionalString(task.prompt).includes(cohortMarker)) continue
    const createdAt = config.profile === 'production'
      ? optionalEpochMillis(task.createdAtEpochMs)
      : Date.parse(optionalString(task.createdAt))
    if (!Number.isFinite(createdAt) || createdAt < windowStart || createdAt > now.getTime()) continue
    const taskId = optionalString(task.taskId)
    if (!taskId) continue
    const taskDigest = digest(`${config.runtimeId}\0${taskId}`)
    if (state.terminal_tasks[taskDigest] || state.affinity_violations[taskDigest]) continue
    const runtimeType = optionalString(task.runtimeType).toUpperCase()
    const instanceId = optionalString(task.runtimeInstanceId)
    if (runtimeType === 'APP_SERVER' && (!instanceId || !healthyInstanceIds.has(instanceId))) {
      state.affinity_violations[taskDigest] = {
        reason: instanceId ? 'unknown_runtime_instance' : 'missing_runtime_instance',
        observed_at: now.toISOString(),
      }
      continue
    }
    if (!instanceId || !healthyInstanceIds.has(instanceId)) continue
    const errorMessage = optionalString(task.errorMessage)
    const resultText = optionalString(task.resultText)
    state.terminal_tasks[taskDigest] = {
      status: status.toLowerCase() as TerminalTaskStatus,
      internal_error: status === 'FAILED' && INTERNAL_ERROR.test(errorMessage),
      recovery_unknown: status === 'FAILED' && RECOVERY_UNKNOWN.test(errorMessage),
      affinity_mismatch: AFFINITY_ERROR.test(errorMessage),
      privacy_leakage: containsMarker(errorMessage, markers) || containsMarker(resultText, markers),
      observed_at: now.toISOString(),
    }
  }
}

function incorporateWorkerHealth(
  state: CanarySoakState,
  rawHealth: unknown,
  markers: string[],
  now: Date,
  config: CanarySoakConfig,
  context: CycleHealthContext,
): void {
  const health = asRecord(rawHealth, 'CANARY_WORKER_HEALTH_INVALID')
  if (containsMarker(JSON.stringify(health), markers)) state.health_privacy_leakages++
  if (health.ready !== true) throw new CanarySoakError('CANARY_WORKER_NOT_READY')
  if (optionalString(health.runtime_id) !== config.runtimeId) {
    throw new CanarySoakError('CANARY_WORKER_RUNTIME_MISMATCH')
  }
  const runtimeRevision = nonNegativeInteger(health.runtime_revision, 'CANARY_WORKER_RUNTIME_REVISION_INVALID')
  if (config.runtimeRevision !== undefined && runtimeRevision !== config.runtimeRevision) {
    throw new CanarySoakError('CANARY_WORKER_RUNTIME_REVISION_MISMATCH')
  }
  if (context.runtimeRevision !== undefined && runtimeRevision !== context.runtimeRevision) {
    throw new CanarySoakError('CANARY_WORKER_RUNTIME_REVISION_MISMATCH')
  }
  context.runtimeRevision = runtimeRevision
  const runtimeMetrics = asOptionalRecord(health.runtime_metrics)
  const pool = asOptionalRecord(runtimeMetrics?.pool)
  if (!pool) throw new CanarySoakError('CANARY_WORKER_POOL_METRICS_MISSING')
  const retiredCounter = nonNegativeInteger(pool.retired_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const createdCounter = nonNegativeInteger(pool.created_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const reusedCounter = nonNegativeInteger(pool.reused_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const crashesCounter = nonNegativeInteger(pool.crashes_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const rejectedCounter = nonNegativeInteger(pool.rejected_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const acquireTimeoutsCounter = nonNegativeInteger(
    pool.acquire_timeouts_total,
    'CANARY_WORKER_POOL_METRICS_INVALID',
  )
  const instanceId = boundedString(health.instance_id, 512, 'CANARY_WORKER_INSTANCE_INVALID')
  const instanceDigest = digest(instanceId)
  if (context.instanceDigests.has(instanceDigest)) {
    throw new CanarySoakError('CANARY_WORKER_INSTANCE_DUPLICATED')
  }
  context.instanceDigests.add(instanceDigest)
  context.instanceIds.add(instanceId)
  const previous = state.worker_samples[instanceDigest]
  const rotations = (previous?.rotations || 0)
    + counterDelta(previous?.retired_counter, retiredCounter)
  const crashes = (previous?.crashes || 0)
    + counterDelta(previous?.crashes_counter, crashesCounter)
  const rejections = (previous?.rejections || 0)
    + counterDelta(previous?.rejected_counter, rejectedCounter)
  const acquireTimeouts = (previous?.acquire_timeouts || 0)
    + counterDelta(previous?.acquire_timeouts_counter, acquireTimeoutsCounter)
  state.worker_samples[instanceDigest] = {
    instance_digest: instanceDigest,
    created_counter: createdCounter,
    reused_counter: reusedCounter,
    retired_counter: retiredCounter,
    rejected_counter: rejectedCounter,
    acquire_timeouts_counter: acquireTimeoutsCounter,
    rotations,
    crashes_counter: crashesCounter,
    crashes,
    rejections,
    acquire_timeouts: acquireTimeouts,
    instances: nonNegativeInteger(pool.instances, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    busy: nonNegativeInteger(pool.busy, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    idle: nonNegativeInteger(pool.idle, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    creating: nonNegativeInteger(pool.creating, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    queued: nonNegativeInteger(pool.queued, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    lanes: nonNegativeInteger(pool.lanes, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    draining: requiredBoolean(pool.draining, 'CANARY_WORKER_POOL_METRICS_INVALID'),
    sampled_at: now.toISOString(),
  }
}

function validateState(raw: unknown, now = new Date()): CanarySoakState {
  assertSanitizedState(raw)
  const value = asRecord(raw, 'CANARY_STATE_INVALID')
  assertOnlyKeys(value, [
    'schema_version', 'profile', 'evidence_class', 'config_fingerprint', 'window_started_at',
    'last_attempt_at', 'last_sample_at', 'next_due_at', 'continuity_resets', 'completed_cycles',
    'incomplete_cycles', 'navigator_poll_failures', 'worker_poll_failures',
    'health_privacy_leakages', 'last_cycle_complete', 'terminal_tasks', 'affinity_violations',
    'worker_samples',
  ], 'CANARY_STATE_INVALID')
  if (value.schema_version !== 3) throw new CanarySoakError('CANARY_STATE_VERSION_UNSUPPORTED')
  if (value.profile !== 'production' && value.profile !== 'local-smoke') {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  const expectedEvidence = value.profile === 'production' ? 'PRODUCTION_CANDIDATE' : 'NON_PRODUCTION_SMOKE'
  if (value.evidence_class !== expectedEvidence) throw new CanarySoakError('CANARY_STATE_INVALID')
  if (typeof value.config_fingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(value.config_fingerprint)) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  const windowStartedAt = requireIsoDate(value.window_started_at)
  const lastAttemptAt = requireOptionalIsoDate(value.last_attempt_at)
  const lastSampleAt = requireOptionalIsoDate(value.last_sample_at)
  const nextDueAt = requireIsoDate(value.next_due_at)
  const latestAllowed = now.getTime()
  if (windowStartedAt > latestAllowed
    || (lastAttemptAt !== undefined && lastAttemptAt > latestAllowed)
    || (lastSampleAt !== undefined && lastSampleAt > latestAllowed)) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  if ((lastAttemptAt !== undefined && lastAttemptAt < windowStartedAt)
    || (lastSampleAt !== undefined && (lastSampleAt < windowStartedAt
      || (lastAttemptAt !== undefined && lastSampleAt > lastAttemptAt)))) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  if (lastSampleAt !== undefined && lastAttemptAt === undefined) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  const dueReference = lastAttemptAt ?? windowStartedAt
  if (nextDueAt < dueReference || nextDueAt > latestAllowed + MAX_NEXT_DUE_FUTURE_MS) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  for (const key of [
    'continuity_resets', 'completed_cycles', 'incomplete_cycles', 'navigator_poll_failures',
    'worker_poll_failures', 'health_privacy_leakages',
  ]) {
    nonNegativeInteger(value[key], 'CANARY_STATE_INVALID')
  }
  if (typeof value.last_cycle_complete !== 'boolean') throw new CanarySoakError('CANARY_STATE_INVALID')
  if (value.last_cycle_complete === true && (lastSampleAt === undefined || lastSampleAt !== lastAttemptAt)) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }

  const terminalTasks = asRecord(value.terminal_tasks, 'CANARY_STATE_INVALID')
  for (const [taskDigest, rawTask] of Object.entries(terminalTasks)) {
    if (!/^[a-f0-9]{64}$/.test(taskDigest)) throw new CanarySoakError('CANARY_STATE_INVALID')
    const task = asRecord(rawTask, 'CANARY_STATE_INVALID')
    assertOnlyKeys(
      task,
      ['status', 'internal_error', 'recovery_unknown', 'affinity_mismatch', 'privacy_leakage', 'observed_at'],
      'CANARY_STATE_INVALID',
    )
    if (!['completed', 'failed', 'aborted'].includes(String(task.status))) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
    for (const key of ['internal_error', 'recovery_unknown', 'affinity_mismatch', 'privacy_leakage']) {
      if (typeof task[key] !== 'boolean') throw new CanarySoakError('CANARY_STATE_INVALID')
    }
    const observedAt = requireIsoDate(task.observed_at)
    if (observedAt < windowStartedAt || observedAt > latestAllowed
      || (lastAttemptAt !== undefined && observedAt > lastAttemptAt)) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
  }

  const affinityViolations = asRecord(value.affinity_violations, 'CANARY_STATE_INVALID')
  for (const [taskDigest, rawViolation] of Object.entries(affinityViolations)) {
    if (!/^[a-f0-9]{64}$/.test(taskDigest)) throw new CanarySoakError('CANARY_STATE_INVALID')
    const violation = asRecord(rawViolation, 'CANARY_STATE_INVALID')
    assertOnlyKeys(violation, ['reason', 'observed_at'], 'CANARY_STATE_INVALID')
    if (!['missing_runtime_instance', 'unknown_runtime_instance'].includes(String(violation.reason))) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
    const observedAt = requireIsoDate(violation.observed_at)
    if (observedAt < windowStartedAt || observedAt > latestAllowed
      || (lastAttemptAt !== undefined && observedAt > lastAttemptAt)) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
  }

  const workerSamples = asRecord(value.worker_samples, 'CANARY_STATE_INVALID')
  for (const [workerDigest, rawSample] of Object.entries(workerSamples)) {
    if (!/^[a-f0-9]{64}$/.test(workerDigest)) throw new CanarySoakError('CANARY_STATE_INVALID')
    const sample = asRecord(rawSample, 'CANARY_STATE_INVALID')
    assertOnlyKeys(
      sample,
      [
        'instance_digest', 'created_counter', 'reused_counter', 'retired_counter', 'rejected_counter',
        'acquire_timeouts_counter', 'rotations', 'crashes_counter', 'crashes', 'rejections',
        'acquire_timeouts', 'instances', 'busy', 'idle', 'creating', 'queued', 'lanes', 'draining',
        'sampled_at',
      ],
      'CANARY_STATE_INVALID',
    )
    if (typeof sample.instance_digest !== 'string' || !/^[a-f0-9]{64}$/.test(sample.instance_digest)) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
    for (const key of [
      'created_counter', 'reused_counter', 'retired_counter', 'rejected_counter',
      'acquire_timeouts_counter', 'rotations', 'crashes_counter', 'crashes', 'rejections',
      'acquire_timeouts', 'instances', 'busy', 'idle', 'creating', 'queued', 'lanes',
    ]) {
      nonNegativeInteger(sample[key], 'CANARY_STATE_INVALID')
    }
    if (typeof sample.draining !== 'boolean') throw new CanarySoakError('CANARY_STATE_INVALID')
    const sampledAt = requireIsoDate(sample.sampled_at)
    if (sampledAt < windowStartedAt || sampledAt > latestAllowed
      || (lastAttemptAt !== undefined && sampledAt > lastAttemptAt)) {
      throw new CanarySoakError('CANARY_STATE_INVALID')
    }
  }
  return value as unknown as CanarySoakState
}

function cloneState(state: CanarySoakState): CanarySoakState {
  return structuredClone(state)
}

async function acquireStateLease(stateFile: string, now: Date): Promise<StateLease> {
  const leasePath = `${stateFile}.lease`
  const leaseId = crypto.randomUUID()
  const owner = {
    schema_version: 1,
    lease_id: leaseId,
    hostname: os.hostname(),
    pid: process.pid,
    acquired_at: now.toISOString(),
  }
  await fs.mkdir(path.dirname(stateFile), { recursive: true })
  for (let attempt = 0; attempt < 3; attempt++) {
    let created = false
    try {
      await fs.mkdir(leasePath, { recursive: false, mode: 0o700 })
      created = true
      const ownerFile = path.join(leasePath, 'owner.json')
      const handle = await fs.open(ownerFile, 'wx', 0o600)
      try {
        await handle.writeFile(`${JSON.stringify(owner)}\n`, 'utf8')
        await handle.sync()
      } finally {
        await handle.close()
      }
      return { leasePath, leaseId }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
        if (created) await fs.rm(leasePath, { recursive: true, force: true }).catch(() => undefined)
        throw new CanarySoakError('CANARY_STATE_LEASE_FAILED')
      }
    }
    const existingOwner = await readLeaseOwner(leasePath)
    if (existingOwner.hostname !== os.hostname() || isProcessAlive(existingOwner.pid)) {
      throw new CanarySoakError('CANARY_STATE_LEASE_HELD')
    }
    const claimed = await claimStateLeaseReclamation(leasePath, existingOwner, now)
    if (!claimed) continue
    const confirmedOwner = await readLeaseOwner(leasePath)
    if (!sameLeaseOwner(existingOwner, confirmedOwner)
      || confirmedOwner.hostname !== os.hostname()
      || isProcessAlive(confirmedOwner.pid)) {
      throw new CanarySoakError('CANARY_STATE_LEASE_HELD')
    }
    const stalePath = `${leasePath}.stale.${crypto.randomUUID()}`
    try {
      await fs.rename(leasePath, stalePath)
      const movedOwner = await readLeaseOwner(stalePath)
      if (!sameLeaseOwner(existingOwner, movedOwner)) {
        throw new CanarySoakError('CANARY_STATE_LEASE_FAILED')
      }
      await fs.rm(stalePath, { recursive: true, force: true })
    } catch (error) {
      if (error instanceof CanarySoakError) throw error
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw new CanarySoakError('CANARY_STATE_LEASE_FAILED')
      }
      throw new CanarySoakError('CANARY_STATE_LEASE_HELD')
    }
  }
  throw new CanarySoakError('CANARY_STATE_LEASE_HELD')
}

async function claimStateLeaseReclamation(
  leasePath: string,
  observedOwner: StateLeaseOwner,
  now: Date,
): Promise<boolean> {
  const claimPath = path.join(leasePath, 'reclaim.lock')
  let handle: fs.FileHandle | undefined
  try {
    handle = await fs.open(claimPath, 'wx', 0o600)
    await handle.writeFile(`${JSON.stringify({
      schema_version: 1,
      claim_id: crypto.randomUUID(),
      observed_lease_id: observedOwner.leaseId,
      hostname: os.hostname(),
      pid: process.pid,
      claimed_at: now.toISOString(),
    })}\n`, 'utf8')
    await handle.sync()
    return true
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code === 'EEXIST') throw new CanarySoakError('CANARY_STATE_LEASE_HELD')
    if (code === 'ENOENT') return false
    throw new CanarySoakError('CANARY_STATE_LEASE_FAILED')
  } finally {
    await handle?.close().catch(() => undefined)
  }
}

async function readLeaseOwner(leasePath: string): Promise<StateLeaseOwner> {
  let owner: Record<string, unknown>
  try {
    owner = asRecord(JSON.parse(await fs.readFile(path.join(leasePath, 'owner.json'), 'utf8')), '')
  } catch {
    throw new CanarySoakError('CANARY_STATE_LEASE_INVALID')
  }
  if (owner.schema_version !== 1 || typeof owner.hostname !== 'string'
    || !Number.isSafeInteger(owner.pid) || Number(owner.pid) <= 0
    || typeof owner.lease_id !== 'string' || !owner.lease_id
    || typeof owner.acquired_at !== 'string' || !Number.isFinite(Date.parse(owner.acquired_at))) {
    throw new CanarySoakError('CANARY_STATE_LEASE_INVALID')
  }
  return { leaseId: owner.lease_id, hostname: owner.hostname, pid: Number(owner.pid) }
}

function sameLeaseOwner(left: StateLeaseOwner, right: StateLeaseOwner): boolean {
  return left.leaseId === right.leaseId && left.hostname === right.hostname && left.pid === right.pid
}

async function releaseStateLease(lease: StateLease): Promise<void> {
  try {
    await assertStateLeaseOwned(lease)
    await fs.rm(lease.leasePath, { recursive: true })
  } catch (error) {
    if (error instanceof CanarySoakError) throw error
    throw new CanarySoakError('CANARY_STATE_LEASE_RELEASE_FAILED')
  }
}

async function assertStateLeaseOwned(lease: StateLease): Promise<void> {
  try {
    const owner = asRecord(
      JSON.parse(await fs.readFile(path.join(lease.leasePath, 'owner.json'), 'utf8')),
      'CANARY_STATE_LEASE_INVALID',
    )
    if (owner.lease_id !== lease.leaseId) throw new CanarySoakError('CANARY_STATE_LEASE_LOST')
  } catch (error) {
    if (error instanceof CanarySoakError) throw error
    throw new CanarySoakError('CANARY_STATE_LEASE_LOST')
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException).code !== 'ESRCH'
  }
}

function requiredEnvironmentSecret(env: NodeJS.ProcessEnv, name: string, code: string): string {
  const value = env[name]?.trim() || ''
  if (value.length < 8 || /[\r\n]/.test(value)) throw new CanarySoakError(code)
  return value
}

function containsMarker(value: string, markers: string[]): boolean {
  return markers.some(marker => value.includes(marker))
}

function rejectEmbeddedSecrets(value: Record<string, unknown>): void {
  const visit = (candidate: unknown, key = ''): void => {
    if (key && /(?:token|secret|password|authorization|api[_-]?key)/i.test(key) && !/(?:Env|EnvNames)$/.test(key)) {
      throw new CanarySoakError('CANARY_CONFIG_EMBEDDED_SECRET_FORBIDDEN')
    }
    if (Array.isArray(candidate)) candidate.forEach(item => visit(item))
    else if (candidate && typeof candidate === 'object') {
      Object.entries(candidate).forEach(([childKey, child]) => visit(child, childKey))
    }
  }
  visit(value)
}

function requiredProfile(value: unknown): CanarySoakProfile {
  if (value !== 'production' && value !== 'local-smoke') throw new CanarySoakError('CANARY_CONFIG_PROFILE_INVALID')
  return value
}

function validateTasksPath(tasksPath: string, navigatorBaseUrl: string): void {
  if (!tasksPath.startsWith('/') || tasksPath.includes('//') || tasksPath.includes('\\')
    || tasksPath.includes('?') || tasksPath.includes('#')) {
    throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
  }
  for (const rawSegment of tasksPath.split('/')) {
    let decoded: string
    try {
      decoded = decodeURIComponent(rawSegment)
    } catch {
      throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
    }
    if (decoded === '.' || decoded === '..' || decoded.includes('/') || decoded.includes('\\')) {
      throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
    }
  }
  const base = new URL(navigatorBaseUrl)
  const resolved = new URL(tasksPath, `${navigatorBaseUrl}/`)
  if (resolved.origin !== base.origin) throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
}

function assertProductionTransport(rawUrl: string): void {
  const url = new URL(rawUrl)
  if (url.protocol === 'https:' || isLoopbackHost(url.hostname)) return
  throw new CanarySoakError('CANARY_CONFIG_PRODUCTION_INSECURE_TRANSPORT')
}

function isLoopbackHost(hostname: string): boolean {
  const normalized = hostname.toLowerCase()
  if (normalized === 'localhost' || normalized === '[::1]' || normalized === '::1') return true
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(normalized)
  return Boolean(match && Number(match[1]) === 127 && match.slice(1).every(part => Number(part) <= 255))
}

function isUltraModel(model: string): boolean {
  return PRODUCTION_ULTRA_MODELS.has(model.trim().toLowerCase())
}

function normalizedHttpUrl(value: unknown, requireHealthPath: boolean): string {
  const raw = boundedString(value, 2_048, 'CANARY_CONFIG_URL_INVALID')
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new CanarySoakError('CANARY_CONFIG_URL_INVALID')
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new CanarySoakError('CANARY_CONFIG_URL_INVALID')
  }
  if (requireHealthPath && !url.pathname.endsWith('/health')) {
    throw new CanarySoakError('CANARY_CONFIG_HEALTH_URL_INVALID')
  }
  return url.toString().replace(/\/$/, '')
}

function environmentName(value: unknown, code: string): string {
  const name = boundedString(value, 128, code)
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) throw new CanarySoakError(code)
  return name
}

function integer(value: unknown, fallback: number, min: number, max: number, code: string): number {
  if (value === undefined) return fallback
  const result = numeric(value, min, max, code)
  if (!Number.isInteger(result)) throw new CanarySoakError(code)
  return result
}

function optionalInteger(value: unknown, min: number, max: number, code: string): number | undefined {
  if (value === undefined) return undefined
  const result = numeric(value, min, max, code)
  if (!Number.isInteger(result)) throw new CanarySoakError(code)
  return result
}

function nonNegativeInteger(value: unknown, code: string): number {
  const result = numeric(value, 0, Number.MAX_SAFE_INTEGER, code)
  if (!Number.isInteger(result)) throw new CanarySoakError(code)
  return result
}

function optionalEpochMillis(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value
    : Number.NaN
}

function requiredBoolean(value: unknown, code: string): boolean {
  if (typeof value !== 'boolean') throw new CanarySoakError(code)
  return value
}

function counterDelta(previous: number | undefined, current: number): number {
  if (previous === undefined) return 0
  return current >= previous ? current - previous : current
}

function numeric(value: unknown, min: number, max: number, code: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
    throw new CanarySoakError(code)
  }
  return value
}

function boundedString(value: unknown, max: number, code: string): string {
  if (typeof value !== 'string') throw new CanarySoakError(code)
  const result = value.trim()
  if (!result || result.length > max || /[\r\n]/.test(result)) throw new CanarySoakError(code)
  return result
}

function optionalBoundedString(value: unknown, max: number, code: string): string | undefined {
  if (value === undefined || value === null || value === '') return undefined
  return boundedString(value, max, code)
}

function optionalString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function optionalStringArray(value: unknown): string[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) {
    throw new CanarySoakError('CANARY_CONFIG_STRING_ARRAY_INVALID')
  }
  return [...new Set(value)]
}

function requiredArray(value: unknown, code: string): unknown[] {
  if (!Array.isArray(value)) throw new CanarySoakError(code)
  return value
}

function asRecord(value: unknown, code: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new CanarySoakError(code)
  return value as Record<string, unknown>
}

function asOptionalRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function assertOnlyKeys(value: Record<string, unknown>, allowed: string[], code: string): void {
  const allowedKeys = new Set(allowed)
  if (Object.keys(value).some(key => !allowedKeys.has(key))) throw new CanarySoakError(code)
}

function requireIsoDate(value: unknown): number {
  if (typeof value !== 'string') {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed) || new Date(parsed).toISOString() !== value) {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  return parsed
}

function requireOptionalIsoDate(value: unknown): number | undefined {
  return value === undefined ? undefined : requireIsoDate(value)
}

function stableErrorCode(error: unknown, fallback: string): string {
  return error instanceof CanarySoakError ? error.code : fallback
}

function digest(value: string): string {
  return crypto.createHash('sha256').update(value).digest('hex')
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => `${JSON.stringify(key)}:${canonicalJson(child)}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}
