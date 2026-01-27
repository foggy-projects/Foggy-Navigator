import client from './client'
import type { Conversation, CreateConversationRequest, Message, SendMessageRequest } from '@/types'

export async function listConversations(params?: {
  userId?: string
  status?: string
}): Promise<Conversation[]> {
  return client.get('/conversations', { params })
}

export async function getConversation(id: string): Promise<Conversation> {
  return client.get(`/conversations/${id}`)
}

export async function createConversation(
  request: CreateConversationRequest
): Promise<Conversation> {
  return client.post('/conversations', request)
}

export async function deleteConversation(id: string): Promise<void> {
  return client.delete(`/conversations/${id}`)
}

export async function stopConversation(id: string): Promise<void> {
  return client.post(`/conversations/${id}/stop`)
}

export async function getMessages(conversationId: string): Promise<Message[]> {
  return client.get(`/conversations/${conversationId}/messages`)
}

export async function sendMessage(request: SendMessageRequest): Promise<Message> {
  return client.post('/messages', request)
}
