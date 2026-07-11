import type {
  CodexRuntime,
  CodexRuntimeRoutingPolicy,
} from '@/types/codexRuntime'

export type RuntimeTagType = 'success' | 'warning' | 'danger' | 'info'

export const CODEX_RUNTIME_POLICIES: ReadonlyArray<{
  value: CodexRuntimeRoutingPolicy
  label: string
}> = [
  { value: 'DARK', label: 'Dark' },
  { value: 'ULTRA_CANARY', label: 'Ultra 灰度' },
  { value: 'ULTRA_DEFAULT', label: 'Ultra 默认' },
  { value: 'ALL_CANARY', label: '全模型灰度' },
  { value: 'ALL_DEFAULT', label: '全模型默认' },
  { value: 'DRAINING', label: '排空' },
]

const ROUTING_POLICY_LABELS: Readonly<Record<CodexRuntimeRoutingPolicy, string>> =
  Object.fromEntries(CODEX_RUNTIME_POLICIES.map((policy) => [policy.value, policy.label])) as
    Readonly<Record<CodexRuntimeRoutingPolicy, string>>

const ADJACENT_ROUTING_POLICIES: Readonly<Record<CodexRuntimeRoutingPolicy, readonly CodexRuntimeRoutingPolicy[]>> = {
  DARK: ['ULTRA_CANARY', 'DRAINING'],
  ULTRA_CANARY: ['DARK', 'ULTRA_DEFAULT', 'DRAINING'],
  ULTRA_DEFAULT: ['ULTRA_CANARY', 'ALL_CANARY', 'DRAINING'],
  ALL_CANARY: ['ULTRA_DEFAULT', 'ALL_DEFAULT', 'DRAINING'],
  ALL_DEFAULT: ['ALL_CANARY', 'DRAINING'],
  DRAINING: ['DARK'],
}

export function runtimeKey(runtime: Pick<CodexRuntime, 'runtimeId' | 'revision'>): string {
  return `${runtime.runtimeId}@${runtime.revision}`
}

export function runtimeInstanceKey(
  runtime: Pick<CodexRuntime, 'runtimeId' | 'revision' | 'instanceId'>,
): string {
  return `${runtimeKey(runtime)}#${runtime.instanceId ?? ''}`
}

export function isCanaryPolicy(policy: CodexRuntimeRoutingPolicy): boolean {
  return policy === 'ULTRA_CANARY' || policy === 'ALL_CANARY'
}

export function isRoutingPolicyTransitionAllowed(
  current: CodexRuntimeRoutingPolicy,
  requested: CodexRuntimeRoutingPolicy,
): boolean {
  return current === requested || ADJACENT_ROUTING_POLICIES[current].includes(requested)
}

export function routingTransitionBlockReason(
  current: CodexRuntimeRoutingPolicy,
  requested: CodexRuntimeRoutingPolicy,
): string | undefined {
  if (isRoutingPolicyTransitionAllowed(current, requested)) return undefined

  const queue: Array<{ policy: CodexRuntimeRoutingPolicy; firstStep?: CodexRuntimeRoutingPolicy }> = [
    { policy: current },
  ]
  const visited = new Set<CodexRuntimeRoutingPolicy>([current])

  while (queue.length > 0) {
    const node = queue.shift()!
    for (const next of ADJACENT_ROUTING_POLICIES[node.policy]) {
      if (next === 'DRAINING' && requested !== 'DRAINING') continue
      if (visited.has(next)) continue
      const firstStep = node.firstStep || next
      if (next === requested) return `需先切换至 ${ROUTING_POLICY_LABELS[firstStep]}`
      visited.add(next)
      queue.push({ policy: next, firstStep })
    }
  }

  return '当前阶段不可切换'
}

export function routingPolicyOptionLabel(
  current: CodexRuntimeRoutingPolicy,
  requested: CodexRuntimeRoutingPolicy,
): string {
  const label = ROUTING_POLICY_LABELS[requested]
  const reason = routingTransitionBlockReason(current, requested)
  return reason ? `${label}（${reason}）` : label
}

export function isUltraRoutingPolicy(policy: CodexRuntimeRoutingPolicy): boolean {
  return policy === 'ULTRA_CANARY'
    || policy === 'ULTRA_DEFAULT'
    || policy === 'ALL_CANARY'
    || policy === 'ALL_DEFAULT'
}

export function isUltraRoutingConfigured(runtime: CodexRuntime): boolean {
  if (!runtime.enabled) return false
  if (!isUltraRoutingPolicy(runtime.routingPolicy)) return false
  return runtime.routingPolicy !== 'ULTRA_CANARY' || runtime.rolloutPercentage > 0
}

export function isRuntimeCapabilityFresh(
  runtime: CodexRuntime,
): boolean {
  return runtime.capabilityFresh === true
}

export function supportsUltraCapability(runtime: CodexRuntime): boolean {
  return runtime.supportsUltra === true
}

export function isUltraRuntimeAvailable(runtime: CodexRuntime): boolean {
  return runtime.archived !== true
    && runtime.readinessStatus === 'READY'
    && isUltraRoutingConfigured(runtime)
    && isRuntimeCapabilityFresh(runtime)
    && supportsUltraCapability(runtime)
}

export function readinessLabel(status: string): string {
  switch (status) {
    case 'READY': return 'Ready'
    case 'PENDING': return '待检查'
    case 'INCOMPATIBLE': return '不兼容'
    case 'UNREACHABLE': return '不可达'
    default: return status || '未知'
  }
}

export function readinessTagType(status: string): RuntimeTagType {
  switch (status) {
    case 'READY': return 'success'
    case 'PENDING': return 'warning'
    case 'INCOMPATIBLE':
    case 'UNREACHABLE': return 'danger'
    default: return 'info'
  }
}

export function shortDigest(value?: string): string {
  if (!value) return '-'
  return value.length > 14 ? `${value.slice(0, 12)}...` : value
}
