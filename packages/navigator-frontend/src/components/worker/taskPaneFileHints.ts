import type { SessionFileHintsResponse } from '@/types/sessionFileHints'

export interface LoadTaskFileHintsResult {
  response: SessionFileHintsResponse | null
  error?: unknown
}

export async function loadTaskFileHints(
  taskId: string | undefined,
  fetcher: (taskId: string) => Promise<SessionFileHintsResponse>,
): Promise<LoadTaskFileHintsResult> {
  if (!taskId) {
    return { response: null }
  }

  try {
    return { response: await fetcher(taskId) }
  } catch (error) {
    return { response: null, error }
  }
}
