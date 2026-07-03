import { expect, test, type Page, type Route } from '@playwright/test'

const now = '2026-06-19T08:00:00Z'

const worker = {
  workerId: 'worker-history',
  name: 'History Worker',
  baseUrl: 'http://history-worker.local',
  workerBackend: 'CLAUDE_CODE',
  authMode: 'SUBSCRIPTION',
  status: 'ONLINE',
  hostname: 'history-host',
  createdAt: now,
}

const directory = {
  directoryId: 'dir-history',
  workerId: worker.workerId,
  projectName: 'History Project',
  path: '/repo/history',
  directoryType: 'STANDARD',
  gitStatus: 'clean',
  createdAt: now,
  updatedAt: now,
}

const tasks = [
  {
    taskId: 'task-root',
    sessionId: 'session-root',
    workerId: worker.workerId,
    directoryId: directory.directoryId,
    prompt: 'Main task domain',
    sessionFirstPrompt: 'Main task domain',
    cwd: directory.path,
    status: 'COMPLETED',
    providerType: 'claude-worker',
    model: 'sonnet',
    claudeSessionId: 'claude-root',
    source: 'PLATFORM',
    fileCheckpointingEnabled: true,
    costUsd: 0.11,
    createdAt: '2026-06-19T08:00:00Z',
    updatedAt: '2026-06-19T08:00:00Z',
  },
  {
    taskId: 'task-branch-a',
    sessionId: 'session-branch-a',
    parentSessionId: 'session-root',
    workerId: worker.workerId,
    directoryId: directory.directoryId,
    prompt: 'Branch A investigation',
    sessionFirstPrompt: 'Branch A investigation',
    cwd: directory.path,
    status: 'COMPLETED',
    providerType: 'claude-worker',
    model: 'sonnet',
    claudeSessionId: 'claude-branch-a',
    source: 'PLATFORM',
    fileCheckpointingEnabled: true,
    costUsd: 0.22,
    createdAt: '2026-06-19T08:10:00Z',
    updatedAt: '2026-06-19T08:10:00Z',
  },
  {
    taskId: 'task-branch-b',
    sessionId: 'session-branch-b',
    parentSessionId: 'session-root',
    workerId: worker.workerId,
    directoryId: directory.directoryId,
    prompt: 'Branch B from branch A',
    sessionFirstPrompt: 'Branch B from branch A',
    cwd: directory.path,
    status: 'COMPLETED',
    providerType: 'claude-worker',
    model: 'sonnet',
    claudeSessionId: 'claude-branch-b',
    source: 'PLATFORM',
    fileCheckpointingEnabled: true,
    costUsd: 0.33,
    createdAt: '2026-06-19T08:20:00Z',
    updatedAt: '2026-06-19T08:20:00Z',
  },
]

const incomingRelations: Record<string, unknown> = {
  'session-branch-a': {
    id: 1,
    relationType: 'FORWARD',
    targetMode: 'NEW_SESSION',
    sourceSessionId: 'session-root',
    sourceMessageId: 'msg-root',
    targetSessionId: 'session-branch-a',
    createdAt: '2026-06-19T08:10:00Z',
  },
  'session-branch-b': {
    id: 2,
    relationType: 'FORWARD',
    targetMode: 'NEW_SESSION',
    sourceSessionId: 'session-branch-a',
    sourceMessageId: 'msg-branch-a',
    targetSessionId: 'session-branch-b',
    createdAt: '2026-06-19T08:20:00Z',
  },
}

type MockApiOptions = {
  keepArchivedTasksInList?: boolean
  resumeWithoutParent?: boolean
  delayReloadAfterResume?: boolean
}

function rx(data: unknown) {
  return {
    code: 200,
    message: 'ok',
    data,
  }
}

async function fulfill(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(rx(data)),
  })
}

