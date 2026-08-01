import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

export const LIFECYCLE_SCHEMA = 'NAVIGATOR_WORKER_LIFECYCLE_V1' as const
export const BINDING_SCHEMA = 'NAVIGATOR_LIFECYCLE_BINDING_V1' as const
export const BINDING_VERSION = 'JCS_SHA256_V1' as const

export type LifecycleOwnershipMode = 'SHADOW' | 'ENFORCED'
export type LifecycleCommandKind = 'TASK_CREATE' | 'TASK_RESUME' | 'TERMINATION_CANCEL'

export interface LifecycleContext {
  schema: typeof LIFECYCLE_SCHEMA
  ownership_mode: LifecycleOwnershipMode
  command_kind: LifecycleCommandKind
  navigator_task_id: string
  dispatch_id: string
  delivery_attempt: number
  expected_physical_worker_id: string
  expected_state_generation: string
  termination_operation_id: string | null
}

export interface SafeBinding {
  version: typeof BINDING_VERSION
  digest: string
  payloadDigest: string
  capabilityPayloadDigest: string | null
}

export interface SafeBindingInput {
  context: LifecycleContext
  httpMethod: string
  routeTemplate: string
  bodyWithoutLifecycleContext: unknown
  providerTaskId: string | null
  capabilityPayload: string | null
}

export interface LifecycleIdentity {
  schema_version: 1
  physical_worker_id: string
  state_generation: string
  instance_epoch: string
  created_at: string
}

export interface LifecycleDisposition {
  schema: typeof LIFECYCLE_SCHEMA
  ownership_mode: LifecycleOwnershipMode
  physical_worker_id: string
  state_generation: string
  instance_epoch: string
  navigator_task_id: string
  provider_task_id: string | null
  dispatch_id: string
  request_delivery_attempt: number | null
  disposition_delivery_attempt: number
  command_kind: LifecycleCommandKind
  acceptance_disposition: 'ACCEPTED' | 'REJECTED'
  effect_phase: 'PRE_EFFECT' | 'PREPARED' | 'EFFECT_STARTED' | 'RESULT_OBSERVED'
  disposition_version: number
  duplicate: boolean
  accepted: boolean
  provider_effect_started: boolean
  reconcile_required: boolean
  safe_binding_digest_version: typeof BINDING_VERSION
  safe_binding_digest: string
  never_accepted_proof: boolean
  fact_cursor: number
  code?: string
  termination_operation_id?: string
}

export interface LifecycleFactInput {
  fact_type: string
  aggregate_type: 'WORKER' | 'SESSION' | 'TASK' | 'TERMINATION_OPERATION'
  aggregate_id: string
  safe_reason_code: string
  navigator_task_id?: string
  provider_task_id?: string
  operation_id?: string
  ownership_mode?: LifecycleOwnershipMode
  dispatch_id?: string
  safe_binding_digest_version?: typeof BINDING_VERSION
  safe_binding_digest?: string
  terminal_outcome?: 'COMPLETED' | 'FAILED' | 'CANCELLED'
}

export interface LifecycleFactRecord extends LifecycleFactInput {
  schema: typeof LIFECYCLE_SCHEMA
  schema_version: 1
  fact_id: string
  idempotency_key: string
  source_sequence: number
  physical_worker_id: string
  state_generation: string
  instance_epoch: string
  recorded_at: string
}

export const NEVER_ACCEPTED_REASON_CODES = [
  'WORKER_TASK_ADMISSION_CAPACITY_REJECTED',
  'WORKER_TASK_ADMISSION_THREAD_CONFLICT',
  'WORKER_TASK_RESUME_TARGET_NOT_FOUND',
] as const

export type NeverAcceptedReasonCode = typeof NEVER_ACCEPTED_REASON_CODES[number]

type DurableState = {
  schema_version: 1
  high_watermark: number
  acked_through_sequence: number
  dispatches: Record<string, LifecycleDisposition>
  termination_operations: Record<string, string>
  facts: LifecycleFactRecord[]
}

type OpenOptions = {
  directory: string
  physicalWorkerId: string
  workerToken: string
  instanceEpoch?: string
}

function requireOpaque(value: string, code: string): string {
  const normalized = value.trim()
  if (!normalized || normalized.length > 256 || /\s/.test(normalized)) {
    throw new Error(code)
  }
  return normalized
}

function base64UrlSha256(value: string | Buffer): string {
  return crypto.createHash('sha256').update(value).digest('base64url')
}

