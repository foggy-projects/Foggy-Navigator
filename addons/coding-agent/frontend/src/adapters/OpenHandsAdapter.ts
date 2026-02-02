import { AipMessageType } from '@foggy/chat'
import type { AipMessage, EventAdapter } from '@foggy/chat'

/**
 * Raw event structure from the backend SSE stream.
 */
export interface OhRawEvent {
  id: string
  conversationId: string
  kind: string
  data: Record<string, unknown>
  createdAt: string
}

let _seq = 0
function makeId(): string {
  return `oh-${Date.now()}-${++_seq}`
}

function ts(raw: OhRawEvent): number {
  return raw.createdAt ? new Date(raw.createdAt).getTime() : Date.now()
}

/**
 * Extract text from OpenHands content array or string.
 * OH uses [{type:"text", text:"..."}] arrays for content fields.
 */
function extractText(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    return value
      .filter((item: any) => item?.type === 'text' && item?.text)
      .map((item: any) => item.text)
      .join('\n')
  }
  return ''
}

export const openHandsAdapter: EventAdapter<OhRawEvent> = {
  convert(raw: OhRawEvent, sessionId: string): AipMessage[] {
    const messages: AipMessage[] = []
    const base = { sessionId, timestamp: ts(raw) }
    const data = raw.data ?? {}
    const dataKind = (data.kind as string) ?? ''

    switch (raw.kind) {
      case 'MESSAGE_SENT': {
        // Two formats:
        // 1. Simple: { content: "text", messageId: "..." }  (our backend user-send echo — always skip)
        // 2. OH MessageEvent: { kind: "MessageEvent", llm_message: { role, content: [{type,text}] }, source: "user"|"agent" }
        if (dataKind !== 'MessageEvent') {
          // Our backend's user-send echo — skip (already shown via addUserMessage)
          break
        }
        const llmMsg = data.llm_message as Record<string, unknown> | undefined
        const isUser = (data.source as string) === 'user'
        if (isUser || !llmMsg) break // Skip user echoes from OH

        const content = extractText(llmMsg.content)
        if (content) {
          messages.push({
            ...base,
            messageId: raw.id || makeId(),
            type: AipMessageType.TEXT_COMPLETE,
            payload: { content },
          })
        }
        break
      }

      case 'AGENT_ACTION': {
        const thought = extractText(data.thought)
        const reasoning = (data.reasoning_content as string) ?? ''
        const action = data.action as Record<string, unknown> | undefined
        const command = (action?.command as string) ?? (data.command as string) ?? ''
        const path = (action?.path as string) ?? (data.path as string) ?? ''
        const toolCallId = (data.tool_call_id as string) ?? (data.id as string) ?? raw.id ?? makeId()
        const toolName = (data.tool_name as string) ?? (action?.kind as string) ?? (dataKind || 'action')

        // If it has a command or is a terminal/file action, it's a tool call
        if (
          command ||
          toolName === 'terminal' ||
          dataKind === 'ActionEvent' ||
          dataKind === 'CommandEvent' ||
          dataKind === 'TerminalAction' ||
          dataKind === 'CmdRunAction' ||
          dataKind === 'IPythonRunCellAction' ||
          dataKind === 'FileWriteAction' ||
          dataKind === 'FileReadAction'
        ) {
          messages.push({
            ...base,
            messageId: makeId(),
            type: AipMessageType.TOOL_CALL_START,
            payload: {
              toolCallId,
              toolName,
              command: command || undefined,
              path: path || undefined,
              thought: thought || reasoning || undefined,
            },
          })
        } else if (thought || reasoning) {
          // Pure thinking without command
          messages.push({
            ...base,
            messageId: makeId(),
            type: AipMessageType.THINKING,
            payload: { thought: thought || reasoning },
          })
        }
        break
      }

      case 'AGENT_OBSERVATION': {
        // OH ObservationEvent: { observation: { content: [{type,text}], exit_code, kind }, tool_call_id, tool_name }
        const observation = data.observation as Record<string, unknown> | undefined
        const output = observation
          ? extractText(observation.content)
          : extractText(data.content) || (data.output as string) || ''
        const exitCode = (observation?.exit_code as number) ?? (data.exit_code as number | undefined)
        const toolCallId = (data.tool_call_id as string) ?? (data.action_id as string) ?? (data.id as string) ?? raw.id ?? makeId()
        const toolName = (data.tool_name as string) ?? (observation?.kind as string) ?? (dataKind || 'observation')
        const isError = (observation?.is_error as boolean) || dataKind === 'ErrorObservation' || dataKind === 'error'

        if (isError) {
          messages.push({
            ...base,
            messageId: makeId(),
            type: AipMessageType.TOOL_CALL_ERROR,
            payload: {
              toolCallId,
              toolName,
              errorMessage: output,
            },
          })
        } else {
          messages.push({
            ...base,
            messageId: makeId(),
            type: AipMessageType.TOOL_CALL_RESULT,
            payload: {
              toolCallId,
              toolName,
              output: output || undefined,
              exitCode,
            },
          })
        }
        break
      }

      case 'CONVERSATION_STATUS': {
        // Two formats:
        // 1. Simple: { status: "RUNNING" }
        // 2. OH ConversationStateUpdateEvent: { key: "execution_status", value: "running", kind: "ConversationStateUpdateEvent" }
        let status = (data.status as string) ?? ''
        if (!status && dataKind === 'ConversationStateUpdateEvent') {
          const key = data.key as string
          const value = data.value
          if (key === 'execution_status' && typeof value === 'string') {
            status = value.toUpperCase()
          } else {
            // Skip non-status state updates (e.g., stats)
            break
          }
        }
        if (status) {
          messages.push({
            ...base,
            messageId: raw.id || makeId(),
            type: AipMessageType.STATE_SYNC,
            payload: { status },
          })
        }
        break
      }

      case 'ERROR': {
        const error = (data.error as string) ?? (data.message as string) ?? 'Unknown error'
        messages.push({
          ...base,
          messageId: raw.id || makeId(),
          type: AipMessageType.ERROR,
          payload: { error, source: (data.source as string) ?? raw.kind },
        })
        break
      }
    }

    return messages
  },
}
