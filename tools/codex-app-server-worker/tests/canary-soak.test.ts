import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  CanarySoakError,
  assertSanitizedState,
  createInitialState,
  evaluateCanarySoakGate,
  isSampleDue,
  readCanarySoakState,
  renderCanarySoakReport,
  resolveCanarySoakConfig,
  resolveGateThresholds,
  sampleCanarySoak,
  writeAtomicCanarySoakState,
  type CanarySoakConfig,
} from '../src/operations/canary-soak.js'
import { tempDirectory } from './helpers.js'

test('production gate calculates terminal outcomes, rotations, and zero-tolerance findings', () => {
  const config = productionConfig('C:\canary-state.json')
  const state = createInitialState(config, new Date('2026-07-01T00:00:00.000Z'))
  state.last_sample_at = '2026-07-04T00:00:00.000Z'
  state.last_cycle_complete = true
  state.completed_cycles = 216
  for (let index = 0; index < 50; index++) {
    state.terminal_tasks[`digest-${index}`] = {
      status: index === 49 ? 'failed' : 'completed',
      internal_error: false,
      affinity_mismatch: false,
      privacy_leakage: false,
      observed_at: '2026-07-04T00:00:00.000Z',
    }
  }
  state.worker_samples.worker = {
    instance_digest: 'instance-digest',
    created_counter: 2,
    reused_counter: 48,
    retired_counter: 2,
    rejected_counter: 0,
    acquire_timeouts_counter: 0,
    rotations: 2,
    crashes_counter: 0,
    crashes: 0,
    rejections: 0,
    acquire_timeouts: 0,
    instances: 1,
    busy: 0,
    idle: 1,
    creating: 0,
    queued: 0,
    lanes: 1,
    draining: false,
    sampled_at: state.last_sample_at,
  }

  const passing = evaluateCanarySoakGate(state, config.thresholds)
  assert.equal(passing.passed, true)
  assert.equal(passing.successRate, 0.98)
  assert.equal(passing.terminalTasks, 50)
  assert.equal(passing.poolRotations, 2)
  const stale = evaluateCanarySoakGate(state, config.thresholds, {
    now: new Date('2026-07-04T00:20:00.000Z'),
    maxSampleGapMs: 600_000,
  })
  assert.equal(stale.passed, false)
  assert.equal(stale.checks.checkpoint_fresh, false)

  state.terminal_tasks['digest-49'].internal_error = true
  state.terminal_tasks['digest-49'].affinity_mismatch = true
  const failing = evaluateCanarySoakGate(state, config.thresholds)
  assert.equal(failing.passed, false)
  assert.equal(failing.internalErrorRate, 0.02)
  assert.equal(failing.checks.internal_error_rate, false)
  assert.equal(failing.checks.affinity_mismatch, false)
})

