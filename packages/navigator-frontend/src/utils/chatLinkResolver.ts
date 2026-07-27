import type { FileSearchResponse, FileSearchResult } from '@/api/fileBrowser'
import { parseFileReference } from '@/utils/fileReference'

const WINDOWS_ABSOLUTE_PATH_RE = /^[A-Za-z]:[\\/]/
const POSIX_ABSOLUTE_PATH_RE = /^\//
const URL_SCHEME_RE = /^[A-Za-z][A-Za-z0-9+.-]*:/
const LEADING_RELATIVE_SEGMENTS_RE = /^(?:\.\/|\/)+/
const LEADING_PARENT_SEGMENTS_RE = /^(?:\.\.\/)+/
const SEARCH_MAX_RESULTS = 80

export interface ResolveChatLinkOptions {
  href: string
  text?: string
  origin: string
  directoryId: string
  workerId?: string | null
  directoryRoot: string
  searchFiles: (directoryId: string, query: string, maxResults?: number) => Promise<FileSearchResponse>
}

export type ChatLinkResolution =
  | { kind: 'open'; url: string }
  | { kind: 'warn'; message: string }

type SearchMatchResult =
  | { kind: 'match'; relativePath: string }
  | { kind: 'none' }
  | { kind: 'ambiguous' }

export async function resolveChatLinkTarget(options: ResolveChatLinkOptions): Promise<ChatLinkResolution> {
  const directDeeplink = normalizeNavigatorFileBrowserLink(options.href, options.origin)
  if (directDeeplink) {
    return { kind: 'open', url: directDeeplink }
  }

  if (isHttpUrl(options.href)) {
    return { kind: 'open', url: options.href }
  }

  if (!options.directoryId) {
    return { kind: 'warn', message: '当前未选择工作目录，无法定位文件' }
  }

  if (!options.directoryRoot) {
    return { kind: 'warn', message: '无法获取工作目录路径' }
  }

  const rawCandidate = pickWorkspaceCandidate(options.href, options.text)
  if (!rawCandidate) {
    return options.href
      ? { kind: 'open', url: options.href }
      : { kind: 'warn', message: '未识别到可打开的链接' }
  }

  const fileReference = parseFileReference(rawCandidate)

  if (isLocalFilePath(fileReference.path)) {
    return resolveAbsoluteWorkspacePath(fileReference, options)
  }

  const searchTarget = normalizeRelativeSearchTarget(fileReference.path)
  if (!searchTarget) {
    return { kind: 'warn', message: '未识别到工作区内文件路径' }
  }

  const basename = getBasename(searchTarget)
  if (!basename) {
    return { kind: 'warn', message: '未识别到工作区内文件路径' }
  }

  const searchResponse = await options.searchFiles(options.directoryId, basename, SEARCH_MAX_RESULTS)
  const match = selectSearchMatch(searchResponse.results, searchTarget)

  if (match.kind === 'match') {
    return {
      kind: 'open',
      url: buildFileBrowserUrl(
        options.origin,
        options.directoryId,
        options.workerId,
        match.relativePath,
        fileReference.line,
      ),
    }
  }

  if (match.kind === 'ambiguous') {
    return { kind: 'warn', message: '找到多个同名文件，无法自动定位，请补充更完整路径' }
  }

  return { kind: 'warn', message: '未找到对应文件，无法自动定位' }
}

