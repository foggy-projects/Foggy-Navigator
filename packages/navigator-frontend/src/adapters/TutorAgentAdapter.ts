import { AipMessageType } from '@foggy/chat'
import type { AipMessage, EventAdapter } from '@foggy/chat'
import type { AgentMessage } from '@/types'

export const tutorAgentAdapter: EventAdapter<AgentMessage> = {
  convert(raw: AgentMessage, sessionId: string): AipMessage[] {
    const type = AipMessageType[raw.type as keyof typeof AipMessageType]
    if (!type) return []

    return [{
      messageId: raw.messageId,
      sessionId,
      timestamp: raw.timestamp,
      type,
      payload: raw.payload,
    }]
  },
}
