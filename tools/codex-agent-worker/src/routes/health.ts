import { Router, Request, Response } from 'express'
import fs from 'fs'
import os from 'os'
import path from 'path'
import { Codex } from '@openai/codex-sdk'
import { config } from '../config.js'
import { taskRegistry } from '../codex/sdk-wrapper.js'
import type { HealthResponse } from '../models.js'
import { APP_VERSION } from '../version.js'

const router = Router()

export function resolveCodexAuthMode(
  apiKey: string | undefined,
  authJsonExists: boolean
): 'api_key' | 'codex_login' | 'none' {
  if (apiKey) return 'api_key'
  if (authJsonExists) return 'codex_login'
  return 'none'
}

export function checkCodexSdkAvailable(createCodex: () => unknown = () => new Codex()): boolean {
  try {
    createCodex()
    return true
  } catch {
    return false
  }
}

/**
 * GET /health — Worker health check
 */
router.get('/health', (_req: Request, res: Response) => {
  const activeTasks = Array.from(taskRegistry.values())
    .filter(t => t.status === 'running').length

  const authJsonExists = fs.existsSync(path.join(os.homedir(), '.codex', 'auth.json'))
  const codexAuthMode = resolveCodexAuthMode(config.openaiApiKey || undefined, authJsonExists)
  const codexSdkAvailable = checkCodexSdkAvailable(() => new Codex({
    apiKey: config.openaiApiKey || undefined,
  }))

  const response: HealthResponse = {
    status: 'ok',
    hostname: os.hostname(),
    version: APP_VERSION,
    worker_name: config.workerName,
    active_tasks: activeTasks,
    codex_sdk_available: codexSdkAvailable,
    codex_auth_configured: codexAuthMode !== 'none',
    codex_auth_mode: codexAuthMode,
  }

  res.json(response)
})

export default router
