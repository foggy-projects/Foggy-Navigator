import { expect, test, type Page, type Route } from '@playwright/test'
import path from 'node:path'

const now = new Date().toISOString()
const expectedCliVersion = '0.144.1'
const expectedSchemaDigest = '6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f'

async function captureEvidence(page: Page, name: string) {
  const evidenceDir = process.env.OPT005_EVIDENCE_DIR
  if (!evidenceDir) return
  await page.screenshot({ path: path.join(evidenceDir, name), fullPage: true })
}

const worker = {
  workerId: 'worker-runtime-e2e',
  name: 'Runtime E2E Worker',
  baseUrl: 'http://claude-worker.local',
  workerBackend: 'CLAUDE_CODE',
  authMode: 'SUBSCRIPTION',
  status: 'ONLINE',
  hostname: 'runtime-host',
  codexBaseUrl: 'http://sdk-codex.local',
  codexModel: 'codex-latest',
  codexAuthTokenConfigured: true,
  createdAt: now,
  updatedAt: now,
}

type EndpointState = {
  endpointId: string
  workerId: string
  endpointUrl: string
  endpointDisplay: string
  tokenConfigured: boolean
  configurationVersion: number
  lastSyncStatus: string
  lastSyncMessage?: string
  lastSyncedAt?: string
  lastRuntimeId?: string
  lastRuntimeRevision?: number
  createdAt: string
  updatedAt: string
}

type RuntimeState = {
  runtimeId: string
  revision: number
  workerId: string
  runtimeType: 'APP_SERVER'
  runtimeSource: 'ENDPOINT_SYNC'
  endpointId: string
  endpointConfigured: boolean
  instanceId?: string
  enabled: boolean
  routingPolicy: string
  rolloutPercentage: number
  priority: number
  routingEpoch: number
  readinessStatus: string
  readinessMessage?: string
  contractVersion?: string
  cliVersion?: string
  schemaDigest?: string
  capabilityFresh?: boolean
  supportsUltra?: boolean
  archived?: boolean
  archivedAt?: string
  expectedCliVersion: string
  expectedSchemaDigest: string
  lastCapabilityAt?: string
  createdAt: string
  updatedAt: string
}

const incompatibleRuntime: RuntimeState = {
  runtimeId: 'runtime-legacy',
  revision: 1,
  workerId: worker.workerId,
  runtimeType: 'APP_SERVER',
  runtimeSource: 'ENDPOINT_SYNC',
  endpointId: 'endpoint-legacy',
  endpointConfigured: true,
  enabled: false,
  routingPolicy: 'DARK',
  rolloutPercentage: 0,
  priority: 0,
  routingEpoch: 1,
  readinessStatus: 'INCOMPATIBLE',
  readinessMessage: 'cli_version must be 0.144.1; schema_digest mismatch',
  contractVersion: 'codex-app-server-worker/v1',
  cliVersion: '0.143.0',
  schemaDigest: 'stale-schema-digest-123456789',
  expectedCliVersion,
  expectedSchemaDigest,
  lastCapabilityAt: now,
  createdAt: now,
  updatedAt: now,
}