export function normalizeLinkHref(href: string): string {
  if (!href) return ''
  let value = href.trim()
  value = value.replace(/^file:\/\/\/(?=[A-Za-z]:[\\/])/i, '')
  value = value.replace(/^file:\/\//i, '')
  value = value.replace(/\\/g, '/')
  try {
    value = decodeURIComponent(value)
  } catch {
    // ignore decode failures and keep the raw value
  }
  value = value.replace(/^\/([A-Za-z]:\/)/, '$1')
  return value
}

export function isLocalFilePath(path: string): boolean {
  return WINDOWS_ABSOLUTE_PATH_RE.test(path) || POSIX_ABSOLUTE_PATH_RE.test(path)
}

function resolveAbsoluteWorkspacePath(
  fileReference: ReturnType<typeof parseFileReference>,
  options: Pick<ResolveChatLinkOptions, 'origin' | 'directoryId' | 'workerId' | 'directoryRoot'>,
): ChatLinkResolution {
  const normalizedHref = normalizeAbsolutePath(fileReference.path)
  const normalizedRoot = normalizeAbsolutePath(options.directoryRoot)

  if (!normalizedHref || !normalizedRoot) {
    return { kind: 'warn', message: '无法获取工作目录路径' }
  }

  if (normalizedHref.kind !== normalizedRoot.kind) {
    return { kind: 'warn', message: '该链接不在当前工作目录下，无法自动定位' }
  }

  if (normalizedHref.comparable === normalizedRoot.comparable) {
    return {
      kind: 'open',
      url: buildFileBrowserUrl(options.origin, options.directoryId, options.workerId),
    }
  }

  const rootPrefix = normalizedRoot.path.endsWith('/') ? normalizedRoot.path : normalizedRoot.path + '/'
  const comparableRootPrefix = normalizedRoot.kind === 'windows' ? rootPrefix.toLowerCase() : rootPrefix
  if (!normalizedHref.comparable.startsWith(comparableRootPrefix)) {
    return { kind: 'warn', message: '该链接不在当前工作目录下，无法自动定位' }
  }

  const relativePath = normalizedHref.path.slice(rootPrefix.length)

  if (!relativePath) {
    return {
      kind: 'open',
      url: buildFileBrowserUrl(options.origin, options.directoryId, options.workerId),
    }
  }

  return {
    kind: 'open',
    url: buildFileBrowserUrl(
      options.origin,
      options.directoryId,
      options.workerId,
      relativePath,
      fileReference.line,
    ),
  }
}

function pickWorkspaceCandidate(href: string, text?: string): string {
  const normalizedHref = normalizeWorkspaceCandidate(href)
  if (looksLikeWorkspaceFileCandidate(normalizedHref)) {
    return normalizedHref
  }

  const normalizedText = normalizeWorkspaceCandidate(text || '')
  if (looksLikeWorkspaceFileCandidate(normalizedText)) {
    return normalizedText
  }

  return ''
}

function normalizeWorkspaceCandidate(value: string): string {
  const rawPath = value.trim().replace(/[?#].*$/, '')
  const normalized = normalizeLinkHref(rawPath).replace(/\/+$/, '')
  return normalized.trim()
}

function looksLikeWorkspaceFileCandidate(value: string): boolean {
  if (!value) return false
  if (isLocalFilePath(value)) return true
  if (value.startsWith('#')) return false
  if (URL_SCHEME_RE.test(value)) return false

  const basename = getBasename(value)
  if (!basename.includes('.')) return false

  return true
}

function normalizeRelativeSearchTarget(value: string): string {
  return value
    .replace(/\\/g, '/')
    .replace(LEADING_RELATIVE_SEGMENTS_RE, '')
    .replace(LEADING_PARENT_SEGMENTS_RE, '')
    .replace(/\/+/g, '/')
    .replace(/\/+$/, '')
}

function selectSearchMatch(results: FileSearchResult[], searchTarget: string): SearchMatchResult {
  const files = results.filter(result => result.type !== 'directory')
  const normalizedTarget = normalizeRelativePath(searchTarget)
  const exactMatches = files.filter(result => normalizeRelativePath(result.relative_path) === normalizedTarget)

  if (exactMatches.length === 1) {
    return { kind: 'match', relativePath: exactMatches[0]!.relative_path }
  }
  if (exactMatches.length > 1) {
    return { kind: 'ambiguous' }
  }

  if (normalizedTarget.includes('/')) {
    const suffixMatches = files.filter((result) => {
      const normalizedResult = normalizeRelativePath(result.relative_path)
      return normalizedResult === normalizedTarget || normalizedResult.endsWith('/' + normalizedTarget)
    })

    if (suffixMatches.length === 1) {
      return { kind: 'match', relativePath: suffixMatches[0]!.relative_path }
    }
    if (suffixMatches.length > 1) {
      return { kind: 'ambiguous' }
    }
  }

  const basename = getBasename(normalizedTarget).toLowerCase()
  const basenameMatches = files.filter((result) => {
    return getBasename(result.relative_path).toLowerCase() === basename
  })

  if (basenameMatches.length === 1) {
    return { kind: 'match', relativePath: basenameMatches[0]!.relative_path }
  }
  if (basenameMatches.length > 1) {
    return { kind: 'ambiguous' }
  }

  return { kind: 'none' }
}

function normalizeRelativePath(path: string): string {
  return path
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .replace(/\/+/g, '/')
    .replace(/\/+$/, '')
    .toLowerCase()
}

interface NormalizedAbsolutePath {
  kind: 'windows' | 'posix'
  path: string
  comparable: string
}

function normalizeAbsolutePath(path: string): NormalizedAbsolutePath | null {
  const normalizedSeparators = path.replace(/\\/g, '/')
  const kind = WINDOWS_ABSOLUTE_PATH_RE.test(normalizedSeparators)
    ? 'windows'
    : POSIX_ABSOLUTE_PATH_RE.test(normalizedSeparators)
      ? 'posix'
      : null

  if (!kind) return null

  const prefix = kind === 'windows' ? normalizedSeparators.slice(0, 2) : ''
  const remainder = kind === 'windows' ? normalizedSeparators.slice(2) : normalizedSeparators
  const segments: string[] = []

  for (const segment of remainder.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      if (segments.length === 0) return null
      segments.pop()
      continue
    }
    segments.push(segment)
  }

  const normalizedPath = kind === 'windows'
    ? `${prefix}/${segments.join('/')}`
    : `/${segments.join('/')}`

  return {
    kind,
    path: normalizedPath,
    comparable: kind === 'windows' ? normalizedPath.toLowerCase() : normalizedPath,
  }
}

function getBasename(path: string): string {
  const segments = path.replace(/\\/g, '/').split('/').filter(Boolean)
  return segments[segments.length - 1] || ''
}

function buildFileBrowserUrl(
  origin: string,
  directoryId: string,
  workerId?: string | null,
  relativePath?: string,
  line?: number,
): string {
  const params = new URLSearchParams({ directoryId })
  if (workerId) params.set('workerId', workerId)
  if (relativePath) params.set('filePath', relativePath.replace(/\\/g, '/'))
  if (line && line > 0) params.set('line', String(line))
  return `${origin}/#/files?${params.toString()}`
}

function normalizeNavigatorFileBrowserLink(href: string, origin: string): string | null {
  if (!href) return null

  const trimmed = href.trim()
  if (!trimmed) return null

  if (trimmed.startsWith('/#/files?')) {
    return `${origin}${trimmed}`
  }

  if (trimmed.startsWith('#/files?')) {
    return `${origin}/${trimmed}`
  }

  if (!isHttpUrl(trimmed)) {
    return null
  }

  try {
    const url = new URL(trimmed)
    if (url.origin === origin && url.hash.startsWith('#/files')) {
      return url.toString()
    }
  } catch {
    return null
  }

  return null
}

function isHttpUrl(value: string): boolean {
  return /^https?:\/\//i.test(value.trim())
}
