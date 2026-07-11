import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import type { AppConfig } from './config.js'
import type { TaskManager } from './task-manager.js'
import {
  assertCodexHomeIsolation,
  hasUsableAllowedWorkingRoot,
  workerPrivatePaths,
} from './path-guards.js'
import {
  resolveBundledCodexLauncher,
  resolveBundledCodexVersion,
  VALIDATED_APP_SERVER_CLI_VERSION,
} from './app-server/runtime.js'
import {
  APP_SERVER_PROTOCOL_VERSION,
  APP_VERSION,
  CAPABILITY_CONTRACT_VERSION,
  WORKER_CONTRACT_VERSION,
} from './version.js'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const schemaLock = JSON.parse(fs.readFileSync(
  path.join(packageRoot, 'contracts', 'app-server-schema-lock.json'),
  'utf8',
)) as { schema_digest: string }

export type RuntimeReadiness = {
  ready: boolean
  reasons: string[]
  cliVersion: string | undefined
  cliAvailable: boolean
}

const CLI_PROBE_TTL_MS = 30_000
const MODEL_REASONING_MATRIX: Readonly<Record<string, readonly string[]>> = Object.freeze({
  'gpt-5.6-sol': Object.freeze(['low', 'medium', 'high', 'xhigh', 'max', 'ultra']),
  'gpt-5.6-terra': Object.freeze(['low', 'medium', 'high', 'xhigh', 'max', 'ultra']),
  'gpt-5.6-luna': Object.freeze(['low', 'medium', 'high', 'xhigh', 'max']),
  'gpt-5.5': Object.freeze(['low', 'medium', 'high', 'xhigh']),
  'gpt-5.4': Object.freeze(['low', 'medium', 'high', 'xhigh']),
  'gpt-5.3-codex-spark': Object.freeze(['low', 'medium', 'high', 'xhigh']),
})
let cachedCliProbe: { expiresAt: number; available: boolean } | undefined

export function resolveRuntimeReadiness(config: AppConfig): RuntimeReadiness {
  const cliVersion = resolveBundledCodexVersion()
  const cliAvailable = resolveCachedCliAvailability()
  return evaluateRuntimeReadiness(config, cliVersion, cliAvailable)
}

export function evaluateRuntimeReadiness(
  config: AppConfig,
  cliVersion: string | undefined,
  cliAvailable: boolean,
): RuntimeReadiness {
  const reasons: string[] = []
  if (!config.stateEncryptionKey) reasons.push('STATE_ENCRYPTION_KEY_MISSING')
  if (!config.workerToken) reasons.push('WORKER_TOKEN_MISSING')
  if (config.allowedCwds.length === 0) reasons.push('ALLOWED_CWDS_MISSING')
  else if (!hasUsableAllowedWorkingRoot(config.allowedCwds, workerPrivatePaths(config))) {
    reasons.push('ALLOWED_CWDS_UNAVAILABLE')
  }
  if (!config.codexHome) reasons.push('CODEX_HOME_MISSING')
  if (config.codexHome) {
    try {
      assertCodexHomeIsolation(config)
    } catch {
      reasons.push('CODEX_HOME_NOT_ISOLATED')
    }
  }
  if (!cliAvailable) reasons.push('APP_SERVER_CLI_UNAVAILABLE')
  if (cliVersion !== VALIDATED_APP_SERVER_CLI_VERSION) reasons.push('APP_SERVER_CLI_VERSION_MISMATCH')
  return { ready: reasons.length === 0, reasons, cliVersion, cliAvailable }
}

export function resetRuntimeProbeCacheForTests(): void {
  cachedCliProbe = undefined
}

