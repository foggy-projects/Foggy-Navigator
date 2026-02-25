import client from './client'
import type { RX } from '@/types'

export interface SshConnectResult {
  sessionId: string
  wsUrl: string
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
