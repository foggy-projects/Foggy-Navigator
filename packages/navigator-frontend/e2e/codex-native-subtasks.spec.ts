import { expect, test, type Page, type Route } from '@playwright/test'

const now = '2026-07-10T04:00:00Z'

const worker = {
  workerId: 'worker-codex',
  name: 'Codex Worker',
  baseUrl: 'http://codex-worker.local',
  workerBackend: 'OPENAI_CODEX',
  authMode: 'SUBSCRIPTION',
  status: 'ONLINE',
  hostname: 'codex-host',
  createdAt: now,
}

const directory = {
  directoryId: 'dir-codex',
  workerId: worker.workerId,
  projectName: 'Navigator',
  path: '/repo/navigator',
  directoryType: 'STANDARD',
  gitStatus: 'clean',
  createdAt: now,
  updatedAt: now,
}

const task = {
  taskId: 'task-codex-ultra',
  sessionId: 'session-codex-ultra',
  workerId: worker.workerId,
  directoryId: directory.directoryId,
  prompt: 'Inspect the Ultra pipeline',
  cwd: directory.path,
  status: 'RUNNING',
  providerType: 'codex-worker',
  model: 'gpt-5.6-sol:ultra',
  codexThreadId: 'root-thread',
  source: 'PLATFORM',
  fileCheckpointingEnabled: false,
  costUsd: 0,
  createdAt: now,
  updatedAt: now,
}

const snapshot = {
  taskId: task.taskId,
  subtasks: [
    {
      subtaskId: 'child-thread-secret-1',
      depth: 1,
      label: 'Kepler',
      role: 'explorer',
      status: 'RUNNING',
      activity: 'Inspecting Worker protocol boundaries',
      startedAt: '2026-07-10T04:00:05Z',
      updatedAt: '2026-07-10T04:00:20Z',
      lastEventSeq: 41,
    },
    {
      subtaskId: 'child-thread-secret-2',
      parentSubtaskId: 'child-thread-secret-1',
      depth: 2,
      label: 'Ptolemy',
      role: 'reviewer',
      status: 'COMPLETED',
      activity: 'Verified the fallback boundary',
      startedAt: '2026-07-10T04:00:07Z',
      completedAt: '2026-07-10T04:00:16Z',
      durationMs: 9_000,
      lastEventSeq: 42,
    },
    {
      subtaskId: 'child-thread-secret-3',
      depth: 1,
      label: 'Gauss',
      role: 'tester',
      status: 'FAILED',
      activity: 'Browser probe failed',
      lastEventSeq: 43,
    },
    {
      subtaskId: 'child-thread-secret-4',
      depth: 1,
      label: 'Nash',
      role: 'planner',
      status: 'INTERRUPTED',
      activity: 'Stopped with the parent turn',
      lastEventSeq: 44,
    },
  ],
}

function rx(data: unknown) {
  return { code: 200, message: 'ok', data }
}

async function fulfill(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(rx(data)),
  })
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
        status: 204,
      })
      return
    }
    if (path === '/sse/subscribe' || path === '/sse/unsubscribe') {
      await fulfill(route, null)
      return
    }
    if (path === '/claude-workers') {
      await fulfill(route, [worker])
      return
    }
    if (path === '/langgraph-workers' || path === '/coding-agents'
        || path === '/config/platform/llm' || path === '/config/platform/agent-model') {
      await fulfill(route, [])
      return
    }
    if (path === '/tasks/page') {
      const state = url.searchParams.get('state')
      const content = !state || state.split(',').includes('PROCESSING') ? [task] : []
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
    if (path === `/tasks/${task.taskId}`) {
      await fulfill(route, task)
      return
    }
    if (path === `/tasks/${task.taskId}/native-subtasks`) {
      await fulfill(route, snapshot)
      return
    }
    if (path === `/tasks/directory/${directory.directoryId}`) {
      await fulfill(route, [task])
      return
    }
    if (path === `/tasks/directory/${directory.directoryId}/page`) {
      await fulfill(route, { content: [task], totalSessions: 1, page: 0, size: 20 })
      return
    }
    if (path === '/sessions/configs') {
      await fulfill(route, [{
        sessionId: task.sessionId,
        pinned: false,
        authBound: false,
        interactionState: 'PROCESSING',
        tags: [],
      }])
      return
    }
    if (path === `/sessions/${task.sessionId}/messages/latest`) {
      await fulfill(route, { messages: [], total: 0, limit: 50, offset: 0, hasMore: false })
      return
    }
    if (path === `/working-directories/worker/${worker.workerId}`) {
      await fulfill(route, [directory])
      return
    }
    if (path === `/working-directories/${directory.directoryId}/agent-teams-configs`
        || path === `/working-directories/${directory.directoryId}/skills`) {
      await fulfill(route, [])
      return
    }
    if (path === `/codex-workers/${worker.workerId}/processes`) {
      await fulfill(route, [])
      return
    }
    if (path === `/codex-tasks/${task.taskId}/file-hints`) {
      await fulfill(route, { taskId: task.taskId, files: [], total: 0, truncated: false })
      return
    }
    if (path.match(/^\/session-relations\/forward\/incoming\//)) {
      await fulfill(route, null)
      return
    }
    if (path.match(/^\/tasks\/workers\/[^/]+\/sessions\/[^/]+\/message-count$/)) {
      await fulfill(route, { user_count: 0, assistant_count: 0, total: 0 })
      return
    }

    await fulfill(route, null)
  })
}

