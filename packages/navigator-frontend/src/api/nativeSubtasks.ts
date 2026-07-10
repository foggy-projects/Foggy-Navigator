import client from './client'
import type { RX } from '@/types'
import type { NativeSubtaskSnapshot } from '@/types/nativeSubtasks'

/** Return the latest native Codex subtask snapshot for one platform task. */
export async function getNativeSubtasks(taskId: string): Promise<NativeSubtaskSnapshot> {
  const rx = (await client.get(
    `/tasks/${encodeURIComponent(taskId)}/native-subtasks`,
    { suppressErrorMessage: true } as any,
  )) as unknown as RX<NativeSubtaskSnapshot>
  return rx.data
}