const legacyEndpoint: EndpointState = {
  endpointId: 'endpoint-legacy',
  workerId: worker.workerId,
  endpointUrl: 'http://legacy-app-server.local:3062',
  endpointDisplay: 'http://legacy-app-server.local:3062',
  tokenConfigured: true,
  configurationVersion: 1,
  lastSyncStatus: 'INCOMPATIBLE',
  lastSyncMessage: 'cli_version must be 0.144.1; schema_digest mismatch',
  lastSyncedAt: now,
  lastRuntimeId: 'runtime-legacy',
  lastRuntimeRevision: 1,
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

async function mockApi(page: Page) {
  const endpoints: EndpointState[] = [{ ...legacyEndpoint }]
  const runtimes: RuntimeState[] = [{ ...incompatibleRuntime }]
  const endpointCreates: Array<Record<string, unknown>> = []
  const endpointUpdates: Array<Record<string, unknown>> = []
  const endpointSyncs: string[] = []
  const routingUpdates: Array<Record<string, unknown>> = []
  const lifecycleUpdates: Array<Record<string, unknown>> = []

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
    if (path === '/langgraph-workers' || path === '/coding-agents'
        || path === '/config/platform/llm' || path === '/config/platform/agent-model') {
      await fulfill(route, [])
      return
    }
    if (path === '/tasks/page') {
      await fulfill(route, {
        content: [],
        totalSessions: 0,
        page: Number(url.searchParams.get('page') || '0'),
        size: Number(url.searchParams.get('size') || '20'),
      })
      return
    }
    if (path === '/tasks' || path === '/sessions/configs') {
      await fulfill(route, [])
      return
    }
    if (path === `/working-directories/worker/${worker.workerId}`) {
      await fulfill(route, [])
      return
    }
    if (path === `/claude-workers/${worker.workerId}/processes`
        || path === `/codex-workers/${worker.workerId}/processes`) {
      await fulfill(route, { processes: [], active_task_count: 0 })
      return
    }

    if (path === '/codex-app-server-endpoints' && request.method() === 'GET') {
      expect(url.searchParams.get('workerId')).toBe(worker.workerId)
      await fulfill(route, endpoints)
      return
    }
    if (path === '/codex-app-server-endpoints' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>
      endpointCreates.push(body)
      const created: EndpointState = {
        endpointId: 'endpoint-canary',
        workerId: String(body.workerId),
        endpointUrl: String(body.endpointUrl),
        endpointDisplay: String(body.endpointUrl),
        tokenConfigured: Boolean(body.authToken),
        configurationVersion: 1,
        lastSyncStatus: 'PENDING',
        createdAt: now,
        updatedAt: now,
      }
      endpoints.unshift(created)
      await fulfill(route, created)
      return
    }

    const endpointSyncMatch = path.match(/^\/codex-app-server-endpoints\/([^/]+)\/sync$/)
    if (endpointSyncMatch && request.method() === 'POST') {
      const endpointId = decodeURIComponent(endpointSyncMatch[1]!)
      endpointSyncs.push(endpointId)
      const endpoint = endpoints.find(item => item.endpointId === endpointId)
      expect(endpoint).toBeTruthy()
      const runtimeId = endpointId === 'endpoint-canary' ? 'runtime-canary' : 'runtime-legacy'
      const previous = runtimes
        .filter(item => item.endpointId === endpointId)
        .sort((left, right) => right.revision - left.revision)[0]
      const revision = (previous?.revision || 0) + 1
      if (previous && !previous.archived) {
        Object.assign(previous, {
          enabled: false,
          routingPolicy: 'DRAINING',
          rolloutPercentage: 0,
          routingEpoch: previous.routingEpoch + 1,
        })
      }
      const created: RuntimeState = {
        runtimeId,
        revision,
        workerId: worker.workerId,
        runtimeType: 'APP_SERVER',
        runtimeSource: 'ENDPOINT_SYNC',
        endpointId,
        endpointConfigured: true,
        instanceId: `app-server-instance-${revision}`,
        enabled: false,
        routingPolicy: 'DARK',
        rolloutPercentage: 0,
        priority: 0,
        routingEpoch: 1,
        readinessStatus: 'READY',
        contractVersion: 'codex-app-server-worker/v1',
        cliVersion: expectedCliVersion,
        schemaDigest: expectedSchemaDigest,
        capabilityFresh: true,
        supportsUltra: true,
        expectedCliVersion,
        expectedSchemaDigest,
        lastCapabilityAt: now,
        createdAt: now,
        updatedAt: now,
      }
      runtimes.unshift(created)
      Object.assign(endpoint!, {
        lastSyncStatus: 'READY',
        lastSyncMessage: undefined,
        lastSyncedAt: now,
        lastRuntimeId: runtimeId,
        lastRuntimeRevision: revision,
      })
      await fulfill(route, { endpoint, runtime: created, runtimeCreated: true })
      return
    }

    const endpointMatch = path.match(/^\/codex-app-server-endpoints\/([^/]+)$/)
    if (endpointMatch && request.method() === 'PUT') {
      const endpointId = decodeURIComponent(endpointMatch[1]!)
      const endpoint = endpoints.find(item => item.endpointId === endpointId)
      expect(endpoint).toBeTruthy()
      const body = request.postDataJSON() as Record<string, unknown>
      endpointUpdates.push({ endpointId, ...body })
      const endpointUrl = String(body.endpointUrl || endpoint!.endpointUrl)
      Object.assign(endpoint!, {
        endpointUrl,
        endpointDisplay: endpointUrl,
        tokenConfigured: body.clearAuthToken === true
          ? false
          : Boolean(body.authToken) || endpoint!.tokenConfigured,
        configurationVersion: endpoint!.configurationVersion + 1,
        lastSyncStatus: 'PENDING',
        updatedAt: now,
      })
      await fulfill(route, endpoint)
      return
    }

    if (path === '/codex-runtimes' && request.method() === 'GET') {
      expect(url.searchParams.get('workerId')).toBe(worker.workerId)
      expect(url.searchParams.get('includeArchived')).toBe('true')
      await fulfill(route, runtimes)
      return
    }

    const rateLimitMatch = path.match(/^\/codex-runtimes\/([^/]+)\/revisions\/(\d+)\/rate-limits$/)
    if (rateLimitMatch && request.method() === 'GET') {
      await fulfill(route, {
        contractVersion: 1,
        runtimeId: decodeURIComponent(rateLimitMatch[1]!),
        runtimeRevision: Number(rateLimitMatch[2]),
        instanceId: 'app-server-instance-e2e',
        scope: 'E2E',
        state: 'AVAILABLE',
        observedAtEpochMs: Date.now(),
        stale: false,
        limits: [],
        errorCode: null,
      })
      return
    }

    const runtimeMatch = path.match(/^\/codex-runtimes\/([^/]+)\/revisions\/(\d+)\/(refresh|routing|archive|unarchive)$/)
    if (runtimeMatch) {
      const runtimeId = decodeURIComponent(runtimeMatch[1]!)
      const revision = Number(runtimeMatch[2])
      const action = runtimeMatch[3]
      const runtime = runtimes.find(item => item.runtimeId === runtimeId && item.revision === revision)
      expect(runtime).toBeTruthy()

      if (action === 'refresh' && request.method() === 'POST') {
        Object.assign(runtime!, { readinessStatus: 'READY', capabilityFresh: true, lastCapabilityAt: now })
        await fulfill(route, runtime)
        return
      }
      if (action === 'routing' && request.method() === 'PUT') {
        const body = request.postDataJSON() as Record<string, unknown>
        routingUpdates.push(body)
        expect(body.expectedRoutingEpoch).toBe(runtime!.routingEpoch)
        Object.assign(runtime!, {
          enabled: body.enabled,
          routingPolicy: body.routingPolicy,
          rolloutPercentage: body.rolloutPercentage,
          priority: body.priority ?? runtime!.priority,
          routingEpoch: runtime!.routingEpoch + 1,
          updatedAt: now,
        })
        await fulfill(route, runtime)
        return
      }
      if ((action === 'archive' || action === 'unarchive') && request.method() === 'POST') {
        const body = request.postDataJSON() as Record<string, unknown>
        lifecycleUpdates.push({ runtimeId, revision, action, ...body })
        expect(body.expectedRoutingEpoch).toBe(runtime!.routingEpoch)
        Object.assign(runtime!, action === 'archive' ? {
          archived: true,
          archivedAt: now,
          enabled: false,
          routingPolicy: 'DARK',
          rolloutPercentage: 0,
          routingEpoch: runtime!.routingEpoch + 1,
        } : {
          archived: false,
          archivedAt: undefined,
          enabled: false,
          routingPolicy: 'DARK',
          rolloutPercentage: 0,
          routingEpoch: runtime!.routingEpoch + 1,
        })
        await fulfill(route, runtime)
        return
      }
    }

    await fulfill(route, null)
  })

  return { endpointCreates, endpointUpdates, endpointSyncs, routingUpdates, lifecycleUpdates }
}

