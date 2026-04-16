import client from './client'

/**
 * Approve or reject a pending Skill approval for a langgraph-biz-worker task.
 */
export async function approveTask(
  taskId: string,
  form: {
    approvalResult: string
    comment?: string
    reviewedBy?: string
  },
): Promise<void> {
  await client.post(`/langgraph-tasks/${taskId}/approve`, form)
}
