import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createUniNavigatorBffClient } from '../lib/client'

describe('UniNavigatorBffClient', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('calls upstream BFF ask through uni.request and unwraps code 0 envelope', async () => {
    ;(uni.request as any).mockImplementation((options: any) => {
      options.success({
        statusCode: 200,
        data: {
          code: 0,
          data: {
            taskId: 'task-1',
            contextId: 'ctx-1',
            status: 'SUBMITTED',
          },
        },
      })
    })

    const client = createUniNavigatorBffClient({
      baseUrl: '/bff/navigator/',
      agentId: 'agent-1',
      maxTurns: 3,
      headers: () => ({ Authorization: 'Bearer app-token' }),
    })

    await expect(client.ask('hello', undefined, {
      clientContext: { upstreamUserId: 'u1' },
    })).resolves.toMatchObject({
      taskId: 'task-1',
      contextId: 'ctx-1',
    })

    expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({
      url: '/bff/navigator/api/v1/open/agents/agent-1/ask',
      method: 'POST',
      data: {
        question: 'hello',
        maxTurns: 3,
        clientContext: { upstreamUserId: 'u1' },
      },
      header: expect.objectContaining({
        Accept: 'application/json',
        Authorization: 'Bearer app-token',
        'Content-Type': 'application/json',
      }),
    }))
  })

  it('builds polling and session URLs with escaped identifiers', async () => {
    const calls: any[] = []
    ;(uni.request as any).mockImplementation((options: any) => {
      calls.push(options)
      options.success({ statusCode: 200, data: { code: 0, data: { messages: [] } } })
    })

    const client = createUniNavigatorBffClient({
      baseUrl: '/bff/navigator',
      agentId: 'agent/a',
    })

    await client.getTaskMessages('task 1', 'cursor 1', 20)
    await client.getSessionMessages!('ctx/1', { limit: 10 })

    expect(calls[0].url).toBe('/bff/navigator/api/v1/open/agents/agent%2Fa/tasks/task%201/messages?cursor=cursor%201&limit=20')
    expect(calls[1].url).toBe('/bff/navigator/api/v1/open/agents/agent%2Fa/sessions/ctx%2F1/messages?limit=10')
  })

  it('sanitizes sensitive backend error messages', async () => {
    ;(uni.request as any).mockImplementation((options: any) => {
      options.success({
        statusCode: 500,
        data: { message: 'stack has task_scoped_token=secret' },
      })
    })

    const client = createUniNavigatorBffClient({
      baseUrl: '/bff/navigator',
      agentId: 'agent-1',
    })

    await expect(client.cancelTask!('task-1')).rejects.toThrow('请求失败，请稍后重试或联系管理员。')
  })
})
