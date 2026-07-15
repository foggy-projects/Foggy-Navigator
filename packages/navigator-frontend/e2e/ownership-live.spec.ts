import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Page,
  type Response,
} from '@playwright/test'

const liveEnabled = process.env.OWNERSHIP_LIVE_E2E === '1'
const genericAccessDenied = 'Resource access denied'

type RxEnvelope<T> = {
  code: number
  data: T
  message?: string
  msg?: string
}

type LiveSession = {
  id: string
  taskName?: string
}

type BrowserApiResult<T = unknown> = {
  status: number
  body: RxEnvelope<T>
}

// Credentials must not be retained in Playwright failure artifacts.
test.use({ trace: 'off', screenshot: 'off', video: 'off' })

test.describe('live Session ownership boundary', () => {
  test.skip(!liveEnabled, 'set OWNERSHIP_LIVE_E2E=1 to run the isolated live ownership check')

  test('two authenticated users cannot cross the Session boundary @live', async ({
    browser,
    request,
  }, testInfo) => {
    test.setTimeout(60_000)
    requireIsolatedLoopbackEnvironment(testInfo.project.use.baseURL)
    const password = requirePassword()
    const suffix = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`
    const tenantId = `ownership_e2e_${suffix}`
    const usernameA = `ownership_a_${suffix}`
    const usernameB = `ownership_b_${suffix}`
    const marker = `ownership-session-${suffix}`

    await registerUser(request, tenantId, usernameA, password)
    await registerUser(request, tenantId, usernameB, password)

    const contextA = await browser.newContext()
    const contextB = await browser.newContext()
    const pageA = await contextA.newPage()
    const pageB = await contextB.newPage()
    let sessionId: string | null = null
    let primaryError: unknown

    await restrictPageToOwnershipApis(pageA)
    await restrictPageToOwnershipApis(pageB)

    try {
      await test.step('authenticate two isolated browser contexts and create owner Session', async () => {
        await loginThroughUi(pageA, usernameA, password)
        await loginThroughUi(pageB, usernameB, password)

        const created = await browserApi<LiveSession>(pageA, '/api/v1/sessions', {
          method: 'POST',
          body: { title: marker, agentId: 'ownership-live-fixture' },
        })
        expect(created.status).toBe(200)
        expect(created.body.code).toBe(200)
        expect(created.body.data.taskName).toBe(marker)
        sessionId = created.body.data.id
        expect(sessionId).toBeTruthy()
      })

      await test.step('owner can list, deep-link, and load Session history', async () => {
        const ownerListResponse = pageA.waitForResponse(isSessionListResponse)
        const ownerHistoryResponse = pageA.waitForResponse(
          response => isSessionHistoryResponse(response, sessionId!),
        )
        await pageA.goto(`/#/c/${encodeURIComponent(sessionId!)}`)

        const ownerList = await readRx<LiveSession[]>(await ownerListResponse)
        expect(ownerList.data.some(session => session.id === sessionId)).toBe(true)
        expect((await ownerHistoryResponse).status()).toBe(200)
        await expect(pageA.getByText(marker, { exact: true }).first()).toBeVisible()
      })

      await test.step('non-owner is denied by list, deep-link, history, SSE, and direct read', async () => {
        const otherListResponse = pageB.waitForResponse(isSessionListResponse)
        const forbiddenHistoryResponse = pageB.waitForResponse(
          response => isSessionHistoryResponse(response, sessionId!),
          { timeout: 10_000 },
        )
        const forbiddenSubscriptionResponse = pageB.waitForResponse(
          isSubscriptionResponse,
          { timeout: 10_000 },
        )
        await pageB.evaluate((id) => {
          window.location.hash = `/c/${encodeURIComponent(id)}`
        }, sessionId!)
        await expect(pageB).toHaveURL(new RegExp(`/#/c/${sessionId}$`))

        const otherList = await readRx<LiveSession[]>(await otherListResponse)
        expect(otherList.data.some(session => session.id === sessionId)).toBe(false)
        expect(JSON.stringify(otherList)).not.toContain(marker)

        const forbiddenHistory = await forbiddenHistoryResponse
        expect(forbiddenHistory.status()).toBe(403)
        assertGenericDenial(await forbiddenHistory.json(), sessionId!, marker, usernameA)

        const forbiddenSubscription = await forbiddenSubscriptionResponse
        expect(forbiddenSubscription.status()).toBe(403)
        expect(subscriptionSessionIds(forbiddenSubscription)).toContain(sessionId)

        await expect(pageB.getByText('无权限访问').first()).toBeVisible()
        await expect(pageB.getByText(marker, { exact: true })).toHaveCount(0)

        const forbiddenDirectRead = await browserApi<LiveSession>(
          pageB,
          `/api/v1/sessions/${encodeURIComponent(sessionId!)}`,
        )
        expect(forbiddenDirectRead.status).toBe(403)
        assertGenericDenial(forbiddenDirectRead.body, sessionId!, marker, usernameA)
      })
    } catch (error) {
      primaryError = error
      throw error
    } finally {
      if (sessionId && !pageA.isClosed()) {
        try {
          const deleted = await browserApi(pageA, `/api/v1/sessions/${encodeURIComponent(sessionId)}`, {
            method: 'DELETE',
          })
          if (!primaryError) {
            expect(deleted.status).toBe(200)
            expect(deleted.body.code).toBe(200)
          }
        } catch (cleanupError) {
          if (!primaryError) throw cleanupError
        }
      }
      await Promise.all([
        ...(pageA.isClosed() ? [] : [contextA.close()]),
        ...(pageB.isClosed() ? [] : [contextB.close()]),
      ])
    }
  })
})

