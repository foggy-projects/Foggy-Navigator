const TOKEN_KEY = 'navigator_token'
const USER_INFO_KEY = 'navigator_user'

export interface UserInfo {
  userId: string
  username: string
  roles: string[]
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function setUserInfo(userInfo: UserInfo): void {
  localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo))
}

export function getUserInfo(): UserInfo | null {
  const raw = localStorage.getItem(USER_INFO_KEY)
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
  localStorage.removeItem(USER_INFO_KEY)
}