export function canonicalizeJson(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value)
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('LIFECYCLE_BINDING_JSON_INVALID')
    return JSON.stringify(Object.is(value, -0) ? 0 : value)
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalizeJson).join(',')}]`
  }
  if (typeof value === 'object') {
    const source = value as Record<string, unknown>
    const keys = Object.keys(source).sort((left, right) => (
      left < right ? -1 : left > right ? 1 : 0
    ))
    return `{${keys.map(key => (
      `${JSON.stringify(key)}:${canonicalizeJson(source[key])}`
    )).join(',')}}`
  }
  throw new Error('LIFECYCLE_BINDING_JSON_INVALID')
}

export function computeSafeBindingDigest(input: SafeBindingInput): SafeBinding {
  const payloadDigest = base64UrlSha256(canonicalizeJson(
    input.bodyWithoutLifecycleContext ?? {},
  ))
  const capabilityPayloadDigest = input.capabilityPayload === null
    ? null
    : base64UrlSha256(input.capabilityPayload)
  const binding = {
    schema: BINDING_SCHEMA,
    ownership_mode: input.context.ownership_mode,
    http_method: input.httpMethod.toUpperCase(),
    route_template: input.routeTemplate,
    command_kind: input.context.command_kind,
    navigator_task_id: input.context.navigator_task_id,
    provider_task_id: input.providerTaskId,
    dispatch_id: input.context.dispatch_id,
    termination_operation_id: input.context.termination_operation_id,
    payload_digest: payloadDigest,
    capability_payload_digest: capabilityPayloadDigest,
  }
  return {
    version: BINDING_VERSION,
    digest: base64UrlSha256(canonicalizeJson(binding)),
    payloadDigest,
    capabilityPayloadDigest,
  }
}

function atomicJsonWrite(filePath: string, value: unknown): void {
  const directory = path.dirname(filePath)
  const tempPath = `${filePath}.${process.pid}.${crypto.randomUUID()}.tmp`
  const handle = fs.openSync(tempPath, 'wx', 0o600)
  try {
    fs.writeFileSync(handle, `${JSON.stringify(value)}\n`, { encoding: 'utf8' })
    fs.fsyncSync(handle)
  } finally {
    fs.closeSync(handle)
  }
  fs.renameSync(tempPath, filePath)
  const directoryHandle = fs.openSync(directory, 'r')
  try {
    fs.fsyncSync(directoryHandle)
  } finally {
    fs.closeSync(directoryHandle)
  }
}

function readJson<T>(filePath: string): T {
  return JSON.parse(fs.readFileSync(filePath, 'utf8')) as T
}

export class LifecycleStore {
  readonly identity: LifecycleIdentity
  private readonly stateFile: string
  private state: DurableState

  private constructor(
    private readonly directory: string,
    identity: LifecycleIdentity,
    state: DurableState,
  ) {
    this.identity = identity
    this.state = state
    this.stateFile = path.join(directory, 'state.json')
  }

  static open(options: OpenOptions): LifecycleStore {
    if (!path.isAbsolute(options.directory)) {
      throw new Error('CODEX_LIFECYCLE_STORE_DIR_REQUIRED')
    }
    const workerId = requireOpaque(
      options.physicalWorkerId,
      'LIFECYCLE_PHYSICAL_WORKER_ID_REQUIRED',
    )
    if (!options.workerToken.trim()) {
      throw new Error('LIFECYCLE_AUTH_NOT_CONFIGURED')
    }
    fs.mkdirSync(options.directory, { recursive: true, mode: 0o700 })
    fs.accessSync(options.directory, fs.constants.R_OK | fs.constants.W_OK)
    const identityFile = path.join(options.directory, 'identity.json')
    const instanceEpoch = options.instanceEpoch?.trim() || crypto.randomUUID()
    let durableIdentity: Omit<LifecycleIdentity, 'instance_epoch'>
    if (fs.existsSync(identityFile)) {
      const existing = readJson<LifecycleIdentity>(identityFile)
      if (existing.physical_worker_id !== workerId) {
        throw new Error('LIFECYCLE_IDENTITY_MISMATCH')
      }
      durableIdentity = {
        schema_version: 1,
        physical_worker_id: existing.physical_worker_id,
        state_generation: existing.state_generation,
        created_at: existing.created_at,
      }
    } else {
      durableIdentity = {
        schema_version: 1,
        physical_worker_id: workerId,
        state_generation: crypto.randomUUID(),
        created_at: new Date().toISOString(),
      }
      atomicJsonWrite(identityFile, durableIdentity)
    }
    const identity: LifecycleIdentity = { ...durableIdentity, instance_epoch: instanceEpoch }
    const stateFile = path.join(options.directory, 'state.json')
    const state = fs.existsSync(stateFile)
      ? readJson<DurableState>(stateFile)
      : {
          schema_version: 1 as const,
          high_watermark: 0,
          acked_through_sequence: 0,
          dispatches: {},
          termination_operations: {},
          facts: [],
        }
    state.termination_operations ??= {}
    if (!fs.existsSync(stateFile)) atomicJsonWrite(stateFile, state)
    return new LifecycleStore(options.directory, identity, state)
  }

  get highWatermark(): number {
    return this.state.high_watermark
  }

  get ackedThroughSequence(): number {
    return this.state.acked_through_sequence
  }

  appendFact(input: LifecycleFactInput): LifecycleFactRecord {
    const sequence = this.state.high_watermark + 1
    const fact: LifecycleFactRecord = {
      ...input,
      schema: LIFECYCLE_SCHEMA,
      schema_version: 1,
      fact_id: crypto.randomUUID(),
      idempotency_key: `${this.identity.state_generation}:${sequence}`,
      source_sequence: sequence,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      recorded_at: new Date().toISOString(),
    }
    this.commit({
      ...this.state,
      high_watermark: sequence,
      facts: [...this.state.facts, fact],
    })
    return fact
  }

  acknowledge(throughSequence: number): number {
    if (!Number.isSafeInteger(throughSequence)
      || throughSequence < 0
      || throughSequence > this.state.high_watermark) {
      throw new Error('LIFECYCLE_ACK_INVALID')
    }
    if (throughSequence <= this.state.acked_through_sequence) {
      return this.state.acked_through_sequence
    }
    this.commit({ ...this.state, acked_through_sequence: throughSequence })
    return throughSequence
  }

  prepareAcceptedDispatch(
    context: LifecycleContext,
    binding: SafeBinding,
    allocateProviderTaskId: () => string,
  ): LifecycleDisposition {
    this.validateContext(context)
    const prior = this.state.dispatches[context.dispatch_id]
    if (prior) {
      this.assertModeAndBinding(prior, context.ownership_mode, binding)
      return {
        ...prior,
        request_delivery_attempt: context.delivery_attempt,
        duplicate: true,
        instance_epoch: this.identity.instance_epoch,
      }
    }
    const providerTaskId = requireOpaque(
      allocateProviderTaskId(),
      'LIFECYCLE_PROVIDER_TASK_ID_UNRESOLVED',
    )
    const nextSequence = this.state.high_watermark + 1
    const disposition: LifecycleDisposition = {
      schema: LIFECYCLE_SCHEMA,
      ownership_mode: context.ownership_mode,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      navigator_task_id: context.navigator_task_id,
      provider_task_id: providerTaskId,
      dispatch_id: context.dispatch_id,
      request_delivery_attempt: context.delivery_attempt,
      disposition_delivery_attempt: context.delivery_attempt,
      command_kind: context.command_kind,
      acceptance_disposition: 'ACCEPTED',
      effect_phase: 'PREPARED',
      disposition_version: 1,
      duplicate: false,
      accepted: true,
      provider_effect_started: false,
      reconcile_required: false,
      safe_binding_digest_version: binding.version,
      safe_binding_digest: binding.digest,
      never_accepted_proof: false,
      fact_cursor: nextSequence,
    }
    this.commit({
      ...this.state,
      high_watermark: nextSequence,
      dispatches: { ...this.state.dispatches, [context.dispatch_id]: disposition },
    })
    return disposition
  }

  prepareAcceptedTerminationDispatch(
    context: LifecycleContext,
    binding: SafeBinding,
    providerTaskId: string,
  ): LifecycleDisposition {
    this.validateContext(context)
    if (context.command_kind !== 'TERMINATION_CANCEL'
        || !context.termination_operation_id) {
      throw new Error('LIFECYCLE_COMMAND_KIND_MISMATCH')
    }
    const prior = this.state.dispatches[context.dispatch_id]
    if (prior) {
      this.assertModeAndBinding(prior, context.ownership_mode, binding)
      if (prior.termination_operation_id !== context.termination_operation_id) {
        throw new Error('TERMINATION_OPERATION_REPLAY_DETECTED')
      }
      return {
        ...prior,
        request_delivery_attempt: context.delivery_attempt,
        duplicate: true,
        instance_epoch: this.identity.instance_epoch,
      }
    }
    const boundDispatch = this.state.termination_operations[
      context.termination_operation_id
    ]
    if (boundDispatch && boundDispatch !== context.dispatch_id) {
      throw new Error('TERMINATION_OPERATION_REPLAY_DETECTED')
    }
    const nextSequence = this.state.high_watermark + 1
    const disposition: LifecycleDisposition = {
      schema: LIFECYCLE_SCHEMA,
      ownership_mode: context.ownership_mode,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      navigator_task_id: context.navigator_task_id,
      provider_task_id: requireOpaque(
        providerTaskId,
        'LIFECYCLE_PROVIDER_TASK_ID_UNRESOLVED',
      ),
      dispatch_id: context.dispatch_id,
      request_delivery_attempt: context.delivery_attempt,
      disposition_delivery_attempt: context.delivery_attempt,
      command_kind: 'TERMINATION_CANCEL',
      acceptance_disposition: 'ACCEPTED',
      effect_phase: 'PREPARED',
      disposition_version: 1,
      duplicate: false,
      accepted: true,
      provider_effect_started: false,
      reconcile_required: false,
      safe_binding_digest_version: binding.version,
      safe_binding_digest: binding.digest,
      never_accepted_proof: false,
      fact_cursor: nextSequence,
      termination_operation_id: context.termination_operation_id,
    }
    this.commit({
      ...this.state,
      high_watermark: nextSequence,
      dispatches: { ...this.state.dispatches, [context.dispatch_id]: disposition },
      termination_operations: {
        ...this.state.termination_operations,
        [context.termination_operation_id]: context.dispatch_id,
      },
    })
    return disposition
  }

  /**
   * Atomically records a fail-closed admission rejection and the exact fact
   * authorizing Navigator to prove that no provider effect was ever accepted.
   */
  rejectBeforeEffect(
    context: LifecycleContext,
    binding: SafeBinding,
    reason: NeverAcceptedReasonCode,
  ): LifecycleDisposition {
    this.validateContext(context)
    if (context.ownership_mode !== 'ENFORCED') {
      throw new Error('LIFECYCLE_OWNERSHIP_MODE_MISMATCH')
    }
    if (!NEVER_ACCEPTED_REASON_CODES.includes(reason)) {
      throw new Error('LIFECYCLE_NEVER_ACCEPTED_REASON_NOT_ALLOWED')
    }
    const prior = this.state.dispatches[context.dispatch_id]
    if (prior) {
      this.assertModeAndBinding(prior, context.ownership_mode, binding)
      if (prior.acceptance_disposition !== 'REJECTED'
          || prior.effect_phase !== 'PRE_EFFECT'
          || prior.code !== reason
          || !prior.never_accepted_proof) {
        throw new Error('LIFECYCLE_DISPATCH_ALREADY_ACCEPTED')
      }
      return {
        ...prior,
        request_delivery_attempt: context.delivery_attempt,
        duplicate: true,
        instance_epoch: this.identity.instance_epoch,
      }
    }

    const nextSequence = this.state.high_watermark + 1
    const disposition: LifecycleDisposition = {
      schema: LIFECYCLE_SCHEMA,
      ownership_mode: context.ownership_mode,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      navigator_task_id: context.navigator_task_id,
      provider_task_id: null,
      dispatch_id: context.dispatch_id,
      request_delivery_attempt: context.delivery_attempt,
      disposition_delivery_attempt: context.delivery_attempt,
      command_kind: context.command_kind,
      acceptance_disposition: 'REJECTED',
      effect_phase: 'PRE_EFFECT',
      disposition_version: 1,
      duplicate: false,
      accepted: false,
      provider_effect_started: false,
      reconcile_required: false,
      safe_binding_digest_version: binding.version,
      safe_binding_digest: binding.digest,
      never_accepted_proof: true,
      fact_cursor: nextSequence,
      code: reason,
    }
    const fact: LifecycleFactRecord = {
      fact_type: 'TASK_NEVER_ACCEPTED_CONFIRMED',
      aggregate_type: 'TASK',
      aggregate_id: context.navigator_task_id,
      safe_reason_code: reason,
      navigator_task_id: context.navigator_task_id,
      ownership_mode: context.ownership_mode,
      dispatch_id: context.dispatch_id,
      safe_binding_digest_version: binding.version,
      safe_binding_digest: binding.digest,
      schema: LIFECYCLE_SCHEMA,
      schema_version: 1,
      fact_id: crypto.randomUUID(),
      idempotency_key: `${this.identity.state_generation}:${nextSequence}`,
      source_sequence: nextSequence,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      recorded_at: new Date().toISOString(),
    }
    this.commit({
      ...this.state,
      high_watermark: nextSequence,
      dispatches: {
        ...this.state.dispatches,
        [context.dispatch_id]: disposition,
      },
      facts: [...this.state.facts, fact],
    })
    return disposition
  }

  markEffectStarted(dispatchId: string): LifecycleDisposition {
    const prior = this.state.dispatches[dispatchId]
    if (!prior) throw new Error('LIFECYCLE_DISPATCH_NOT_FOUND')
    if (prior.effect_phase === 'EFFECT_STARTED'
        || prior.effect_phase === 'RESULT_OBSERVED') {
      return prior
    }
    const nextSequence = this.state.high_watermark + 1
    const next: LifecycleDisposition = {
      ...prior,
      effect_phase: 'EFFECT_STARTED',
      disposition_version: prior.disposition_version + 1,
      provider_effect_started: true,
      fact_cursor: nextSequence,
    }
    this.commit({
      ...this.state,
      high_watermark: nextSequence,
      dispatches: { ...this.state.dispatches, [dispatchId]: next },
    })
    return next
  }

  /**
   * Atomically records the no-longer-replayable result phase and its normalized
   * lifecycle fact. A restart or redelivery observes the same disposition.
   */
  markResultObserved(
    dispatchId: string,
    factType: string,
    terminalOutcome: 'COMPLETED' | 'FAILED' | 'CANCELLED' | null,
    safeReasonCode: string,
  ): LifecycleDisposition {
    const prior = this.state.dispatches[dispatchId]
    if (!prior) throw new Error('LIFECYCLE_DISPATCH_NOT_FOUND')
    if (prior.effect_phase === 'RESULT_OBSERVED') return prior
    if (prior.effect_phase !== 'EFFECT_STARTED') {
      throw new Error('LIFECYCLE_EFFECT_NOT_STARTED')
    }
    const nextSequence = this.state.high_watermark + 1
    const next: LifecycleDisposition = {
      ...prior,
      effect_phase: 'RESULT_OBSERVED',
      disposition_version: prior.disposition_version + 1,
      provider_effect_started: true,
      fact_cursor: nextSequence,
    }
    const fact: LifecycleFactRecord = {
      fact_type: factType,
      aggregate_type: terminalOutcome ? 'TASK' : 'TERMINATION_OPERATION',
      aggregate_id: terminalOutcome
        ? prior.navigator_task_id
        : prior.termination_operation_id ?? prior.dispatch_id,
      safe_reason_code: safeReasonCode,
      navigator_task_id: prior.navigator_task_id,
      provider_task_id: prior.provider_task_id ?? undefined,
      operation_id: prior.termination_operation_id,
      ownership_mode: prior.ownership_mode,
      dispatch_id: prior.dispatch_id,
      safe_binding_digest_version: prior.safe_binding_digest_version,
      safe_binding_digest: prior.safe_binding_digest,
      terminal_outcome: terminalOutcome ?? undefined,
      schema: LIFECYCLE_SCHEMA,
      schema_version: 1,
      fact_id: crypto.randomUUID(),
      idempotency_key: `${this.identity.state_generation}:${nextSequence}`,
      source_sequence: nextSequence,
      physical_worker_id: this.identity.physical_worker_id,
      state_generation: this.identity.state_generation,
      instance_epoch: this.identity.instance_epoch,
      recorded_at: new Date().toISOString(),
    }
    this.commit({
      ...this.state,
      high_watermark: nextSequence,
      dispatches: { ...this.state.dispatches, [dispatchId]: next },
      facts: [...this.state.facts, fact],
    })
    return next
  }

  /**
   * Converges every authorized lifecycle dispatch for the same provider task.
   * A normal asynchronous cancellation is first acknowledged as
   * CANCEL_REQUESTED by the abort route and becomes terminal later in the SDK
   * completion path.  Persisting both the initial query disposition and the
   * termination disposition here keeps their immutable bindings separate
   * while emitting an exact terminal fact for each authorized command.
   */
  markProviderTaskTerminal(
    providerTaskId: string,
    terminalOutcome: 'COMPLETED' | 'FAILED' | 'CANCELLED',
    safeReasonCode = 'PROVIDER_RESULT_OBSERVED',
  ): LifecycleDisposition[] {
    const matching = Object.values(this.state.dispatches)
      .filter(disposition => (
        disposition.provider_task_id === providerTaskId
        && disposition.effect_phase === 'EFFECT_STARTED'
      ))
      .sort((left, right) => left.dispatch_id.localeCompare(right.dispatch_id))
    return matching.map(disposition => this.markResultObserved(
      disposition.dispatch_id,
      'TASK_PROVIDER_TERMINAL_OBSERVED',
      terminalOutcome,
      safeReasonCode,
    ))
  }

  getDispatch(
    dispatchId: string,
    expectedMode: LifecycleOwnershipMode,
    binding: SafeBinding,
  ): LifecycleDisposition | undefined {
    const record = this.state.dispatches[dispatchId]
    if (!record) return undefined
    this.assertModeAndBinding(record, expectedMode, binding)
    return { ...record, request_delivery_attempt: null }
  }

  inventory(afterSequence: number): {
    min_available_sequence: number
    through_sequence: number
    facts: LifecycleFactRecord[]
    dispatches: LifecycleDisposition[]
    tasks: Array<{
      navigator_task_id: string
      provider_task_id: string
      ownership_mode: LifecycleOwnershipMode
      initial_dispatch_id: string
      safe_binding_digest_version: typeof BINDING_VERSION
      safe_binding_digest: string
      lifecycle_state: string
      last_sequence: number
    }>
  } {
    if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) {
      throw new Error('LIFECYCLE_CURSOR_INVALID')
    }
    const dispatches = Object.values(this.state.dispatches)
    const taskDispositions = dispatches
      .filter(disposition => (
        disposition.provider_task_id !== null
        && disposition.command_kind !== 'TERMINATION_CANCEL'
      ))
      .sort((left, right) => left.fact_cursor - right.fact_cursor)
    const seenTasks = new Set<string>()
    return {
      min_available_sequence: 1,
      through_sequence: this.state.high_watermark,
      facts: this.state.facts.filter(fact => fact.source_sequence > afterSequence),
      dispatches,
      tasks: taskDispositions
        .filter(disposition => {
          const identity = `${disposition.navigator_task_id}\u0000${disposition.provider_task_id}`
          if (seenTasks.has(identity)) return false
          seenTasks.add(identity)
          return true
        })
        .map(disposition => ({
          navigator_task_id: disposition.navigator_task_id,
          provider_task_id: disposition.provider_task_id as string,
          ownership_mode: disposition.ownership_mode,
          initial_dispatch_id: disposition.dispatch_id,
          safe_binding_digest_version: disposition.safe_binding_digest_version,
          safe_binding_digest: disposition.safe_binding_digest,
          lifecycle_state: disposition.effect_phase,
          last_sequence: disposition.fact_cursor,
        })),
    }
  }

  private validateContext(context: LifecycleContext): void {
    if (context.schema !== LIFECYCLE_SCHEMA
      || !['SHADOW', 'ENFORCED'].includes(context.ownership_mode)
      || !Number.isSafeInteger(context.delivery_attempt)
      || context.delivery_attempt < 1) {
      throw new Error('LIFECYCLE_CONTEXT_INVALID')
    }
    if (context.expected_physical_worker_id !== this.identity.physical_worker_id) {
      throw new Error('LIFECYCLE_IDENTITY_MISMATCH')
    }
    if (context.expected_state_generation !== this.identity.state_generation) {
      throw new Error('LIFECYCLE_STATE_GENERATION_MISMATCH')
    }
  }

  private assertModeAndBinding(
    prior: LifecycleDisposition,
    expectedMode: LifecycleOwnershipMode,
    binding: SafeBinding,
  ): void {
    if (prior.ownership_mode !== expectedMode) {
      throw new Error('LIFECYCLE_OWNERSHIP_MODE_MISMATCH')
    }
    if (prior.safe_binding_digest_version !== binding.version
      || !crypto.timingSafeEqual(
        Buffer.from(prior.safe_binding_digest, 'base64url'),
        Buffer.from(binding.digest, 'base64url'),
      )) {
      throw new Error('LIFECYCLE_DISPATCH_BINDING_MISMATCH')
    }
  }

  private commit(next: DurableState): void {
    atomicJsonWrite(this.stateFile, next)
    this.state = next
  }
}
