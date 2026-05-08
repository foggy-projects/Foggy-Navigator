import client from './client'
import type { BizWorkerPool, ClientAppModelConfigGrant, RX } from '@/types'

export async function listBizWorkerPools(): Promise<BizWorkerPool[]> {
  const rx = (await client.get('/business-agent/worker-pools')) as unknown as RX<BizWorkerPool[]>
  return rx.data
}

export async function createBizWorkerPool(form: {
  poolId: string
  name: string
  workerBackend: string
  routingPolicy: string
}): Promise<BizWorkerPool> {
  const rx = (await client.post('/business-agent/worker-pools', form)) as unknown as RX<BizWorkerPool>
  return rx.data
}

export async function updateBizWorkerPoolStatus(poolId: string, status: string): Promise<BizWorkerPool> {
  const rx = (await client.put(`/business-agent/worker-pools/${poolId}/status`, { status })) as unknown as RX<BizWorkerPool>
  return rx.data
}

export async function listModelConfigGrants(clientAppId: string): Promise<ClientAppModelConfigGrant[]> {
  const rx = (await client.get(`/client-apps/${clientAppId}/model-config-grants`)) as unknown as RX<ClientAppModelConfigGrant[]>
  return rx.data
}

export async function grantModelConfig(
  clientAppId: string,
  form: { modelConfigId: string; isDefault: boolean; grantScope?: string }
): Promise<ClientAppModelConfigGrant> {
  const rx = (await client.post(`/client-apps/${clientAppId}/model-config-grants`, form)) as unknown as RX<ClientAppModelConfigGrant>
  return rx.data
}

export async function updateGrantStatus(
  clientAppId: string,
  grantId: number,
  status: string
): Promise<ClientAppModelConfigGrant> {
  const rx = (await client.put(`/client-apps/${clientAppId}/model-config-grants/${grantId}/status`, { status })) as unknown as RX<ClientAppModelConfigGrant>
  return rx.data
}

export async function setGrantDefault(
  clientAppId: string,
  grantId: number
): Promise<ClientAppModelConfigGrant> {
  const rx = (await client.put(`/client-apps/${clientAppId}/model-config-grants/${grantId}/default`)) as unknown as RX<ClientAppModelConfigGrant>
  return rx.data
}
