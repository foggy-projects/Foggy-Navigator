export class SdkPreflightError extends Error {
  exitCode: number
  constructor(message: string, exitCode?: number)
}

export interface SdkCompatibility {
  minimumVersion: string
  repairVersion: string
  installedVersion: string | null
  compatible: boolean
  reason: 'compatible' | 'not-installed' | 'invalid-version' | 'below-minimum'
}

export function parseSemver(value: unknown): unknown | null
export function compareSemver(leftValue: string, rightValue: string): number
export function readRuntimeRequirements(workerDir: string): {
  minimumVersion: string
  repairVersion: string
}
export function readInstalledSdkVersion(workerDir: string): string | null
export function inspectSdkCompatibility(workerDir: string): SdkCompatibility
export function parseBoolean(value: unknown, fallback?: boolean): boolean
export function defaultRunCommand(
  command: string,
  args: string[],
  options?: { cwd?: string; capture?: boolean }
): { status: number; stdout: string; stderr: string }
export function resolveNpmInvocation(env?: NodeJS.ProcessEnv): {
  command: string
  argsPrefix: string[]
}
export function installSdkWithRegistryFallback(options: {
  workerDir: string
  version: string
  omitDev?: boolean
  runCommand?: typeof defaultRunCommand
  logger?: Pick<Console, 'log' | 'warn'>
  npmInvocation?: { command: string; argsPrefix: string[] }
}): boolean
export function validateTargetVersion(workerDir: string, targetVersion: string, force?: boolean): {
  minimumVersion: string
  targetVersion: string
  compatible: boolean
  forced: boolean
}
export function ensureSdk(options: {
  workerDir: string
  autoUpdate?: boolean
  omitDev?: boolean
  install?: (options: {
    workerDir: string
    version: string
    omitDev: boolean
    logger: Pick<Console, 'log' | 'warn'>
  }) => boolean
  logger?: Pick<Console, 'log' | 'warn'>
}): SdkCompatibility & { repaired: boolean }
export function runMain(argv?: string[], env?: NodeJS.ProcessEnv): unknown
