import axios from 'axios'
import type { RX } from '@/types'

const authClient = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
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
  const response = await authClient.post<RX<LoginResultDTO>>('/auth/login', request)
  const result = response.data.data

  return {
    token: result.token,
    userId: result.user.id,
    username: result.user.username,
    roles: result.user.roles,
  }
}
