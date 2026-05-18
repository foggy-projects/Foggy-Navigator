import { afterEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import ElementPlus from 'element-plus'
import NavigatorChat from '../components/NavigatorChat.vue'
import SkillFrameBlockView from '../components/SkillFrameBlockView.vue'
import { useNavigatorChat, type UseNavigatorChat } from './useNavigatorChat'
import type { AgentTask, NavigatorAttachmentResult, SkillFrameBlock } from '../types'

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
  it('keeps config enableAttachments when the direct boolean prop is absent', async () => {
    const root = document.createElement('div')
    app = createApp(defineComponent({
      setup() {
        return () => h(NavigatorChat, {
          config: {
            baseUrl: 'http://navigator.test',
            agentId: 'agent-1',
            mode: 'business',
            enableAttachments: true,
            uploadAttachment: async (file: File) => ({
              name: file.name,
              size: file.size,
              mimeType: file.type,
              kind: 'file',
            }),
            fetch: async () => okResponse({
              taskId: 'task-1',
              agentId: 'agent-1',
              status: 'COMPLETED',
              contextId: 'ctx-1',
              terminal: true,
              terminalStatus: 'COMPLETED',
              messages: [],
            }),
          },
        })
      },
    }))
    app.use(ElementPlus)
    app.mount(root)
    await nextTick()

    const chatRoot = root.querySelector('.navigator-chat')
    expect(chatRoot?.getAttribute('data-upload-hook')).toBe('present')
    expect(chatRoot?.getAttribute('data-attachments-enabled')).toBe('true')
    expect(root.querySelector('.nc-attachment-button')).not.toBeNull()
  })

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

  it('sends uploaded attachments as top-level ask payload', async () => {
    const requests: Array<{ url: string; method?: string; body?: unknown }> = []
    const attachments: NavigatorAttachmentResult[] = [
      {
        id: 'att-1',
        name: '回单照片.jpg',
        mimeType: 'image/jpeg',
        size: 123456,
        kind: 'image',
        url: 'https://tms.example.com/attachments/att-1',
        provider: 'tms',
      },
    ]
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
            return okResponse({
              taskId: 'task-attachments',
              agentId: 'agent-1',
              status: 'COMPLETED',
              contextId: 'ctx-attachments',
              terminal: true,
              terminalStatus: 'COMPLETED',
              messages: [],
            })
          },
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('请识别这张回单', { attachments })
    await nextTick()

    const askRequest = requests.find((request) => request.url.includes('/ask'))
    expect(askRequest?.body).toMatchObject({
      question: '请识别这张回单',
      attachments,
    })
    expect(chat.messages.value[0]).toMatchObject({
      role: 'user',
      content: '请识别这张回单',
      attachments,
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

  it('does not use a bare runtime frame id as user-facing skill frame text', async () => {
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
            skillFrameId: 'frm_622f67035042',
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: '已完成操作。',
          timestamp: 2_000,
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
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('执行一下')
    await nextTick()

    const frameMessage = chat.messages.value.find((message) => message.skillFrame)
    expect(frameMessage?.content).toContain('执行步骤')
    expect(frameMessage?.content).not.toContain('frm_622f67035042')
    expect(frameMessage?.skillFrame?.displayName).toBe('执行步骤')
  })

  it('renders details mode with friendly skill and tool labels only', async () => {
    const root = document.createElement('div')
    const frame: SkillFrameBlock = {
      frameId: 'frame-root',
      skillId: 'system.root',
      displayName: '开始处理任务',
      status: 'success',
      openedAt: 1_000,
      closedAt: 2_000,
      durationMs: 1_000,
      openContent: 'Opening frame for skill: system.root',
      rawOpen: { skillId: 'system.root' },
      rawClose: { status: 'COMPLETED' },
      trace: { taskId: 'task-1' },
      children: [],
      toolExecutions: [
        {
          toolCallId: 'tool-1',
          toolName: 'submit_skill_result',
          displayName: '处理完成',
          status: 'success',
          durationMs: 200,
          args: { summary: 'done' },
          result: { status: 'SUCCESS' },
          rawCall: { toolName: 'submit_skill_result' },
          rawResult: { status: 'SUCCESS' },
          summary: ['SUCCESS'],
          trace: { toolCallId: 'tool-1' },
        },
      ],
    }

    app = createApp(defineComponent({
      setup() {
        return () => h(SkillFrameBlockView, {
          frame,
          displayMode: 'details',
        })
      },
    }))
    app.use(ElementPlus)
    app.mount(root)
    await nextTick()

    const text = root.textContent ?? ''
    expect(text).toContain('开始处理任务')
    expect(text).toContain('处理完成')
    expect(text).toContain('结果已准备完成。')
    expect(text).not.toContain('system.root')
    expect(text).not.toContain('submit_skill_result')
    expect(text).not.toContain('Frame 原始 JSON')
    expect(text).not.toContain('参数')
    expect(text).not.toContain('SUCCESS')
  })

  it('localizes LangGraph worker connection text in non-debug assistant messages', async () => {
    const task: AgentTask = {
      taskId: 'task-1',
      agentId: 'agent-1',
      status: 'COMPLETED',
      contextId: 'ctx-1',
      terminal: true,
      terminalStatus: 'COMPLETED',
      messages: [
        {
          id: 'connecting-text',
          type: 'TEXT',
          content: 'Connecting to LangGraph worker...',
          timestamp: 1_000,
        },
      ],
    }

    let chat: UseNavigatorChat | undefined
    app = createApp(defineComponent({
      setup() {
        chat = useNavigatorChat({
          baseUrl: 'http://navigator.test',
          agentId: 'agent-1',
          mode: 'details',
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('执行一下')
    await nextTick()

    const assistantText = chat.messages.value.find((message) => message.role === 'assistant')?.content ?? ''
    expect(assistantText).toBe('正在连接至道同。')
    expect(assistantText).not.toContain('LangGraph')
  })

  it('switches display modes in the component without changing the backend protocol', async () => {
    const root = document.createElement('div')
    let requests = 0
    let chatVm: { send: (content: string) => Promise<void> } | undefined
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
          content: 'Opening frame for skill: system.root',
          timestamp: 1_000,
          metadata: {
            subtype: 'skill_frame_open',
            skillFrameId: 'frame-root',
            skillId: 'system.root',
          },
        },
        {
          id: 'tool-call',
          type: 'TOOL_CALL',
          content: 'Calling tool submit_skill_result',
          timestamp: 1_100,
          toolCallId: 'tool-1',
          metadata: {
            skillFrameId: 'frame-root',
            toolName: 'submit_skill_result',
            args: { summary: 'done' },
          },
        },
        {
          id: 'tool-result',
          type: 'TOOL_RESULT',
          content: 'Tool submit_skill_result returned SUCCESS',
          timestamp: 1_300,
          toolCallId: 'tool-1',
          metadata: {
            skillFrameId: 'frame-root',
            toolName: 'submit_skill_result',
            result: { status: 'SUCCESS' },
          },
        },
        {
          id: 'frame-close',
          type: 'STATE',
          content: 'Closing frame for skill: system.root',
          timestamp: 1_500,
          metadata: {
            subtype: 'skill_frame_close',
            skillFrameId: 'frame-root',
            skillId: 'system.root',
            status: 'COMPLETED',
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: '已完成操作。',
          timestamp: 2_000,
          terminal: true,
          terminalStatus: 'COMPLETED',
        },
      ],
    }

    app = createApp(defineComponent({
      setup() {
        return () => h(NavigatorChat, {
          ref: (instance: unknown) => {
            chatVm = instance as typeof chatVm
          },
          showHeader: true,
          config: {
            baseUrl: 'http://navigator.test',
            agentId: 'agent-1',
            mode: 'business',
            showDisplayModeSwitcher: true,
            fetch: async () => {
              requests += 1
              return okResponse(task)
            },
          },
        })
      },
    }))
    app.use(ElementPlus)
    app.mount(root)
    await nextTick()
    if (!chatVm) throw new Error('failed to mount navigator chat')

    await chatVm.send('执行一下')
    await nextTick()

    expect(requests).toBe(1)
    expect(root.textContent ?? '').toContain('已完成操作。')
    expect(root.textContent ?? '').not.toContain('开始处理任务')
    expect(root.textContent ?? '').not.toContain('system.root')

    const modeButtons = Array.from(root.querySelectorAll<HTMLButtonElement>('.nc-mode-button'))
    modeButtons.find((button) => button.textContent?.trim() === '详情')?.click()
    await nextTick()

    expect(requests).toBe(1)
    expect(root.textContent ?? '').toContain('开始处理任务')
    expect(root.textContent ?? '').toContain('处理完成')
    expect(root.textContent ?? '').not.toContain('system.root')
    expect(root.textContent ?? '').not.toContain('submit_skill_result')
    expect(root.textContent ?? '').not.toContain('Frame 原始 JSON')

    modeButtons.find((button) => button.textContent?.trim() === '调试')?.click()
    await nextTick()

    expect(requests).toBe(1)
    expect(root.textContent ?? '').toContain('system.root')
    expect(root.textContent ?? '').toContain('submit_skill_result')
    expect(root.textContent ?? '').toContain('Frame 原始 JSON')
  })

  it('keeps execution report fields on skill frames', async () => {
    const task: AgentTask = {
      taskId: 'task-1',
      agentId: 'agent-1',
      status: 'COMPLETED',
      contextId: 'ctx-1',
      terminal: true,
      terminalStatus: 'COMPLETED',
      messages: [
        {
          id: 'frame-close',
          type: 'STATE',
          content: 'skill frame close',
          timestamp: 1_000,
          metadata: {
            subtype: 'skill_frame_close',
            skillFrameId: 'frame-1',
            skillId: 'tms-fulfillment-agent',
            execution_report_ref: 'frame-report://task-1/frame-1',
            execution_report_digest: {
              status: 'COMPLETED',
              summary: '履约技能执行完成',
              skill_id: 'tms-fulfillment-agent',
              frame_kind: 'SKILL',
              generated_at: '2026-05-17T12:00:00Z',
            },
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: '已完成操作。',
          timestamp: 2_000,
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
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('创建车辆')
    await nextTick()

    const frame = chat.messages.value.find((message) => message.skillFrame)?.skillFrame
    expect(frame?.executionReportRef).toBe('frame-report://task-1/frame-1')
    expect(frame?.executionReportDigest).toMatchObject({
      status: 'COMPLETED',
      summary: '履约技能执行完成',
      reportRef: 'frame-report://task-1/frame-1',
      skillId: 'tms-fulfillment-agent',
      frameKind: 'SKILL',
      generatedAt: '2026-05-17T12:00:00Z',
    })
  })

  it('keeps execution report fields on tool results and final replies', async () => {
    const task: AgentTask = {
      taskId: 'task-1',
      agentId: 'agent-1',
      status: 'COMPLETED',
      contextId: 'ctx-1',
      terminal: true,
      terminalStatus: 'COMPLETED',
      messages: [
        {
          id: 'tool-result',
          type: 'TOOL_RESULT',
          content: JSON.stringify({ status: 'COMPLETED', success: true }),
          timestamp: 1_000,
          metadata: {
            toolName: 'invoke_business_function',
            toolCallId: 'tool-1',
            functionId: 'tms.vehicle.create',
            execution_report_ref: 'frame-report://task-1/function-frame',
            execution_report_digest: {
              status: 'COMPLETED',
              summary: '业务函数执行完成',
              skill_id: 'tms-vehicle-agent',
              frame_kind: 'FUNCTION',
              generated_at: '2026-05-17T12:00:01Z',
            },
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: '车辆创建完成。',
          timestamp: 2_000,
          metadata: {
            execution_report_ref: 'frame-report://task-1/root-frame',
            execution_report_digest: {
              status: 'COMPLETED',
              summary: '根任务执行完成',
              skill_id: 'root-agent',
              frame_kind: 'ROOT',
              generated_at: '2026-05-17T12:00:02Z',
            },
          },
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
          showToolResults: true,
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('创建车辆')
    await nextTick()

    const tool = chat.messages.value.find((message) => message.toolExecution)?.toolExecution
    expect(tool?.executionReportRef).toBe('frame-report://task-1/function-frame')
    expect(tool?.executionReportDigest).toMatchObject({
      status: 'COMPLETED',
      summary: '业务函数执行完成',
      reportRef: 'frame-report://task-1/function-frame',
      skillId: 'tms-vehicle-agent',
      frameKind: 'FUNCTION',
      generatedAt: '2026-05-17T12:00:01Z',
    })

    const reply = chat.messages.value.find((message) => message.messageType === 'RESULT')
    expect(reply?.executionReportRef).toBe('frame-report://task-1/root-frame')
    expect(reply?.executionReportDigest).toMatchObject({
      status: 'COMPLETED',
      summary: '根任务执行完成',
      reportRef: 'frame-report://task-1/root-frame',
      skillId: 'root-agent',
      frameKind: 'ROOT',
      generatedAt: '2026-05-17T12:00:02Z',
    })
  })

  it('shows task progress retry events in details mode', async () => {
    const task: AgentTask = {
      taskId: 'task-progress',
      agentId: 'agent-1',
      status: 'COMPLETED',
      contextId: 'ctx-1',
      terminal: true,
      terminalStatus: 'COMPLETED',
      messages: [
        {
          id: 'retry-progress',
          type: 'STATE',
          content: 'LLM call retrying after LLM_REQUEST_TIMEOUT (1/2)',
          timestamp: 1_000,
          metadata: {
            subtype: 'task_progress',
            progressType: 'llm_retrying',
            reason: 'LLM_REQUEST_TIMEOUT',
            attempt: 1,
            maxAttempts: 2,
          },
        },
        {
          id: 'final-result',
          type: 'RESULT',
          content: '处理完成。',
          timestamp: 2_000,
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
          mode: 'details',
          fetch: async () => okResponse(task),
        })
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    if (!chat) throw new Error('failed to create chat composable')

    await chat.send('处理一下')
    await nextTick()

    expect(chat.progressText.value).toBe('模型调用重试中 1/2')
    const progressMessage = chat.messages.value.find((message) =>
      message.processKind === 'state' && message.content.includes('LLM_REQUEST_TIMEOUT')
    )
    expect(progressMessage?.process).toBe(true)
  })
})