async function mockApi(page: Page, options: MockApiOptions = {}) {
  const archivedSessionIds = new Set<string>()
  const heldSessionIds = new Set<string>()
  const deletedSessionIds = new Set<string>()
  let resumeCalled = false
  const resumedBranchA = {
    ...tasks[1]!,
    taskId: 'task-branch-a-resumed',
    prompt: 'Continue Branch A',
    status: 'RUNNING',
    parentSessionId: options.resumeWithoutParent ? undefined : 'session-root',
    costUsd: 0.44,
    createdAt: '2026-06-19T08:30:00Z',
    updatedAt: '2026-06-19T08:30:00Z',
  }
  const markSessionWithChildren = (target: Set<string>, sessionId: string) => {
    target.add(sessionId)
    for (const task of tasks.filter((item) => item.parentSessionId === sessionId)) {
      markSessionWithChildren(target, task.sessionId)
    }
  }
  const listedTasks = () => {
    const sourceTasks = resumeCalled ? [resumedBranchA, ...tasks] : tasks
    const withoutDeleted = sourceTasks.filter((task) => !deletedSessionIds.has(task.sessionId))
    if (options.keepArchivedTasksInList) return withoutDeleted
    return withoutDeleted.filter((task) =>
      !archivedSessionIds.has(task.sessionId) && !heldSessionIds.has(task.sessionId),
    )
  }

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
      await fulfill(route, [worker])
      return
    }

    if (path === '/langgraph-workers' || path === '/coding-agents' || path === '/config/platform/llm') {
      await fulfill(route, [])
      return
    }

    if (path === '/config/platform/agent-model') {
      await fulfill(route, [])
      return
    }

    if (path.match(/^\/claude-workers\/[^/]+\/processes$/)) {
      await fulfill(route, [])
      return
    }

    if (path === '/tasks/page') {
      if (resumeCalled && options.delayReloadAfterResume) {
        await new Promise((resolve) => setTimeout(resolve, 1200))
      }
      await fulfill(route, {
        content: listedTasks(),
        totalSessions: listedTasks().length,
        page: Number(url.searchParams.get('page') || '0'),
        size: Number(url.searchParams.get('size') || '20'),
      })
      return
    }

    if (path === '/tasks/resume') {
      resumeCalled = true
      await fulfill(route, resumedBranchA)
      return
    }

    if (path === `/tasks/directory/${directory.directoryId}/page`) {
      await fulfill(route, {
        content: listedTasks(),
        totalSessions: listedTasks().length,
        page: Number(url.searchParams.get('page') || '0'),
        size: Number(url.searchParams.get('size') || '20'),
      })
      return
    }

    if (path === `/tasks/directory/${directory.directoryId}`) {
      await fulfill(route, listedTasks())
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
        sessionIds
          .filter((sessionId) => !deletedSessionIds.has(sessionId))
          .map((sessionId) => ({
            sessionId,
            pinned: false,
            authBound: false,
            interactionState: archivedSessionIds.has(sessionId)
              ? 'ARCHIVED'
              : heldSessionIds.has(sessionId) ? 'ON_HOLD' : 'PROCESSING',
            tags: [],
          })),
      )
      return
    }

    const archiveMatch = path.match(/^\/sessions\/([^/]+)\/config\/archive$/)
    if (archiveMatch) {
      const sessionId = archiveMatch[1]!
      markSessionWithChildren(archivedSessionIds, sessionId)
      await fulfill(route, {
        sessionId,
        pinned: false,
        authBound: false,
        interactionState: 'ARCHIVED',
        tags: [],
      })
      return
    }

    const holdMatch = path.match(/^\/sessions\/([^/]+)\/config\/hold$/)
    if (holdMatch) {
      const sessionId = holdMatch[1]!
      markSessionWithChildren(heldSessionIds, sessionId)
      await fulfill(route, {
        sessionId,
        pinned: false,
        authBound: false,
        interactionState: 'ON_HOLD',
        tags: [],
      })
      return
    }

    const deleteMatch = path.match(/^\/sessions\/([^/]+)$/)
    if (request.method() === 'DELETE' && deleteMatch) {
      markSessionWithChildren(deletedSessionIds, deleteMatch[1]!)
      await fulfill(route, null)
      return
    }

    const incomingForwardMatch = path.match(/^\/session-relations\/forward\/incoming\/([^/]+)$/)
    if (incomingForwardMatch) {
      await fulfill(route, incomingRelations[incomingForwardMatch[1]!] || null)
      return
    }

    const latestMessagesMatch = path.match(/^\/sessions\/([^/]+)\/messages\/latest$/)
    if (latestMessagesMatch) {
      await fulfill(route, {
        messages: [],
        total: 0,
        limit: Number(url.searchParams.get('limit') || '50'),
        offset: Number(url.searchParams.get('offset') || '0'),
        hasMore: false,
      })
      return
    }

    if (path.match(/^\/tasks\/workers\/[^/]+\/sessions\/[^/]+\/message-count$/)) {
      await fulfill(route, {
        user_count: 0,
        assistant_count: 0,
        total: 0,
      })
      return
    }

    if (path.match(/^\/tasks\/workers\/[^/]+\/sessions\/[^/]+\/messages$/)) {
      await fulfill(route, [])
      return
    }

    const workerDirectoryMatch = path.match(/^\/working-directories\/worker\/([^/]+)$/)
    if (workerDirectoryMatch) {
      await fulfill(route, [])
      return
    }

    if (path.match(/^\/working-directories\/[^/]+\/agent-teams-configs$/)) {
      await fulfill(route, [])
      return
    }

    await fulfill(route, null)
  })
}