function requireIsolatedLoopbackEnvironment(baseURL: unknown): void {
  if (process.env.OWNERSHIP_E2E_ISOLATED !== '1') {
    throw new Error('OWNERSHIP_E2E_ISOLATED=1 is required; shared environments are forbidden')
  }
  if (typeof baseURL !== 'string') {
    throw new Error('Playwright baseURL is required for the live ownership check')
  }
  const url = new URL(baseURL)
  if (!['127.0.0.1', 'localhost', '::1'].includes(url.hostname)) {
    throw new Error('The live ownership check is restricted to an isolated loopback frontend')
  }
}

function requirePassword(): string {
  const password = process.env.OWNERSHIP_E2E_PASSWORD
  if (!password) {
    throw new Error('OWNERSHIP_E2E_PASSWORD is required when live ownership testing is enabled')
  }
  return password
}

async function registerUser(
  request: APIRequestContext,
  tenantId: string,
  username: string,
  password: string,
): Promise<void> {
  const response = await request.post('/api/v1/auth/register', {
    data: {
      tenantId,
      username,
      password,
      displayName: username,
      roles: 'VIEWER',
    },
  })
  expect(response.status()).toBe(200)
  const body = await readRx<string>(response)
  expect(body.code).toBe(200)
  expect(body.data).toBeTruthy()
}

async function loginThroughUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/#/login')
  const loginResponse = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/auth/login' && response.request().method() === 'POST'
  })

  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.evaluate((value) => {
    const input = document.querySelector<HTMLInputElement>('input[type="password"]')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    if (!input || !setter) {
      throw new Error('Password input is unavailable')
    }
    setter.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }, password)
  await page.getByRole('button', { name: '登录', exact: true }).click()

  const response = await loginResponse
  expect(response.status()).toBe(200)
  await expect(page.locator('.user-dropdown')).toContainText(username)
}

async function restrictPageToOwnershipApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const allowed = path === '/api/v1/auth/login'
      || path === '/api/v1/auth/register'
      || path === '/api/v1/sessions'
      || path.startsWith('/api/v1/sessions/')
      || path === '/api/v1/sse/unified'
      || path === '/api/v1/sse/subscribe'
      || path === '/api/v1/sse/unsubscribe'

    if (allowed) {
      await route.continue()
    } else {
      await route.abort('blockedbyclient')
    }
  })
}

async function browserApi<T>(
  page: Page,
  path: string,
  options: { method?: 'GET' | 'POST' | 'DELETE'; body?: Record<string, unknown> } = {},
): Promise<BrowserApiResult<T>> {
  return page.evaluate(async ({ requestPath, method, body }) => {
    const token = localStorage.getItem('navigator_token')
    if (!token) {
      throw new Error('Authenticated browser token is missing')
    }
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 5_000)
    try {
      const response = await fetch(requestPath, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      })
      return {
        status: response.status,
        body: await response.json(),
      }
    } finally {
      window.clearTimeout(timeout)
    }
  }, {
    requestPath: path,
    method: options.method ?? 'GET',
    body: options.body,
  })
}

function isSessionListResponse(response: Response): boolean {
  const url = new URL(response.url())
  return url.pathname === '/api/v1/sessions' && response.request().method() === 'GET'
}

function isSessionHistoryResponse(response: Response, sessionId: string): boolean {
  const url = new URL(response.url())
  return url.pathname === `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`
    && response.request().method() === 'GET'
}

function isSubscriptionResponse(response: Response): boolean {
  const url = new URL(response.url())
  const request = response.request()
  return url.pathname === '/api/v1/sse/subscribe' && request.method() === 'POST'
}

function subscriptionSessionIds(response: Response): string[] {
  try {
    const body = response.request().postDataJSON() as { sessionIds?: string[] }
    return body.sessionIds ?? []
  } catch {
    return []
  }
}

async function readRx<T>(response: APIResponse | Response): Promise<RxEnvelope<T>> {
  return await response.json() as RxEnvelope<T>
}

function assertGenericDenial(
  payload: unknown,
  sessionId: string,
  marker: string,
  ownerUsername: string,
): void {
  const body = payload as Partial<RxEnvelope<unknown>>
  expect(body.message ?? body.msg).toBe(genericAccessDenied)
  const serialized = JSON.stringify(payload)
  expect(serialized).not.toContain(sessionId)
  expect(serialized).not.toContain(marker)
  expect(serialized).not.toContain(ownerUsername)
}
