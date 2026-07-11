import { describe, expect, it } from 'vitest'
import type { CodexRuntime } from '@/types/codexRuntime'
import {
  isRoutingPolicyTransitionAllowed,
  isUltraRuntimeAvailable,
  readinessLabel,
  readinessTagType,
  routingPolicyOptionLabel,
  routingTransitionBlockReason,
  runtimeInstanceKey,
  shortDigest,
} from '../codexRuntime'

function makeRuntime(overrides: Partial<CodexRuntime> = {}): CodexRuntime {
  return {
    runtimeId: 'runtime-1',
    revision: 1,
    workerId: 'worker-1',
    runtimeType: 'APP_SERVER',
    endpointConfigured: true,
    enabled: true,
    routingPolicy: 'ULTRA_DEFAULT',
    rolloutPercentage: 0,
    priority: 0,
    routingEpoch: 1,
    readinessStatus: 'READY',
    capabilityFresh: true,
    supportsUltra: true,
    lastCapabilityAt: new Date().toISOString(),
    createdAt: '2026-07-10T10:00:00',
    updatedAt: '2026-07-10T10:00:00',
    ...overrides,
  }
}

describe('codexRuntime UI state', () => {
  it('requires readiness, enabled routing, and a non-zero Ultra-only canary cohort for Ultra', () => {
    expect(isUltraRuntimeAvailable(makeRuntime())).toBe(true)
    expect(isUltraRuntimeAvailable(makeRuntime({ enabled: false }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ readinessStatus: 'INCOMPATIBLE' }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ routingPolicy: 'DARK' }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ routingPolicy: 'ULTRA_CANARY', rolloutPercentage: 0 }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ routingPolicy: 'ULTRA_CANARY', rolloutPercentage: 5 }))).toBe(true)
    expect(isUltraRuntimeAvailable(makeRuntime({ routingPolicy: 'ALL_CANARY', rolloutPercentage: 0 }))).toBe(true)
    expect(isUltraRuntimeAvailable(makeRuntime({ routingPolicy: 'ALL_DEFAULT' }))).toBe(true)
    expect(isUltraRuntimeAvailable(makeRuntime({ capabilityFresh: false }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ capabilityFresh: undefined }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ supportsUltra: false }))).toBe(false)
    expect(isUltraRuntimeAvailable(makeRuntime({ supportsUltra: undefined }))).toBe(false)
  })

  it('maps readiness states and truncates long schema digests', () => {
    expect(readinessLabel('READY')).toBe('Ready')
    expect(readinessLabel('INCOMPATIBLE')).toBe('不兼容')
    expect(readinessTagType('UNREACHABLE')).toBe('danger')
    expect(shortDigest('6f2550bb528581f17c4c3a3857dca92c')).toBe('6f2550bb5285...')
  })

  it('distinguishes restarted instances of the same runtime revision', () => {
    expect(runtimeInstanceKey(makeRuntime({ instanceId: 'instance-a' })))
      .toBe('runtime-1@1#instance-a')
    expect(runtimeInstanceKey(makeRuntime({ instanceId: 'instance-b' })))
      .toBe('runtime-1@1#instance-b')
  })

  it('allows only adjacent routing stages and explains disabled jumps', () => {
    expect(isRoutingPolicyTransitionAllowed('DARK', 'ULTRA_CANARY')).toBe(true)
    expect(isRoutingPolicyTransitionAllowed('DARK', 'DRAINING')).toBe(true)
    expect(isRoutingPolicyTransitionAllowed('DARK', 'ULTRA_DEFAULT')).toBe(false)
    expect(routingTransitionBlockReason('DARK', 'ALL_DEFAULT')).toBe('需先切换至 Ultra 灰度')
    expect(routingTransitionBlockReason('ALL_DEFAULT', 'DARK')).toBe('需先切换至 全模型灰度')
    expect(routingPolicyOptionLabel('DARK', 'ALL_DEFAULT'))
      .toBe('全模型默认（需先切换至 Ultra 灰度）')
  })
})
