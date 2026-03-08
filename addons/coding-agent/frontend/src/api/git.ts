import client from './client'
import type {
  GitCredential,
  CreateGitCredentialRequest,
  UpdateGitCredentialRequest,
  GitProject,
  GitBranch,
  GitProvider
} from '@/types'

// === Git 凭证 API ===

export async function listGitCredentials(userId: string, provider?: GitProvider): Promise<GitCredential[]> {
  const params: Record<string, unknown> = { userId }
  if (provider) params.provider = provider
  // client 响应拦截器已经返回 response.data，所以 resp 就是数据
  return await client.get<GitCredential[]>('/git-credentials', { params }) as unknown as GitCredential[]
}

export async function getGitCredential(userId: string, credentialId: string): Promise<GitCredential> {
  return await client.get<GitCredential>(`/git-credentials/${credentialId}`, { params: { userId } }) as unknown as GitCredential
}

export async function createGitCredential(userId: string, req: CreateGitCredentialRequest): Promise<GitCredential> {
  return await client.post<GitCredential>('/git-credentials', req, { params: { userId } }) as unknown as GitCredential
}

export async function updateGitCredential(userId: string, credentialId: string, req: UpdateGitCredentialRequest): Promise<GitCredential> {
  return await client.put<GitCredential>(`/git-credentials/${credentialId}`, req, { params: { userId } }) as unknown as GitCredential
}

export async function deleteGitCredential(userId: string, credentialId: string): Promise<void> {
  await client.delete(`/git-credentials/${credentialId}`, { params: { userId } })
}

export async function testGitCredential(userId: string, credentialId: string): Promise<{ success: boolean; message: string }> {
  return await client.post<{ success: boolean; message: string }>(`/git-credentials/${credentialId}/test`, null, { params: { userId } }) as unknown as { success: boolean; message: string }
}

// === Git 项目/分支 API ===

export async function listGitProjects(
  userId: string,
  credentialId: string,
  search?: string,
  page = 1,
  perPage = 20
): Promise<GitProject[]> {
  const params: Record<string, unknown> = { userId, page, perPage }
  if (search) params.search = search
  return await client.get<GitProject[]>(`/git/${credentialId}/projects`, { params }) as unknown as GitProject[]
}

export async function getGitProject(userId: string, credentialId: string, projectId: string): Promise<GitProject> {
  return await client.get<GitProject>(`/git/${credentialId}/projects/${encodeURIComponent(projectId)}`, { params: { userId } }) as unknown as GitProject
}

export async function listGitBranches(
  userId: string,
  credentialId: string,
  projectId: string,
  search?: string
): Promise<GitBranch[]> {
  const params: Record<string, string> = { userId }
  if (search) params.search = search
  return await client.get<GitBranch[]>(
    `/git/${credentialId}/projects/${encodeURIComponent(projectId)}/branches`,
    { params }
  ) as unknown as GitBranch[]
}
