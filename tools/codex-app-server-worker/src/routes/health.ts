import os from 'node:os'
import { Router } from 'express'
import type { AppConfig } from '../config.js'
import { buildCapabilityManifest, resolveRuntimeReadiness } from '../runtime-capabilities.js'
import type { TaskManager } from '../task-manager.js'
import { APP_VERSION } from '../version.js'
import { resolveExternalModeState } from '../external-mode.js'

export function createHealthRouter(config: AppConfig, manager: TaskManager): Router {
  const router = Router()
  router.get('/health', (_req, res) => {
    const readiness = resolveRuntimeReadiness(config)
    const external = resolveExternalModeState(config)
    if (!manager.isAccepting()) {
      readiness.ready = false
      readiness.reasons.push('APP_SERVER_WORKER_DRAINING')
    }
    res.json({
      status: readiness.ready ? 'ok' : 'degraded',
      ready: readiness.ready,
      reasons: readiness.reasons,
      mode: external.mode,
      external_enabled: external.external_enabled,
      external_ready: external.external_ready,
      auth_configured: external.auth_configured,
      hostname: os.hostname(),
      worker_name: config.workerName,
      version: APP_VERSION,
      runtime_id: config.runtimeId,
      runtime_revision: config.runtimeRevision,
      instance_id: config.instanceId,
      active_tasks: manager.activeCount(),
      queued_tasks: manager.queuedCount(),
      runtime_metrics: manager.runtimeMetrics(),
      cli_version: readiness.cliVersion || null,
      cli_compatible: readiness.cliVersion === '0.144.3',
      state_encryption_configured: Boolean(config.stateEncryptionKey),
    })
  })
  router.get('/api/v1/capabilities', (_req, res) => {
    res.json(buildCapabilityManifest(config, manager))
  })
  return router
}
