/**
 * Conversation Config API (/api/v1/sessions/{id}/config/*)
 *
 * Session-level configuration: pin, title, archive, hold.
 * Aligned with PC frontend session config endpoints.
 *
 * Note: Auth binding (bindConversationAuth / updateConversationAuth) is
 * intentionally excluded — mobile does not support credential editing.
 */
import client from './client'
import type { RX, ConversationConfig } from './types'

/**
 * Batch-load conversation configs for given session IDs.
 */
export async function listConversationConfigs(
  sessionIds: string[],
): Promise<ConversationConfig[]> {
  if (sessionIds.length === 0) return []
  const rx = (await client.get('/sessions/configs', {
    params: { sessionIds: sessionIds.join(',') },
  })) as unknown as RX<ConversationConfig[]>
  return rx.data
}

/**
 * Toggle pin status for a conversation.
 */
export async function updateConversationPin(
  sessionId: string,
  pinned: boolean,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/pin`, {
    pinned,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

/**
 * Update custom title for a conversation.
 */
export async function updateConversationTitle(
  sessionId: string,
  title: string,
): Promise<ConversationConfig> {
  const rx = (await client.patch(`/sessions/${sessionId}/config/title`, {
    title,
  })) as unknown as RX<ConversationConfig>
  return rx.data
}

/**
 * Archive a conversation (set interactionState = ARCHIVED).
 */
export async function archiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/archive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

/**
 * Unarchive a conversation.
 */
export async function unarchiveConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/unarchive`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

/**
 * Hold a conversation (set interactionState = ON_HOLD).
 */
export async function holdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/hold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}

/**
 * Unhold a conversation.
 */
export async function unholdConversation(
  sessionId: string,
): Promise<ConversationConfig> {
  const rx = (await client.post(
    `/sessions/${sessionId}/config/unhold`,
  )) as unknown as RX<ConversationConfig>
  return rx.data
}
