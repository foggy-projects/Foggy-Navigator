import { describe, it, expect } from 'vitest'
import { AipMessageType } from '@foggy/chat'
import { tutorAgentAdapter } from '@/adapters/TutorAgentAdapter'
import type { AgentMessage } from '@/types'

function makeRaw(type: string, payload: unknown = {}): AgentMessage {
  return {
    messageId: 'msg-1',
    sessionId: 'session-1',
    agentId: 'tutor-agent',
    timestamp: 1000,
    version: '1.0',
    type,
    payload,
  }
}

describe('tutorAgentAdapter', () => {
  // ========== Known types ==========

  it.each([
    'TEXT_CHUNK',
    'TEXT_COMPLETE',
    'TOOL_CALL_START',
    'TOOL_CALL_RESULT',
    'TOOL_CALL_ERROR',
    'THINKING',
    'STATE_SYNC',
    'ERROR',
    'TASK_COMPLETED',
    'ROUTE_REQUEST',
    'SESSION_START',
    'SESSION_END',
    'HEARTBEAT',
  ])('converts known type %s to AipMessage', (type) => {
    const raw = makeRaw(type, { content: 'test' })
    const result = tutorAgentAdapter.convert(raw, 'session-1')

    expect(result).toHaveLength(1)
    expect(result[0].type).toBe(AipMessageType[type as keyof typeof AipMessageType])
    expect(result[0].messageId).toBe('msg-1')
    expect(result[0].sessionId).toBe('session-1')
    expect(result[0].timestamp).toBe(1000)
    expect(result[0].payload).toEqual({ content: 'test' })
  })

  // ========== Unknown types ==========

  it('returns empty array for unknown type', () => {
    const raw = makeRaw('UNKNOWN_TYPE')
    const result = tutorAgentAdapter.convert(raw, 'session-1')
    expect(result).toEqual([])
  })

  it('returns empty array for empty string type', () => {
    const raw = makeRaw('')
    const result = tutorAgentAdapter.convert(raw, 'session-1')
    expect(result).toEqual([])
  })

  // ========== Payload passthrough ==========

  it('passes TEXT_CHUNK payload through unchanged', () => {
    const payload = { content: 'Hello world', messageId: 'inner-1' }
    const result = tutorAgentAdapter.convert(makeRaw('TEXT_CHUNK', payload), 's1')

    expect(result[0].payload).toEqual(payload)
  })

  it('passes TOOL_CALL_START payload through unchanged', () => {
    const payload = { toolCallId: 'tc-1', toolName: 'bash', command: 'ls', thought: 'list files' }
    const result = tutorAgentAdapter.convert(makeRaw('TOOL_CALL_START', payload), 's1')

    expect(result[0].payload).toEqual(payload)
  })

  it('passes TASK_COMPLETED payload through unchanged', () => {
    const payload = {
      taskId: 'task-1',
      targetAgentId: 'coding-agent',
      taskType: 'CODING',
      status: 'COMPLETED',
      resultSummary: 'Done',
    }
    const result = tutorAgentAdapter.convert(makeRaw('TASK_COMPLETED', payload), 's1')

    expect(result[0].payload).toEqual(payload)
  })

  // ========== Session ID ==========

  it('uses provided sessionId, not the one in raw', () => {
    const raw = makeRaw('TEXT_COMPLETE', { content: 'hi' })
    const result = tutorAgentAdapter.convert(raw, 'override-session')

    expect(result[0].sessionId).toBe('override-session')
  })
})
