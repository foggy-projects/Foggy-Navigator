import client from './client'
import type { RX } from '@/types'

export interface MonitorEvent {
  id: number
  service: string
  instance?: string
  eventType: string
  level: string
  loggerName: string
  message: string
  stackTrace?: string
  eventTime: string
  createdAt: string
}

export interface MonitorStats {
  errorsLast1h: number
  errorsLast24h: number
}

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export async function getMonitoringEvents(params: {
  service?: string
  level?: string
  page?: number
  size?: number
}): Promise<PageResult<MonitorEvent>> {
  const rx = (await client.get('/monitoring/events', { params })) as unknown as RX<PageResult<MonitorEvent>>
  return rx.data
}

export async function getMonitoringStats(): Promise<MonitorStats> {
  const rx = (await client.get('/monitoring/stats')) as unknown as RX<MonitorStats>
  return rx.data
}
