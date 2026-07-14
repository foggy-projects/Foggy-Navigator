import type { AppConfig } from './config.js'
import type { NextFunction, Request, Response } from 'express'

export const EXTERNAL_AUTH_TOKEN_REQUIRED = 'EXTERNAL_AUTH_TOKEN_REQUIRED'
export const EXTERNAL_EXECUTION_POLICY_PENDING = 'EXTERNAL_EXECUTION_POLICY_PENDING'
export const EXTERNAL_WORKER_UNREADY = 'EXTERNAL_WORKER_UNREADY'

export interface ExternalModeState {
  mode: 'internal-dev' | 'external-enabled'
  external_enabled: boolean
  external_ready: boolean
  auth_configured: boolean
  reasons: string[]
}

export function resolveExternalModeState(
  runtimeConfig: Pick<AppConfig, 'externalEnabled' | 'workerToken'>,
): ExternalModeState {
  const authConfigured = Boolean(runtimeConfig.workerToken.trim())
  const reasons: string[] = []
  if (runtimeConfig.externalEnabled) {
    if (!authConfigured) reasons.push(EXTERNAL_AUTH_TOKEN_REQUIRED)
    // Workspace, tool, sandbox, approval and network limits are not yet a
    // complete external contract. A token alone must never imply readiness.
    reasons.push(EXTERNAL_EXECUTION_POLICY_PENDING)
  }
  return {
    mode: runtimeConfig.externalEnabled ? 'external-enabled' : 'internal-dev',
    external_enabled: runtimeConfig.externalEnabled,
    external_ready: runtimeConfig.externalEnabled && reasons.length === 0,
    auth_configured: authConfigured,
    reasons,
  }
}

export function createExternalModeMiddleware(
  runtimeConfig: Pick<AppConfig, 'externalEnabled' | 'workerToken'>,
) {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (req.path === '/health') {
      next()
      return
    }
    const external = resolveExternalModeState(runtimeConfig)
    if (external.external_enabled && !external.external_ready) {
      res.status(503).json({
        error: EXTERNAL_WORKER_UNREADY,
        reasons: external.reasons,
      })
      return
    }
    next()
  }
}
