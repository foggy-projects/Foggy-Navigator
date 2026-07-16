import crypto from 'node:crypto'
import type { AppConfig } from '../config.js'
import { readAppServerLaneApiKey } from './lane.js'
import {
  AppServerRpcError,
  AppServerRuntimeInstance,
  isAppServerProcessTreeSafetyError,
  type PersistentTurnOptions,
} from './runtime.js'
import {
  isRateLimitReached,
  RateLimitsProtocolError,
  type PoolRateLimitsView,
  type SafeAccountRateLimits,
} from './rate-limits.js'

const DEFAULT_RATE_LIMITS_TTL_MS = 60_000
const RATE_LIMITS_CACHE_RETENTION_MS = 15 * 60_000
const MAX_RATE_LIMITS_INVALIDATION_RETRIES = 3

export type AppServerLane = {
  key: string
  cliVersion: string
  authFingerprint: string
  codexHomeFingerprint: string
  baseUrlFingerprint: string
  processEnvFingerprint: string
  env: Record<string, string>
}

export interface PoolRuntimeInstance {
  readonly pid: number | undefined
  isHealthy(): boolean
  isActive(): boolean
  requiresAttention?(): boolean
  markObservedTerminal?(threadId?: string, turnId?: string): void
  /**
   * True only after this runtime observed a matching provider terminal event
   * for its current root turn.  It is a no-signal fence for a late manual PID
   * operation while TaskManager is still persisting that terminal result.
   */
  hasProviderTerminalObserved?(threadId?: string, turnId?: string): boolean
  /**
   * The boolean is deliberately an observation result, not a dispatch ACK.
   * A signed manual PID operation may signal a process, but TaskManager must
   * retain the task unless the runtime also verified that its child exited.
   */
  forceTerminateForAuthorizedOperation?(
    expectedPid: number,
    verificationTimeoutMs?: number,
    threadId?: string,
    turnId?: string,
  ): boolean | void | Promise<boolean | void>
  runTurn(options: PersistentTurnOptions): ReturnType<AppServerRuntimeInstance['runTurn']>
  readThread(threadId: string, includeTurns?: boolean): Promise<Record<string, unknown>>
  listLoadedThreads?(): Promise<string[]>
  interruptTurn?(threadId: string, turnId: string): Promise<void>
  close(timeoutMs?: number): void | Promise<void>
  onFatal?(handler: (error: Error) => void): () => void
  readAccountRateLimits?(): Promise<SafeAccountRateLimits>
  onRateLimitsUpdated?(handler: () => void): () => void
}

export type PoolInstanceFactory = (lane: AppServerLane, signal?: AbortSignal) => Promise<PoolRuntimeInstance>

export type PoolMetrics = {
  instances: number
  busy: number
  idle: number
  creating: number
  queued: number
  lanes: number
  draining: boolean
  created_total: number
  reused_total: number
  retired_total: number
  crashes_total: number
  rejected_total: number
  acquire_timeouts_total: number
}

export class AppServerPoolOverloadedError extends Error {
  readonly code = 'APP_SERVER_POOL_OVERLOADED'
  constructor() {
    super('Codex app-server pool queue is full')
    this.name = 'AppServerPoolOverloadedError'
  }
}

export class AppServerPoolDrainingError extends Error {
  readonly code = 'APP_SERVER_POOL_DRAINING'
  constructor() {
    super('Codex app-server pool is draining')
    this.name = 'AppServerPoolDrainingError'
  }
}

export class AppServerPoolAcquireTimeoutError extends Error {
  readonly code = 'APP_SERVER_POOL_ACQUIRE_TIMEOUT'
  constructor() {
    super('Timed out waiting for a Codex app-server instance')
    this.name = 'AppServerPoolAcquireTimeoutError'
  }
}

/**
 * The Worker owns at most one resident child. Reusing it across an
 * auth/home/environment boundary would silently run work with the wrong
 * startup credentials or CODEX_HOME, so incompatible lanes fail closed.
 */
export class AppServerPoolSingleInstanceLaneMismatchError extends Error {
  readonly code = 'APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH'
  constructor() {
    super('The resident app-server child is bound to a different execution lane')
    this.name = 'AppServerPoolSingleInstanceLaneMismatchError'
  }
}

