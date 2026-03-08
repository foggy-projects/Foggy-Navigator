import axios from 'axios'
import { createUniAppAxiosAdapter } from '@/utils/uni-axios-adapter'
import { getApiBaseUrl } from '@/utils/config'
import type { RX } from './types'

// Auth uses its own client without interceptors to avoid circular deps
const authClient = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
  adapter: createUniAppAxiosAdapter(),
})

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResultDTO {
  token: string
  tokenType: string
  expiresIn: number
  user: {
    id: string
    username: string
    email?: string
    roles: string[]
  }
}

export interface LoginResponse {
  token: string
  userId: string
  username: string
  roles: string[]
}

export async function login(request: LoginRequest): Promise<LoginResponse> {
  authClient.defaults.baseURL = getApiBaseUrl()
  const response = await authClient.post<RX<LoginResultDTO>>('/auth/login', request)
  const result = response.data.data

  return {
    token: result.token,
    userId: result.user.id,
    username: result.user.username,
    roles: result.user.roles,
  }
}
