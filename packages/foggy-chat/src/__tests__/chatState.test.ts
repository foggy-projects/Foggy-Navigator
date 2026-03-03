import { describe, it, expect, beforeEach } from 'vitest'
import { createChatState } from '../store/chatState'
import { AipMessageType } from '../types/aip'
import type { AipMessage } from '../types/aip'
import type { ChatState } from '../store/chatState'

function makeAip<T>(type: AipMessageType, payload: T, overrides?: Partial<AipMessage<T>>): AipMessage<T> {
  return {
    messageId: `msg-${Date.now()}`,
    sessionId: 'session-1',
    timestamp: Date.now(),
    type,
    payload,
    ...overrides,
  }
}

describe('createChatState', () => {
  let state: ChatState

  beforeEach(() => {
    state = createChatState()
  })

  it('starts with empty messages', () => {
    expect(state.messages.value).toHaveLength(0)
    expect(state.isThinking.value).toBe(false)
    expect(state.connectionStatus.value).toBe('disconnected')
    expect(state.conversationStatus.value).toBe('')
  })

  // ========== TEXT_CHUNK ==========

  describe('TEXT_CHUNK', () => {
    it('creates new assistant message on first chunk', () => {
      state.processAipMessage(makeAip(AipMessageType.TEXT_CHUNK, { content: 'Hello' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].sender).toBe('assistant')
      expect(state.messages.value[0].content).toBe('Hello')
      expect(state.messages.value[0].type).toBe(AipMessageType.TEXT_CHUNK)
    })

    it('appends to existing chunk message', () => {
      state.processAipMessage(makeAip(AipMessageType.TEXT_CHUNK, { content: 'Hello' }))
      state.processAipMessage(makeAip(AipMessageType.TEXT_CHUNK, { content: ' World' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].content).toBe('Hello World')
    })

    it('clears isThinking', () => {
      state.isThinking.value = true
      state.processAipMessage(makeAip(AipMessageType.TEXT_CHUNK, { content: 'Hi' }))
      expect(state.isThinking.value).toBe(false)
    })
  })

  // ========== TEXT_COMPLETE ==========

  describe('TEXT_COMPLETE', () => {
    it('replaces content of last chunk', () => {
      state.processAipMessage(makeAip(AipMessageType.TEXT_CHUNK, { content: 'partial' }))
      state.processAipMessage(makeAip(AipMessageType.TEXT_COMPLETE, { content: 'Full response' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].content).toBe('Full response')
      expect(state.messages.value[0].type).toBe(AipMessageType.TEXT_COMPLETE)
    })

    it('creates new message if no prior chunk', () => {
      state.processAipMessage(makeAip(AipMessageType.TEXT_COMPLETE, { content: 'Direct' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].content).toBe('Direct')
    })
  })

  // ========== THINKING ==========

  describe('THINKING', () => {
    it('sets isThinking and adds message with thought', () => {
      state.processAipMessage(makeAip(AipMessageType.THINKING, { thought: 'Let me think...' }))

      expect(state.isThinking.value).toBe(true)
      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].thought).toBe('Let me think...')
      expect(state.messages.value[0].sender).toBe('assistant')
    })
  })

  // ========== TOOL_CALL_START ==========

  describe('TOOL_CALL_START', () => {
    it('creates tool message', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_START, {
        toolCallId: 'tc-1',
        toolName: 'bash',
        command: 'ls -la',
        thought: 'List files',
      }))

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.sender).toBe('tool')
      expect(msg.toolCallId).toBe('tc-1')
      expect(msg.toolName).toBe('bash')
      expect(msg.content).toBe('ls -la')
      expect(msg.thought).toBe('List files')
    })

    it('uses path if no command', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_START, {
        toolCallId: 'tc-2',
        toolName: 'read',
        path: '/etc/hosts',
      }))

      expect(state.messages.value[0].content).toBe('/etc/hosts')
    })

    it('merges into existing TOOL_CALL_RESULT', () => {
      // Result arrives before start (out-of-order)
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_RESULT, {
        toolCallId: 'tc-3',
        toolName: 'bash',
        output: 'done',
      }))
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_START, {
        toolCallId: 'tc-3',
        toolName: 'bash',
        command: 'echo hi',
      }, { timestamp: 100 }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].toolOutput).toBe('done')
      expect(state.messages.value[0].content).toBe('echo hi')
      expect(state.messages.value[0].type).toBe(AipMessageType.TOOL_CALL_START)
    })
  })

  // ========== TOOL_CALL_RESULT ==========

  describe('TOOL_CALL_RESULT', () => {
    it('attaches output to existing start message', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_START, {
        toolCallId: 'tc-1',
        toolName: 'bash',
        command: 'pwd',
      }))
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_RESULT, {
        toolCallId: 'tc-1',
        toolName: 'bash',
        output: '/home/user',
      }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].toolOutput).toBe('/home/user')
    })

    it('creates standalone message if no start exists', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_RESULT, {
        toolCallId: 'tc-new',
        toolName: 'bash',
        output: 'result',
      }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].type).toBe(AipMessageType.TOOL_CALL_RESULT)
    })
  })

  // ========== TOOL_CALL_ERROR ==========

  describe('TOOL_CALL_ERROR', () => {
    it('attaches error to existing start message', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_START, {
        toolCallId: 'tc-1',
        toolName: 'bash',
        command: 'fail',
      }))
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_ERROR, {
        toolCallId: 'tc-1',
        toolName: 'bash',
        errorMessage: 'command not found',
      }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].error).toBe('command not found')
    })

    it('creates standalone message if no start exists', () => {
      state.processAipMessage(makeAip(AipMessageType.TOOL_CALL_ERROR, {
        toolCallId: 'tc-orphan',
        toolName: 'bash',
        errorMessage: 'timeout',
      }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].error).toBe('timeout')
    })
  })

  // ========== STATE_SYNC ==========

  describe('STATE_SYNC', () => {
    it('updates conversationStatus and adds system message', () => {
      state.processAipMessage(makeAip(AipMessageType.STATE_SYNC, { status: 'IDLE' }))

      expect(state.conversationStatus.value).toBe('IDLE')
      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].sender).toBe('system')
      expect(state.messages.value[0].content).toContain('IDLE')
    })

    it('skips duplicate status', () => {
      state.processAipMessage(makeAip(AipMessageType.STATE_SYNC, { status: 'IDLE' }))
      state.processAipMessage(makeAip(AipMessageType.STATE_SYNC, { status: 'IDLE' }))

      expect(state.messages.value).toHaveLength(1)
    })

    it('falls back to content when status is undefined', () => {
      state.processAipMessage(makeAip(AipMessageType.STATE_SYNC, { content: 'Task stream reconnected', subtype: 'reconnected' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].content).toBe('Task stream reconnected')
    })

    it('skips STATE_SYNC with no status and no content', () => {
      state.processAipMessage(makeAip(AipMessageType.STATE_SYNC, {}))

      expect(state.messages.value).toHaveLength(0)
    })
  })

  // ========== ERROR ==========

  describe('ERROR', () => {
    it('adds error system message', () => {
      state.processAipMessage(makeAip(AipMessageType.ERROR, { error: 'Server error' }))

      expect(state.messages.value).toHaveLength(1)
      expect(state.messages.value[0].sender).toBe('system')
      expect(state.messages.value[0].error).toBe('Server error')
    })
  })

  // ========== TASK_COMPLETED ==========

  describe('TASK_COMPLETED', () => {
    it('adds system message with payload in raw field', () => {
      const payload = {
        taskId: 'task-1',
        targetAgentId: 'coding-agent',
        taskType: 'CODING',
        status: 'COMPLETED',
        resultSummary: 'All tests passed',
      }
      state.processAipMessage(makeAip(AipMessageType.TASK_COMPLETED, payload))

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.sender).toBe('system')
      expect(msg.type).toBe(AipMessageType.TASK_COMPLETED)
      expect(msg.content).toBe('All tests passed')
      expect(msg.raw).toEqual(payload)
    })

    it('handles missing resultSummary', () => {
      state.processAipMessage(makeAip(AipMessageType.TASK_COMPLETED, {
        taskId: 'task-2',
        targetAgentId: 'claude-worker',
        taskType: 'CLAUDE_WORKER',
        status: 'FAILED',
      }))

      expect(state.messages.value[0].content).toBe('')
    })
  })

  // ========== CONFIRMATION_REQUEST ==========

  describe('CONFIRMATION_REQUEST', () => {
    it('adds permission request message', () => {
      const payload = {
        permissionId: 'perm-1',
        toolName: 'Bash',
        toolInput: { command: 'rm -rf /tmp/old' },
        taskId: 'task-1',
      }
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, payload))

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.sender).toBe('system')
      expect(msg.type).toBe(AipMessageType.CONFIRMATION_REQUEST)
      expect(msg.permissionId).toBe('perm-1')
      expect(msg.permissionStatus).toBe('pending')
      expect(msg.toolName).toBe('Bash')
      expect(msg.raw).toEqual(payload)
    })

    it('adds user question message with questions array', () => {
      const payload = {
        permissionId: 'perm-q',
        taskId: 'task-2',
        questions: [
          {
            question: 'Which library?',
            header: 'Library',
            options: [
              { label: 'React', description: 'Popular UI lib' },
              { label: 'Vue', description: 'Progressive framework' },
            ],
            multiSelect: false,
          },
        ],
      }
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, payload))

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.permissionId).toBe('perm-q')
      expect(msg.permissionStatus).toBe('pending')
      expect(msg.questions).toHaveLength(1)
      expect(msg.questions![0].question).toBe('Which library?')
      expect(msg.questions![0].options).toHaveLength(2)
      expect(msg.planReview).toBeUndefined()
    })

    it('adds plan review message with planReview flag', () => {
      const payload = {
        permissionId: 'perm-plan',
        taskId: 'task-3',
        planReview: true,
      }
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, payload))

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.permissionId).toBe('perm-plan')
      expect(msg.permissionStatus).toBe('pending')
      expect(msg.planReview).toBe(true)
      expect(msg.questions).toBeUndefined()
    })

    it('handles empty questions array', () => {
      const payload = {
        permissionId: 'perm-empty',
        taskId: 'task-4',
        questions: [],
      }
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, payload))

      const msg = state.messages.value[0]
      expect(msg.questions).toEqual([])
    })
  })

  // ========== resolvePermission ==========

  describe('resolvePermission', () => {
    it('resolves permission to approved', () => {
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, {
        permissionId: 'perm-1',
        taskId: 'task-1',
        toolName: 'Bash',
      }))

      state.resolvePermission('perm-1', 'approved')

      expect(state.messages.value[0].permissionStatus).toBe('approved')
    })

    it('resolves permission to denied', () => {
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, {
        permissionId: 'perm-2',
        taskId: 'task-2',
        toolName: 'Write',
      }))

      state.resolvePermission('perm-2', 'denied')

      expect(state.messages.value[0].permissionStatus).toBe('denied')
    })

    it('does nothing for unknown permissionId', () => {
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, {
        permissionId: 'perm-3',
        taskId: 'task-3',
      }))

      state.resolvePermission('unknown-id', 'approved')

      expect(state.messages.value[0].permissionStatus).toBe('pending')
    })

    it('resolves question permission', () => {
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, {
        permissionId: 'perm-q',
        taskId: 'task-q',
        questions: [{ question: 'Q?', header: 'H', options: [], multiSelect: false }],
      }))

      state.resolvePermission('perm-q', 'approved')

      expect(state.messages.value[0].permissionStatus).toBe('approved')
    })

    it('resolves plan review permission', () => {
      state.processAipMessage(makeAip(AipMessageType.CONFIRMATION_REQUEST, {
        permissionId: 'perm-plan',
        taskId: 'task-plan',
        planReview: true,
      }))

      state.resolvePermission('perm-plan', 'denied')

      expect(state.messages.value[0].permissionStatus).toBe('denied')
    })
  })

  // ========== addUserMessage ==========

  describe('addUserMessage', () => {
    it('adds user message', () => {
      state.addUserMessage('Hello from user')

      expect(state.messages.value).toHaveLength(1)
      const msg = state.messages.value[0]
      expect(msg.sender).toBe('user')
      expect(msg.content).toBe('Hello from user')
      expect(msg.type).toBe(AipMessageType.TEXT_COMPLETE)
    })
  })

  // ========== sortedMessages ==========

  describe('sortedMessages', () => {
    it('returns messages sorted by timestamp ascending', () => {
      state.processAipMessage(makeAip(AipMessageType.TEXT_COMPLETE, { content: 'Second' }, { timestamp: 2000 }))
      state.processAipMessage(makeAip(AipMessageType.ERROR, { error: 'First' }, { timestamp: 1000 }))

      const sorted = state.sortedMessages.value
      expect(sorted[0].timestamp).toBe(1000)
      expect(sorted[1].timestamp).toBe(2000)
    })
  })

  // ========== setConnectionStatus ==========

  describe('setConnectionStatus', () => {
    it('updates connection status', () => {
      state.setConnectionStatus('connected')
      expect(state.connectionStatus.value).toBe('connected')

      state.setConnectionStatus('error')
      expect(state.connectionStatus.value).toBe('error')
    })
  })

  // ========== clearMessages ==========

  describe('clearMessages', () => {
    it('clears all state', () => {
      state.addUserMessage('test')
      state.isThinking.value = true
      state.conversationStatus.value = 'RUNNING'

      state.clearMessages()

      expect(state.messages.value).toHaveLength(0)
      expect(state.isThinking.value).toBe(false)
      expect(state.conversationStatus.value).toBe('')
    })
  })
})