export class AppServerPoolDrainTimeoutError extends Error {
  readonly code = 'APP_SERVER_POOL_DRAIN_TIMEOUT'
  constructor() {
    super('Timed out retiring Codex app-server instances')
    this.name = 'AppServerPoolDrainTimeoutError'
  }
}

type InstanceRecord = {
  id: string
  laneKey: string
  runtime: PoolRuntimeInstance
  activeLeases: number
  crashed: boolean
  createdAt: number
  lastUsedAt: number
  taskCount: number
  unsubscribeFatal?: () => void
  unsubscribeRateLimits?: () => void
}

type RateLimitsCacheEntry = {
  limits: SafeAccountRateLimits
  observedAtEpochMs: number
  lastAttemptAtEpochMs: number
  invalidated: boolean
  errorCode?: string
}

type Waiter = {
  lane: AppServerLane
  preferredInstanceId?: string
  resolve: (lease: AppServerLease) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
  signal?: AbortSignal
  abort?: () => void
  settled: boolean
}

export class AppServerLease {
  private released = false

  constructor(
    readonly instanceId: string,
    readonly runtime: PoolRuntimeInstance,
    private readonly releaseLease: (healthy: boolean) => void,
  ) {}

  release(healthy = true): void {
    if (this.released) return
    this.released = true
    this.releaseLease(healthy)
  }
}

export class AppServerPool {
  private readonly instances = new Map<string, InstanceRecord>()
  private readonly waiters: Waiter[] = []
  private readonly creatingByLane = new Map<string, number>()
  private readonly creationControllers = new Set<AbortController>()
  private readonly retirements = new Set<Promise<void>>()
  private readonly retirementOwners = new Set<PoolRuntimeInstance>()
  private readonly retirementFailures: Error[] = []
  private readonly rateLimitsCache = new Map<string, RateLimitsCacheEntry>()
  private readonly rateLimitsRefreshes = new Map<string, Promise<PoolRateLimitsView>>()
  private readonly rateLimitsInvalidationVersions = new Map<string, number>()
  private readonly factory: PoolInstanceFactory
  private readonly sweepTimer: NodeJS.Timeout
  private creating = 0
  private draining = false
  private drainPromise?: Promise<void>
  private drainDeadline?: number
  private pumpScheduled = false
  private readonly counters = {
    created: 0,
    reused: 0,
    retired: 0,
    crashes: 0,
    rejected: 0,
    timeouts: 0,
  }

  constructor(
    private readonly config: Pick<AppConfig,
      | 'poolMaxQueue'
      | 'poolAcquireTimeoutMs'
      | 'poolIdleTtlMs'
      | 'stateDir'>,
    factory: PoolInstanceFactory = async (lane, signal) => AppServerRuntimeInstance.start({
      env: lane.env,
      apiKey: readAppServerLaneApiKey(lane),
      signal,
      processTreeStateDir: config.stateDir,
    }),
    private readonly now: () => number = () => Date.now(),
    private readonly rateLimitsTtlMs = DEFAULT_RATE_LIMITS_TTL_MS,
  ) {
    this.factory = factory
    this.sweepTimer = setInterval(() => this.sweep(), Math.min(config.poolIdleTtlMs, 1_000))
    this.sweepTimer.unref()
  }

  acquire(lane: AppServerLane, signal?: AbortSignal): Promise<AppServerLease> {
    return this.enqueueAcquire(lane, signal)
  }

  async acquireForThread(
    lane: AppServerLane,
    threadId: string,
    signal?: AbortSignal,
  ): Promise<AppServerLease> {
    if (this.draining) throw new AppServerPoolDrainingError()
    if (signal?.aborted) throw abortError()
    if (this.waiters.length >= this.config.poolMaxQueue) {
      this.counters.rejected++
      throw new AppServerPoolOverloadedError()
    }
    const preferredInstanceId = await this.findLoadedThreadInstance(lane.key, threadId, signal)
    return this.enqueueAcquire(lane, signal, preferredInstanceId)
  }

