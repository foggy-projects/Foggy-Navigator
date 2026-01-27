export interface Conversation {
  id: string
  userId: string
  title?: string
  status: 'ACTIVE' | 'COMPLETED' | 'ERROR' | 'STOPPED'
  createdAt: string
  updatedAt: string
  containerId?: string
}

export interface Message {
  id: string
  conversationId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
}

export interface Container {
  id: string
  imageTag: string
  status: 'CREATING' | 'RUNNING' | 'STOPPED' | 'ERROR'
  createdAt: string
  stoppedAt?: string
  errorMessage?: string
}

export interface Event {
  id: string
  conversationId: string
  type: string
  data: string
  createdAt: string
}

export interface CreateConversationRequest {
  userId: string
  initialMessage: string
}

export interface SendMessageRequest {
  conversationId: string
  content: string
}
