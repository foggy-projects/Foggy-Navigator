import client from './client'
import type { RX, Session, Message, GuideCard, PaginatedMessages } from './types'

export async function listSessions(): Promise<Session[]> {
  const rx = (await client.get('/sessions')) as unknown as RX<Session[]>
  return rx.data
}

export async function createSession(title: string, agentId = 'tutor-agent'): Promise<Session> {
  const rx = (await client.post('/sessions', { title, agentId })) as unknown as RX<Session>
  return rx.data
}

export async function deleteSession(id: string): Promise<void> {
  await client.delete(`/sessions/${id}`)
}

export async function getMessages(sessionId: string): Promise<Message[]> {
  const rx = (await client.get(`/sessions/${sessionId}/messages`)) as unknown as RX<Message[]>
  return rx.data
}

/**
 * Load latest messages with pagination (tail-based).
 * offset=0 returns the most recent `limit` messages.
 */
export async function getLatestMessages(
  sessionId: string,
  limit = 50,
  offset = 0,
): Promise<PaginatedMessages> {
  const rx = (await client.get(`/sessions/${sessionId}/messages/latest`, {
    params: { limit, offset },
  })) as unknown as RX<PaginatedMessages>
  return rx.data
}

export async function sendMessage(sessionId: string, content: string): Promise<Message> {
  const rx = (await client.post(`/sessions/${sessionId}/messages`, { content })) as unknown as RX<Message>
  return rx.data
}

export async function getGuideCards(agentId?: string): Promise<GuideCard[]> {
  const rx = (await client.get('/sessions/guide-cards', { params: { agentId } })) as unknown as RX<GuideCard[]>
  return rx.data
}
