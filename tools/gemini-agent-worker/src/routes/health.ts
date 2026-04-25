import { Router, Request, Response } from 'express'
import fs from 'fs'
import os from 'os'
import path from 'path'
import { config } from '../config.js'
import { hasGeminiCli, taskRegistry } from '../gemini/cli-wrapper.js'
import type { HealthResponse } from '../models.js'
import { APP_VERSION } from '../version.js'

const router = Router()

function resolveGeminiAuthMode(): 'env' | 'local_login' | 'none' {
  if (process.env.GEMINI_API_KEY?.trim()) return 'env'
  const configPaths = [
    path.join(os.homedir(), '.gemini', 'settings.json'),
    path.join(os.homedir(), '.config', 'gemini', 'settings.json'),
  ]
  if (configPaths.some(candidate => fs.existsSync(candidate))) {
    return 'local_login'
  }
  return 'none'
}

router.get('/health', async (_req: Request, res: Response) => {
  const activeTasks = Array.from(taskRegistry.values()).filter(t => t.status === 'running').length
  const authMode = resolveGeminiAuthMode()
  const response: HealthResponse = {
    status: 'ok',
    hostname: os.hostname(),
    version: APP_VERSION,
    worker_name: config.workerName,
    active_tasks: activeTasks,
    gemini_cli_available: await hasGeminiCli(),
    gemini_auth_configured: authMode !== 'none',
    gemini_auth_mode: authMode,
    default_model: config.defaultModel,
    model_aliases: config.modelAliases,
  }
  res.json(response)
})

export default router
