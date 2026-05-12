import { afterEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { useNavigatorChat, type UseNavigatorChat } from './useNavigatorChat'
import type { AgentTask } from '../types'

let app: App<Element> | undefined

afterEach(() => {
  app?.unmount()
  app = undefined
})

function okResponse(data: unknown): Response {
  return new Response(JSON.stringify({ code: 0, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('useNavigatorChat business action UX', () => {

  it('loads historical messages and reuses contextId on the next send', async () => {
    const requests: Array<{ url: string; method?: string; body?: unknown }> = []
    let chat: UseNavigatorChat | undefined
    app = createApp(defineComponent({
      setup() {
        chat = useNavigatorChat({
          baseUrl: 'http://navigator.test',
          agentId: 'agent-1',
          mode: 'business',
          fetch: async (url, init) => {
            requests.push({
              url,
              method: init.method,
              body: init.body ? JSON.parse(String(init.body)) : undefined,
            })
            if (String(url).includes('/sessions/ctx-1/messages')) {
              return okResponse({
                contextId: 'ctx-1',
                messages: [
                  {
                    messageId: 'hist-user',
                    contextId: 'ctx-1',
                    taskId: 'task-old',
                    role: 'USER',
                    type: 'USER',
                    content: '历史问题',
                    createdAt: '2026-05-12T10:00:00',
                  },
                  {
                    messageId: 'hist-result',
                    contextId: 'ctx-1',
                    taskId: 'task-old',
                    role: 'ASSISTANT',
                    type: 'RESULT',
                    content: '历史回答',
                    createdAt: '2026-05-12T10:00:01',
                  },
                ],
                nextCursor: 'hist-result',
                hasMore: false,
              })
            }
            return okResponse({
              taskId: 'task-new',
              agentId: 'agent-1',
              status: 'COMPLETED',
              contextId: 'ctx-1',
              terminal: true,
              terminalStatus: 'COMPLETED',
              messages: [
                {
                  id: 'new-result',
                  type: 'RESULT',
                  content: '继续回答',
                  timestamp: 2_000,
                  terminal: true,
                  terminalStatus: 'COMPLETED',
                },
              ],
            })
          },
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.loadSession('ctx-1')
    await nextTick()

    expect(chat.contextId.value).toBe('ctx-1')
    expect(chat.messages.value.map((message) => [message.role, message.content])).toEqual([
      ['user', '历史问题'],
      ['assistant', '历史回答'],
    ])

    await chat.send('继续问', {
      clientContext: { upstreamConversationId: 'tms-1' },
    })

    const askRequest = requests.find((request) => request.url.includes('/ask'))
    expect(askRequest?.body).toMatchObject({
      question: '继续问',
      contextId: 'ctx-1',
      clientContext: { upstreamConversationId: 'tms-1' },
    })
  })

  it('moves tool-result actions onto the final assistant reply and finalizes open skill frames', async () => {
    const structuredOutput = {
      type: 'OPEN_TMS_PAGE',
      label: '补齐开单草稿',
      routeName: 'OrderWorkbench',
      query: { aiDraftId: 'afd_xxx' },
    }
    const task: AgentTask = {
      taskId: 'task-1',
      agentId: 'agent-1',
      status: 'COMPLETED',
      contextId: 'ctx-1',
      terminal: true,
      terminalStatus: 'COMPLETED',
      messages: [
        {
          id: 'frame-open',
          type: 'STATE',
          content: 'skill frame open',
          timestamp: 1_000,
          metadata: {
            subtype: 'skill_frame_open',
            skillFrameId: 'frame-1',
            skillId: 'tms-order-opening-v1',
          },
        },
        {
          id: 'tool-result',
          type: 'TOOL_RESULT',
          content: JSON.stringify({ ok: true }),
          timestamp: 2_000,
          metadata: {
            toolName: 'submit_skill_result',
            toolCallId: 'tool-1',
            skillFrameId: 'frame-1',
            args: {
              summary: '已生成开单草稿，可点击打开补齐并确认。',
              structured_output: structuredOutput,
            },
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: JSON.stringify({
            summary: '已生成开单草稿，可点击打开补齐并确认。',
            structured_output: structuredOutput,
          }),
          timestamp: 3_000,
          terminal: true,
          terminalStatus: 'COMPLETED',
        },
      ],
    }

    let chat: UseNavigatorChat | undefined
    app = createApp(defineComponent({
      setup() {
        chat = useNavigatorChat({
          baseUrl: 'http://navigator.test',
          agentId: 'agent-1',
          mode: 'business',
          showRuntimeEvents: true,
          showToolCalls: true,
          showToolResults: true,
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('18911897361客户要发个100KG的货')
    await nextTick()

    const messages = chat.messages.value
    const actionMessages = messages.filter((message) => message.actions?.length)
    expect(actionMessages).toHaveLength(1)
    expect(actionMessages[0]).toMatchObject({
      role: 'assistant',
      messageType: 'RESULT',
      content: '已生成开单草稿，可点击打开补齐并确认。',
    })
    expect(actionMessages[0].actions?.[0]).toMatchObject({
      type: 'OPEN_TMS_PAGE',
      label: '补齐开单草稿',
      payload: structuredOutput,
    })

    const frame = messages.find((message) => message.skillFrame)?.skillFrame
    expect(frame?.status).toBe('success')
  })
})
