import crypto from 'node:crypto'
import type { AppConfig } from '../config.js'
import {
  AppServerRuntimeInstance,
  isAppServerProcessTreeSafetyError,
  type PersistentTurnOptions,
} from './runtime.js'

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
  runTurn(options: PersistentTurnOptions): ReturnType<AppServerRuntimeInstance['runTurn']>
  readThread(threadId: string, includeTurns?: boolean): Promise<Record<string, unknown>>
  interruptTurn?(threadId: string, turnId: string): Promise<void>
  close(timeoutMs?: number): void | Promise<void>
  onFatal?(handler: (error: Error) => void): () => void
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
  busy: boolean
  crashed: boolean
  createdAt: number
  lastUsedAt: number
  taskCount: number
  unsubscribeFatal?: () => void
}

type Waiter = {
  lane: AppServerLane
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
      | 'poolMaxInstances'
      | 'poolMaxInstancesPerLane'
      | 'poolMaxQueue'
      | 'poolAcquireTimeoutMs'
      | 'poolIdleTtlMs'
      | 'poolMaxLifetimeMs'
      | 'poolMaxTasksPerInstance'
      | 'stateDir'>,
    factory: PoolInstanceFactory = async (lane, signal) => AppServerRuntimeInstance.start({
      env: lane.env,
      signal,
      processTreeStateDir: config.stateDir,
    }),
    private readonly now: () => number = () => Date.now(),
  ) {
    this.factory = factory
    this.sweepTimer = setInterval(() => this.sweep(), Math.min(config.poolIdleTtlMs, 1_000))
    this.sweepTimer.unref()
  }

  acquire(lane: AppServerLane, signal?: AbortSignal): Promise<AppServerLease> {
    if (this.draining) return Promise.reject(new AppServerPoolDrainingError())
    if (signal?.aborted) return Promise.reject(abortError())
    if (this.waiters.length >= this.config.poolMaxQueue) {
      this.counters.rejected++
      return Promise.reject(new AppServerPoolOverloadedError())
    }
    return new Promise((resolve, reject) => {
      const waiter: Waiter = {
        lane,
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
      busy: records.filter(record => record.busy).length,
      idle: records.filter(record => !record.busy).length,
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

  sweep(): void {
    const now = this.now()
    for (const record of [...this.instances.values()]) {
      if (record.busy) continue
      if (!record.runtime.isHealthy() || record.crashed) {
        this.retire(record, true)
      } else if (
        now - record.lastUsedAt >= this.config.poolIdleTtlMs
        || now - record.createdAt >= this.config.poolMaxLifetimeMs
        || record.taskCount >= this.config.poolMaxTasksPerInstance
      ) {
        this.retire(record, false)
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
      for (const record of [...this.instances.values()]) if (!record.busy) this.retire(record, false)
      while (([...this.instances.values()].some(record => record.busy) || this.creating > 0) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 25))
      }
      for (const record of [...this.instances.values()]) this.retire(record, false)
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
      const idle = this.findReusable(waiter.lane.key)
      if (idle) {
        this.removeWaiter(waiter)
        this.counters.reused++
        this.resolveWaiter(waiter, this.lease(idle))
        continue
      }
      if (this.canCreate(waiter.lane.key)) {
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
          const now = this.now()
          const record: InstanceRecord = {
            id: crypto.randomUUID(),
            laneKey: waiter.lane.key,
            runtime,
            busy: false,
            crashed: false,
            createdAt: now,
            lastUsedAt: now,
            taskCount: 0,
          }
          record.unsubscribeFatal = runtime.onFatal?.(() => this.handleFatal(record))
          this.instances.set(record.id, record)
          this.counters.created++
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
      } else if (this.retireIdleForReplacement(waiter.lane.key)) {
        this.schedulePump()
      }
    }
  }

  private canCreate(laneKey: string): boolean {
    // A retiring child still owns its global slot until close() settles.
    const total = this.instances.size + this.creating + this.retirements.size
    if (total >= this.config.poolMaxInstances) return false
    return this.hasLaneCreationCapacity(laneKey)
  }

  private hasLaneCreationCapacity(laneKey: string): boolean {
    const laneInstances = [...this.instances.values()].filter(record => record.laneKey === laneKey).length
      + (this.creatingByLane.get(laneKey) || 0)
    return laneInstances < this.config.poolMaxInstancesPerLane
  }

  private retireIdleForReplacement(laneKey: string): boolean {
    // Serialize replacements so concurrent waiters cannot over-evict idle lanes.
    if (
      this.retirements.size > 0
      || this.instances.size + this.creating < this.config.poolMaxInstances
      || !this.hasLaneCreationCapacity(laneKey)
    ) {
      return false
    }
    const candidate = [...this.instances.values()]
      .filter(record => !record.busy && !record.runtime.isActive())
      .sort((left, right) => {
        const leftSameLane = left.laneKey === laneKey ? 1 : 0
        const rightSameLane = right.laneKey === laneKey ? 1 : 0
        return leftSameLane - rightSameLane
          || left.lastUsedAt - right.lastUsedAt
          || left.createdAt - right.createdAt
          || left.id.localeCompare(right.id)
      })[0]
    if (!candidate) return false
    this.retire(candidate, candidate.crashed || !candidate.runtime.isHealthy())
    return true
  }

  private findReusable(laneKey: string): InstanceRecord | undefined {
    const now = this.now()
    for (const record of [...this.instances.values()]) {
      if (record.laneKey !== laneKey || record.busy) continue
      if (
        record.crashed
        || !record.runtime.isHealthy()
        || now - record.lastUsedAt >= this.config.poolIdleTtlMs
        || now - record.createdAt >= this.config.poolMaxLifetimeMs
        || record.taskCount >= this.config.poolMaxTasksPerInstance
      ) {
        this.retire(record, record.crashed || !record.runtime.isHealthy())
        continue
      }
      return record
    }
    return undefined
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
    record.busy = true
    return new AppServerLease(record.id, record.runtime, healthy => {
      if (!record.busy) return
      record.busy = false
      record.lastUsedAt = this.now()
      record.taskCount++
      if (!healthy || record.crashed || !record.runtime.isHealthy()) {
        this.retire(record, true)
      } else if (
        this.draining
        || record.taskCount >= this.config.poolMaxTasksPerInstance
        || record.lastUsedAt - record.createdAt >= this.config.poolMaxLifetimeMs
      ) {
        this.retire(record, false)
      }
      this.schedulePump()
    })
  }

  private handleFatal(record: InstanceRecord): void {
    if (record.crashed) return
    record.crashed = true
    if (!record.busy) {
      this.retire(record, true)
      this.schedulePump()
    }
  }

  private retire(record: InstanceRecord, crashed: boolean): void {
    if (!this.instances.delete(record.id)) return
    record.unsubscribeFatal?.()
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
