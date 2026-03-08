import client from './client'
import type { Container } from '@/types'

export async function listContainers(params?: {
  status?: string
}): Promise<Container[]> {
  return client.get('/containers', { params })
}

export async function getContainer(id: string): Promise<Container> {
  return client.get(`/containers/${id}`)
}

export async function deleteContainer(id: string): Promise<void> {
  return client.delete(`/containers/${id}`)
}
