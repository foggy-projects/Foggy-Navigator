const SERVERS_KEY = 'navigator_servers'
const ACTIVE_SERVER_KEY = 'navigator_active_server_id'

/** 兼容旧版单地址存储 */
const LEGACY_URL_KEY = 'navigator_server_url'

export interface ServerConfig {
  id: string
  name: string
  url: string
}

/** 生成短 ID */
function genId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

/** 默认服务器列表 */
const DEFAULT_SERVERS: ServerConfig[] = [
  { id: 'kvm17', name: 'KVM 开发机17', url: 'https://dev-kvm-jdk17-api.qlfloor.com' },
  // { id: 'kvm12', name: 'KVM 开发机12', url: 'https://dev-kvm-jdk12-api.qlfloor.com' },
  { id: 'local', name: 'local', url: 'http://localhost:8112' }
]

/** 获取所有服务器配置 */
export function getServers(): ServerConfig[] {
  const raw = uni.getStorageSync(SERVERS_KEY)
  if (raw) {
    try {
      const list = JSON.parse(raw) as ServerConfig[]
      if (Array.isArray(list) && list.length > 0) return list
    } catch { /* ignore */ }
  }
  // 迁移旧版单地址
  const legacyUrl = uni.getStorageSync(LEGACY_URL_KEY)
  if (legacyUrl) {
    const migrated: ServerConfig[] = [{ id: 'default', name: '默认', url: legacyUrl }]
    saveServers(migrated)
    setActiveServerId('default')
    uni.removeStorageSync(LEGACY_URL_KEY)
    return migrated
  }
  return DEFAULT_SERVERS
}

function saveServers(list: ServerConfig[]): void {
  uni.setStorageSync(SERVERS_KEY, JSON.stringify(list))
}

/** 获取当前活动服务器 ID */
export function getActiveServerId(): string {
  const id = uni.getStorageSync(ACTIVE_SERVER_KEY)
  if (id) return id
  const servers = getServers()
  return servers[0]?.id || 'kvm17'
}

export function setActiveServerId(id: string): void {
  uni.setStorageSync(ACTIVE_SERVER_KEY, id)
}

/** 获取当前活动服务器配置 */
export function getActiveServer(): ServerConfig {
  const servers = getServers()
  const activeId = getActiveServerId()
  return servers.find((s) => s.id === activeId) || servers[0] || DEFAULT_SERVERS[0]
}

/** 添加服务器 */
export function addServer(name: string, url: string): ServerConfig {
  const servers = getServers()
  const server: ServerConfig = { id: genId(), name, url: url.replace(/\/+$/, '') }
  servers.push(server)
  saveServers(servers)
  return server
}

/** 更新服务器 */
export function updateServer(id: string, name: string, url: string): void {
  const servers = getServers()
  const idx = servers.findIndex((s) => s.id === id)
  if (idx >= 0) {
    servers[idx] = { ...servers[idx], name, url: url.replace(/\/+$/, '') }
    saveServers(servers)
  }
}

/** 删除服务器（不能删除当前活动的） */
export function removeServer(id: string): boolean {
  const servers = getServers()
  if (servers.length <= 1) return false
  const idx = servers.findIndex((s) => s.id === id)
  if (idx < 0) return false
  servers.splice(idx, 1)
  saveServers(servers)
  // 如果删除的是当前活动的，切换到第一个
  if (getActiveServerId() === id) {
    setActiveServerId(servers[0].id)
  }
  return true
}

// --- 兼容旧 API（其他模块继续用这两个函数）---

/** 获取当前服务器地址 */
export function getServerUrl(): string {
  return getActiveServer().url
}

/** 设置当前服务器地址（更新活动服务器的 url） */
export function setServerUrl(url: string): void {
  const active = getActiveServer()
  updateServer(active.id, active.name, url)
}

/** 获取 API 基础路径 */
export function getApiBaseUrl(): string {
  // #ifdef H5
  return '/api/v1'
  // #endif

  // #ifndef H5
  return `${getServerUrl()}/api/v1`
  // #endif
}

/** 获取 SSE 基础路径 */
export function getSseBaseUrl(): string {
  // #ifdef H5
  return '/api/v1'
  // #endif

  // #ifndef H5
  return `${getServerUrl()}/api/v1`
  // #endif
}