async function openCodexTask(page: Page) {
  await mockApi(page)
  await page.goto('/')
  await page.getByText(worker.name, { exact: true }).click()
  const conversation = page.locator('.conv-item', { hasText: task.prompt }).first()
  await expect(conversation).toBeVisible()
  const subscribeRequest = page.waitForRequest((request) =>
    request.url().includes('/api/v1/sse/subscribe'),
  )
  const snapshotResponse = page.waitForResponse((response) =>
    response.url().includes(`/api/v1/tasks/${task.taskId}/native-subtasks`),
  )
  await conversation.click()
  await subscribeRequest
  await snapshotResponse
  await expect(page.locator('.task-pane').first()).toBeVisible()
}

test('renders recoverable Ultra subtask progress without leaking ids or overflowing a narrow pane', async ({ page }) => {
  await openCodexTask(page)

  const pane = page.locator('.task-pane').first()
  const bar = pane.locator('.native-subtasks')
  await expect(bar).toBeVisible()
  await expect(bar).toHaveAttribute('data-event-seq', '44')
  await expect(bar.locator('.native-subtask-strip')).toContainText('Codex 子任务')
  await expect(bar.locator('.native-subtask-strip')).toContainText('1 进行中')
  await expect(bar.locator('.native-subtask-strip')).toContainText('1 失败')
  await expect(bar.locator('.native-subtask-strip')).toContainText('1 中断')

  await bar.locator('.native-subtask-strip').click()
  await expect(bar.locator('.native-subtask-row')).toHaveCount(4)
  await expect(bar.getByText('Kepler', { exact: true })).toBeVisible()
  await expect(bar.getByText('Verified the fallback boundary', { exact: true })).toBeVisible()
  await expect(bar.getByText('已完成', { exact: true })).toBeVisible()
  await expect(bar).not.toContainText('child-thread-secret')

  const rowPadding = await bar.locator('.native-subtask-row').evaluateAll((rows) =>
    rows.slice(0, 2).map((row) => Number.parseFloat(getComputedStyle(row).paddingLeft)),
  )
  expect(rowPadding[1]).toBeGreaterThan(rowPadding[0]!)

  await pane.evaluate((element) => {
    const paneElement = element as HTMLElement
    paneElement.style.width = '320px'
    paneElement.style.minWidth = '320px'
    paneElement.style.maxWidth = '320px'
    paneElement.style.flex = '0 0 320px'
  })

  const metrics = await bar.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    paneClientWidth: element.parentElement?.clientWidth ?? 0,
    rowsFit: [...element.querySelectorAll<HTMLElement>('.native-subtask-row')]
      .every((row) => row.scrollWidth <= row.clientWidth),
  }))
  expect(metrics.clientWidth).toBe(metrics.paneClientWidth)
  expect(metrics.clientWidth).toBeGreaterThanOrEqual(318)
  expect(metrics.clientWidth).toBeLessThanOrEqual(320)
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
  expect(metrics.rowsFit).toBe(true)
  await expect(bar.locator('.subtask-role').first()).toBeHidden()
  await expect(bar.locator('.subtask-activity').first()).toBeHidden()
})
