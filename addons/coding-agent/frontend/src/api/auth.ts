import axios from 'axios'

const authClient = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

export interface LoginRequest {
  username: string
  password: string
}

export interface UserDTO {
  id: string
  username: string
  email?: string
  tenantId?: string
  roles: string[]
}

export interface LoginResultDTO {
  token: string
  tokenType: string
  expiresIn: number
  user: UserDTO
}

// RX 响应包装器
export interface RX<T> {
  code: string
  message: string
  data: T
  success: boolean
}

export interface LoginResponse {
  token: string
  userId: string
  username: string
  roles: string[]
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
}

/**
 * 用户登录
 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await authClient.post<RX<LoginResultDTO>>('/auth/login', request)
  const result = response.data.data

  return {
    token: result.token,
    userId: result.user.id,
    username: result.user.username,
    roles: result.user.roles
  }
}

/**
 * 用户注册
 */
export async function register(request: RegisterRequest): Promise<LoginResponse> {
  await authClient.post<RX<string>>('/auth/register', request)

  // 注册成功后需要再登录获取 token
  return login({ username: request.username, password: request.password })
}