test.describe('history session branch grouping', () => {
  test('shows root conversation with flat branches and branch source details', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(page.locator('.conv-list > .conv-item')).toHaveCount(1)

    await expect(root.locator('.conv-rel-tag')).toContainText('分支 2')
    await expect(root.locator('.branch-session-item')).toHaveCount(2)
    await expect(root.locator('.branch-session-item', { hasText: 'Branch A investigation' })).toBeVisible()
    await expect(root.locator('.branch-session-item', { hasText: 'Branch B from branch A' })).toBeVisible()

    const branchB = root.locator('.branch-session-item', { hasText: 'Branch B from branch A' })
    await branchB.locator('.branch-session-more-trigger').click()
    const menu = page.locator('.el-dropdown__popper:visible').last()
    await expect(menu).toBeVisible()
    await menu.getByText('详情', { exact: true }).click()

    const dialog = page.getByRole('dialog', { name: '会话详情' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('分支会话', { exact: true })).toBeVisible()
    await expect(dialog.getByText('Main task domain (session-root)')).toHaveCount(2)
    await expect(dialog.getByText('Branch A investigation (session-branch-a)')).toBeVisible()
    await expect(dialog.getByText('msg-branch-a')).toBeVisible()
  })

  test('hides archived branch even when stale task cache still contains it', async ({ page }) => {
    await mockApi(page, { keepArchivedTasksInList: true })
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(root.locator('.branch-session-item')).toHaveCount(2)

    const branchA = root.locator('.branch-session-item', { hasText: 'Branch A investigation' })
    await branchA.locator('.branch-session-more-trigger').click()
    const menu = page.locator('.el-dropdown__popper:visible').last()
    await expect(menu).toBeVisible()
    await menu.getByText('归档', { exact: true }).click()
    await page.getByRole('button', { name: '确认归档' }).click()

    await expect(root.locator('.branch-session-item', { hasText: 'Branch A investigation' })).toHaveCount(0)
    await expect(root.locator('.branch-session-item', { hasText: 'Branch B from branch A' })).toBeVisible()
    await expect(root.locator('.conv-rel-tag')).toContainText('分支 1')
  })

  test('archives root conversation with cascade confirmation for branches', async ({ page }) => {
    await mockApi(page, { keepArchivedTasksInList: true })
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(root.locator('.branch-session-item')).toHaveCount(2)

    await root.locator('.conv-more-trigger').click()
    const menu = page.locator('.el-dropdown__popper:visible').last()
    await expect(menu).toBeVisible()
    await menu.getByText('归档', { exact: true }).click()

    const confirmDialog = page.getByRole('dialog', { name: '归档会话' })
    await expect(confirmDialog).toBeVisible()
    await expect(confirmDialog.getByText('该操作会同时归档所有子会话')).toBeVisible()
    await confirmDialog.getByRole('button', { name: '确认归档' }).click()

    await expect(page.locator('.conv-item', { hasText: 'Main task domain' })).toHaveCount(0)
  })

  test('holds root conversation with cascade confirmation for branches', async ({ page }) => {
    await mockApi(page, { keepArchivedTasksInList: true })
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(root.locator('.branch-session-item')).toHaveCount(2)

    await root.locator('.conv-more-trigger').click()
    const menu = page.locator('.el-dropdown__popper:visible').last()
    await expect(menu).toBeVisible()
    await menu.getByText('搁置', { exact: true }).click()

    const confirmDialog = page.getByRole('dialog', { name: '搁置会话' })
    await expect(confirmDialog).toBeVisible()
    await expect(confirmDialog.getByText('该操作会同时搁置所有子会话')).toBeVisible()
    await confirmDialog.getByRole('button', { name: '确认搁置' }).click()

    await expect(page.locator('.conv-item', { hasText: 'Main task domain' })).toHaveCount(0)
  })

  test('deletes root conversation with cascade confirmation for branches', async ({ page }) => {
    await mockApi(page, { keepArchivedTasksInList: true })
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(root.locator('.branch-session-item')).toHaveCount(2)

    await root.locator('.conv-more-trigger').click()
    const menu = page.locator('.el-dropdown__popper:visible').last()
    await expect(menu).toBeVisible()
    await menu.getByText('删除', { exact: true }).click()

    const confirmDialog = page.getByRole('dialog', { name: '提示' })
    await expect(confirmDialog).toBeVisible()
    await expect(confirmDialog.getByText('该操作会同时删除所有子会话')).toBeVisible()
    await confirmDialog.getByRole('button', { name: '确认' }).click()

    await expect(page.locator('.conv-item', { hasText: 'Main task domain' })).toHaveCount(0)
  })

  test('keeps branch nested when resumed task response lacks parent', async ({ page }) => {
    await mockApi(page, {
      resumeWithoutParent: true,
    })
    await page.goto('/')
    await page.getByText('History Worker', { exact: true }).click()

    const root = page.locator('.conv-item', { hasText: 'Main task domain' }).first()
    await expect(root).toBeVisible()
    await expect(page.locator('.conv-list > .conv-item')).toHaveCount(1)

    await root.locator('.branch-session-item', { hasText: 'Branch A investigation' }).click()
    await page.locator('.task-pane').first().waitFor({ state: 'visible' })
    await page.locator('.task-pane textarea').first().fill('Continue Branch A')
    await page.locator('.task-pane .send-btn-inside').first().click()

    await expect(page.locator('.conv-item', { hasText: 'Main task domain' })).toHaveCount(1)
    await expect(root.locator('.branch-session-item', { hasText: 'Branch A investigation' })).toBeVisible()
    await expect(root.locator('.branch-session-item')).toHaveCount(2)
  })
})
