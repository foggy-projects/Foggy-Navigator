import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import NavigatorMobileChat from './NavigatorMobileChat.vue'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  NavigatorChatConfig,
} from '../types'

let app: App<Element> | undefined

afterEach(() => {
  app?.unmount()
  app = undefined
  vi.useRealTimers()
})

function okResponse(data: unknown): Response {
  return new Response(JSON.stringify({ code: 0, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

function mountMobile(props: Record<string, unknown>) {
  const root = document.createElement('div')
  app = createApp(defineComponent({
    setup() {
      return () => h(NavigatorMobileChat, props)
    },
  }))
  app.mount(root)
  return root
}

describe('NavigatorMobileChat', () => {
  it('loads session history through the configured BFF base URL', async () => {
    const requests: Array<{ url: string; method?: string }> = []
    const config: NavigatorChatConfig = {
      baseUrl: 'https://tms-bff.test/navigator',
      agentId: 'tms-agent',
      fetch: async (url, init) => {
        requests.push({ url: String(url), method: init.method })
        if (String(url).includes('/sessions?')) {
          return okResponse({
            sessions: [
              {
                contextId: 'ctx-mobile-1',
                title: '移动端签收确认',
                status: 'COMPLETED',
                turnCount: 2,
                lastMessagePreview: '已生成签收工作台入口',
                updatedAt: '2026-05-20T10:20:00',
              },
            ],
            hasMore: false,
          })
        }
        if (String(url).includes('/sessions/ctx-mobile-1/messages')) {
          return okResponse({
            contextId: 'ctx-mobile-1',
            messages: [
              {
                messageId: 'u1',
                role: 'USER',
                type: 'USER',
                content: '继续这个签收任务',
                createdAt: '2026-05-20T10:20:01',
              },
              {
                messageId: 'a1',
                role: 'ASSISTANT',
                type: 'RESULT',
                content: '已恢复移动端签收上下文。',
                createdAt: '2026-05-20T10:20:02',
              },
            ],
            hasMore: false,
          })
        }
        return okResponse({})
      },
    }

    const root = mountMobile({
      config,
      showHistory: true,
      title: 'TMS 移动助手',
    })
    await flushPromises()

    root.querySelector<HTMLButtonElement>('[aria-label="打开历史会话"]')?.click()
    await nextTick()

    expect(root.querySelector('.nmc-history-sheet')).not.toBeNull()
    expect(root.textContent).toContain('移动端签收确认')

    root.querySelector<HTMLButtonElement>('.nmc-history-content')?.click()
    await flushPromises()

    expect(root.textContent).toContain('继续这个签收任务')
    expect(root.textContent).toContain('已恢复移动端签收上下文')
    expect(requests.every((request) => request.url.startsWith('https://tms-bff.test/navigator/api/v1/open/'))).toBe(true)
    expect(root.textContent).not.toContain('task_scoped_token')
    expect(root.textContent).not.toContain('adapterConfigJson')
  })

  it('renders tool progress, business actions, and lazy report markdown after sending', async () => {
    const loadMarkdown = vi.fn(async () => '# report\n\nfrom upstream BFF')
    const config: NavigatorChatConfig = {
      baseUrl: 'https://tms-bff.test/navigator',
      agentId: 'tms-agent',
      pollInterval: 10,
      showToolCalls: true,
      showToolResults: true,
      executionReportMarkdownLoader: loadMarkdown,
      fetch: async (url, init) => {
        if (init.method === 'POST' && String(url).endsWith('/ask')) {
          return okResponse({
            taskId: 'task-mobile-1',
            agentId: 'tms-agent',
            status: 'COMPLETED',
            contextId: 'ctx-mobile-1',
            terminal: true,
            terminalStatus: 'COMPLETED',
            messages: [
              {
                id: 'call-1',
                type: 'TOOL_CALL',
                content: '调用业务能力',
                timestamp: Date.now(),
                toolCallId: 'tool-1',
                toolName: 'invoke_business_function',
                functionId: 'tms.fulfillment.openSignWorkbench',
                args: { waybillNo: 'TMS-001' },
              },
              {
                id: 'result-1',
                type: 'TOOL_RESULT',
                content: '业务能力调用完成',
                timestamp: Date.now() + 1,
                toolCallId: 'tool-1',
                toolName: 'invoke_business_function',
                functionId: 'tms.fulfillment.openSignWorkbench',
                result: { status: 'SUCCESS' },
                executionReportRef: 'report-tool-1',
                executionReportDigest: {
                  status: 'COMPLETED',
                  summary: 'Tool completed',
                  reportRef: 'report-tool-1',
                },
              },
              {
                id: 'final-1',
                type: 'RESULT',
                content: '已准备好签收工作台。',
                timestamp: Date.now() + 2,
                terminal: true,
                terminalStatus: 'COMPLETED',
                metadata: {
                  structured_output: {
                    type: 'OPEN_TMS_PAGE',
                    label: '打开签收工作台',
                    pageLabel: '签收工作台',
                    payload: { waybillNo: 'TMS-001' },
                  },
                },
                executionReportRef: 'report-final-1',
                executionReportDigest: {
                  status: 'COMPLETED',
                  summary: 'Task completed',
                  reportRef: 'report-final-1',
                },
              },
            ],
          })
        }
        return okResponse({})
      },
    }

    const root = mountMobile({
      config,
      showToolCalls: true,
      showToolResults: true,
      title: 'TMS 移动助手',
    })

    const textarea = root.querySelector<HTMLTextAreaElement>('.nmc-textarea')
    if (!textarea) throw new Error('textarea missing')
    textarea.value = '打开签收工作台'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector<HTMLButtonElement>('.nmc-send-button')?.click()
    await flushPromises()

    expect(root.textContent).toContain('已准备好签收工作台')
    expect(root.textContent).toContain('业务能力调用完成')
    expect(root.textContent).toContain('Tool completed')
    expect(root.textContent).toContain('打开签收工作台')

    root.querySelector<HTMLButtonElement>('.nmc-report-card')?.click()
    await nextTick()
    root.querySelector<HTMLButtonElement>('.nmc-report-markdown .nmc-secondary-button')?.click()
    await flushPromises()

    expect(loadMarkdown).toHaveBeenCalledWith('report-tool-1')
    expect(root.textContent).toContain('from upstream BFF')
  })

  it('renders sanitized errors from OpenAPI ERROR messages', async () => {
    const config: NavigatorChatConfig = {
      baseUrl: 'https://tms-bff.test/navigator',
      agentId: 'tms-agent',
      fetch: async () => okResponse({
        taskId: 'task-mobile-error',
        agentId: 'tms-agent',
        status: 'FAILED',
        contextId: 'ctx-mobile-err',
        terminal: true,
        terminalStatus: 'FAILED',
        messages: [
          {
            id: 'err-1',
            type: 'ERROR',
            content: '操作执行失败，请稍后重试或联系管理员。',
            terminal: true,
            terminalStatus: 'FAILED',
            timestamp: Date.now(),
          },
        ],
      }),
    }
    const root = mountMobile({ config })

    const textarea = root.querySelector<HTMLTextAreaElement>('.nmc-textarea')
    if (!textarea) throw new Error('textarea missing')
    textarea.value = '触发失败'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector<HTMLButtonElement>('.nmc-send-button')?.click()
    await flushPromises()

    expect(root.querySelector('.nmc-error-card')).not.toBeNull()
    expect(root.textContent).toContain('操作执行失败')
    expect(root.textContent).not.toContain('stack')
    expect(root.textContent).not.toContain('runtime credential')
  })

  it('emits approve and reject decisions from the mobile suspension sheet only', async () => {
    const decisions: BusinessSuspensionDecisionPayload[] = []
    const visible = ref(true)
    const suspension: BusinessSuspensionDialogModel = {
      suspendId: 'sus-mobile-1',
      suspensionType: 'APPROVAL_REQUIRED',
      status: 'pending',
      title: '签收确认',
      summary: '确认打开签收工作台',
      functionId: 'tms.fulfillment.sign',
      riskLevel: 'P2',
      displayFields: [{ label: '运单号', value: 'TMS-001' }],
    }
    const config: NavigatorChatConfig = {
      baseUrl: 'https://tms-bff.test/navigator',
      agentId: 'tms-agent',
      fetch: async () => okResponse({}),
    }
    const root = document.createElement('div')
    app = createApp(defineComponent({
      setup() {
        return () => h(NavigatorMobileChat, {
          config,
          suspension,
          suspensionDialogVisible: visible.value,
          'onUpdate:suspensionDialogVisible': (value: boolean) => {
            visible.value = value
          },
          onSuspensionDecision: (payload: BusinessSuspensionDecisionPayload) => {
            decisions.push(payload)
          },
        })
      },
    }))
    app.mount(root)
    await nextTick()

    expect(root.textContent).toContain('签收确认')
    expect(root.textContent).toContain('TMS-001')
    expect(root.textContent).not.toContain('task_scoped_token')
    expect(root.textContent).not.toContain('manifestJson')

    root.querySelector<HTMLButtonElement>('.nmc-secondary-button--danger')?.click()
    await nextTick()
    root.querySelector<HTMLButtonElement>('.nmc-primary-button')?.click()
    await nextTick()

    expect(decisions).toEqual([
      {
        suspendId: 'sus-mobile-1',
        suspensionType: 'APPROVAL_REQUIRED',
        decision: 'rejected',
      },
      {
        suspendId: 'sus-mobile-1',
        suspensionType: 'APPROVAL_REQUIRED',
        decision: 'approved',
      },
    ])
  })
})
