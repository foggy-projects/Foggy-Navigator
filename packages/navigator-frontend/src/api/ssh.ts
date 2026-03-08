import client from './client'
import type { RX } from '@/types'
import { getToken } from '@/utils/auth'

export interface SshConnectResult {
  sessionId: string
  wsUrl: string
}

export interface SshSessionDTO {
  sessionId: string
  directoryId: string
  label: string
  wsUrl: string
  cols: number
  rows: number
  connectedAt: string
  lastActivity: string
}

/**
 * 将后端返回的相对 wsUrl 路径构建为完整 WebSocket URL。
 * 自动处理 ws/wss 协议、host、JWT token 拼接。
 */
export function buildSshWsUrl(path: string): string {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const separator = path.includes('?') ? '&' : '?'
  const token = getToken() || ''
  return `${protocol}//${location.host}${path}${separator}token=${token}`
}

export async function sshConnect(form: {
  workerId: string
  directoryId?: string
  host: string
  port: number
  username: string
  password: string
  cols?: number
  rows?: number
}): Promise<SshConnectResult> {
  const rx = (await client.post('/ssh/connect', form)) as unknown as RX<SshConnectResult>
  return rx.data
}

export async function sshClose(sessionId: string, workerId: string): Promise<void> {
  await client.post(`/ssh/${sessionId}/close?workerId=${encodeURIComponent(workerId)}`)
}

export async function sshResize(
  sessionId: string,
  workerId: string,
  cols: number,
  rows: number,
): Promise<void> {
  await client.post(`/ssh/${sessionId}/resize?workerId=${encodeURIComponent(workerId)}`, {
    cols,
    rows,
  })
}

export async function listSshSessions(workerId: string): Promise<SshSessionDTO[]> {
  const rx = (await client.get(
    `/ssh/sessions?workerId=${encodeURIComponent(workerId)}`,
  )) as unknown as RX<SshSessionDTO[]>
  return rx.data ?? []
}
