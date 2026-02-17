const SERVER_URL_KEY = 'navigator_server_url'

/** 默认服务器地址 */
const DEFAULT_SERVER_URL = 'http://localhost:8112'

export function getServerUrl(): string {
  return uni.getStorageSync(SERVER_URL_KEY) || DEFAULT_SERVER_URL
}

export function setServerUrl(url: string): void {
  uni.setStorageSync(SERVER_URL_KEY, url)
}

/** 获取 API 基础路径 */
export function getApiBaseUrl(): string {
  // H5 模式下通过 proxy 代理，不需要完整 URL
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