  private enqueueAcquire(
    lane: AppServerLane,
    signal?: AbortSignal,
    preferredInstanceId?: string,
  ): Promise<AppServerLease> {
    if (this.draining) return Promise.reject(new AppServerPoolDrainingError())
    if (signal?.aborted) return Promise.reject(abortError())
    if (this.waiters.length >= this.config.poolMaxQueue) {
      this.counters.rejected++
      return Promise.reject(new AppServerPoolOverloadedError())
    }
    return new Promise((resolve, reject) => {
      const waiter: Waiter = {
        lane,
        preferredInstanceId,
        resolve,
        reject,
        settled: false,
        signal,
        timer: setTimeout(() => {
          if (waiter.settled) return
          this.removeWaiter(waiter)
          waiter.settled = true
          this.counters.timeouts++
          reject(new AppServerPoolAcquireTimeoutError())
        }, this.config.poolAcquireTimeoutMs),
      }
      if (signal) {
        waiter.abort = () => {
          if (waiter.settled) return
          this.removeWaiter(waiter)
          waiter.settled = true
          clearTimeout(waiter.timer)
          reject(abortError())
        }
        signal.addEventListener('abort', waiter.abort, { once: true })
      }
      this.waiters.push(waiter)
      this.schedulePump()
    })
  }

  isDraining(): boolean {
    return this.draining
  }

  failClosed(error: Error): void {
    this.markRetirementFailure(error)
  }

  metrics(): PoolMetrics {
    const records = [...this.instances.values()]
    return {
      instances: records.length,
      busy: records.filter(record => record.activeLeases > 0 || record.runtime.requiresAttention?.()).length,
      idle: records.filter(record => record.activeLeases === 0 && !record.runtime.requiresAttention?.()).length,
      creating: this.creating,
      queued: this.waiters.length,
      lanes: new Set(records.map(record => record.laneKey)).size,
      draining: this.draining,
      created_total: this.counters.created,
      reused_total: this.counters.reused,
      retired_total: this.counters.retired,
      crashes_total: this.counters.crashes,
      rejected_total: this.counters.rejected,
      acquire_timeouts_total: this.counters.timeouts,
    }
  }

  readRateLimits(lane: AppServerLane, refresh = false): Promise<PoolRateLimitsView> {
    const cached = this.rateLimitsCache.get(lane.key)
    const stale = !cached
      || cached.invalidated
      || this.now() - cached.observedAtEpochMs >= this.rateLimitsTtlMs
    if (!refresh && cached && !stale) return Promise.resolve(this.rateLimitsView(cached, false))
    const active = this.rateLimitsRefreshes.get(lane.key)
    if (active) return active
    const reading = this.refreshRateLimits(lane).finally(() => {
      if (this.rateLimitsRefreshes.get(lane.key) === reading) this.rateLimitsRefreshes.delete(lane.key)
    })
    this.rateLimitsRefreshes.set(lane.key, reading)
    return reading
  }

  sweep(): void {
    const now = this.now()
    for (const record of [...this.instances.values()]) {
      // An unverified runtime is deliberately retained until a terminal
      // observation or a separately authorized kill.  Do not let periodic
      // pool maintenance turn an observability failure into a process kill.
      if (record.activeLeases > 0 || record.runtime.isActive() || record.runtime.requiresAttention?.()) continue
      if (!record.runtime.isHealthy() || record.crashed) {
        this.retire(record, true)
      }
    }
    for (const [laneKey, cached] of this.rateLimitsCache) {
      const laneIsResident = [...this.instances.values()].some(record => record.laneKey === laneKey)
      if (!laneIsResident && now - cached.lastAttemptAtEpochMs >= RATE_LIMITS_CACHE_RETENTION_MS) {
        this.rateLimitsCache.delete(laneKey)
        this.rateLimitsInvalidationVersions.delete(laneKey)
      }
    }
    this.schedulePump()
  }

