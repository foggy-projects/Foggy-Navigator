import { describe, expect, it } from 'vitest'
import { AipMessageType } from '@foggy/chat'
import type { ChatMessage } from '@foggy/chat'
import {
  canEnableRewind,
  canShowContinuationInput,
  getPendingSingleSelectQuestion,
  parseQuestionShortcut,
} from '../taskPaneResume'

describe('taskPaneResume', () => {
  it('allows continuation for provider-agnostic sessions', () => {
    expect(canShowContinuationInput({
      sessionId: 'session-1',
      status: 'COMPLETED',
    })).toBe(true)
  })

  it('does not allow continuation before the platform session exists', () => {
    expect(canShowContinuationInput({
      status: 'COMPLETED',
    })).toBe(false)
  })

  it.each(['RUNNING', 'CANCEL_REQUESTED', 'AWAITING_PERMISSION', 'AWAITING_INPUT'])(
    'does not allow continuation while task is %s',
    (status) => {
      expect(canShowContinuationInput({ sessionId: 'session-active', status })).toBe(false)
    },
  )

  it('does not enable Claude Code rewind for LangGraph Biz tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-langgraph-1',
      sessionId: 'session-langgraph-1',
      workerId: 'worker-1',
      prompt: 'biz task',
      status: 'COMPLETED',
      providerType: 'langgraph-biz-worker',
      model: 'biz-default',
      claudeSessionId: 'worker-session-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(false)
  })

  it('keeps Claude Code rewind enabled for completed Claude tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-claude-1',
      sessionId: 'session-claude-1',
      workerId: 'worker-1',
      prompt: 'claude task',
      status: 'COMPLETED',
      providerType: 'claude-worker',
      model: 'sonnet',
      claudeSessionId: 'claude-session-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(true)
  })

  it('enables platform conversation rewind for completed Codex tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-1',
      sessionId: 'session-codex-1',
      workerId: 'worker-1',
      prompt: 'codex task',
      status: 'COMPLETED',
      providerType: 'codex-worker',
      model: 'codex-deep',
      codexThreadId: 'thread-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(true)
  })

  it('enables platform conversation rewind for completed Codex App Server tasks', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-app-1',
      sessionId: 'session-codex-app-1',
      workerId: 'worker-1',
      prompt: 'app-server task',
      status: 'COMPLETED',
      providerType: 'codex-app-server-worker',
      model: 'gpt-5.6-sol:ultra',
      codexThreadId: 'thread-app-1',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(true)
  })

  it('does not enable rewind while a Codex task is awaiting permission', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-2',
      sessionId: 'session-codex-2',
      workerId: 'worker-1',
      prompt: 'codex task',
      status: 'AWAITING_PERMISSION',
      providerType: 'codex-worker',
      model: 'codex-deep',
      codexThreadId: 'thread-2',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(false)
  })

  it('does not enable rewind while a Codex task is awaiting input', () => {
    expect(canEnableRewind({
      taskId: 'task-codex-input',
      sessionId: 'session-codex-input',
      workerId: 'worker-1',
      prompt: 'codex task',
      status: 'AWAITING_INPUT',
      providerType: 'codex-worker',
      model: 'codex-ultra',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    })).toBe(false)
  })
})

describe('Codex question shortcuts', () => {
  function questionMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
    return {
      id: 'message-question',
      type: AipMessageType.CONFIRMATION_REQUEST,
      sender: 'system',
      content: '',
      timestamp: 1,
      permissionId: 'permission-1',
      permissionStatus: 'pending',
      raw: { taskId: 'task-current' },
      questions: [{
        id: 'deployment-target',
        header: 'Target',
        question: 'Choose a target',
        options: [
          { label: 'Staging', description: '' },
          { label: 'Production', description: '' },
          { label: 'Cancel', description: '' },
        ],
        multiSelect: false,
        isOther: false,
      }],
      ...overrides,
    }
  }

  it('maps a one-based number and an exact label to structured answers', () => {
    const messages = [questionMessage()]

    expect(parseQuestionShortcut(messages, '2', 'task-current', true)).toEqual({
      permissionId: 'permission-1',
      answers: { 'deployment-target': 'Production' },
    })
    expect(parseQuestionShortcut(messages, 'Production', 'task-current', true)).toEqual({
      permissionId: 'permission-1',
      answers: { 'deployment-target': 'Production' },
    })
    expect(parseQuestionShortcut(messages, 'production', 'task-current', true)).toBeNull()
    expect(parseQuestionShortcut(messages, '4', 'task-current', true)).toBeNull()
  })

  it('prefers an exact numeric label over interpreting it as an ordinal', () => {
    const message = questionMessage({
      questions: [{
        ...questionMessage().questions![0]!,
        options: [
          { label: 'Safe', description: '' },
          { label: '1', description: '' },
        ],
      }],
    })

    expect(parseQuestionShortcut([message], '1', 'task-current', true)).toEqual({
      permissionId: 'permission-1',
      answers: { 'deployment-target': '1' },
    })
  })

  it('rejects secret, multi-select, free-form, and multi-question requests', () => {
    const baseQuestion = questionMessage().questions![0]!
    const cases = [
      questionMessage({ questions: [{ ...baseQuestion, isSecret: true }] }),
      questionMessage({ questions: [{ ...baseQuestion, multiSelect: true }] }),
      questionMessage({ questions: [{ ...baseQuestion, options: null }] }),
      questionMessage({ questions: [baseQuestion, { ...baseQuestion, id: 'second' }] }),
    ]

    for (const message of cases) {
      expect(getPendingSingleSelectQuestion([message], 'task-current', true)).toBeNull()
      expect(parseQuestionShortcut([message], '1', 'task-current', true)).toBeNull()
    }
  })

  it('does not fall back to an older question when the latest request is not shortcut-safe', () => {
    const latest = questionMessage({
      id: 'message-latest',
      timestamp: 2,
      permissionId: 'permission-latest',
      questions: [{
        ...questionMessage().questions![0]!,
        id: 'secret',
        isSecret: true,
      }],
    })

    expect(parseQuestionShortcut([questionMessage(), latest], '1', 'task-current', true)).toBeNull()
  })

  it('never routes a shortcut to a stale pending request from another task', () => {
    const stale = questionMessage({ raw: { taskId: 'task-old' } })

    expect(parseQuestionShortcut([stale], '1', 'task-current', true)).toBeNull()

    const current = questionMessage({
      id: 'message-current',
      timestamp: 2,
      permissionId: 'permission-current',
      raw: { taskId: 'task-current' },
    })
    expect(parseQuestionShortcut([current, stale], '1', 'task-current', true)).toEqual({
      permissionId: 'permission-current',
      answers: { 'deployment-target': 'Staging' },
    })
  })

  it('keeps the unique task-id-less fallback only for legacy callers', () => {
    const legacy = questionMessage({ raw: undefined })

    expect(parseQuestionShortcut([legacy], '1', 'task-current', false)).toEqual({
      permissionId: 'permission-1',
      answers: { 'deployment-target': 'Staging' },
    })
    expect(parseQuestionShortcut([legacy], '1', 'task-current', true)).toBeNull()
    expect(parseQuestionShortcut([questionMessage()], '1', undefined, true)).toBeNull()
  })
})
