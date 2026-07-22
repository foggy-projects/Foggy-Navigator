import { Router, Request, Response } from 'express'
import fs from 'fs'
import os from 'os'
import { Codex } from '@openai/codex-sdk'
import { config } from '../config.js'
import {
  CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
  taskRegistry,
} from '../codex/sdk-wrapper.js'
import { isTaskExecutionActive, type HealthResponse } from '../models.js'
import { resolveCodexSdkRuntimeStatus } from '../runtime-requirements.js'
import { APP_VERSION } from '../version.js'
import { resolveExternalModeState } from '../external-mode.js'
import { pathApiFor } from '../path-guards.js'
import { TerminationOperationReceiptLedger } from '../termination-operation.js'

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

export function resolveCodexHomeAuthReadiness(
  codexHome: string,
  codexHomeSource: NonNullable<HealthResponse['codex_home_source']>,
  apiKey: string | undefined,
  authFileExists: (filePath: string) => boolean = fs.existsSync,
): Pick<HealthResponse,
  'codex_home_source'
  | 'codex_home_auth_configured'
  | 'codex_auth_configured'
  | 'codex_auth_mode'
> {
  const authJsonExists = authFileExists(pathApiFor(codexHome).join(codexHome, 'auth.json'))
  const codexAuthMode = resolveCodexAuthMode(apiKey, authJsonExists)
  return {
    codex_home_source: codexHomeSource,
    codex_home_auth_configured: authJsonExists,
    codex_auth_configured: codexAuthMode !== 'none',
    codex_auth_mode: codexAuthMode,
  }
}

export function resolveNavigatorWorkerCredentialReadiness(
  _workerId: string,
  credentialConfigured: boolean,
): string[] {
  return credentialConfigured
    ? [CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY]
    : []
}

export function resolveTerminationReadiness(
  workerId: string,
  workerToken: string,
  replayLedgerReady: boolean,
): Pick<HealthResponse,
  'termination_ready'
  | 'termination_reasons'
  | 'termination_worker_id_configured'
  | 'termination_auth_configured'
  | 'termination_replay_ledger_ready'
> {
  const workerIdConfigured = Boolean(workerId.trim())
  const authConfigured = Boolean(workerToken.trim())
  const reasons: string[] = []
  if (!workerIdConfigured) reasons.push('TERMINATION_WORKER_ID_REQUIRED')
  if (!authConfigured) reasons.push('TERMINATION_AUTH_TOKEN_REQUIRED')
  if (!replayLedgerReady) reasons.push('TERMINATION_REPLAY_LEDGER_UNAVAILABLE')
  return {
    termination_ready: reasons.length === 0,
    termination_reasons: reasons,
    termination_worker_id_configured: workerIdConfigured,
    termination_auth_configured: authConfigured,
    termination_replay_ledger_ready: replayLedgerReady,
  }
}

/**
 * GET /health — Worker health check
 */
router.get('/health', (_req: Request, res: Response) => {
  const activeTasks = Array.from(taskRegistry.values())
    .filter(t => isTaskExecutionActive(t.status)).length

  const codexHomeAuth = resolveCodexHomeAuthReadiness(
    config.codexHome,
    config.codexHomeSource,
    config.openaiApiKey || undefined,
  )
  const codexSdkAvailable = checkCodexSdkAvailable(() => new Codex({
    apiKey: config.openaiApiKey || undefined,
  }))
  const codexSdkStatus = resolveCodexSdkRuntimeStatus()
  const codexBizReadiness = resolveCodexBizReadiness(config.codexBizHomeRoot)
  const terminationReadiness = resolveTerminationReadiness(
    config.navigatorWorkerId,
    config.workerToken,
    new TerminationOperationReceiptLedger(config.terminationOperationLedgerDir).isReady(),
  )
  const external = resolveExternalModeState(config)
  const reasons = [...external.reasons]
  reasons.push(...resolveNavigatorWorkerCredentialReadiness(
    config.navigatorWorkerId,
    Boolean(config.navigatorWorkerCredential),
  ))
  if (!codexSdkAvailable) reasons.push('CODEX_SDK_UNAVAILABLE')
  if (!codexSdkStatus.compatible) reasons.push('CODEX_SDK_VERSION_INCOMPATIBLE')
  const ready = reasons.length === 0

  const response: HealthResponse = {
    status: ready ? 'ok' : 'degraded',
    ready,
    reasons,
    mode: external.mode,
    external_enabled: external.external_enabled,
    external_ready: external.external_ready,
    auth_configured: external.auth_configured,
    hostname: os.hostname(),
    version: APP_VERSION,
    worker_name: config.workerName,
    active_tasks: activeTasks,
    codex_sdk_available: codexSdkAvailable,
    codex_sdk_version: codexSdkStatus.installedVersion,
    codex_sdk_minimum_version: codexSdkStatus.minimumVersion,
    codex_sdk_compatible: codexSdkStatus.compatible,
    ...codexHomeAuth,
    ...codexBizReadiness,
    ...terminationReadiness,
  }

  res.json(response)
})

export default router
