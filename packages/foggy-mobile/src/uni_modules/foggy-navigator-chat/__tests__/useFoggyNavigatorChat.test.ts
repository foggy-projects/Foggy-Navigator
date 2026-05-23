import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFoggyNavigatorChat } from '../lib/useFoggyNavigatorChat'
import type { FoggyNavigatorChatClient } from '../lib/types'

describe('useFoggyNavigatorChat', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('sends ask, polls messages, deduplicates and finishes terminal tasks', async () => {
    const client: FoggyNavigatorChatClient = {
      ask: vi.fn().mockResolvedValue({
        taskId: 'task-1',
        contextId: 'ctx-1',
        status: 'WORKING',
        nextCursor: 'c1',
        messages: [
          { id: 'state-1', type: 'STATE', content: 'working' },
        ],
      }),
      getTaskMessages: vi.fn().mockResolvedValue({
        taskId: 'task-1',
        contextId: 'ctx-1',
        status: 'COMPLETED',
        terminal: true,
        messages: [
          { id: 'result-1', type: 'RESULT', content: 'done', terminal: true },
          { id: 'result-1', type: 'RESULT', content: 'done again', terminal: true },
        ],
      }),
    }

    const chat = useFoggyNavigatorChat({
      baseUrl: '/bff/navigator',
      agentId: 'agent-1',
      pollInterval: 10,
      showRuntimeEvents: true,
    }, { client })

    await chat.send('hello')
    expect(chat.isLoading.value).toBe(true)
    expect(chat.contextId.value).toBe('ctx-1')

    await vi.advanceTimersByTimeAsync(10)

    expect(chat.isLoading.value).toBe(false)
    expect(chat.taskStatus.value).toBe('COMPLETED')
    expect(chat.messages.value.filter((message) => message.content === 'done')).toHaveLength(1)
    expect(client.getTaskMessages).toHaveBeenCalledWith('task-1', 'c1', 50)
  })

  it('loads and clears session history through injected BFF callbacks', async () => {
    const client: FoggyNavigatorChatClient = {
      ask: vi.fn(),
      getTaskMessages: vi.fn(),
      listSessions: vi.fn().mockResolvedValue({
        sessions: [{ contextId: 'ctx-1', title: '历史' }],
      }),
      getSessionMessages: vi.fn().mockResolvedValue({
        contextId: 'ctx-1',
        messages: [
          { id: 'u1', role: 'USER', content: 'question' },
          { id: 'a1', role: 'ASSISTANT', content: 'answer' },
        ],
      }),
      deleteSession: vi.fn().mockResolvedValue(undefined),
    }

    const chat = useFoggyNavigatorChat({
      baseUrl: '/bff/navigator',
      agentId: 'agent-1',
    }, { client })

    await expect(chat.listSessions()).resolves.toMatchObject({
      sessions: [{ contextId: 'ctx-1' }],
    })
    await chat.loadSession('ctx-1')

    expect(chat.messages.value).toMatchObject([
      { role: 'user', content: 'question' },
      { role: 'assistant', content: 'answer' },
    ])

    await chat.deleteSession('ctx-1')
    expect(chat.messages.value).toHaveLength(0)
  })

  it('keeps retrying polling under weak network until timeout budget is reached', async () => {
    const client: FoggyNavigatorChatClient = {
      ask: vi.fn().mockResolvedValue({
        taskId: 'task-1',
        contextId: 'ctx-1',
        status: 'WORKING',
      }),
      getTaskMessages: vi.fn().mockRejectedValue(new Error('network')),
    }

    const chat = useFoggyNavigatorChat({
      baseUrl: '/bff/navigator',
      agentId: 'agent-1',
      pollInterval: 10,
      timeout: 20,
    }, { client })

    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(10)

    expect(chat.isLoading.value).toBe(true)
    expect(chat.networkRetryCount.value).toBe(1)
    expect(chat.progressText.value).toContain('网络波动')

    await vi.advanceTimersByTimeAsync(30)

    expect(chat.isLoading.value).toBe(false)
    expect(chat.messages.value.at(-1)?.content).toContain('网络连接不稳定')
  })
})
