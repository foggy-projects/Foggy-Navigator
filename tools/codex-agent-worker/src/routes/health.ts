import { Router, Request, Response } from 'express'
import fs from 'fs'
import os from 'os'
import path from 'path'
import { Codex } from '@openai/codex-sdk'
import { config } from '../config.js'
import { taskRegistry } from '../codex/sdk-wrapper.js'
import type { HealthResponse } from '../models.js'
import { resolveCodexSdkRuntimeStatus } from '../runtime-requirements.js'
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

export function resolveWorkerHealthStatus(sdkAvailable: boolean, sdkCompatible: boolean): 'ok' | 'degraded' {
  return sdkAvailable && sdkCompatible ? 'ok' : 'degraded'
}

export function resolveCodexBizReadiness(
  codexBizHomeRoot: string | undefined
): Pick<HealthResponse, 'codex_biz_home_root_configured' | 'codex_biz_scoped_home_ready'> {
  const configured = Boolean(codexBizHomeRoot?.trim())
  return {
    codex_biz_home_root_configured: configured,
    codex_biz_scoped_home_ready: configured,
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
  const codexSdkStatus = resolveCodexSdkRuntimeStatus()
  const codexBizReadiness = resolveCodexBizReadiness(config.codexBizHomeRoot)

  const response: HealthResponse = {
    status: resolveWorkerHealthStatus(codexSdkAvailable, codexSdkStatus.compatible),
    hostname: os.hostname(),
    version: APP_VERSION,
    worker_name: config.workerName,
    active_tasks: activeTasks,
    codex_sdk_available: codexSdkAvailable,
    codex_sdk_version: codexSdkStatus.installedVersion,
    codex_sdk_minimum_version: codexSdkStatus.minimumVersion,
    codex_sdk_compatible: codexSdkStatus.compatible,
    codex_auth_configured: codexAuthMode !== 'none',
    codex_auth_mode: codexAuthMode,
    ...codexBizReadiness,
  }

  res.json(response)
})

export default router