export function buildCapabilityManifest(config: AppConfig, manager: TaskManager): Record<string, unknown> {
  const readiness = resolveRuntimeReadiness(config)
  const modelAliases = supportedModelAliases(config.modelAliases)
  if (!manager.isAccepting()) {
    readiness.ready = false
    readiness.reasons.push('APP_SERVER_WORKER_DRAINING')
  }
  return {
    contract_version: CAPABILITY_CONTRACT_VERSION,
    worker_contract_version: WORKER_CONTRACT_VERSION,
    app_server_protocol_version: APP_SERVER_PROTOCOL_VERSION,
    cli_version: readiness.cliVersion || null,
    validated_cli_version: VALIDATED_APP_SERVER_CLI_VERSION,
    schema_digest: schemaLock.schema_digest,
    runtime_type: 'APP_SERVER',
    runtime_id: config.runtimeId,
    runtime_revision: config.runtimeRevision,
    instance_id: config.instanceId,
    models: Object.keys(MODEL_REASONING_MATRIX),
    model_reasoning_matrix: MODEL_REASONING_MATRIX,
    model_aliases: modelAliases,
    worker: {
      name: config.workerName,
      version: APP_VERSION,
      hostname: os.hostname(),
    },
    runtime: {
      runtime_id: config.runtimeId,
      runtime_revision: config.runtimeRevision,
      instance_id: config.instanceId,
      kind: 'APP_SERVER',
    },
    model_capabilities: {
      dynamic_passthrough: {
        direct_execution: true,
        route_selectable: false,
      },
      catalog_source: 'pinned-cli-model-list-dark-launch-snapshot',
      dynamic_catalog_refresh: false,
      validated_models: Object.keys(MODEL_REASONING_MATRIX),
      reasoning_by_model: MODEL_REASONING_MATRIX,
      aliases: modelAliases,
    },
    features: {
      task_accept: true,
      idempotency_key: true,
      durable_acceptance: true,
      durable_events: true,
      resume: true,
      thread_resume: true,
      task_recovery_accepted: true,
      committed_reconciliation: true,
      instance_affinity_guard: true,
      terminal_cleanup: true,
      tombstone_idempotency: true,
      exclusive_process_lease: true,
      same_thread_turn_lock: true,
      same_cwd_write_lock: true,
      abort: true,
      images: true,
      output_schema: true,
      developer_instructions: true,
      sandbox: true,
      approval_modes: ['never'],
      interactive_user_input: true,
      interactive_user_input_experimental: true,
      account_rate_limits_read: true,
      account_rate_limits_advisory_only: true,
      account_rate_limits_model_routing: false,
      network: true,
      web: true,
      attachments: false,
      additional_directories: false,
      business_mcp: false,
      max_turns: false,
      max_turns_one_only: true,
      native_subtask_contract_versions: [1],
    },
    capacity: {
      max_concurrent_tasks: config.maxConcurrentTasks,
      max_queued_tasks: config.maxQueuedTasks,
      pool_max_instances: config.poolMaxInstances,
      pool_max_instances_per_lane: config.poolMaxInstancesPerLane,
      pool_max_queue: config.poolMaxQueue,
      pool_idle_ttl_ms: config.poolIdleTtlMs,
      pool_max_lifetime_ms: config.poolMaxLifetimeMs,
      pool_max_tasks_per_instance: config.poolMaxTasksPerInstance,
      active_tasks: manager.activeCount(),
      queued_tasks: manager.queuedCount(),
      pool_mode: 'exclusive-lease',
      ...manager.runtimeMetrics(),
    },
    readiness: {
      ready: readiness.ready,
      reasons: readiness.reasons,
      cli_available: readiness.cliAvailable,
    },
  }
}

function supportedModelAliases(aliases: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(aliases).filter(([, value]) => (
    value.split(':', 1)[0]?.trim().toLowerCase() !== 'gpt-5.4-mini'
  )))
}

function probeAppServer(): boolean {
  try {
    const launcher = resolveBundledCodexLauncher()
    const result = spawnSync(process.execPath, [launcher, 'app-server', '--help'], {
      stdio: 'ignore',
      timeout: 5_000,
      windowsHide: true,
    })
    return !result.error && result.status === 0
  } catch {
    return false
  }
}

export function resolveCachedCliAvailability(
  probe: () => boolean = probeAppServer,
  now = Date.now(),
  ttlMs = CLI_PROBE_TTL_MS,
): boolean {
  if (cachedCliProbe && cachedCliProbe.expiresAt > now) return cachedCliProbe.available
  const available = probe()
  cachedCliProbe = { available, expiresAt: now + ttlMs }
  return available
}
