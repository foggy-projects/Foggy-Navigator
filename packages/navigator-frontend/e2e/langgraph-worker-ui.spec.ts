import { expect, test, type Page, type Route } from '@playwright/test'

const now = '2026-05-01T00:00:00Z'

const workers = {
  claude: {
    workerId: 'worker-claude',
    name: 'Claude Worker',
    baseUrl: 'http://claude-worker.local',
    workerBackend: 'CLAUDE_CODE',
    authMode: 'SUBSCRIPTION',
    status: 'ONLINE',
    hostname: 'claude-host',
    createdAt: now,
  },
  langgraph: {
    workerId: 'worker-langgraph',
    name: 'LangGraph Biz',
    baseUrl: 'http://127.0.0.1:3061',
    workerBackend: 'LANGGRAPH_BIZ',
    authMode: 'API_KEY',
    status: 'ONLINE',
    hostname: 'langgraph-biz-wsl',
    createdAt: now,
  },
}

const tasks = [
  {
    taskId: 'task-lg-failed',
    sessionId: 'session-lg-failed',
    workerId: workers.langgraph.workerId,
    directoryId: 'dir-lg',
    prompt: 'submit close order request',
    cwd: '/biz/orders',
    status: 'FAILED',
    providerType: 'langgraph-biz-worker',
    model: 'biz-default',
    claudeSessionId: 'worker-session-lg-1',
    source: 'PLATFORM',
    fileCheckpointingEnabled: true,
    costUsd: 0,
    createdAt: '2026-05-01T00:01:00Z',
    updatedAt: '2026-05-01T00:01:00Z',
  },
  {
    taskId: 'task-claude-failed',
    sessionId: 'session-claude-failed',
    workerId: workers.claude.workerId,
    directoryId: 'dir-claude',
    prompt: 'Claude failed task',
    cwd: '/repo',
    status: 'FAILED',
    providerType: 'claude-worker',
    model: 'sonnet',
    claudeSessionId: 'claude-session-1',
    checkpoints: JSON.stringify([{ id: 'cp-1', turnIndex: 1, timestamp: now }]),
    source: 'PLATFORM',
    fileCheckpointingEnabled: true,
    costUsd: 0,
    createdAt: '2026-05-01T00:02:00Z',
    updatedAt: '2026-05-01T00:02:00Z',
  },
]

const directories = {
  'worker-langgraph': [
    {
      directoryId: 'dir-lg',
      workerId: workers.langgraph.workerId,
      projectName: 'Order Biz',
      path: '/biz/orders',
      directoryType: 'STANDARD',
      gitStatus: 'clean',
      createdAt: now,
      updatedAt: now,
    },
  ],
  'worker-claude': [
    {
      directoryId: 'dir-claude',
      workerId: workers.claude.workerId,
      projectName: 'Navigator',
      path: '/repo',
      directoryType: 'STANDARD',
      gitStatus: 'clean',
      createdAt: now,
      updatedAt: now,
    },
  ],
}

const sessionMessages: Record<string, unknown[]> = {
  'session-lg-failed': [
    {
      id: 'msg-lg-report-result',
      sessionId: 'session-lg-failed',
      role: 'ASSISTANT',
      content: 'final result already streamed',
      metadata: {
        type: 'TEXT_COMPLETE',
        content: 'final result already streamed',
        isResult: true,
        execution_report_ref: 'frame-report://task-lg-failed/root-frame',
        execution_report_digest: {
          status: 'COMPLETED',
          summary: 'Frame execution completed for audit review',
          skill_id: 'langgraph-root-agent',
          frame_kind: 'ROOT',
          generated_at: '2026-05-01T00:01:30Z',
        },
      },
      createdAt: '2026-05-01T00:01:30Z',
    },
  ],
}

function rx(data: unknown) {
  return {
    code: 200,
    message: 'ok',
    data,
  }
}

