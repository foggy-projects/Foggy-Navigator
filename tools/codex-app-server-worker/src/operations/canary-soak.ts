import crypto from 'node:crypto'
import fs from 'node:fs/promises'
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
    workerId?: string
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
  workerId?: string
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
  affinity_mismatch: boolean
  privacy_leakage: boolean
  observed_at: string
}

export interface SanitizedWorkerSample {
  instance_digest: string
  retired_counter: number
  rotations: number
  crashes_counter: number
  sampled_at: string
}

export interface CanarySoakState {
  schema_version: 1
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
  affinityMismatches: number
  privacyLeakages: number
  poolRotations: number
  observationHours: number
  successRate: number
  internalErrorRate: number
  checks: Record<string, boolean>
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
const INTERNAL_ERROR = /^(?:APP_SERVER_|CODEX_(?:RUNTIME|WORKER|STREAM|TASK_ACCEPTANCE)_)/i
const AFFINITY_ERROR = /AFFINITY/i
const STATE_FORBIDDEN_KEY = /(?:^|_)(?:token|secret|password|authorization|prompt|result|error_message|endpoint|url|marker)(?:_|$)/i

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
  rejectEmbeddedSecrets(value)
  const profile = requiredProfile(value.profile)
  const navigator = asRecord(value.navigator, 'CANARY_CONFIG_NAVIGATOR_INVALID')
  const workers = requiredArray(value.workers, 'CANARY_CONFIG_WORKERS_INVALID')
  const navigatorBaseUrl = normalizedHttpUrl(navigator.baseUrl, false)
  const navigatorTasksPath = optionalString(navigator.tasksPath) || '/api/v1/codex-tasks'
  if (!navigatorTasksPath.startsWith('/') || navigatorTasksPath.includes('?') || navigatorTasksPath.includes('#')) {
    throw new CanarySoakError('CANARY_CONFIG_TASKS_PATH_INVALID')
  }
  const navigatorTokenEnv = environmentName(navigator.tokenEnv, 'CANARY_CONFIG_TOKEN_ENV_INVALID')
  const runtimeId = boundedString(navigator.runtimeId, 128, 'CANARY_CONFIG_RUNTIME_ID_INVALID')
  const workerId = optionalBoundedString(navigator.workerId, 128, 'CANARY_CONFIG_WORKER_ID_INVALID')
  const workerHealthUrls = [...new Set(workers.map((worker) => {
    const record = asRecord(worker, 'CANARY_CONFIG_WORKER_INVALID')
    return normalizedHttpUrl(record.healthUrl, true)
  }))]
  if (workerHealthUrls.length === 0) throw new CanarySoakError('CANARY_CONFIG_WORKERS_REQUIRED')

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
    worker_id: workerId || null,
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
    workerId,
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
  const resolved = { ...baseline }
  for (const key of Object.keys(overrides)) {
    if (!(key in baseline)) throw new CanarySoakError('CANARY_CONFIG_THRESHOLD_UNKNOWN')
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
    schema_version: 1,
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
    worker_samples: {},
  }
}

