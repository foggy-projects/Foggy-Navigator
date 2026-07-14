import client from './client'
import type { ClaudeWorker, RX } from '@/types'

function markLangGraphWorker(worker: ClaudeWorker): ClaudeWorker {
  return { ...worker, workerBackend: 'LANGGRAPH_BIZ' }
}

export async function listWorkers(): Promise<ClaudeWorker[]> {
  const rx = (await client.get('/langgraph-workers')) as unknown as RX<ClaudeWorker[]>
  return rx.data.map(markLangGraphWorker)
}

export async function registerWorker(form: {
  name: string
  baseUrl: string
  authToken: string
  authMode?: string
}): Promise<ClaudeWorker> {
  const rx = (await client.post('/langgraph-workers', form)) as unknown as RX<ClaudeWorker>
  return markLangGraphWorker(rx.data)
}

export async function updateWorker(
  workerId: string,
  form: { name?: string; baseUrl?: string; authToken?: string; authMode?: string },
): Promise<ClaudeWorker> {
  const rx = (await client.put(`/langgraph-workers/${workerId}`, form)) as unknown as RX<ClaudeWorker>
  return markLangGraphWorker(rx.data)
}

export async function deleteWorker(workerId: string): Promise<void> {
  await client.delete(`/langgraph-workers/${workerId}`)
}

export async function triggerHealthCheck(workerId: string): Promise<ClaudeWorker> {
  const rx = (await client.post(`/langgraph-workers/${workerId}/health-check`)) as unknown as RX<ClaudeWorker>
  return markLangGraphWorker(rx.data)
}

/**
 * Respond to a pending Skill approval through the unified Task API.
 *
 * The authenticated UI principal is the reviewer authority; caller-supplied
 * reviewer identity fields are deliberately excluded from this body.
 */
export async function approveTask(
  taskId: string,
  form: {
    approvalResult: 'approved' | 'rejected'
    comment?: string
  },
): Promise<void> {
  await client.post(`/tasks/${encodeURIComponent(taskId)}/respond`, form)
}
