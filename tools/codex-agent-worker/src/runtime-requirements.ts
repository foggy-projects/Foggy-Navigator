import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { inspectSdkCompatibility } from '../scripts/ensure-sdk.mjs'

export interface CodexSdkRuntimeStatus {
  installedVersion: string
  minimumVersion: string
  compatible: boolean
  reason: string
}

export function resolveWorkerRoot(): string {
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
}

export function resolveCodexSdkRuntimeStatus(workerRoot: string = resolveWorkerRoot()): CodexSdkRuntimeStatus {
  try {
    const status = inspectSdkCompatibility(workerRoot)
    return {
      installedVersion: status.installedVersion || 'not-installed',
      minimumVersion: status.minimumVersion,
      compatible: status.compatible,
      reason: status.reason,
    }
  } catch (error) {
    return {
      installedVersion: 'unknown',
      minimumVersion: 'unknown',
      compatible: false,
      reason: error instanceof Error ? error.message : 'runtime-requirements-unavailable',
    }
  }
}
