import { Router } from 'express'
import type { AppConfig } from '../config.js'
import type { PoolRateLimitsView } from '../app-server/rate-limits.js'
import type { TaskManager } from '../task-manager.js'

export function createRuntimeRouter(config: AppConfig, manager: TaskManager): Router {
  const router = Router()
  router.get('/api/v1/runtime/rate-limits', async (req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    const refresh = parseRefresh(req.query.refresh)
    if (refresh === undefined) {
      res.status(400).json({ error: 'INVALID_REFRESH_PARAMETER' })
      return
    }
    let view: PoolRateLimitsView
    try {
      view = await manager.readDefaultRateLimits(refresh)
    } catch {
      view = {
        state: 'UNKNOWN',
        observed_at_epoch_ms: null,
        stale: false,
        limits: [],
        error_code: 'RATE_LIMITS_SOURCE_UNAVAILABLE',
      }
    }
    res.json({
      contract_version: 1,
      runtime_id: config.runtimeId,
      runtime_revision: config.runtimeRevision,
      instance_id: config.instanceId,
      scope: 'DEFAULT_CODEX_HOME',
      ...view,
    })
  })
  return router
}

function parseRefresh(value: unknown): boolean | undefined {
  if (value === undefined) return false
  if (value === 'true') return true
  if (value === 'false') return false
  return undefined
}
