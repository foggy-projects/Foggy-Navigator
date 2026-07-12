import { expect, test, type Locator, type Page, type Route } from '@playwright/test'
import path from 'node:path'

const now = '2026-07-12T08:00:00Z'

async function captureEvidence(page: Page, name: string) {
  const evidenceDir = process.env.OPT005_EVIDENCE_DIR
  if (!evidenceDir) return
  await page.screenshot({ path: path.join(evidenceDir, name), fullPage: true })
}

async function captureLocatorEvidence(locator: Locator, name: string) {
  const evidenceDir = process.env.OPT005_EVIDENCE_DIR
  if (!evidenceDir) return
  await locator.screenshot({ path: path.join(evidenceDir, name) })
}

const worker = {
  workerId: 'worker-provider-split',
  name: 'Provider Split Worker',
  baseUrl: 'http://claude-worker.local',
  workerBackend: 'CLAUDE_CODE',
  authMode: 'SUBSCRIPTION',
  status: 'ONLINE',
  hostname: 'provider-split-host',
  codexBaseUrl: 'http://codex-sdk.local',
  codexAuthTokenConfigured: true,
  createdAt: now,
  updatedAt: now,
}

const workerWithoutCodex = {
  ...worker,
  workerId: 'worker-without-codex',
  name: 'Claude Only Worker',
  codexBaseUrl: '',
  codexAuthTokenConfigured: false,
}

const sdkModelConfig = {
  id: 'model-codex-sdk',
  tenantId: 'tenant-e2e',
  name: 'Codex SDK Max',
  category: 'CODING',
  baseUrl: '',
  modelName: 'codex-latest:max',
  isDefault: true,
  hasApiKey: false,
  scope: 'GLOBAL',
  allowedWorkerIds: [],
  availableModels: ['codex-latest:max', 'codex-terra:max'],
  workerBackend: 'OPENAI_CODEX',
  sortOrder: 1,
  createdAt: now,
  updatedAt: now,
}

const appServerModelConfig = {
  ...sdkModelConfig,
  id: 'model-codex-app-server',
  name: 'Codex App Server Ultra',
  modelName: 'codex-latest:ultra',
  isDefault: false,
  availableModels: ['codex-latest:high', 'codex-latest:ultra', 'codex-terra:ultra'],
  workerBackend: 'OPENAI_CODEX_APP_SERVER',
  sortOrder: 2,
}

const sdkTask = {
  taskId: 'task-sdk-history',
  sessionId: 'session-sdk-history',
  workerId: worker.workerId,
  prompt: 'SDK history task',
  sessionFirstPrompt: 'SDK history task',
  cwd: '/repo/provider-split',
  status: 'COMPLETED',
  providerType: 'codex-worker',
  model: 'codex-latest:max',
  modelConfigId: sdkModelConfig.id,
  codexThreadId: 'thread-sdk-history',
  source: 'PLATFORM',
  createdAt: now,
  updatedAt: now,
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

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('navigator_token', 'provider-split-e2e-token')
    localStorage.setItem(
      'navigator_user',
      JSON.stringify({
        userId: 'provider-split-e2e',
        username: 'root',
        roles: ['SUPER_ADMIN', 'ADMIN'],
      }),
    )
  })
}

async function mockSettingsApi(page: Page) {
  const connectionTests: Array<Record<string, unknown>> = []
  await authenticate(page)

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')

    if (path === '/claude-workers') {
      await fulfill(route, [worker, workerWithoutCodex])
      return
    }
    if (path === '/config/platform/llm' && request.method() === 'GET') {
      await fulfill(route, [])
      return
    }
    if (path === '/config/platform/llm/test-connection' && request.method() === 'POST') {
      connectionTests.push(request.postDataJSON() as Record<string, unknown>)
      await fulfill(route, 'APP_SERVER_READY')
      return
    }
    if (path === '/codex-app-server-endpoints' && request.method() === 'GET') {
      const endpointWorkerId = url.searchParams.get('workerId')
      await fulfill(route, endpointWorkerId === worker.workerId ? [{
        endpointId: 'endpoint-provider-split',
        workerId: worker.workerId,
        endpointUrl: 'http://codex-app-server.local',
        endpointDisplay: 'http://codex-app-server.local',
        tokenConfigured: false,
        configurationVersion: 1,
        lastSyncStatus: 'SUCCESS',
        createdAt: now,
        updatedAt: now,
      }] : [])
      return
    }
    if (path === '/codex-runtimes/availability' && request.method() === 'GET') {
      const model = url.searchParams.get('model') || ''
      const modelAvailable = !model.startsWith('codex-luna')
      await fulfill(route, {
        appServerManaged: true,
        modelAvailable,
        ultraAvailable: modelAvailable && model.endsWith(':ultra'),
        blockReason: modelAvailable ? null : 'CODEX_RUNTIME_UNAVAILABLE',
      })
      return
    }
    if (path === '/task-assistant/config') {
      await fulfill(route, null)
      return
    }

    await fulfill(route, [])
  })

  return { connectionTests }
}

