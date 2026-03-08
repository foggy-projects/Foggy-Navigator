import client from './client'
import type { RX } from '@/types'

// ---- Types ----------------------------------------------------------------

export interface FileEntry {
  name: string
  path: string
  is_dir: boolean
  size: number
  modified: string
}

export interface DirectoryListing {
  path: string
  entries: FileEntry[]
  total: number
}

export interface FileContent {
  path: string
  content: string | null
  language: string
  size: number
  line_count: number
  is_binary: boolean
  too_large: boolean
}

export interface DiffFileEntry {
  file: string
  status: string
  insertions: number
  deletions: number
}

export interface GitDiffSummary {
  path: string
  branch: string | null
  files: DiffFileEntry[]
  total_insertions: number
  total_deletions: number
}

export interface FileDiff {
  file: string
  status: string
  original: string | null
  modified: string | null
  language: string
}

export interface FileSearchResult {
  name: string
  relative_path: string
  size: number
  modified: string
}

export interface FileSearchResponse {
  query: string
  results: FileSearchResult[]
  total: number
}

export interface ContentMatch {
  file: string
  line_number: number
  line_content: string
  context_before: string[]
  context_after: string[]
}

export interface ContentSearchResponse {
  query: string
  matches: ContentMatch[]
  total_matches: number
  total_files: number
}

export interface FoggyIgnoreResponse {
  patterns: string[]
}

// ---- Git Log Types --------------------------------------------------------

export interface GitLogEntry {
  hash: string
  short_hash: string
  author_name: string
  author_email: string
  author_date: string
  relative_date: string
  subject: string
  body: string
  refs: string
}

export interface GitLogResponse {
  branch: string
  upstream: string | null
  ahead: number
  behind: number
  commits: GitLogEntry[]
  has_more: boolean
}

export interface CommitFileEntry {
  file: string
  status: string
  insertions: number
  deletions: number
}

export interface CommitDetailResponse {
  hash: string
  short_hash: string
  author_name: string
  author_email: string
  author_date: string
  relative_date: string
  subject: string
  body: string
  files: CommitFileEntry[]
  total_insertions: number
  total_deletions: number
}

export interface CommitFileDiff {
  file: string
  status: string
  original: string | null
  modified: string | null
  language: string
}

// ---- API ------------------------------------------------------------------

export async function listDirectory(
  directoryId: string,
  subPath?: string,
  showHidden?: boolean,
): Promise<DirectoryListing> {
  const params: Record<string, string> = { directoryId }
  if (subPath) params.subPath = subPath
  if (showHidden !== undefined) params.showHidden = String(showHidden)
  const rx = (await client.get('/file-browser/list', { params })) as unknown as RX<DirectoryListing>
  return rx.data
}

export async function readFileContent(
  directoryId: string,
  subPath: string,
): Promise<FileContent> {
  const rx = (await client.get('/file-browser/content', {
    params: { directoryId, subPath },
  })) as unknown as RX<FileContent>
  return rx.data
}

export async function getGitDiffSummary(directoryId: string): Promise<GitDiffSummary> {
  const rx = (await client.get('/file-browser/git-diff', {
    params: { directoryId },
  })) as unknown as RX<GitDiffSummary>
  return rx.data
}

export async function getFileDiff(directoryId: string, file: string): Promise<FileDiff> {
  const rx = (await client.get('/file-browser/git-diff/file', {
    params: { directoryId, file },
  })) as unknown as RX<FileDiff>
  return rx.data
}

export async function searchFiles(
  directoryId: string,
  query: string,
  maxResults = 50,
): Promise<FileSearchResponse> {
  const rx = (await client.get('/file-browser/search', {
    params: { directoryId, query, maxResults },
  })) as unknown as RX<FileSearchResponse>
  return rx.data
}

export async function searchContent(
  directoryId: string,
  query: string,
  maxResults = 50,
  contextLines = 2,
  caseSensitive = false,
  filePattern?: string,
): Promise<ContentSearchResponse> {
  const rx = (await client.get('/file-browser/search-content', {
    params: {
      directoryId,
      query,
      maxResults,
      contextLines,
      caseSensitive,
      ...(filePattern ? { filePattern } : {}),
    },
  })) as unknown as RX<ContentSearchResponse>
  return rx.data
}

export async function getFoggyIgnore(directoryId: string): Promise<FoggyIgnoreResponse> {
  const rx = (await client.get('/file-browser/ignore', {
    params: { directoryId },
  })) as unknown as RX<FoggyIgnoreResponse>
  return rx.data
}

export async function addFoggyIgnore(directoryId: string, pattern: string): Promise<FoggyIgnoreResponse> {
  const rx = (await client.post('/file-browser/ignore', {
    directoryId,
    pattern,
  })) as unknown as RX<FoggyIgnoreResponse>
  return rx.data
}

export async function removeFoggyIgnore(directoryId: string, pattern: string): Promise<FoggyIgnoreResponse> {
  const rx = (await client.delete('/file-browser/ignore', {
    data: { directoryId, pattern },
  })) as unknown as RX<FoggyIgnoreResponse>
  return rx.data
}

// ---- Git Log API ----------------------------------------------------------

export async function getGitLog(
  directoryId: string,
  limit = 50,
  skip = 0,
): Promise<GitLogResponse> {
  const rx = (await client.get('/file-browser/git-log', {
    params: { directoryId, limit, skip },
  })) as unknown as RX<GitLogResponse>
  return rx.data
}

export async function getCommitDetail(
  directoryId: string,
  hash: string,
): Promise<CommitDetailResponse> {
  const rx = (await client.get('/file-browser/git-log/commit', {
    params: { directoryId, hash },
  })) as unknown as RX<CommitDetailResponse>
  return rx.data
}

export async function getCommitFileDiff(
  directoryId: string,
  hash: string,
  file: string,
): Promise<CommitFileDiff> {
  const rx = (await client.get('/file-browser/git-log/commit/file-diff', {
    params: { directoryId, hash, file },
  })) as unknown as RX<CommitFileDiff>
  return rx.data
}