test('production thresholds cannot be lowered but may be tightened', () => {
  assert.throws(
    () => resolveGateThresholds('production', { minTerminalTasks: 49 }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_THRESHOLD_WEAKENED',
  )
  assert.throws(
    () => resolveGateThresholds('production', { maxAffinityMismatches: 1 }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_THRESHOLD_WEAKENED',
  )
  const tightened = resolveGateThresholds('production', {
    minTerminalTasks: 75,
    minObservationHours: 96,
    maxInternalErrorRate: 0.005,
  })
  assert.equal(tightened.minTerminalTasks, 75)
  assert.equal(tightened.minObservationHours, 96)
  assert.equal(tightened.minPoolRotations, 2)
  assert.equal(tightened.maxInternalErrorRate, 0.005)
})

test('configuration rejects embedded credentials and unsafe production evidence settings', () => {
  const raw = localRawConfig('state.json')
  assert.throws(
    () => resolveCanarySoakConfig({ ...raw, navigator: { ...raw.navigator, token: 'embedded-secret' } }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_EMBEDDED_SECRET_FORBIDDEN',
  )
  const production = {
    ...raw,
    profile: 'production',
    privacyMarkerEnvNames: ['CODEX_CANARY_PRIVACY_MARKER'],
    maxSampleGapSeconds: 901,
  }
  assert.throws(
    () => resolveCanarySoakConfig(production),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_SAMPLE_GAP_TOO_LARGE',
  )
  assert.throws(
    () => resolveCanarySoakConfig({ ...production, maxSampleGapSeconds: 600, privacyMarkerEnvNames: [] }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_MARKERS_REQUIRED',
  )
})

test('atomic checkpoint contains only sanitized digests and leaves no temporary file', async () => {
  const directory = await tempDirectory('codex-canary-state-')
  const stateFile = path.join(directory, 'checkpoint.json')
  const config = localConfig(stateFile)
  const state = createInitialState(config, new Date('2026-07-10T00:00:00.000Z'))
  state.terminal_tasks['a'.repeat(64)] = {
    status: 'completed',
    internal_error: false,
    affinity_mismatch: false,
    privacy_leakage: false,
    observed_at: '2026-07-10T00:01:00.000Z',
  }
  await writeAtomicCanarySoakState(stateFile, state, ['navigator-secret-value'])

  const persisted = await fs.readFile(stateFile, 'utf8')
  assert.doesNotMatch(persisted, /navigator-secret-value/)
  assert.deepEqual(await fs.readdir(directory), ['checkpoint.json'])
  assert.throws(
    () => assertSanitizedState({ ...state, navigator_token: 'navigator-secret-value' }, ['navigator-secret-value']),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_STATE_UNSAFE_FIELD',
  )
  const forbiddenFingerprintFragment = state.config_fingerprint.slice(0, 12)
  await assert.rejects(
    writeAtomicCanarySoakState(stateFile, state, [forbiddenFingerprintFragment]),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_STATE_SECRET_DETECTED',
  )
})

test('once sampling resumes checkpoints, skips before due time, and accumulates pool rotations', async () => {
  const directory = await tempDirectory('codex-canary-resume-')
  const config = localConfig(path.join(directory, 'checkpoint.json'))
  const env = {
    NAVIGATOR_CANARY_TOKEN: 'navigator-token-value',
    CODEX_CANARY_PRIVACY_MARKER: 'privacy-marker-value',
  }
  const firstNow = new Date('2026-07-10T00:00:00.000Z')
  let calls = 0
  let retiredTotal = 0
  let tasks = [task('task-one', 'COMPLETED', firstNow.toISOString())]
  const fetchMock = (async (input: string | URL | Request, init?: RequestInit) => {
    calls++
    const url = String(input)
    if (url.includes('/api/v1/codex-tasks')) {
      assert.equal((init?.headers as Record<string, string>).Authorization, 'Bearer navigator-token-value')
      return Response.json({ code: 200, data: tasks })
    }
    return Response.json({
      ready: true,
      runtime_id: 'runtime-canary',
      instance_id: 'worker-instance-raw',
      runtime_metrics: {
        pool: {
          instances: 1,
          busy: 0,
          idle: 1,
          creating: 0,
          queued: 0,
          lanes: 1,
          draining: false,
          created_total: 2,
          reused_total: 1,
          retired_total: retiredTotal,
          crashes_total: 0,
          rejected_total: 0,
          acquire_timeouts_total: 0,
        },
      },
    })
  }) as typeof fetch

  const first = await sampleCanarySoak(config, undefined, { fetch: fetchMock, env, now: () => firstNow })
  assert.equal(first.due, true)
  assert.equal(first.cycleComplete, true)
  assert.equal(calls, 2)
  assert.equal(Object.keys(first.state.terminal_tasks).length, 1)
  const serialized = await fs.readFile(config.stateFile, 'utf8')
  assert.doesNotMatch(serialized, /task-one|worker-instance-raw|navigator-token-value|privacy-marker-value/)

  const resumed = await readCanarySoakState(config)
  assert.ok(resumed)
  const beforeDue = new Date('2026-07-10T00:00:30.000Z')
  assert.equal(isSampleDue(resumed, beforeDue), false)
  const skipped = await sampleCanarySoak(config, resumed, { fetch: fetchMock, env, now: () => beforeDue })
  assert.equal(skipped.due, false)
  assert.equal(calls, 2)

  retiredTotal = 2
  tasks = [
    ...tasks,
    task('task-two', 'FAILED', '2026-07-10T00:01:00.000Z', 'CODEX_RUNTIME_AFFINITY_MISMATCH', 'privacy-marker-value'),
  ]
  const due = new Date('2026-07-10T00:01:01.000Z')
  const second = await sampleCanarySoak(config, skipped.state, { fetch: fetchMock, env, now: () => due })
  assert.equal(calls, 4)
  assert.equal(Object.keys(second.state.terminal_tasks).length, 2)
  assert.equal(Object.values(second.state.worker_samples)[0].rotations, 2)
  const classified = Object.values(second.state.terminal_tasks).find(value => value.status === 'failed')
  assert.equal(classified?.internal_error, true)
  assert.equal(classified?.affinity_mismatch, true)
  assert.equal(classified?.privacy_leakage, true)

  const afterGap = new Date('2026-07-10T00:07:00.000Z')
  const reset = await sampleCanarySoak(config, second.state, { fetch: fetchMock, env, now: () => afterGap })
  assert.equal(reset.state.continuity_resets, 1)
  assert.equal(reset.state.window_started_at, afterGap.toISOString())
  assert.equal(Object.keys(reset.state.terminal_tasks).length, 0)
  assert.equal(Object.values(reset.state.worker_samples)[0].rotations, 0)
})

test('local smoke report is permanently marked as non-production evidence', () => {
  const config = localConfig('C:\local-smoke.json')
  const report = renderCanarySoakReport(
    createInitialState(config, new Date('2026-07-10T00:00:00.000Z')),
    config.thresholds,
  )
  assert.match(report.split('\n')[0], /CANNOT BE USED AS PRODUCTION EVIDENCE/)
  assert.match(report, /production_evidence_eligible: false/)
})

function task(
  taskId: string,
  status: string,
  createdAt: string,
  errorMessage = '',
  resultText = '',
): Record<string, unknown> {
  return {
    taskId,
    status,
    createdAt,
    runtimeId: 'runtime-canary',
    runtimeType: 'APP_SERVER',
    runtimeInstanceId: 'instance-canary',
    workerId: 'worker-canary',
    errorMessage,
    resultText,
  }
}

function localRawConfig(stateFile: string): Record<string, unknown> {
  return {
    profile: 'local-smoke',
    navigator: {
      baseUrl: 'http://127.0.0.1:8112',
      tokenEnv: 'NAVIGATOR_CANARY_TOKEN',
      runtimeId: 'runtime-canary',
      workerId: 'worker-canary',
    },
    workers: [{ healthUrl: 'http://127.0.0.1:3062/health' }],
    stateFile,
    sampleIntervalSeconds: 60,
    maxSampleGapSeconds: 300,
    privacyMarkerEnvNames: ['CODEX_CANARY_PRIVACY_MARKER'],
  }
}

function localConfig(stateFile: string): CanarySoakConfig {
  return resolveCanarySoakConfig(localRawConfig(stateFile))
}

function productionConfig(stateFile: string): CanarySoakConfig {
  return resolveCanarySoakConfig({ ...localRawConfig(stateFile), profile: 'production' })
}
