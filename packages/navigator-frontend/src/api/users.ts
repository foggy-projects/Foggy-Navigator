import client from './client'
import type {
  RX,
  UserDTO,
  UserRegisterForm,
  UserUpdateForm,
  ApiKeyDTO,
  ApiKeyCreateForm,
} from '@/types'

// ===== 用户管理 =====

/** 获取当前登录用户的完整信息 */
export async function getCurrentUser(): Promise<UserDTO> {
  const rx = (await client.get('/auth/me')) as unknown as RX<UserDTO>
  return rx.data
}

/** 按租户列出用户 */
export async function listUsersByTenant(tenantId: string): Promise<UserDTO[]> {
  const rx = (await client.get(`/users/tenant/${tenantId}`)) as unknown as RX<UserDTO[]>
  return rx.data
}

/** 获取单个用户 */
export async function getUser(userId: string): Promise<UserDTO> {
  const rx = (await client.get(`/users/${userId}`)) as unknown as RX<UserDTO>
  return rx.data
}

/** 创建用户（注册） */
export async function createUser(form: UserRegisterForm): Promise<string> {
  const rx = (await client.post('/auth/register', form)) as unknown as RX<string>
  return rx.data
}

/** 更新用户 */
export async function updateUser(userId: string, form: UserUpdateForm): Promise<void> {
  await client.put(`/users/${userId}`, form)
}

/** 删除用户（软删除） */
export async function deleteUser(userId: string): Promise<void> {
  await client.delete(`/users/${userId}`)
}

// ===== API Key 管理 =====

/** 创建 API Key */
export async function createApiKey(userId: string, form: ApiKeyCreateForm): Promise<ApiKeyDTO> {
  const rx = (await client.post(`/users/${userId}/api-keys`, form)) as unknown as RX<ApiKeyDTO>
  return rx.data
}

/** 列出用户的 API Keys */
export async function listApiKeys(userId: string): Promise<ApiKeyDTO[]> {
  const rx = (await client.get(`/users/${userId}/api-keys`)) as unknown as RX<ApiKeyDTO[]>
  return rx.data
}

/** 撤销 API Key */
export async function revokeApiKey(apiKeyId: string): Promise<void> {
  await client.delete(`/users/api-keys/${apiKeyId}`)
}