async function openAddCodingModelDialog(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('tab', { name: 'LLM 配置', exact: true }).click()
  await page.getByRole('button', { name: '+ 添加', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加 AI 模型' })
  await expect(dialog).toBeVisible()

  await dialog.locator('.el-form-item', { hasText: '模型类别' }).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '编程', exact: true }).click()
  return dialog
}

function availableModelsField(dialog: Locator) {
  return dialog.getByRole('group', { name: '可用模型', exact: true })
}

async function selectBackend(dialog: Locator, label: string) {
  await dialog.locator('.worker-backend-options').getByText(label, { exact: true }).click()
}

async function selectModel(dialog: Locator, page: Page, accessibleName: RegExp) {
  await dialog.locator('.el-form-item', { hasText: '模型名称' }).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: accessibleName }).first().click()
}

async function selectConnectionTestWorker(dialog: Locator, page: Page) {
  const formItem = dialog.getByText('连接测试 Worker', { exact: true })
    .locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-form-item ")][1]')
  await formItem.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: workerWithoutCodex.name, exact: true })).toHaveCount(0)
  await page.getByRole('option', { name: worker.name, exact: true }).click()
}

async function assertWithinViewport(locator: Locator, viewportWidth: number) {
  await locator.scrollIntoViewIfNeeded()
  const box = await locator.boundingBox()
  expect(box, 'critical control should have a bounding box').not.toBeNull()
  expect(box!.x, 'critical control should not overflow the left edge').toBeGreaterThanOrEqual(-1)
  expect(
    box!.x + box!.width,
    'critical control should not overflow the right edge',
  ).toBeLessThanOrEqual(viewportWidth + 1)
}

