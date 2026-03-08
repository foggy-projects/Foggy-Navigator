export type GitProvider = 'GITLAB' | 'GITHUB'

export interface Conversation {
  conversationId: string
  userId: string
  projectId?: string
  status: 'STARTING' | 'WAITING_FOR_SANDBOX' | 'PREPARING_REPOSITORY' | 'READY' | 'RUNNING' | 'IDLE' | 'PAUSED' | 'ERROR' | 'STOPPED'
  // 新 Git 字段
  gitCredentialId?: string
  gitProvider?: GitProvider
  gitProjectId?: string
  gitProjectPath?: string
  gitRepoUrl?: string
  baseBranch?: string
  workingBranch?: string
  /** @deprecated use workingBranch */
  branchName?: string
  createdAt: string
  updatedAt: string
}

export interface Message {
  messageId: string
  conversationId: string
  role?: 'USER' | 'ASSISTANT'
  content: string
  timestamp: string
}

export interface Container {
  id: string
  imageTag: string
  status: 'CREATING' | 'RUNNING' | 'STOPPED' | 'ERROR'
  createdAt: string
  stoppedAt?: string
  errorMessage?: string
}

export interface Event {
  id: string
  conversationId: string
  kind: string
  data: Record<string, unknown>
  createdAt: string
}

export interface CreateConversationRequest {
  userId: string
  initialMessage: string
  // 新流程字段
  gitCredentialId?: string
  gitProjectId?: string
  baseBranch?: string
  taskDescription?: string
  // 旧流程字段 (deprecated)
  gitRepoUrl?: string
  branchName?: string
}

// Git 凭证
export interface GitCredential {
  credentialId: string
  userId: string
  provider: GitProvider
  serverUrl: string
  displayName: string
  createdAt: string
  updatedAt: string
}

export interface CreateGitCredentialRequest {
  provider: GitProvider
  serverUrl: string
  displayName: string
  accessToken: string
}

export interface UpdateGitCredentialRequest {
  displayName?: string
  accessToken?: string
}

// Git 项目
export interface GitProject {
  id: string
  name: string
  path: string
  pathWithNamespace: string
  description?: string
  httpUrlToRepo: string
  sshUrlToRepo?: string
  defaultBranch: string
  avatarUrl?: string
  webUrl?: string
}

// Git 分支
export interface GitBranch {
  name: string
  isDefault: boolean
  isProtected: boolean
  commitId?: string
  commitMessage?: string
}

export interface SendMessageRequest {
  conversationId: string
  content: string
}
