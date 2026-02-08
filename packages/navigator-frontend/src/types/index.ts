/** 后端 AgentMessage — SSE event 的 raw payload */
export interface AgentMessage {
  messageId: string
  sessionId: string
  agentId: string
  timestamp: number
  version: string
  type: string // TEXT_CHUNK | TEXT_COMPLETE | ERROR | ...
  payload: unknown
}

/** 后端 Session 实体 */
export interface Session {
  id: string
  userId: string
  agentId: string
  status: 'ACTIVE' | 'CLOSED'
  taskName?: string
  createdAt: string
  updatedAt: string
}

/** 后端 Message 实体 */
export interface Message {
  id: string
  sessionId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
}

/** 引导卡片 */
export interface GuideCard {
  title: string
  description: string
  icon: string
}

/** RX<T> 响应包装 */
export interface RX<T> {
  code: number
  message: string
  data: T
}

/** 路由动作 */
export type RouteAction = 'DELEGATE' | 'RETURN' | 'NAVIGATE'

/** 路由模式 */
export type RouteMode = 'REPLACE' | 'NEW_TAB'

/** 路由目标 */
export interface RouteTarget {
  agentId?: string
  sessionId?: string
  url?: string
}

/** ROUTE_REQUEST 事件的 payload */
export interface RoutePayload {
  action: RouteAction
  mode: RouteMode
  target: RouteTarget
  context?: Record<string, unknown>
}

// ===== 配置管理类型 =====

/** 数据源配置 */
export interface DatasourceConfig {
  id: string
  tenantId: string
  name: string
  type: 'JDBC' | 'MONGO'
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  status: string
  connectionValid: boolean
  description: string
  createdAt: string
}

/** 语义层配置 */
export interface SemanticLayerConfig {
  id: string
  tenantId: string
  datasourceId: string
  gitRepoUrl: string
  gitBranch: string
  gitAuthType: string
  semanticLayerPath: string
  modelsPath: string
  queriesPath: string
  autoSync: boolean
  syncInterval: number
  modelCount: number
  status: string
  description: string
  createdAt: string
}

/** 数据源配置表单 */
export interface DatasourceConfigForm {
  tenantId?: string
  basicInfo: {
    name: string
    type: 'JDBC' | 'MONGO'
    description?: string
  }
  jdbcInfo?: {
    dbType: string
    host: string
    port: number
    databaseName: string
    username: string
    password: string
    jdbcUrl?: string
    extraParams?: string
  }
  mongoInfo?: {
    hosts: string
    port: number
    database: string
    username: string
    password: string
    connectionString?: string
  }
}

/** 语义层配置表单 */
export interface SemanticLayerConfigForm {
  tenantId?: string
  datasourceId: string
  description?: string
  gitConfig: {
    repoUrl: string
    branch: string
    authType: 'NONE' | 'TOKEN' | 'BASIC' | 'SSH'
    accessToken?: string
    username?: string
    password?: string
  }
}