test.describe('Codex provider split acceptance', () => {
  test('Settings separates SDK/App catalogs and tests App Server through a selected Worker', async ({ page }) => {
    const requests = await mockSettingsApi(page)
    const dialog = await openAddCodingModelDialog(page)

    await selectBackend(dialog, 'OpenAI Codex')
    const sdkModels = availableModelsField(dialog)
    await expect(sdkModels.getByRole('checkbox', { name: 'Max', exact: true })).toHaveCount(3)
    await expect(sdkModels.getByRole('checkbox', { name: 'Ultra', exact: true })).toHaveCount(0)
    await expect(dialog.getByText('Ultra 仅支持 Codex App Server 后端', { exact: true })).toHaveCount(0)

    await selectBackend(dialog, 'Codex App Server')
    const workerField = dialog.getByRole('combobox', { name: /连接测试 Worker/ })
    await expect(workerField).toBeVisible()
    await selectConnectionTestWorker(dialog, page)
    const appModels = availableModelsField(dialog)
    await expect(appModels.getByRole('checkbox', { name: 'Max', exact: true })).toHaveCount(2)
    await expect(appModels.getByRole('checkbox', { name: 'Ultra', exact: true })).toHaveCount(2)
    await expect(appModels.getByText('Codex Luna', { exact: true })).toHaveCount(0)
    await expect(dialog.getByText('Ultra 仅在此后端开放', { exact: false })).toBeVisible()

    await selectModel(dialog, page, /^Ultra$/)
    await dialog.getByRole('button', { name: '测试连接', exact: true }).click()

    await expect.poll(() => requests.connectionTests).toHaveLength(1)
    expect(requests.connectionTests[0]).toEqual(expect.objectContaining({
      baseUrl: '',
      apiKey: '',
      modelName: 'codex-latest:ultra',
      workerBackend: 'OPENAI_CODEX_APP_SERVER',
      workerId: worker.workerId,
    }))
    await expect(page.getByText('连接成功: APP_SERVER_READY', { exact: true })).toBeVisible()

    await assertWithinViewport(dialog, 1280)
    await assertWithinViewport(dialog.locator('.worker-backend-options'), 1280)
    await assertWithinViewport(workerField, 1280)
    await captureEvidence(page, 'OPT-005-model-provider-split-desktop.png')
  })

  test('Settings keeps provider catalog and connection controls inside a 320px viewport', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 720 })
    await mockSettingsApi(page)
    const dialog = await openAddCodingModelDialog(page)
    await selectBackend(dialog, 'Codex App Server')

    const backendOptions = dialog.locator('.worker-backend-options')
    const workerField = dialog.getByRole('combobox', { name: /连接测试 Worker/ })
    await selectConnectionTestWorker(dialog, page)
    const footer = dialog.locator('.el-dialog__footer')
    await expect(availableModelsField(dialog).getByRole('checkbox', { name: 'Ultra', exact: true })).toHaveCount(2)

    await assertWithinViewport(dialog, 320)
    await assertWithinViewport(backendOptions, 320)
    await assertWithinViewport(workerField, 320)
    await assertWithinViewport(footer, 320)
    await captureEvidence(page, 'OPT-005-model-provider-split-320.png')
  })

  test('Workers creates a new App Server session when continuing SDK history', async ({ page }) => {
    const createRequests: Array<Record<string, unknown>> = []
    const resumeRequests: Array<Record<string, unknown>> = []
    let created = false
    const appTask = {
      ...sdkTask,
      taskId: 'task-app-server-new',
      sessionId: 'session-app-server-new',
      prompt: 'continue through app server',
      status: 'RUNNING',
      providerType: 'codex-app-server-worker',
      model: 'codex-latest:high',
      modelConfigId: appServerModelConfig.id,
      codexThreadId: 'thread-app-server-new',
      createdAt: '2026-07-12T08:01:00Z',
      updatedAt: '2026-07-12T08:01:00Z',
    }

    await authenticate(page)
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname.replace(/^\/api\/v1/, '')

      if (path === '/sse/unified') {
        await route.fulfill({ status: 204 })
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
      if (path === '/config/platform/llm') {
        await fulfill(route, [sdkModelConfig, appServerModelConfig])
        return
      }
      if (path === '/config/platform/agent-model'
        || path === '/langgraph-workers'
        || path === '/coding-agents') {
        await fulfill(route, [])
        return
      }
      if (path === '/tasks/page') {
        const state = url.searchParams.get('state')
        const tasks = state === 'AWAITING_REPLY' ? [] : [sdkTask]
        await fulfill(route, {
          content: tasks,
          totalSessions: tasks.length,
          page: Number(url.searchParams.get('page') || '0'),
          size: Number(url.searchParams.get('size') || '20'),
        })
        return
      }
      if (path === '/tasks' && request.method() === 'GET') {
        await fulfill(route, [])
        return
      }
      if (path === '/tasks' && request.method() === 'POST') {
        createRequests.push(request.postDataJSON() as Record<string, unknown>)
        created = true
        await fulfill(route, appTask)
        return
      }
      if (path === '/tasks/resume' && request.method() === 'POST') {
        resumeRequests.push(request.postDataJSON() as Record<string, unknown>)
        await fulfill(route, appTask)
        return
      }
      if (path === '/sessions/configs') {
        const sessionIds = (request.postDataJSON() as { sessionIds?: string[] } | null)?.sessionIds || []
        await fulfill(route, sessionIds.map(sessionId => ({
          sessionId,
          pinned: false,
          authBound: false,
          authModelConfigId: sessionId === sdkTask.sessionId ? sdkModelConfig.id : appServerModelConfig.id,
          interactionState: sessionId === sdkTask.sessionId ? 'AWAITING_REPLY' : 'PROCESSING',
          tags: [],
        })))
        return
      }
      if (path === `/tasks/${sdkTask.taskId}`) {
        await fulfill(route, sdkTask)
        return
      }
      if (path === `/tasks/${appTask.taskId}`) {
        await fulfill(route, appTask)
        return
      }
      if (/^\/sessions\/[^/]+\/messages\/latest$/.test(path)) {
        await fulfill(route, {
          messages: [],
          total: 0,
          limit: Number(url.searchParams.get('limit') || '50'),
          offset: Number(url.searchParams.get('offset') || '0'),
          hasMore: false,
        })
        return
      }
      if (/^\/tasks\/workers\/[^/]+\/sessions\/[^/]+\/message-count$/.test(path)) {
        await fulfill(route, { user_count: 0, assistant_count: 0, total: 0 })
        return
      }
      if (/^\/tasks\/workers\/[^/]+\/sessions\/[^/]+\/messages$/.test(path)) {
        await fulfill(route, [])
        return
      }
      if (path === `/working-directories/worker/${worker.workerId}`) {
        await fulfill(route, [])
        return
      }
      if (path === `/claude-workers/${worker.workerId}/processes`
        || path === `/codex-workers/${worker.workerId}/processes`
        || path === `/gemini-workers/${worker.workerId}/processes`) {
        await fulfill(route, { processes: [], active_task_count: 0 })
        return
      }
      if (path === '/codex-runtimes/availability') {
        expect(url.searchParams.get('workerId')).toBe(worker.workerId)
        await fulfill(route, {
          appServerManaged: true,
          modelAvailable: true,
          ultraAvailable: true,
          blockReason: null,
        })
        return
      }
      if (/^\/session-relations\/forward\/incoming\//.test(path)) {
        await fulfill(route, null)
        return
      }

      await fulfill(route, [])
    })

    await page.goto('/')
    await page.getByText(worker.name, { exact: true }).click()
    const historyItem = page.locator('.conv-item', { hasText: sdkTask.prompt }).first()
    await expect(historyItem).toBeVisible()
    await expect(historyItem.getByText('Codex SDK', { exact: true })).toBeVisible()
    await historyItem.click()

    const pane = page.locator('.task-pane').first()
    await expect(pane).toBeVisible()
    await expect(pane.getByText('Codex SDK', { exact: true })).toBeVisible()

    const modelConfigSelect = page.locator('.new-task-mini .mini-toolbar .el-select').first()
    await modelConfigSelect.click()
    await page.getByRole('option', { name: appServerModelConfig.name, exact: true }).click()
    await expect(page.locator('.new-task-mini')).toContainText(appServerModelConfig.name)

    const paneInput = pane.locator('textarea').last()
    await paneInput.fill('continue through app server')
    await pane.getByRole('button', { name: '发送', exact: true }).click()

    const confirm = page.getByRole('dialog', { name: '创建新会话' })
    await expect(confirm.getByText('无法续接原生会话', { exact: false })).toBeVisible()
    await captureLocatorEvidence(confirm, 'OPT-005-cross-provider-new-session.png')
    await confirm.getByRole('button', { name: '创建新会话', exact: true }).click()

    await expect.poll(() => created).toBe(true)
    expect(createRequests).toHaveLength(1)
    expect(createRequests[0]).toEqual(expect.objectContaining({
      workerId: worker.workerId,
      prompt: 'continue through app server',
      cwd: sdkTask.cwd,
      model: 'codex-latest:high',
      modelConfigId: appServerModelConfig.id,
      providerType: 'codex-app-server-worker',
    }))
    expect(createRequests[0]).not.toHaveProperty('sessionId')
    expect(resumeRequests).toHaveLength(0)
    await expect(page.getByText('已创建新会话', { exact: true })).toBeVisible()
    const appPane = page.locator('.task-pane', { hasText: appTask.prompt })
    await expect(appPane.getByText('Codex App Server', { exact: true })).toBeVisible()

    await assertWithinViewport(page.locator('.new-task-mini'), 1280)
    await assertWithinViewport(page.locator('.task-pane').first(), 1280)
  })
})
