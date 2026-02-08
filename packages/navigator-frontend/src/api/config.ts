import client from './client'
import type {
  RX,
  DatasourceConfig,
  DatasourceConfigForm,
  SemanticLayerConfig,
  SemanticLayerConfigForm,
} from '@/types'

// ===== 数据源 =====

export async function listDatasources(): Promise<DatasourceConfig[]> {
  const rx = (await client.get('/config/datasources')) as unknown as RX<DatasourceConfig[]>
  return rx.data
}

export async function getDatasource(id: string): Promise<DatasourceConfig> {
  const rx = (await client.get(`/config/datasource/${id}`)) as unknown as RX<DatasourceConfig>
  return rx.data
}

export async function saveDatasource(form: DatasourceConfigForm): Promise<string> {
  const rx = (await client.post('/config/datasource', form)) as unknown as RX<string>
  return rx.data
}

export async function updateDatasource(id: string, form: DatasourceConfigForm): Promise<void> {
  await client.put(`/config/datasource/${id}`, form)
}

export async function deleteDatasource(id: string): Promise<void> {
  await client.delete(`/config/datasource/${id}`)
}

// ===== 语义层 =====

export async function listSemanticLayers(datasourceId?: string): Promise<SemanticLayerConfig[]> {
  const rx = (await client.get('/config/semantic-layers', {
    params: datasourceId ? { datasourceId } : undefined,
  })) as unknown as RX<SemanticLayerConfig[]>
  return rx.data
}

export async function saveSemanticLayer(form: SemanticLayerConfigForm): Promise<string> {
  const rx = (await client.post('/config/semantic-layer', form)) as unknown as RX<string>
  return rx.data
}

export async function updateSemanticLayer(
  id: string,
  form: SemanticLayerConfigForm,
): Promise<void> {
  await client.put(`/config/semantic-layer/${id}`, form)
}

export async function deleteSemanticLayer(id: string): Promise<void> {
  await client.delete(`/config/semantic-layer/${id}`)
}

// ===== 连接测试 =====

export async function testDatasourceConnection(
  id: string,
): Promise<{ success: boolean; message: string }> {
  const rx = (await client.post(
    `/config/datasource/${id}/test-connection`,
  )) as unknown as RX<{ success: boolean; message: string }>
  return rx.data
}
