import fs from 'node:fs'
import path from 'node:path'

const looksWindowsPath = (value: string): boolean => /^[a-zA-Z]:[\\/]/.test(value) || value.includes('\\')
const pathApiFor = (...values: string[]): typeof path.win32 | typeof path.posix =>
  values.some(looksWindowsPath) ? path.win32 : path.posix

export function isPathWithinAllowedCwd(candidate: string, allowedCwd: string): boolean {
  const pathApi = pathApiFor(candidate, allowedCwd)
  const caseInsensitive = pathApi === path.win32
  const normalize = (value: string): string => {
    const normalized = pathApi.normalize(value)
    const withoutTrailing = normalized === pathApi.parse(normalized).root
      ? normalized
      : normalized.replace(/[\\/]+$/, '')
    return caseInsensitive ? withoutTrailing.toLowerCase() : withoutTrailing
  }
  const relative = pathApi.relative(normalize(allowedCwd), normalize(candidate))
  return relative === '' || (!relative.startsWith('..') && !pathApi.isAbsolute(relative))
}

export function isAllowedWorkingPath(candidate: string, allowedCwds: string[]): boolean {
  return resolveAllowedWorkingPath(candidate, allowedCwds) !== undefined
}

export function resolveAllowedWorkingPath(candidate: string, allowedCwds: string[]): string | undefined {
  if (allowedCwds.length === 0) return undefined
  let realCandidate: string
  try {
    realCandidate = fs.realpathSync.native(candidate)
  } catch {
    return undefined
  }
  const allowed = allowedCwds.some(root => {
    try {
      return isPathWithinAllowedCwd(realCandidate, fs.realpathSync.native(root))
    } catch {
      return false
    }
  })
  return allowed ? realCandidate : undefined
}

export function assertCodexHomeIsolation(options: {
  codexHome: string
  codexBizHomeRoot?: string
  stateDir: string
  allowedCwds: string[]
}): void {
  const homes = [options.codexHome, options.codexBizHomeRoot].filter(Boolean)
  const protectedPaths = [options.stateDir, ...options.allowedCwds].filter(Boolean)
  for (const home of homes) {
    for (const protectedPath of protectedPaths) {
      if (pathsOverlap(canonicalIfPresent(home!), canonicalIfPresent(protectedPath))) {
        throw isolationError()
      }
    }
  }
  if (options.codexHome && options.codexBizHomeRoot
      && pathsOverlap(canonicalIfPresent(options.codexHome), canonicalIfPresent(options.codexBizHomeRoot))) {
    throw isolationError()
  }
}

export function resolveContainedHomePath(root: string, candidate: string): string | undefined {
  try {
    const realRoot = fs.realpathSync.native(root)
    const realCandidate = fs.realpathSync.native(candidate)
    return isPathWithinAllowedCwd(realCandidate, realRoot) ? realCandidate : undefined
  } catch {
    return undefined
  }
}

function pathsOverlap(left: string, right: string): boolean {
  return isPathWithinAllowedCwd(left, right) || isPathWithinAllowedCwd(right, left)
}

function canonicalIfPresent(value: string): string {
  const absolute = path.resolve(value)
  let cursor = absolute
  const suffix: string[] = []
  while (true) {
    try {
      return path.join(fs.realpathSync.native(cursor), ...suffix)
    } catch {
      const parent = path.dirname(cursor)
      if (parent === cursor) return absolute
      suffix.unshift(path.basename(cursor))
      cursor = parent
    }
  }
}

function isolationError(): Error & { code: string } {
  const error = new Error('Codex home paths must be isolated from state and allowed workspaces') as Error & { code: string }
  error.code = 'CODEX_HOME_NOT_ISOLATED'
  return error
}