export async function readCanarySoakState(config: CanarySoakConfig): Promise<CanarySoakState | undefined> {
  let raw: unknown
  try {
    raw = JSON.parse(await fs.readFile(config.stateFile, 'utf8'))
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined
    throw new CanarySoakError('CANARY_STATE_READ_FAILED')
  }
  const state = validateState(raw)
  if (state.profile !== config.profile || state.config_fingerprint !== config.fingerprint) {
    throw new CanarySoakError('CANARY_STATE_CONFIG_MISMATCH')
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
  if (!isSampleDue(existingState, now)) {
    return { state: existingState!, due: false, cycleComplete: existingState!.last_cycle_complete, errorCodes: [] }
  }
  let state = existingState || createInitialState(config, now)
  const lastSuccess = state.last_sample_at ? Date.parse(state.last_sample_at) : undefined
  if (lastSuccess !== undefined && Number.isFinite(lastSuccess) && now.getTime() - lastSuccess > config.maxSampleGapMs) {
    state = createInitialState(config, now, state.continuity_resets + 1)
  }
  const fetchImpl = dependencies.fetch || fetch
  const env = dependencies.env || process.env
  const token = requiredEnvironmentSecret(env, config.navigatorTokenEnv, 'CANARY_NAVIGATOR_TOKEN_MISSING')
  const markers = config.privacyMarkerEnvNames.map(name =>
    requiredEnvironmentSecret(env, name, 'CANARY_PRIVACY_MARKER_MISSING'))
  const nextState = cloneState(state)
  const iso = now.toISOString()
  nextState.last_attempt_at = iso
  nextState.next_due_at = new Date(now.getTime() + config.sampleIntervalMs).toISOString()
  const errorCodes: string[] = []

  let navigatorComplete = false
  try {
    const tasks = await fetchNavigatorTasks(config, token, fetchImpl)
    incorporateTasks(nextState, config, tasks, markers, now)
    navigatorComplete = true
  } catch (error) {
    nextState.navigator_poll_failures++
    errorCodes.push(stableErrorCode(error, 'CANARY_NAVIGATOR_POLL_FAILED'))
  }

  let workersComplete = true
  for (const healthUrl of config.workerHealthUrls) {
    try {
      const health = await fetchJson(healthUrl, {}, config.requestTimeoutMs, fetchImpl, 'CANARY_WORKER_HEALTH_FAILED')
      incorporateWorkerHealth(nextState, healthUrl, health, markers, now)
    } catch (error) {
      workersComplete = false
      nextState.worker_poll_failures++
      errorCodes.push(stableErrorCode(error, 'CANARY_WORKER_HEALTH_FAILED'))
    }
  }
  const cycleComplete = navigatorComplete && workersComplete
  nextState.last_cycle_complete = cycleComplete
  if (cycleComplete) {
    nextState.completed_cycles++
    nextState.last_sample_at = iso
  } else {
    nextState.incomplete_cycles++
  }
  assertSanitizedState(nextState, [token, ...markers])
  await writeAtomicCanarySoakState(config.stateFile, nextState, [token, ...markers])
  return { state: nextState, due: true, cycleComplete, errorCodes: [...new Set(errorCodes)] }
}

export function evaluateCanarySoakGate(
  state: CanarySoakState,
  thresholds: GateThresholds,
): GateResult {
  const tasks = Object.values(state.terminal_tasks)
  const terminalTasks = tasks.length
  const completedTasks = tasks.filter(task => task.status === 'completed').length
  const failedTasks = tasks.filter(task => task.status === 'failed').length
  const abortedTasks = tasks.filter(task => task.status === 'aborted').length
  const internalErrors = tasks.filter(task => task.internal_error).length
  const affinityMismatches = tasks.filter(task => task.affinity_mismatch).length
  const privacyLeakages = tasks.filter(task => task.privacy_leakage).length + state.health_privacy_leakages
  const poolRotations = Object.values(state.worker_samples).reduce((sum, sample) => sum + sample.rotations, 0)
  const end = state.last_sample_at ? Date.parse(state.last_sample_at) : Date.parse(state.window_started_at)
  const start = Date.parse(state.window_started_at)
  const observationHours = Math.max(0, end - start) / 3_600_000
  const successRate = terminalTasks === 0 ? 0 : completedTasks / terminalTasks
  const internalErrorRate = terminalTasks === 0 ? 0 : internalErrors / terminalTasks
  const checks = {
    has_complete_sample: state.completed_cycles > 0 && state.last_cycle_complete,
    terminal_tasks: terminalTasks >= thresholds.minTerminalTasks,
    observation_window: observationHours >= thresholds.minObservationHours,
    pool_rotations: poolRotations >= thresholds.minPoolRotations,
    success_rate: successRate >= thresholds.minSuccessRate,
    internal_error_rate: internalErrorRate <= thresholds.maxInternalErrorRate,
    affinity_mismatch: affinityMismatches <= thresholds.maxAffinityMismatches,
    privacy_leakage: privacyLeakages <= thresholds.maxPrivacyLeakages,
  }
  return {
    passed: Object.values(checks).every(Boolean),
    productionEvidenceEligible: state.profile === 'production',
    terminalTasks,
    completedTasks,
    failedTasks,
    abortedTasks,
    internalErrors,
    affinityMismatches,
    privacyLeakages,
    poolRotations,
    observationHours,
    successRate,
    internalErrorRate,
    checks,
  }
}

export function renderCanarySoakReport(state: CanarySoakState, thresholds: GateThresholds): string {
  const gate = evaluateCanarySoakGate(state, thresholds)
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
    `window_started_at: ${state.window_started_at}`,
    `last_successful_sample_at: ${state.last_sample_at || 'none'}`,
    `continuity_resets: ${state.continuity_resets}`,
    `terminal_tasks: ${gate.terminalTasks}/${thresholds.minTerminalTasks}`,
    `completed_failed_aborted: ${gate.completedTasks}/${gate.failedTasks}/${gate.abortedTasks}`,
    `observation_hours: ${gate.observationHours.toFixed(2)}/${thresholds.minObservationHours}`,
    `pool_rotations: ${gate.poolRotations}/${thresholds.minPoolRotations}`,
    `success_rate: ${(gate.successRate * 100).toFixed(2)}%/${(thresholds.minSuccessRate * 100).toFixed(2)}%`,
    `internal_errors: ${gate.internalErrors} (${(gate.internalErrorRate * 100).toFixed(2)}%)`,
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
): Promise<void> {
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
  now: Date,
): void {
  const windowStart = Date.parse(state.window_started_at)
  for (const task of tasks) {
    const status = optionalString(task.status).toUpperCase()
    if (!TERMINAL_STATUSES.has(status)) continue
    if (optionalString(task.runtimeId) !== config.runtimeId) continue
    if (config.workerId && optionalString(task.workerId) !== config.workerId) continue
    const createdAt = Date.parse(optionalString(task.createdAt))
    if (!Number.isFinite(createdAt) || createdAt < windowStart) continue
    const taskId = optionalString(task.taskId)
    if (!taskId) continue
    const taskDigest = digest(`${config.runtimeId}\0${taskId}`)
    if (state.terminal_tasks[taskDigest]) continue
    const errorMessage = optionalString(task.errorMessage)
    const resultText = optionalString(task.resultText)
    const runtimeType = optionalString(task.runtimeType).toUpperCase()
    const instanceId = optionalString(task.runtimeInstanceId)
    state.terminal_tasks[taskDigest] = {
      status: status.toLowerCase() as TerminalTaskStatus,
      internal_error: status === 'FAILED' && INTERNAL_ERROR.test(errorMessage),
      affinity_mismatch: AFFINITY_ERROR.test(errorMessage) || (runtimeType === 'APP_SERVER' && !instanceId),
      privacy_leakage: containsMarker(errorMessage, markers) || containsMarker(resultText, markers),
      observed_at: now.toISOString(),
    }
  }
}

function incorporateWorkerHealth(
  state: CanarySoakState,
  healthUrl: string,
  rawHealth: unknown,
  markers: string[],
  now: Date,
): void {
  const health = asRecord(rawHealth, 'CANARY_WORKER_HEALTH_INVALID')
  if (containsMarker(JSON.stringify(health), markers)) state.health_privacy_leakages++
  const runtimeMetrics = asOptionalRecord(health.runtime_metrics)
  const pool = asOptionalRecord(runtimeMetrics?.pool)
  if (!pool) throw new CanarySoakError('CANARY_WORKER_POOL_METRICS_MISSING')
  const retiredCounter = nonNegativeInteger(pool.retired_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const crashesCounter = nonNegativeInteger(pool.crashes_total, 'CANARY_WORKER_POOL_METRICS_INVALID')
  const instanceId = boundedString(health.instance_id, 512, 'CANARY_WORKER_INSTANCE_INVALID')
  const workerDigest = digest(healthUrl)
  const previous = state.worker_samples[workerDigest]
  const rotations = (previous?.rotations || 0)
    + (previous && retiredCounter >= previous.retired_counter ? retiredCounter - previous.retired_counter : 0)
  state.worker_samples[workerDigest] = {
    instance_digest: digest(instanceId),
    retired_counter: retiredCounter,
    rotations,
    crashes_counter: crashesCounter,
    sampled_at: now.toISOString(),
  }
}

function validateState(raw: unknown): CanarySoakState {
  assertSanitizedState(raw)
  const value = asRecord(raw, 'CANARY_STATE_INVALID')
  if (value.schema_version !== 1) throw new CanarySoakError('CANARY_STATE_VERSION_UNSUPPORTED')
  if (value.profile !== 'production' && value.profile !== 'local-smoke') {
    throw new CanarySoakError('CANARY_STATE_INVALID')
  }
  if (!value.terminal_tasks || !value.worker_samples) throw new CanarySoakError('CANARY_STATE_INVALID')
  return value as unknown as CanarySoakState
}

function cloneState(state: CanarySoakState): CanarySoakState {
  return structuredClone(state)
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

function nonNegativeInteger(value: unknown, code: string): number {
  const result = numeric(value, 0, Number.MAX_SAFE_INTEGER, code)
  if (!Number.isInteger(result)) throw new CanarySoakError(code)
  return result
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
