import path from 'node:path'

export const looksWindowsPath = (value: string): boolean =>
  /^[a-zA-Z]:[\\/]/.test(value) || value.includes('\\')

export const pathApiFor = (...values: string[]): typeof path.win32 | typeof path.posix =>
  values.some(looksWindowsPath) ? path.win32 : path.posix

export const isAbsolutePlatformPath = (value: string): boolean =>
  path.posix.isAbsolute(value) || path.win32.isAbsolute(value)

export const normalizePlatformPath = (value: string): string =>
  pathApiFor(value).normalize(value)

const normalizeBoundary = (pathApi: typeof path.win32 | typeof path.posix, value: string): string => {
  const normalized = pathApi.normalize(value)
  return normalized === pathApi.parse(normalized).root ? normalized : normalized.replace(/[\\/]+$/, '')
}

export const isPathWithinAllowedCwd = (candidate: string, allowedCwd: string): boolean => {
  const pathApi = pathApiFor(candidate, allowedCwd)
  const normalizeCase = pathApi === path.win32
  const normalizedCandidate = normalizeBoundary(pathApi, candidate)
  const normalizedAllowed = normalizeBoundary(pathApi, allowedCwd)
  const candidateForCompare = normalizeCase ? normalizedCandidate.toLowerCase() : normalizedCandidate
  const allowedForCompare = normalizeCase ? normalizedAllowed.toLowerCase() : normalizedAllowed
  const relative = pathApi.relative(allowedForCompare, candidateForCompare)
  return relative === '' || (!!relative && !relative.startsWith('..') && !pathApi.isAbsolute(relative))
}

export function resolveSafeChildPath(rootPath: string, relativeFilePath: string): string | null {
  const requested = relativeFilePath.trim()
  if (!requested) return null
  if (path.posix.isAbsolute(requested) || path.win32.isAbsolute(requested)) return null

  const segments = requested.replace(/\\/g, '/').split('/')
  if (segments.some(segment => segment === '..')) return null

  const pathApi = pathApiFor(rootPath, requested)
  const resolved = pathApi.resolve(rootPath, requested)
  return isPathWithinAllowedCwd(resolved, rootPath) ? resolved : null
}
