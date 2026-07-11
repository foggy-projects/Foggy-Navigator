import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isAccountRateLimitsUpdated,
  isRateLimitReached,
  parseAccountRateLimitsRead,
  RateLimitsProtocolError,
} from '../src/app-server/rate-limits.js'

test('rate-limit read projects only the pinned non-sensitive allowlist', () => {
  const parsed = parseAccountRateLimitsRead({
    rateLimits: snapshot('codex', 25),
    rateLimitsByLimitId: {
      codex: snapshot('codex', 25),
      review: snapshot('review', 100, 'rate_limit_reached'),
    },
    rateLimitResetCredits: {
      availableCount: 1,
      credits: [{ id: 'private-reset-credit-id' }],
    },
    email: 'private@example.com',
  })

  assert.deepEqual(parsed, {
    limits: [
      safeSnapshot('codex', 25, null),
      safeSnapshot('review', 100, 'rate_limit_reached'),
    ],
  })
  const serialized = JSON.stringify(parsed)
  for (const secret of [
    'private@example.com', 'private-reset-credit-id', 'planType', 'credits',
    'individualLimit', 'rateLimitResetCredits', 'balance',
  ]) {
    assert.equal(serialized.includes(secret), false)
  }
  assert.equal(isRateLimitReached(parsed), true)
})

test('rate-limit parser falls back to the historical bucket and validates boundaries', () => {
  assert.deepEqual(parseAccountRateLimitsRead({ rateLimits: snapshot(null, 0) }), {
    limits: [safeSnapshot(null, 0, null)],
  })
  for (const invalid of [
    { rateLimits: { ...snapshot('codex', 0), primary: { usedPercent: -1 } } },
    { rateLimits: { ...snapshot('codex', 0), primary: { usedPercent: 101 } } },
    { rateLimits: { ...snapshot('codex', 0), primary: { usedPercent: 1.5 } } },
    { rateLimits: { ...snapshot('codex', 0), rateLimitReachedType: 'future_private_value' } },
    { rateLimits: snapshot('codex', 0), rateLimitsByLimitId: { other: snapshot('codex', 0) } },
  ]) {
    assert.throws(() => parseAccountRateLimitsRead(invalid), RateLimitsProtocolError)
  }
})

test('sparse updated notifications are invalidations only', () => {
  assert.equal(isAccountRateLimitsUpdated('account/rateLimits/updated', {
    rateLimits: { primary: { usedPercent: 50 }, planType: 'private-plan' },
  }), true)
  assert.equal(isAccountRateLimitsUpdated('account/rateLimits/updated', {}), false)
  assert.equal(isAccountRateLimitsUpdated('account/updated', { rateLimits: {} }), false)
})

function snapshot(limitId: string | null, usedPercent: number, reached: string | null = null) {
  return {
    limitId,
    limitName: limitId ? `${limitId} limit` : null,
    primary: { usedPercent, windowDurationMins: 300, resetsAt: 1_800_000_000 },
    secondary: null,
    credits: { hasCredits: true, unlimited: false, balance: '99.00-private' },
    individualLimit: { limit: '1000', used: '10', remainingPercent: 99, resetsAt: 1_900_000_000 },
    planType: 'private-plan',
    rateLimitReachedType: reached,
    arbitraryPrivateField: 'must-not-cross-boundary',
  }
}

function safeSnapshot(limitId: string | null, usedPercent: number, reached: string | null) {
  return {
    limit_id: limitId,
    limit_name: limitId ? `${limitId} limit` : null,
    primary: { used_percent: usedPercent, window_duration_mins: 300, resets_at: 1_800_000_000 },
    secondary: null,
    rate_limit_reached_type: reached,
  }
}