async function openAppServerTab(page: Page) {
  await page.locator('.worker-item').filter({ hasText: worker.name }).click()
  const workerHeader = page.locator('.worker-header')
  await expect(workerHeader).toContainText(worker.name)
  await workerHeader.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑物理 Worker' })
  await expect(dialog.getByRole('tab', { name: '基本信息', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: '连接工具', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: 'Codex', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: 'Codex App Server', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: 'Gemini', exact: true })).toBeVisible()
  await dialog.getByRole('tab', { name: 'Codex App Server', exact: true }).click()
  return dialog
}

test('configures App Server through Endpoint sync and manages derived Runtime lifecycle', async ({ page }) => {
  const requests = await mockApi(page)
  await page.goto('/')
  const dialog = await openAppServerTab(page)

  await expect(dialog.getByText('Codex Ultra 当前不可用')).toBeVisible()
  await expect(dialog.getByTestId('add-codex-runtime')).toHaveCount(0)
  const incompatibleRow = dialog.getByTestId('runtime-runtime-legacy@1')
  await expect(incompatibleRow.getByText('不兼容', { exact: true })).toBeVisible()
  await expect(incompatibleRow).toContainText('0.143.0 / 0.144.1')
  await expect(incompatibleRow).toContainText('schema_digest mismatch')

  await dialog.getByTestId('add-codex-app-server-endpoint').click()
  await dialog.getByTestId('endpoint-url-input').fill('http://app-server.local:3062')
  await dialog.getByTestId('save-codex-app-server-endpoint').click()
  expect(requests.endpointCreates).toEqual([{
    workerId: worker.workerId,
    endpointUrl: 'http://app-server.local:3062',
    authToken: '',
  }])

  const endpointRow = dialog.getByTestId('endpoint-endpoint-canary')
  await expect(endpointRow.getByText('无令牌', { exact: true })).toBeVisible()
  await endpointRow.getByRole('button', { name: '同步 http://app-server.local:3062' }).click()
  await expect.poll(() => requests.endpointSyncs).toEqual(['endpoint-canary'])

  const canaryRow = dialog.getByTestId('runtime-runtime-canary@1')
  await expect(canaryRow.getByText('Ready', { exact: true })).toBeVisible()
  await expect(canaryRow.getByText('Endpoint 同步', { exact: true })).toBeVisible()
  await expect(canaryRow).toContainText(`${expectedCliVersion} / ${expectedCliVersion}`)
  await expect(dialog.getByText('Codex Ultra 当前不可用')).toBeVisible()

  await canaryRow.locator('.runtime-enabled-control .el-switch').click()
  await canaryRow.locator('.runtime-policy-control .el-select').click()
  await expect(page.getByRole('option', {
    name: '全模型默认（需先切换至 Ultra 灰度）',
    exact: true,
  })).toHaveAttribute('aria-disabled', 'true')
  await page.getByRole('option', { name: 'Ultra 灰度', exact: true }).click()
  await canaryRow.locator('.runtime-percentage-control input').fill('25')
  await canaryRow.locator('.runtime-percentage-control input').press('Tab')
  await canaryRow.getByRole('button', { name: '保存 runtime-canary 路由配置' }).click()
  await expect.poll(() => requests.routingUpdates).toEqual([{
    enabled: true,
    routingPolicy: 'ULTRA_CANARY',
    rolloutPercentage: 25,
    expectedRoutingEpoch: 1,
  }])
  await expect(dialog.getByText('Codex Ultra 可用', { exact: true })).toBeVisible()

  await endpointRow.getByRole('button', { name: '编辑 http://app-server.local:3062' }).click()
  await dialog.getByTestId('endpoint-url-input').fill('http://app-server-v2.local:3062')
  await dialog.getByTestId('endpoint-token-input').fill('replacement-runtime-secret')
  await dialog.getByTestId('save-codex-app-server-endpoint').click()
  expect(requests.endpointUpdates).toEqual([expect.objectContaining({
    endpointId: 'endpoint-canary',
    endpointUrl: 'http://app-server-v2.local:3062',
    authToken: 'replacement-runtime-secret',
  })])
  await expect(dialog).not.toContainText('replacement-runtime-secret')

  await endpointRow.getByRole('button', { name: '同步 http://app-server-v2.local:3062' }).click()
  const revisionTwo = dialog.getByTestId('runtime-runtime-canary@2')
  await expect(revisionTwo.getByText('Ready', { exact: true })).toBeVisible()
  await expect.poll(() => requests.endpointSyncs).toEqual(['endpoint-canary', 'endpoint-canary'])

  await revisionTwo.getByRole('button', { name: '归档 runtime-canary@2' }).click()
  await page.getByRole('button', { name: '归档', exact: true }).click()
  await expect(revisionTwo).toBeHidden()
  await dialog.locator('.runtime-header-actions .el-checkbox').click()
  const archivedRow = dialog.getByTestId('runtime-runtime-canary@2')
  await expect(archivedRow.getByText('已归档', { exact: true })).toBeVisible()
  await expect(archivedRow.locator('.runtime-routing-grid')).toHaveCount(0)
  await archivedRow.getByRole('button', { name: '恢复 runtime-canary@2' }).click()
  await expect(archivedRow.getByText('已归档', { exact: true })).toHaveCount(0)
  expect(requests.lifecycleUpdates).toEqual([
    { runtimeId: 'runtime-canary', revision: 2, action: 'archive', expectedRoutingEpoch: 1 },
    { runtimeId: 'runtime-canary', revision: 2, action: 'unarchive', expectedRoutingEpoch: 2 },
  ])
  await dialog.locator('.el-dialog__headerbtn').click()
  await expect(dialog).toBeHidden()
  const evidenceDialog = await openAppServerTab(page)
  await expect(evidenceDialog.getByTestId('runtime-runtime-canary@2')).toBeVisible()
  await expect(evidenceDialog.getByText('Codex Ultra 当前不可用', { exact: true })).toBeVisible()
  await expect(page.locator('.el-message')).toHaveCount(0, { timeout: 10_000 })
  await captureEvidence(page, 'OPT-006-endpoint-runtime-desktop.png')
})

