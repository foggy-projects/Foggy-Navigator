import { Router, Request, Response } from 'express'
import os from 'os'
import { config } from '../config.js'
import { taskRegistry } from '../codex/sdk-wrapper.js'
import type { HealthResponse } from '../models.js'

const router = Router()

/**
 * GET /health — Worker health check
 */
router.get('/health', (_req: Request, res: Response) => {
  const activeTasks = Array.from(taskRegistry.values())
    .filter(t => t.status === 'running').length

  // SDK availability is checked at startup via static import in sdk-wrapper.ts
  // If the worker started successfully, the SDK is available
  const codexSdkAvailable = true

  const response: HealthResponse = {
    status: 'ok',
    hostname: os.hostname(),
    version: '1.0.0',
    worker_name: config.workerName,
    active_tasks: activeTasks,
    codex_sdk_available: codexSdkAvailable,
  }

  res.json(response)
})

export default router
