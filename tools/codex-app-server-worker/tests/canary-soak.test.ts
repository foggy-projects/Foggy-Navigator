import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import test from 'node:test'
import {
  CanarySoakError,
  assertSanitizedState,
  createInitialState,
  evaluateCanarySoakGate,
  isSampleDue,
  readCanarySoakState,
  resetCanarySoakState,
  renderCanarySoakReport,
  resolveCanarySoakConfig,
  resolveGateThresholds,
  sampleCanarySoak,
  writeAtomicCanarySoakState,
  type CanarySoakConfig,
} from '../src/operations/canary-soak.js'
import { runCanarySoakCli } from '../src/operations/canary-soak-cli.js'
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
      recovery_unknown: false,
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
  assert.equal(passing.passed, false)
  assert.equal(passing.checks.external_production_evidence, false)
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
  const production = productionRawConfig('state.json')
  production.maxSampleGapSeconds = 901
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
  assert.throws(
    () => resolveCanarySoakConfig({ ...raw, profile: 'production' }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_COHORT_INCOMPLETE',
  )
  for (const tasksPath of ['//evil.example/tasks', '/api\\tasks', '/api/../tasks', '/api/%2e%2e/tasks']) {
    assert.throws(
      () => resolveCanarySoakConfig({
        ...raw,
        navigator: { ...(raw.navigator as Record<string, unknown>), tasksPath },
      }),
      (error: unknown) => error instanceof CanarySoakError
        && error.code === 'CANARY_CONFIG_TASKS_PATH_INVALID',
    )
  }
  assert.throws(
    () => resolveCanarySoakConfig({
      ...raw,
      workers: Array.from({ length: 17 }, (_, index) => ({ healthUrl: `http://127.0.0.1:${3000 + index}/health` })),
    }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_WORKERS_INVALID',
  )
  assert.throws(
    () => resolveCanarySoakConfig({ ...raw, workers: [] }),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_WORKERS_INVALID',
  )
  const wrongProvider = productionRawConfig('state.json')
  wrongProvider.navigator = {
    ...(wrongProvider.navigator as Record<string, unknown>),
    providerType: 'codex-biz-worker',
  }
  assert.throws(
    () => resolveCanarySoakConfig(wrongProvider),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_PROVIDER_INVALID',
  )
  const wrongModel = productionRawConfig('state.json')
  wrongModel.navigator = { ...(wrongModel.navigator as Record<string, unknown>), model: 'codex-max' }
  assert.throws(
    () => resolveCanarySoakConfig(wrongModel),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_MODEL_NOT_ULTRA',
  )
  const insecure = productionRawConfig('state.json')
  insecure.navigator = {
    ...(insecure.navigator as Record<string, unknown>),
    baseUrl: 'http://navigator.example.test',
  }
  assert.throws(
    () => resolveCanarySoakConfig(insecure),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_INSECURE_TRANSPORT',
  )
  const insecureWorker = productionRawConfig('state.json')
  insecureWorker.workers = [{ healthUrl: 'http://worker.example.test/health' }]
  assert.throws(
    () => resolveCanarySoakConfig(insecureWorker),
    (error: unknown) => error instanceof CanarySoakError
      && error.code === 'CANARY_CONFIG_PRODUCTION_INSECURE_TRANSPORT',
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
    recovery_unknown: false,
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
      runtime_revision: 1,
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

test('production sampling counts only the exact healthy Ultra cohort and classifies all internal failures', async () => {
  const directory = await tempDirectory('codex-canary-cohort-')
  const config = productionConfig(path.join(directory, 'checkpoint.json'))
  const now = new Date()
  const cohortMarker = 'cohort-marker-value'
  const exact = productionTask('exact-completed', now, cohortMarker)
  const tasks = [
    exact,
    { ...productionTask('exact-codex-failure', now, cohortMarker), status: 'FAILED', errorMessage: 'CODEX_AUTH_FAILED' },
    {
      ...productionTask('exact-recovery-failure', now, cohortMarker),
      status: 'FAILED',
      errorMessage: 'APP_SERVER_RECOVERY_UNKNOWN',
    },
    { ...exact, taskId: 'wrong-revision', runtimeRevision: 8 },
    { ...exact, taskId: 'wrong-epoch', routingEpoch: 12 },
    { ...exact, taskId: 'wrong-worker', workerId: 'other-worker' },
    { ...exact, taskId: 'wrong-model', model: 'codex-max' },
    { ...exact, taskId: 'canonical-model-is-not-ultra', model: 'gpt-5.6-sol' },
    { ...exact, taskId: 'wrong-provider', providerType: 'codex-biz-worker' },
    { ...exact, taskId: 'wrong-runtime-type', runtimeType: 'SDK_EXEC' },
    { ...exact, taskId: 'missing-marker', prompt: 'ordinary prompt' },
    { ...exact, taskId: 'unhealthy-instance', runtimeInstanceId: 'other-instance' },
    { ...exact, taskId: 'missing-epoch', createdAtEpochMs: undefined },
    { ...exact, taskId: 'string-epoch', createdAtEpochMs: String(now.getTime()) },
  ]
  const sampled = await sampleCanarySoak(config, undefined, {
    now: () => now,
    env: productionEnv(cohortMarker),
    fetch: (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
      ? Response.json({ code: 200, data: tasks })
      : Response.json(workerHealth('production-instance', 7))) as typeof fetch,
  })

  assert.equal(sampled.cycleComplete, true)
  assert.equal(Object.keys(sampled.state.terminal_tasks).length, 3)
  const gate = evaluateCanarySoakGate(sampled.state, config.thresholds)
  assert.equal(gate.internalErrors, 2)
  assert.equal(gate.recoveryUnknown, 1)
  assert.equal(gate.checks.recovery_unknown, false)
  assert.equal(gate.checks.external_production_evidence, false)
  assert.match(renderCanarySoakReport(sampled.state, config.thresholds), /external_production_evidence: false/)
})

test('invalid runtime instances are deduplicated as sanitized affinity violations outside the terminal denominator', async () => {
  const directory = await tempDirectory('codex-canary-affinity-')
  const config = productionConfig(path.join(directory, 'checkpoint.json'))
  const now = new Date()
  const cohortMarker = 'cohort-marker-value'
  const valid = productionTask('valid-terminal-task', now, cohortMarker)
  const missing = {
    ...productionTask('missing-instance-task', now, cohortMarker),
    runtimeInstanceId: '',
  }
  const unknown = {
    ...productionTask('unknown-instance-task', now, cohortMarker),
    runtimeInstanceId: 'unknown-instance-raw',
  }
  const sampled = await sampleCanarySoak(config, undefined, {
    now: () => now,
    env: productionEnv(cohortMarker),
    fetch: (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
      ? Response.json({ code: 200, data: [valid, missing, unknown, unknown] })
      : Response.json(workerHealth('production-instance', 7))) as typeof fetch,
  })

  assert.equal(Object.keys(sampled.state.terminal_tasks).length, 1)
  assert.equal(Object.keys(sampled.state.affinity_violations).length, 2)
  assert.deepEqual(
    Object.values(sampled.state.affinity_violations).map(violation => violation.reason).sort(),
    ['missing_runtime_instance', 'unknown_runtime_instance'],
  )
  const gate = evaluateCanarySoakGate(sampled.state, config.thresholds)
  assert.equal(gate.terminalTasks, 1)
  assert.equal(gate.affinityMismatches, 2)
  assert.equal(gate.checks.affinity_mismatch, false)

  const serialized = await fs.readFile(config.stateFile, 'utf8')
  assert.doesNotMatch(
    serialized,
    /missing-instance-task|unknown-instance-task|unknown-instance-raw|valid-terminal-task|production-instance/,
  )
})

test('production cohort time is epoch-authoritative across legacy timezone representations', async () => {
  const directory = await tempDirectory('codex-canary-epoch-')
  const config = productionConfig(path.join(directory, 'checkpoint.json'))
  const now = new Date('2026-07-10T10:00:00.000Z')
  const cohortMarker = 'cohort-marker-value'
  const exact = productionTask('offset-created-at', now, cohortMarker)
  const tasks = [
    { ...exact, createdAt: '2026-07-10T18:00:00+08:00' },
    { ...exact, taskId: 'naive-created-at', createdAt: '2026-07-10T18:00:00' },
    { ...exact, taskId: 'legacy-only', createdAt: now.toISOString(), createdAtEpochMs: undefined },
    {
      ...exact,
      taskId: 'outside-window',
      createdAt: now.toISOString(),
      createdAtEpochMs: now.getTime() - 1,
    },
    { ...exact, taskId: 'future-epoch', createdAtEpochMs: now.getTime() + 1 },
  ]
  const sampled = await sampleCanarySoak(config, undefined, {
    now: () => now,
    env: productionEnv(cohortMarker),
    fetch: (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
      ? Response.json({ code: 200, data: tasks })
      : Response.json(workerHealth('production-instance', 7))) as typeof fetch,
  })

  assert.equal(sampled.cycleComplete, true)
  assert.equal(Object.keys(sampled.state.terminal_tasks).length, 2)
})

test('health samples deduplicate actual instances, enforce revision, and continue after counter reset', async () => {
  const duplicateDirectory = await tempDirectory('codex-canary-duplicate-')
  const duplicateRaw = productionRawConfig(path.join(duplicateDirectory, 'checkpoint.json'))
  duplicateRaw.workers = [
    { healthUrl: 'http://127.0.0.1:3062/health' },
    { healthUrl: 'http://127.0.0.1:3063/health' },
  ]
  const duplicateConfig = resolveCanarySoakConfig(duplicateRaw)
  const now = new Date()
  const duplicate = await sampleCanarySoak(duplicateConfig, undefined, {
    now: () => now,
    env: productionEnv('cohort-marker-value'),
    fetch: (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
      ? Response.json({ code: 200, data: [] })
      : Response.json(workerHealth('same-instance', 7))) as typeof fetch,
  })
  assert.equal(duplicate.cycleComplete, false)
  assert.ok(duplicate.errorCodes.includes('CANARY_WORKER_INSTANCE_DUPLICATED'))

  const revisionConfig = productionConfig(path.join(duplicateDirectory, 'revision.json'))
  const revision = await sampleCanarySoak(revisionConfig, undefined, {
    now: () => now,
    env: productionEnv('cohort-marker-value'),
    fetch: (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
      ? Response.json({ code: 200, data: [] })
      : Response.json(workerHealth('revision-instance', 8))) as typeof fetch,
  })
  assert.equal(revision.cycleComplete, false)
  assert.ok(revision.errorCodes.includes('CANARY_WORKER_RUNTIME_REVISION_MISMATCH'))

  const resetDirectory = await tempDirectory('codex-canary-counter-reset-')
  const resetConfig = localConfig(path.join(resetDirectory, 'checkpoint.json'))
  let retired = 5
  let crashes = 3
  let rejected = 4
  let acquireTimeouts = 2
  const resetFetch = (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
    ? Response.json({ code: 200, data: [] })
    : Response.json(workerHealth('counter-instance', 1, {
      retired_total: retired,
      crashes_total: crashes,
      rejected_total: rejected,
      acquire_timeouts_total: acquireTimeouts,
    }))) as typeof fetch
  const localEnv = {
    NAVIGATOR_CANARY_TOKEN: 'navigator-token-value',
    CODEX_CANARY_PRIVACY_MARKER: 'privacy-marker-value',
  }
  const first = await sampleCanarySoak(resetConfig, undefined, { now: () => now, env: localEnv, fetch: resetFetch })
  retired = 2
  crashes = 1
  rejected = 1
  acquireTimeouts = 1
  const second = await sampleCanarySoak(resetConfig, first.state, {
    now: () => new Date(now.getTime() + 61_000),
    env: localEnv,
    fetch: resetFetch,
  })
  const sample = Object.values(second.state.worker_samples)[0]
  assert.equal(sample.rotations, 2)
  assert.equal(sample.crashes, 1)
  assert.equal(sample.rejections, 1)
  assert.equal(sample.acquire_timeouts, 1)
})

test('state lease reclaims only a dead local pid and fails closed for live or cross-host owners', async () => {
  const directory = await tempDirectory('codex-canary-lease-')
  const now = new Date()
  const env = {
    NAVIGATOR_CANARY_TOKEN: 'navigator-token-value',
    CODEX_CANARY_PRIVACY_MARKER: 'privacy-marker-value',
  }
  const fetchMock = (async (input: string | URL | Request) => String(input).includes('/codex-tasks')
    ? Response.json({ code: 200, data: [] })
    : Response.json(workerHealth('lease-instance', 1))) as typeof fetch

  for (const [name, hostname, pid, expectedCode] of [
    ['live', os.hostname(), process.pid, 'CANARY_STATE_LEASE_HELD'],
    ['remote', 'different-host.example', 2_147_483_647, 'CANARY_STATE_LEASE_HELD'],
  ] as const) {
    const config = localConfig(path.join(directory, `${name}.json`))
    await writeLease(config.stateFile, hostname, pid, now)
    await assert.rejects(
      sampleCanarySoak(config, undefined, { now: () => now, env, fetch: fetchMock }),
      (error: unknown) => error instanceof CanarySoakError && error.code === expectedCode,
    )
    await fs.rm(`${config.stateFile}.lease`, { recursive: true, force: true })
  }

  const claimedConfig = localConfig(path.join(directory, 'claimed-dead.json'))
  await writeLease(claimedConfig.stateFile, os.hostname(), 2_147_483_647, now)
  await writeReclaimClaim(claimedConfig.stateFile, now)
  await assert.rejects(
    sampleCanarySoak(claimedConfig, undefined, { now: () => now, env, fetch: fetchMock }),
    (error: unknown) => error instanceof CanarySoakError && error.code === 'CANARY_STATE_LEASE_HELD',
  )
  const claimedOwner = JSON.parse(
    await fs.readFile(path.join(`${claimedConfig.stateFile}.lease`, 'owner.json'), 'utf8'),
  ) as Record<string, unknown>
  assert.equal(claimedOwner.lease_id, 'test-lease-id')
  await fs.access(path.join(`${claimedConfig.stateFile}.lease`, 'reclaim.lock'))
  await fs.rm(`${claimedConfig.stateFile}.lease`, { recursive: true, force: true })

  const deadConfig = localConfig(path.join(directory, 'dead.json'))
  await writeLease(deadConfig.stateFile, os.hostname(), 2_147_483_647, now)
  const reclaimed = await sampleCanarySoak(deadConfig, undefined, { now: () => now, env, fetch: fetchMock })
  assert.equal(reclaimed.cycleComplete, true)
  await assert.rejects(fs.access(`${deadConfig.stateFile}.lease`))
})

test('incompatible and impossible checkpoints require explicit atomic reset and require-pass stays pending', async () => {
  const directory = await tempDirectory('codex-canary-reset-')
  const stateFile = path.join(directory, 'checkpoint.json')
  const config = localConfig(stateFile)
  const now = new Date()
  const oldState = { ...createInitialState(config, now), schema_version: 2 }
  await fs.writeFile(stateFile, JSON.stringify(oldState), 'utf8')
  await assert.rejects(
    readCanarySoakState(config, now),
    (error: unknown) => error instanceof CanarySoakError && error.code === 'CANARY_STATE_RESET_REQUIRED',
  )
  const reset = await resetCanarySoakState(config, now)
  assert.equal(reset.schema_version, 3)

  const invalid = createInitialState(config, now)
  invalid.last_attempt_at = new Date(now.getTime() + 10 * 60_000).toISOString()
  invalid.next_due_at = new Date(now.getTime() + 11 * 60_000).toISOString()
  await fs.writeFile(stateFile, JSON.stringify(invalid), 'utf8')
  await assert.rejects(
    readCanarySoakState(config, now),
    (error: unknown) => error instanceof CanarySoakError && error.code === 'CANARY_STATE_RESET_REQUIRED',
  )

  const configPath = path.join(directory, 'config.json')
  await fs.writeFile(configPath, JSON.stringify(localRawConfig(stateFile)), 'utf8')
  const originalWrite = process.stdout.write
  process.stdout.write = (() => true) as typeof process.stdout.write
  try {
    assert.equal(await runCanarySoakCli(['--config', configPath, '--reset']), 0)
    assert.equal(await runCanarySoakCli(['--config', configPath, '--report', '--require-pass']), 1)
  } finally {
    process.stdout.write = originalWrite
  }
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
    runtimeInstanceId: 'worker-instance-raw',
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
  return resolveCanarySoakConfig(productionRawConfig(stateFile))
}

function productionRawConfig(stateFile: string): Record<string, unknown> {
  const raw = localRawConfig(stateFile)
  return {
    ...raw,
    profile: 'production',
    navigator: {
      ...(raw.navigator as Record<string, unknown>),
      runtimeRevision: 7,
      routingEpoch: 11,
      model: 'codex-ultra',
      providerType: 'codex-worker',
      cohortMarkerEnv: 'CODEX_CANARY_COHORT_MARKER',
    },
  }
}

function productionTask(taskId: string, now: Date, cohortMarker: string): Record<string, unknown> {
  return {
    taskId,
    status: 'COMPLETED',
    createdAt: now.toISOString(),
    createdAtEpochMs: now.getTime(),
    runtimeId: 'runtime-canary',
    runtimeRevision: 7,
    routingEpoch: 11,
    runtimeType: 'APP_SERVER',
    runtimeInstanceId: 'production-instance',
    workerId: 'worker-canary',
    model: 'codex-ultra',
    providerType: 'codex-worker',
    prompt: `verify cohort ${cohortMarker}`,
    errorMessage: '',
    resultText: '',
  }
}

function productionEnv(cohortMarker: string): NodeJS.ProcessEnv {
  return {
    NAVIGATOR_CANARY_TOKEN: 'navigator-token-value',
    CODEX_CANARY_PRIVACY_MARKER: 'privacy-marker-value',
    CODEX_CANARY_COHORT_MARKER: cohortMarker,
  }
}

function workerHealth(
  instanceId: string,
  runtimeRevision: number,
  poolOverrides: Record<string, number> = {},
): Record<string, unknown> {
  return {
    ready: true,
    runtime_id: 'runtime-canary',
    runtime_revision: runtimeRevision,
    instance_id: instanceId,
    runtime_metrics: {
      pool: {
        instances: 1,
        busy: 0,
        idle: 1,
        creating: 0,
        queued: 0,
        lanes: 1,
        draining: false,
        created_total: 1,
        reused_total: 0,
        retired_total: 0,
        crashes_total: 0,
        rejected_total: 0,
        acquire_timeouts_total: 0,
        ...poolOverrides,
      },
    },
  }
}

async function writeLease(stateFile: string, hostname: string, pid: number, now: Date): Promise<void> {
  const leasePath = `${stateFile}.lease`
  await fs.mkdir(leasePath, { recursive: true })
  await fs.writeFile(path.join(leasePath, 'owner.json'), JSON.stringify({
    schema_version: 1,
    lease_id: 'test-lease-id',
    hostname,
    pid,
    acquired_at: now.toISOString(),
  }), 'utf8')
}

async function writeReclaimClaim(stateFile: string, now: Date): Promise<void> {
  await fs.writeFile(path.join(`${stateFile}.lease`, 'reclaim.lock'), JSON.stringify({
    schema_version: 1,
    claim_id: 'existing-reclaim-claim',
    observed_lease_id: 'test-lease-id',
    hostname: os.hostname(),
    pid: process.pid,
    claimed_at: now.toISOString(),
  }), 'utf8')
}