  async drain(timeoutMs: number): Promise<void> {
    if (this.drainPromise) return this.drainPromise
    const draining = (async () => {
      this.draining = true
      clearInterval(this.sweepTimer)
      const deadline = Date.now() + timeoutMs
      this.drainDeadline = deadline
      for (const waiter of [...this.waiters]) this.rejectWaiter(waiter, new AppServerPoolDrainingError())
      for (const controller of this.creationControllers) controller.abort('Pool draining')
      for (const record of [...this.instances.values()]) {
        if (record.activeLeases === 0 && !record.runtime.isActive() && !record.runtime.requiresAttention?.()) {
          this.retire(record, false)
        }
      }
      while (([...this.instances.values()].some(record => (
        record.activeLeases > 0 || record.runtime.isActive() || record.runtime.requiresAttention?.()
      )) || this.creating > 0) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 25))
      }
      // Never retire a lane that still owns an active or unverified turn.
      // runtime.close() may SIGTERM/SIGKILL the app-server process tree.
      for (const record of [...this.instances.values()]) {
        if (record.activeLeases === 0 && !record.runtime.isActive() && !record.runtime.requiresAttention?.()) {
          this.retire(record, false)
        }
      }
      while ((this.retirements.size > 0 || this.creating > 0) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 25))
      }
      if (this.retirementFailures.length > 0) {
        throw new AggregateError(
          [...this.retirementFailures],
          'One or more Codex app-server instances could not be retired cleanly',
        )
      }
      if (this.retirements.size > 0 || this.creating > 0 || this.instances.size > 0) {
        throw new AppServerPoolDrainTimeoutError()
      }
    })()
    this.drainPromise = draining
    void draining.catch(() => {
      if (this.drainPromise === draining) this.drainPromise = undefined
    })
    return draining
  }

  private async refreshRateLimits(lane: AppServerLane): Promise<PoolRateLimitsView> {
    const attemptedAt = this.now()
    try {
      const source = await this.ensureRateLimitsSource(lane)
      if (!source || !source.runtime.readAccountRateLimits) {
        return this.rateLimitsFailure(lane.key, 'RATE_LIMITS_SOURCE_UNAVAILABLE', attemptedAt)
      }
      return await this.readRateLimitsSnapshot(lane.key, source.runtime)
    } catch (error) {
      return this.rateLimitsFailure(lane.key, stableRateLimitsError(error), attemptedAt)
    }
  }

  private refreshResidentRateLimits(laneKey: string): void {
    if (this.rateLimitsRefreshes.has(laneKey)) return
    const source = this.findRateLimitsSource(laneKey)
    if (!source?.runtime.readAccountRateLimits) return
    const attemptedAt = this.now()
    const reading = this.readRateLimitsSnapshot(laneKey, source.runtime)
      .catch(error => this.rateLimitsFailure(laneKey, stableRateLimitsError(error), attemptedAt))
      .finally(() => {
        if (this.rateLimitsRefreshes.get(laneKey) === reading) this.rateLimitsRefreshes.delete(laneKey)
      })
    this.rateLimitsRefreshes.set(laneKey, reading)
    void reading
  }

  private async readRateLimitsSnapshot(
    laneKey: string,
    runtime: PoolRuntimeInstance,
  ): Promise<PoolRateLimitsView> {
    let latestLimits: SafeAccountRateLimits | undefined
    for (let attempt = 0; attempt < MAX_RATE_LIMITS_INVALIDATION_RETRIES; attempt++) {
      const versionAtReadStart = this.rateLimitsInvalidationVersions.get(laneKey) || 0
      latestLimits = await runtime.readAccountRateLimits!()
      if ((this.rateLimitsInvalidationVersions.get(laneKey) || 0) === versionAtReadStart) {
        const entry = this.storeRateLimitsSnapshot(laneKey, latestLimits, false)
        return this.rateLimitsView(entry, false)
      }
    }

    const entry = this.storeRateLimitsSnapshot(laneKey, latestLimits!, true)
    entry.errorCode = 'RATE_LIMITS_REFRESH_INVALIDATED'
    return this.rateLimitsView(entry, true)
  }

  private storeRateLimitsSnapshot(
    laneKey: string,
    limits: SafeAccountRateLimits,
    invalidated: boolean,
  ): RateLimitsCacheEntry {
    const observedAtEpochMs = this.now()
    const entry: RateLimitsCacheEntry = {
      limits,
      observedAtEpochMs,
      lastAttemptAtEpochMs: observedAtEpochMs,
      invalidated,
    }
    this.rateLimitsCache.set(laneKey, entry)
    return entry
  }

  private async ensureRateLimitsSource(lane: AppServerLane): Promise<InstanceRecord | undefined> {
    const existing = this.findRateLimitsSource(lane.key)
    if (existing) return existing
    if (this.draining || this.hasIncompatibleResidentLane(lane.key) || !this.canCreate()) return undefined
    const controller = new AbortController()
    this.reserveCreation(lane.key, controller)
    try {
      const runtime = await this.factory(lane, controller.signal)
      if (this.draining) {
        this.trackRuntimeClose(runtime, this.remainingDrainMs())
        return undefined
      }
      const record = this.registerRuntime(lane.key, runtime)
      this.schedulePump()
      return record
    } finally {
      this.finishCreation(lane.key, controller)
      this.schedulePump()
    }
  }

  private findRateLimitsSource(laneKey: string): InstanceRecord | undefined {
    return [...this.instances.values()].find(record => (
      record.laneKey === laneKey
      && !record.crashed
      && record.runtime.isHealthy()
      && Boolean(record.runtime.readAccountRateLimits)
    ))
  }

  private rateLimitsFailure(laneKey: string, errorCode: string, attemptedAt: number): PoolRateLimitsView {
    const cached = this.rateLimitsCache.get(laneKey)
    if (!cached) {
      this.rateLimitsInvalidationVersions.delete(laneKey)
      return {
        state: errorCode === 'RATE_LIMITS_UNSUPPORTED' ? 'UNSUPPORTED' : 'UNKNOWN',
        observed_at_epoch_ms: null,
        stale: false,
        limits: [],
        error_code: errorCode,
      }
    }
    cached.invalidated = true
    cached.lastAttemptAtEpochMs = attemptedAt
    cached.errorCode = errorCode
    return this.rateLimitsView(cached, true)
  }

  private rateLimitsView(entry: RateLimitsCacheEntry, stale: boolean): PoolRateLimitsView {
    return {
      state: stale ? 'STALE' : isRateLimitReached(entry.limits) ? 'LIMIT_REACHED' : 'AVAILABLE',
      observed_at_epoch_ms: entry.observedAtEpochMs,
      stale,
      limits: structuredClone(entry.limits.limits),
      error_code: stale ? entry.errorCode || 'RATE_LIMITS_STALE' : null,
    }
  }

  private registerRuntime(laneKey: string, runtime: PoolRuntimeInstance): InstanceRecord {
    const now = this.now()
    const record: InstanceRecord = {
      id: crypto.randomUUID(),
      laneKey,
      runtime,
      activeLeases: 0,
      crashed: false,
      createdAt: now,
      lastUsedAt: now,
      taskCount: 0,
    }
    record.unsubscribeFatal = runtime.onFatal?.(() => this.handleFatal(record))
    record.unsubscribeRateLimits = runtime.onRateLimitsUpdated?.(() => {
      this.rateLimitsInvalidationVersions.set(
        laneKey,
        (this.rateLimitsInvalidationVersions.get(laneKey) || 0) + 1,
      )
      const cached = this.rateLimitsCache.get(laneKey)
      if (cached) cached.invalidated = true
      this.refreshResidentRateLimits(laneKey)
    })
    this.instances.set(record.id, record)
    this.counters.created++
    return record
  }

  private schedulePump(): void {
    if (this.pumpScheduled || this.draining) return
    this.pumpScheduled = true
    queueMicrotask(() => {
      this.pumpScheduled = false
      this.pump()
    })
  }

  private pump(): void {
    if (this.draining) return
    for (const waiter of [...this.waiters]) {
      if (waiter.settled) continue
      if (waiter.preferredInstanceId) {
        const preferred = this.instances.get(waiter.preferredInstanceId)
        waiter.preferredInstanceId = undefined
        if (
          preferred
          && preferred.laneKey === waiter.lane.key
          && this.isReusable(preferred)
        ) {
          this.removeWaiter(waiter)
          this.counters.reused++
          this.resolveWaiter(waiter, this.lease(preferred))
          continue
        }
      }
      const reusable = this.findReusable(waiter.lane.key)
      if (reusable) {
        this.removeWaiter(waiter)
        this.counters.reused++
        this.resolveWaiter(waiter, this.lease(reusable))
        continue
      }
      if (this.hasIncompatibleResidentLane(waiter.lane.key)) {
        this.removeWaiter(waiter)
        this.counters.rejected++
        this.rejectWaiter(waiter, new AppServerPoolSingleInstanceLaneMismatchError())
      } else if (this.canCreate()) {
        this.removeWaiter(waiter)
        waiter.settled = true
        clearTimeout(waiter.timer)
        if (waiter.abort && waiter.signal) waiter.signal.removeEventListener('abort', waiter.abort)
        const creationController = new AbortController()
        const abortCreation = (): void => creationController.abort('Pool acquire aborted')
        waiter.signal?.addEventListener('abort', abortCreation, { once: true })
        this.reserveCreation(waiter.lane.key, creationController)
        const finishCreation = (): void => {
          waiter.signal?.removeEventListener('abort', abortCreation)
          this.finishCreation(waiter.lane.key, creationController)
        }
        void this.factory(waiter.lane, creationController.signal).then(runtime => {
          finishCreation()
          if (this.draining || waiter.signal?.aborted) {
            this.trackRuntimeClose(runtime, this.remainingDrainMs())
            waiter.reject(this.draining ? new AppServerPoolDrainingError() : abortError())
            this.schedulePump()
            return
          }
          const record = this.registerRuntime(waiter.lane.key, runtime)
          waiter.resolve(this.lease(record))
          this.schedulePump()
        }).catch(error => {
          finishCreation()
          const failure = error instanceof Error ? error : new Error(String(error))
          const processTreeUnsafe = isAppServerProcessTreeSafetyError(failure)
          if (processTreeUnsafe) {
            this.failClosed(failure)
          }
          waiter.reject(processTreeUnsafe
            ? failure
            : this.draining
            ? new AppServerPoolDrainingError()
            : failure)
          this.schedulePump()
        })
      }
    }
  }

  private canCreate(): boolean {
    // A retiring child still owns its global slot until close() settles.
    const total = this.instances.size + this.creating + this.retirements.size
    return total < 1
  }

  private hasIncompatibleResidentLane(laneKey: string): boolean {
    return [...this.instances.values()].some(record => record.laneKey !== laneKey)
      || [...this.creatingByLane.keys()].some(creatingLaneKey => creatingLaneKey !== laneKey)
  }

  private findReusable(laneKey: string): InstanceRecord | undefined {
    for (const record of [...this.instances.values()]) {
      if (record.laneKey !== laneKey) continue
      if (!this.isReusable(record)) {
        if (record.activeLeases === 0) {
          this.retire(record, record.crashed || !record.runtime.isHealthy())
        }
        continue
      }
      return record
    }
    return undefined
  }

  private async findLoadedThreadInstance(
    laneKey: string,
    threadId: string,
    signal?: AbortSignal,
  ): Promise<string | undefined> {
    const candidates = [...this.instances.values()].filter(record => (
      record.laneKey === laneKey
      && !record.crashed
      && record.runtime.isHealthy()
      && Boolean(record.runtime.listLoadedThreads)
    ))
    if (candidates.length === 0) return undefined
    const loaded = await Promise.all(candidates.map(async record => {
      try {
        const threadIds = await record.runtime.listLoadedThreads!()
        return threadIds.includes(threadId) ? record : undefined
      } catch {
        return undefined
      }
    }))
    if (signal?.aborted) throw abortError()
    return loaded
      .filter((record): record is InstanceRecord => Boolean(record))
      .filter(record => (
        this.instances.get(record.id) === record
        && this.isReusable(record)
      ))
      .sort((left, right) => left.lastUsedAt - right.lastUsedAt || left.id.localeCompare(right.id))[0]
      ?.id
  }

  private isReusable(record: InstanceRecord): boolean {
    return !record.crashed
      && !record.runtime.requiresAttention?.()
      && record.runtime.isHealthy()
  }

  private reserveCreation(laneKey: string, controller: AbortController): void {
    this.creating++
    this.creationControllers.add(controller)
    this.creatingByLane.set(laneKey, (this.creatingByLane.get(laneKey) || 0) + 1)
  }

  private finishCreation(laneKey: string, controller: AbortController): void {
    this.creating--
    this.creationControllers.delete(controller)
    const count = (this.creatingByLane.get(laneKey) || 1) - 1
    if (count <= 0) this.creatingByLane.delete(laneKey)
    else this.creatingByLane.set(laneKey, count)
  }

  private lease(record: InstanceRecord): AppServerLease {
    record.activeLeases++
    return new AppServerLease(record.id, record.runtime, healthy => {
      if (record.activeLeases <= 0) return
      record.activeLeases--
      record.lastUsedAt = this.now()
      record.taskCount++
      if (!healthy) record.crashed = true
      if (record.activeLeases === 0) {
        if (record.crashed || !record.runtime.isHealthy()) {
          this.retire(record, true)
        } else if (this.draining) {
          this.retire(record, false)
        }
      }
      this.schedulePump()
    })
  }

  private handleFatal(record: InstanceRecord): void {
    if (record.crashed) return
    record.crashed = true
    if (record.activeLeases === 0) {
      this.retire(record, true)
      this.schedulePump()
    }
  }

  private retire(record: InstanceRecord, crashed: boolean): void {
    if (record.activeLeases > 0 || record.runtime.isActive() || record.runtime.requiresAttention?.()) {
      return
    }
    if (!this.instances.delete(record.id)) return
    record.unsubscribeFatal?.()
    record.unsubscribeRateLimits?.()
    if (
      !this.rateLimitsCache.has(record.laneKey)
      && !this.rateLimitsRefreshes.has(record.laneKey)
      && ![...this.instances.values()].some(candidate => candidate.laneKey === record.laneKey)
    ) {
      this.rateLimitsInvalidationVersions.delete(record.laneKey)
    }
    this.trackRuntimeClose(record.runtime, this.remainingDrainMs())
    this.counters.retired++
    if (crashed) this.counters.crashes++
  }

  private trackRuntimeClose(runtime: PoolRuntimeInstance, timeoutMs?: number): void {
    this.retirementOwners.add(runtime)
    let result: void | Promise<void>
    try {
      result = runtime.close(timeoutMs)
    } catch (error) {
      console.warn('[codex-app-server] child_close_failed reason=CLOSE_THROWN')
      this.markRetirementFailure(error)
      return
    }
    const closing = Promise.resolve(result).then(
      () => { this.retirementOwners.delete(runtime) },
      error => {
        console.warn('[codex-app-server] child_close_failed reason=CLOSE_REJECTED')
        this.markRetirementFailure(error)
      },
    )
    this.retirements.add(closing)
    void closing.finally(() => {
      this.retirements.delete(closing)
      this.schedulePump()
    })
  }

  private markRetirementFailure(error: unknown): void {
    const failure = error instanceof Error ? error : new Error(String(error))
    if (!this.retirementFailures.includes(failure)) this.retirementFailures.push(failure)
    if (this.draining) return
    this.draining = true
    clearInterval(this.sweepTimer)
    for (const waiter of [...this.waiters]) this.rejectWaiter(waiter, new AppServerPoolDrainingError())
    for (const controller of this.creationControllers) controller.abort('Pool retirement failed')
  }

  private remainingDrainMs(): number | undefined {
    return this.drainDeadline === undefined ? undefined : Math.max(0, this.drainDeadline - Date.now())
  }

  private resolveWaiter(waiter: Waiter, lease: AppServerLease): void {
    if (waiter.settled) {
      lease.release()
      return
    }
    waiter.settled = true
    clearTimeout(waiter.timer)
    if (waiter.abort && waiter.signal) waiter.signal.removeEventListener('abort', waiter.abort)
    waiter.resolve(lease)
  }

  private rejectWaiter(waiter: Waiter, error: Error): void {
    if (waiter.settled) return
    this.removeWaiter(waiter)
    waiter.settled = true
    clearTimeout(waiter.timer)
    if (waiter.abort && waiter.signal) waiter.signal.removeEventListener('abort', waiter.abort)
    waiter.reject(error)
  }

  private removeWaiter(waiter: Waiter): void {
    const index = this.waiters.indexOf(waiter)
    if (index >= 0) this.waiters.splice(index, 1)
  }
}

function abortError(): Error {
  const error = new Error('Codex app-server pool acquire aborted')
  error.name = 'AbortError'
  return error
}

function stableRateLimitsError(error: unknown): string {
  if (error instanceof RateLimitsProtocolError) return error.code
  if (error instanceof AppServerRpcError) {
    const message = error.message.toLowerCase()
    if (error.code === -32601 || message.includes('authentication required')) {
      return 'RATE_LIMITS_UNSUPPORTED'
    }
    if (error.code === -32001) return 'RATE_LIMITS_TEMPORARILY_UNAVAILABLE'
    return 'RATE_LIMITS_UPSTREAM_FAILED'
  }
  if (error instanceof Error && error.message.includes('request timed out')) {
    return 'RATE_LIMITS_READ_TIMEOUT'
  }
  return 'RATE_LIMITS_SOURCE_UNAVAILABLE'
}
