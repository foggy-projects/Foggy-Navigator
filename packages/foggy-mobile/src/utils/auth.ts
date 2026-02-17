const TOKEN_KEY = 'navigator_token'
const USER_INFO_KEY = 'navigator_user'

export interface UserInfo {
  userId: string
  username: string
  roles: string[]
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function getToken(): string | null {
  return uni.getStorageSync(TOKEN_KEY) || null
}

export function removeToken(): void {
  uni.removeStorageSync(TOKEN_KEY)
}

export function setUserInfo(userInfo: UserInfo): void {
  uni.setStorageSync(USER_INFO_KEY, JSON.stringify(userInfo))
}

export function getUserInfo(): UserInfo | null {
  const raw = uni.getStorageSync(USER_INFO_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function isLoggedIn(): boolean {
  return !!getToken()
}

export function clearAuth(): void {
  removeToken()
  uni.removeStorageSync(USER_INFO_KEY)
}
