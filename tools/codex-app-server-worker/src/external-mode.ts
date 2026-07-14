import type { AppConfig } from './config.js'

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
    // Existing cwd admission and process isolation are useful, but they do
    // not yet prove the complete external tool/sandbox/approval/network policy.
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