async function mockApi(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('navigator_token', 'e2e-token')
    localStorage.setItem(
      'navigator_user',
      JSON.stringify({ userId: 'u-e2e', username: 'root', roles: ['ADMIN'] }),
    )
  })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')

    if (path === '/sse/unified') {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'data: {"type":"connected"}\n\n',
      })
      return
    }

    if (path === '/sse/subscribe' || path === '/sse/unsubscribe') {
      await fulfill(route, null)
      return
    }

    if (path === '/claude-workers') {
      await fulfill(route, [workers.claude])
      return
    }

    if (path === '/langgraph-workers') {
      await fulfill(route, [workers.langgraph])
      return
    }

    if (path === '/tasks/page') {
      const state = url.searchParams.get('state')
      const content = !state || state.split(',').includes('AWAITING_REPLY') ? tasks : []
      await fulfill(route, {
        content,
        totalSessions: content.length,
        page: Number(url.searchParams.get('page') || '0'),
        size: Number(url.searchParams.get('size') || '20'),
      })
      return
    }

    if (path === '/tasks') {
      await fulfill(route, [])
      return
    }

    if (path === '/sessions/configs') {
      const sessionIds = request.method() === 'POST'
        ? ((request.postDataJSON() as { sessionIds?: string[] } | null)?.sessionIds || [])
        : (url.searchParams.get('sessionIds') || '').split(',').filter(Boolean)
      await fulfill(
        route,
        sessionIds.map((sessionId) => ({
          sessionId,
          pinned: false,
          authBound: false,
          interactionState: 'PROCESSING',
          tags: [],
        })),
      )
      return
    }

    const latestMessagesMatch = path.match(/^\/sessions\/([^/]+)\/messages\/latest$/)
    if (latestMessagesMatch) {
      const sessionId = latestMessagesMatch[1]!
      const messages = sessionMessages[sessionId] || []
      await fulfill(route, {
        messages,
        total: messages.length,
        limit: Number(url.searchParams.get('limit') || '50'),
        offset: Number(url.searchParams.get('offset') || '0'),
        hasMore: false,
      })
      return
    }

    const workerDirectoryMatch = path.match(/^\/working-directories\/worker\/([^/]+)$/)
    if (workerDirectoryMatch) {
      await fulfill(route, directories[workerDirectoryMatch[1] as keyof typeof directories] || [])
      return
    }

    const agentTeamsMatch = path.match(/^\/working-directories\/[^/]+\/agent-teams-configs$/)
    if (agentTeamsMatch) {
      await fulfill(route, [])
      return
    }

    if (path === '/config/platform/llm' || path === '/coding-agents') {
      await fulfill(route, [])
      return
    }

    await fulfill(route, null)
  })
}

async function fulfill(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(rx(data)),
  })
}

async function gotoWorkers(page: Page) {
  await mockApi(page)
  await page.goto('/')
  await expect(page.getByText('LangGraph Biz')).toBeVisible()
  await expect(page.getByText('Claude Worker')).toBeVisible()
}

async function openWorkerConversationMenu(page: Page, workerName: string, prompt: string) {
  await page.getByText(workerName, { exact: true }).click()
  const conversation = page.locator('.conv-item', { hasText: prompt }).first()
  await expect(conversation).toBeVisible()
  await conversation.locator('.conv-more-trigger').click()
  const menu = page.locator('.el-dropdown__popper:visible').last()
  await expect(menu).toBeVisible()
  return menu
}

test.describe('LangGraph Biz Worker UI boundaries', () => {
  test('shows Worker Session ID in LangGraph conversation detail', async ({ page }) => {
    await gotoWorkers(page)
    const menu = await openWorkerConversationMenu(page, 'LangGraph Biz', 'submit close order request')

    await menu.getByText('详情', { exact: true }).click()

    const dialog = page.getByRole('dialog', { name: '会话详情' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('Worker Session ID')).toBeVisible()
    await expect(dialog.getByText('worker-session-lg-1')).toBeVisible()
  })

  test('hides Claude-only recovery actions for failed LangGraph tasks', async ({ page }) => {
    await gotoWorkers(page)
    const menu = await openWorkerConversationMenu(page, 'LangGraph Biz', 'submit close order request')

    await expect(menu.getByText('重新同步', { exact: true })).toHaveCount(0)
    await expect(menu.getByText('回退', { exact: true })).toHaveCount(0)
  })

  test('keeps Claude Code recovery actions for failed Claude tasks', async ({ page }) => {
    await gotoWorkers(page)
    const menu = await openWorkerConversationMenu(page, 'Claude Worker', 'Claude failed task')

    await expect(menu.getByText('重新同步', { exact: true })).toBeVisible()
    await expect(menu.getByText('回退', { exact: true })).toBeVisible()
  })

  test('shows execution report entry from task event digest', async ({ page }) => {
    await gotoWorkers(page)
    await page.getByText('LangGraph Biz', { exact: true }).click()

    const conversation = page.locator('.conv-item', { hasText: 'submit close order request' }).first()
    await expect(conversation).toBeVisible()
    await conversation.click()

    await expect(page.getByText('查看执行报告', { exact: true })).toBeVisible()
    await page.getByText('查看执行报告', { exact: true }).click()

    const dialog = page.getByRole('dialog', { name: '执行报告' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('status', { exact: true })).toBeVisible()
    await expect(dialog.getByText('COMPLETED', { exact: true })).toBeVisible()
    await expect(dialog.getByText('summary', { exact: true })).toBeVisible()
    await expect(dialog.getByText('Frame execution completed for audit review')).toBeVisible()
    await expect(dialog.getByText('skill_id', { exact: true })).toBeVisible()
    await expect(dialog.getByText('langgraph-root-agent', { exact: true })).toBeVisible()
    await expect(dialog.getByText('frame_kind', { exact: true })).toBeVisible()
    await expect(dialog.getByText('ROOT', { exact: true })).toBeVisible()
    await expect(dialog.getByText('generated_at', { exact: true })).toBeVisible()
    await expect(dialog.getByText('2026-05-01T00:01:30Z', { exact: true })).toBeVisible()
    await expect(dialog.getByText('frame-report://task-lg-failed/root-frame', { exact: true })).toBeVisible()
  })
})
