export type SessionFileHintPathScope = 'inside_cwd' | 'outside_cwd' | 'unknown'
export type SessionFileHintChangeKind = 'add' | 'delete' | 'update' | 'unknown'
export type SessionFileHintSourceTool = 'file_change' | 'command_execution'
export type SessionFileHintConfidence = 'high' | 'low'

export interface SessionFileHintFile {
  filePath: string
  cwdRelativePath?: string
  pathScope: SessionFileHintPathScope
  openableInFileBrowser: boolean
  changeKinds: SessionFileHintChangeKind[]
  sourceTools: SessionFileHintSourceTool[]
  confidence: SessionFileHintConfidence
  toolUseIds: string[]
  taskIds: string[]
  firstSeenAt: string
  lastSeenAt: string
  seenCount: number
}

export interface SessionFileHintsResponse {
  taskId?: string
  sessionId?: string
  codexThreadId?: string
  directoryId?: string
  cwd?: string
  session_id?: string
  files: SessionFileHintFile[]
  total: number
  scanned_days?: number
  truncated?: boolean
  message?: string
}