test('keeps Endpoint and Runtime controls usable at 320px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await mockApi(page)
  await page.goto('/')

  await page.getByRole('button', { name: '展开 Worker 导航' }).click()
  const dialog = await openAppServerTab(page)
  await expect(dialog.getByTestId('add-codex-app-server-endpoint')).toBeVisible()
  await expect(dialog.locator('.runtime-header-actions .el-checkbox')).toBeVisible()
  const endpointTitleBox = await dialog.getByText('App Server Endpoint', { exact: true }).boundingBox()
  expect(endpointTitleBox).not.toBeNull()
  expect(endpointTitleBox!.width).toBeGreaterThan(100)
  expect(endpointTitleBox!.height).toBeLessThan(30)

  const dialogBox = await dialog.boundingBox()
  expect(dialogBox).not.toBeNull()
  expect(dialogBox!.x).toBeGreaterThanOrEqual(0)
  expect(dialogBox!.x + dialogBox!.width).toBeLessThanOrEqual(320)
  expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)

  await dialog.getByTestId('add-codex-app-server-endpoint').click()
  await expect(dialog.getByTestId('endpoint-url-input')).toBeVisible()
  await expect(dialog.getByTestId('endpoint-token-input')).toBeVisible()
  await expect(dialog.getByTestId('save-codex-app-server-endpoint')).toBeVisible()
  const legacyEndpointRow = dialog.getByTestId('endpoint-endpoint-legacy')
  const endpointTags = [
    legacyEndpointRow.locator('.el-tag', { hasText: '令牌已配置' }),
    legacyEndpointRow.locator('.el-tag', { hasText: '不兼容' }),
  ]
  for (const tag of endpointTags) {
    await expect(tag).toBeVisible()
    expect(await tag.evaluate(
      element => element.scrollWidth <= element.clientWidth + 1,
    )).toBe(true)
    expect(await tag.locator('.el-tag__content').evaluate(
      element => element.scrollWidth <= element.clientWidth + 1,
    )).toBe(true)
  }
  await captureEvidence(page, 'OPT-006-endpoint-runtime-320.png')
})
