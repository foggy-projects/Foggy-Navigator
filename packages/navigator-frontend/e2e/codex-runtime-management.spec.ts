import { expect, test, type Page, type Route } from '@playwright/test'

const now = new Date().toISOString()
const expectedCliVersion = '0.144.1'
const expectedSchemaDigest = '6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f'
const runtimeToken = 'e2e-runtime-one-time-secret'

const worker = {
  workerId: 'worker-runtime-e2e',
  name: 'Runtime E2E Worker',
  baseUrl: 'http://claude-worker.local',
  workerBackend: 'OPENAI_CODEX',
  authMode: 'SUBSCRIPTION',
  status: 'ONLINE',
  hostname: 'runtime-host',
  codexBaseUrl: 'http://legacy-codex.local',
  codexModel: 'codex-latest',
  codexAuthTokenConfigured: true,
  createdAt: now,
  updatedAt: now,
}

type RuntimeState = {
  runtimeId: string
  revision: number
  workerId: string
  runtimeType: string
  endpointConfigured?: boolean
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
  const runtimes: RuntimeState[] = [{ ...incompatibleRuntime }]
  const registrations: Array<Record<string, unknown>> = []
  const routingUpdates: Array<Record<string, unknown>> = []

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

    if (path === '/codex-runtimes' && request.method() === 'GET') {
      expect(url.searchParams.get('workerId')).toBe(worker.workerId)
      await fulfill(route, runtimes)
      return
    }
    if (path === '/codex-runtimes' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>
      registrations.push(body)
      const created: RuntimeState = {
        runtimeId: String(body.runtimeId),
        revision: 1,
        workerId: String(body.workerId),
        runtimeType: 'APP_SERVER',
        endpointConfigured: true,
        enabled: Boolean(body.enabled),
        routingPolicy: String(body.routingPolicy),
        rolloutPercentage: Number(body.rolloutPercentage),
        priority: Number(body.priority),
        routingEpoch: Number(body.routingEpoch),
        readinessStatus: 'PENDING',
        expectedCliVersion,
        expectedSchemaDigest,
        createdAt: now,
        updatedAt: now,
      }
      runtimes.unshift(created)
      await fulfill(route, created)
      return
    }

    const runtimeMatch = path.match(/^\/codex-runtimes\/([^/]+)\/revisions\/(\d+)\/(refresh|routing)$/)
    if (runtimeMatch) {
      const runtimeId = decodeURIComponent(runtimeMatch[1]!)
      const revision = Number(runtimeMatch[2])
      const action = runtimeMatch[3]
      const runtime = runtimes.find((item) => item.runtimeId === runtimeId && item.revision === revision)
      expect(runtime).toBeTruthy()

      if (action === 'refresh' && request.method() === 'POST') {
        Object.assign(runtime!, {
          instanceId: 'app-server-instance-e2e',
          readinessStatus: 'READY',
          readinessMessage: undefined,
          contractVersion: 'codex-app-server-worker/v1',
          cliVersion: expectedCliVersion,
          schemaDigest: expectedSchemaDigest,
          capabilityFresh: true,
          supportsUltra: true,
          lastCapabilityAt: now,
          updatedAt: now,
        })
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
    }

    await fulfill(route, null)
  })

  return { registrations, routingUpdates }
}

test('promotes a dark runtime to an Ultra canary without exposing its token', async ({ page }) => {
  const requests = await mockApi(page)
  await page.goto('/')

  await page.getByText(worker.name, { exact: true }).click()
  const workerHeader = page.locator('.worker-header')
  await expect(workerHeader).toContainText(worker.name)
  await workerHeader.getByRole('button', { name: '编辑', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: '编辑物理 Worker' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('Codex capability', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: '基本信息', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: '连接工具', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: 'Codex', exact: true })).toBeVisible()
  await expect(dialog.getByRole('tab', { name: 'Gemini', exact: true })).toBeVisible()
  await dialog.getByRole('tab', { name: 'Codex', exact: true }).click()
  await expect(dialog.getByText('Codex Ultra 当前不可用')).toBeVisible()

  const incompatibleRow = dialog.getByTestId('runtime-runtime-legacy@1')
  await expect(incompatibleRow.getByText('不兼容', { exact: true })).toBeVisible()
  await expect(incompatibleRow).toContainText('0.143.0 / 0.144.1')
  await expect(incompatibleRow).toContainText('schema_digest mismatch')
  await expect(incompatibleRow.locator('.mismatch')).toHaveCount(2)

  await dialog.getByTestId('add-codex-runtime').click()
  await dialog.getByTestId('runtime-id-input').fill('runtime-canary')
  await dialog.getByTestId('runtime-endpoint-input').fill('http://app-server.local:3062')
  await dialog.getByTestId('register-codex-runtime').click()
  expect(requests.registrations).toHaveLength(0)
  await expect(dialog.getByTestId('runtime-registration')).toBeVisible()
  await expect(page.getByText('请填写 Runtime ID、Endpoint 和认证令牌')).toBeVisible()
  await dialog.getByTestId('runtime-token-input').fill(runtimeToken)
  await dialog.getByTestId('register-codex-runtime').click()

  await expect(dialog.getByTestId('runtime-registration')).toBeHidden()
  expect(requests.registrations).toEqual([expect.objectContaining({
    runtimeId: 'runtime-canary',
    workerId: worker.workerId,
    runtimeType: 'APP_SERVER',
    endpointUrl: 'http://app-server.local:3062',
    authToken: runtimeToken,
    enabled: false,
    routingPolicy: 'DARK',
    rolloutPercentage: 0,
    routingEpoch: 1,
  })])
  await expect(dialog.locator(`input[value="${runtimeToken}"]`)).toHaveCount(0)
  await expect(dialog).not.toContainText(runtimeToken)

  const canaryRow = dialog.getByTestId('runtime-runtime-canary@1')
  await expect(canaryRow.getByText('Ready', { exact: true })).toBeVisible()
  await expect(canaryRow.getByText('Endpoint 已配置', { exact: true })).toBeVisible()
  await expect(canaryRow).not.toContainText('app-server.local:3062')
  await expect(canaryRow).toContainText(`${expectedCliVersion} / ${expectedCliVersion}`)
  await expect(dialog.getByText('Codex Ultra 当前不可用')).toBeVisible()

  await canaryRow.locator('.runtime-enabled-control .el-switch').click()
  await canaryRow.locator('.runtime-policy-control .el-select').click()
  const skippedStage = page.getByRole('option', {
    name: '全模型默认（需先切换至 Ultra 灰度）',
    exact: true,
  })
  await expect(skippedStage).toHaveAttribute('aria-disabled', 'true')
  await page.getByRole('option', { name: 'Ultra 灰度', exact: true }).click()
  const percentage = canaryRow.locator('.runtime-percentage-control input')
  await percentage.fill('25')
  await percentage.press('Tab')
  await canaryRow.getByRole('button', { name: '保存 runtime-canary 路由配置' }).click()

  await expect.poll(() => requests.routingUpdates).toEqual([{
    enabled: true,
    routingPolicy: 'ULTRA_CANARY',
    rolloutPercentage: 25,
    expectedRoutingEpoch: 1,
  }])
  await expect(dialog.getByText('Codex Ultra 可用', { exact: true })).toBeVisible()
  await expect(dialog.getByText('Codex Ultra 当前不可用')).toBeHidden()
  await expect(dialog).not.toContainText(runtimeToken)
})
