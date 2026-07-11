const MAX_LIMIT_BUCKETS = 64
const MAX_LIMIT_ID_LENGTH = 128
const MAX_LIMIT_NAME_LENGTH = 256

const REACHED_TYPES = new Set([
  'rate_limit_reached',
  'workspace_owner_credits_depleted',
  'workspace_member_credits_depleted',
  'workspace_owner_usage_limit_reached',
  'workspace_member_usage_limit_reached',
])

export type SafeRateLimitWindow = {
  used_percent: number
  window_duration_mins: number | null
  resets_at: number | null
}

export type SafeRateLimitSnapshot = {
  limit_id: string | null
  limit_name: string | null
  primary: SafeRateLimitWindow | null
  secondary: SafeRateLimitWindow | null
  rate_limit_reached_type: string | null
}

export type SafeAccountRateLimits = {
  limits: SafeRateLimitSnapshot[]
}

export type RateLimitsState = 'AVAILABLE' | 'LIMIT_REACHED' | 'STALE' | 'UNSUPPORTED' | 'UNKNOWN'

export type PoolRateLimitsView = {
  state: RateLimitsState
  observed_at_epoch_ms: number | null
  stale: boolean
  limits: SafeRateLimitSnapshot[]
  error_code: string | null
}

export class RateLimitsProtocolError extends Error {
  readonly code = 'RATE_LIMITS_RESPONSE_INVALID'

  constructor() {
    super('Codex app-server returned an invalid rate-limit response')
    this.name = 'RateLimitsProtocolError'
  }
}

export function parseAccountRateLimitsRead(value: unknown): SafeAccountRateLimits {
  const response = requiredRecord(value)
  const historical = parseSnapshot(response.rateLimits)
  const byLimitId = optionalRecord(response.rateLimitsByLimitId)
  if (!byLimitId || Object.keys(byLimitId).length === 0) {
    return { limits: [historical] }
  }
  const entries = Object.entries(byLimitId)
  if (entries.length > MAX_LIMIT_BUCKETS) throw new RateLimitsProtocolError()
  const limits = entries.map(([limitId, snapshot]) => {
    const safeId = boundedString(limitId, MAX_LIMIT_ID_LENGTH, false)
    const parsed = parseSnapshot(snapshot)
    if (parsed.limit_id !== null && parsed.limit_id !== safeId) throw new RateLimitsProtocolError()
    return { ...parsed, limit_id: parsed.limit_id || safeId }
  })
  limits.sort((left, right) => (left.limit_id || '').localeCompare(right.limit_id || ''))
  return { limits }
}

export function isAccountRateLimitsUpdated(method: string, params: unknown): boolean {
  if (method !== 'account/rateLimits/updated') return false
  const record = optionalRecord(params)
  return Boolean(record && optionalRecord(record.rateLimits))
}

export function isRateLimitReached(limits: SafeAccountRateLimits): boolean {
  return limits.limits.some(limit => (
    limit.rate_limit_reached_type !== null
    || (limit.primary?.used_percent ?? 0) >= 100
    || (limit.secondary?.used_percent ?? 0) >= 100
  ))
}

function parseSnapshot(value: unknown): SafeRateLimitSnapshot {
  const snapshot = requiredRecord(value)
  return {
    limit_id: nullableBoundedString(snapshot.limitId, MAX_LIMIT_ID_LENGTH),
    limit_name: nullableBoundedString(snapshot.limitName, MAX_LIMIT_NAME_LENGTH),
    primary: nullableWindow(snapshot.primary),
    secondary: nullableWindow(snapshot.secondary),
    rate_limit_reached_type: nullableReachedType(snapshot.rateLimitReachedType),
  }
}

function nullableWindow(value: unknown): SafeRateLimitWindow | null {
  if (value === undefined || value === null) return null
  const window = requiredRecord(value)
  return {
    used_percent: boundedInteger(window.usedPercent, 0, 100),
    window_duration_mins: nullableInteger(window.windowDurationMins, 1, Number.MAX_SAFE_INTEGER),
    resets_at: nullableInteger(window.resetsAt, 0, Number.MAX_SAFE_INTEGER),
  }
}

function nullableReachedType(value: unknown): string | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string' || !REACHED_TYPES.has(value)) throw new RateLimitsProtocolError()
  return value
}

function nullableBoundedString(value: unknown, maxLength: number): string | null {
  if (value === undefined || value === null) return null
  return boundedString(value, maxLength, true)
}

function boundedString(value: unknown, maxLength: number, allowEmpty: boolean): string {
  if (typeof value !== 'string'
      || (!allowEmpty && value.length === 0)
      || value.length > maxLength
      || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new RateLimitsProtocolError()
  }
  return value
}

function nullableInteger(value: unknown, min: number, max: number): number | null {
  if (value === undefined || value === null) return null
  return boundedInteger(value, min, max)
}

function boundedInteger(value: unknown, min: number, max: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) {
    throw new RateLimitsProtocolError()
  }
  return value as number
}

function requiredRecord(value: unknown): Record<string, unknown> {
  const record = optionalRecord(value)
  if (!record) throw new RateLimitsProtocolError()
  return record
}

function optionalRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}
