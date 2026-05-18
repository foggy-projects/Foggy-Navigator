import { expect, test, type APIRequestContext, type Page, type Request, type Response } from '@playwright/test'

const LIVE_ENABLED = isTruthy(process.env.NAVIGATOR_CHAT_WIDGET_LIVE_E2E)
const BFF_BASE_URL = normalizeBaseUrl(process.env.NAVIGATOR_OBSERVER_BFF_BASE_URL ?? 'http://127.0.0.1:5181')
const NAVIGATOR_BASE_URL = normalizeBaseUrl(
  process.env.NAVIGATOR_BASE_URL
    ?? process.env.NAVI_BASE_URL
    ?? 'http://127.0.0.1:8112',
)
const AGENT_ID = firstPresent(
  process.env.NAVIGATOR_CHAT_WIDGET_AGENT_ID,
  process.env.NAVI_AGENT_ID,
  process.env.NAVIGATOR_AGENT_ID,
)
const UPSTREAM_USER_ID = firstPresent(
  process.env.NAVIGATOR_CHAT_WIDGET_UPSTREAM_USER_ID,
  process.env.NAVI_UPSTREAM_USER_ID,
  process.env.UPSTREAM_USER_ID,
)
const MODEL_CONFIG_ID = firstPresent(
  process.env.NAVIGATOR_CHAT_WIDGET_MODEL_CONFIG_ID,
  process.env.NAVI_MODEL_CONFIG_ID,
  process.env.NAVIGATOR_MODEL_CONFIG_ID,
)
const FIRST_MESSAGE = process.env.NAVIGATOR_CHAT_WIDGET_LIVE_FIRST_MESSAGE ?? 'E2E 查询当前会话上下文'
const SECOND_MESSAGE = process.env.NAVIGATOR_CHAT_WIDGET_LIVE_SECOND_MESSAGE ?? '继续基于上一轮回答补充说明'

interface ObserverConfig {
  authMode?: string
  bffBaseUrl?: string
  navigatorBaseUrl?: string
  agentId?: string
  upstreamUserId?: string
  modelConfigId?: string
}

interface ApiEnvelope<T> {
  code: number
  data?: T
  msg?: string
}

test.describe('real BFF live context continuity @live', () => {
  test.skip(
    !LIVE_ENABLED,
    'Set NAVIGATOR_CHAT_WIDGET_LIVE_E2E=1 to run against a real Observer BFF and Navigator backend.',
  )

  test('keeps the returned contextId across two real business assistant turns', async ({ page, request }) => {
    test.setTimeout(180_000)

    const bffConfig = await readObserverConfig(request)
    const agentId = AGENT_ID ?? bffConfig.agentId
    expect(agentId, 'Observer BFF config or NAVIGATOR_CHAT_WIDGET_AGENT_ID must provide an agentId').toBeTruthy()
    expect(bffConfig.authMode, 'Observer BFF authMode must be configured').toBeTruthy()
    expect(bffConfig.authMode, 'Observer BFF authMode must be configured').not.toBe('missing')

    await seedRealBffSettings(page, {
      agentId,
      upstreamUserId: UPSTREAM_USER_ID ?? bffConfig.upstreamUserId,
      modelConfigId: MODEL_CONFIG_ID ?? bffConfig.modelConfigId,
      navigatorBaseUrl: bffConfig.navigatorBaseUrl ?? NAVIGATOR_BASE_URL,
    })

    const askBodies: unknown[] = []
    page.on('request', (request) => {
      if (isAskRequest(request)) {
        askBodies.push(request.postDataJSON())
      }
    })

    await page.goto('/')
    await expect(page.getByText('Real Navigator BFF')).toBeVisible()
    await expect(page.getByText(bffConfig.authMode!, { exact: true })).toBeVisible()

    const input = page.locator('.navigator-chat textarea').first()
    const sendButton = page.getByRole('button', { name: '发送' })

    await input.fill(FIRST_MESSAGE)
    const firstAsk = page.waitForRequest(isAskRequest)
    const firstAskResponse = page.waitForResponse((response) => isAskRequest(response.request()))
    await sendButton.click()
    await firstAsk
    const firstContextId = await readAskContextId(await firstAskResponse)
    await expect.poll(() => askBodies.length, { timeout: 10_000 }).toBe(1)
    await expect(input).toBeEnabled({ timeout: 150_000 })

    const firstBody = askBodies[0]
    expect(firstBody).toMatchObject({
      question: FIRST_MESSAGE,
      maxTurns: 3,
    })
    expect(getStringField(firstBody, 'contextId')).toBeUndefined()
    expect(firstContextId, 'first ask response must return a contextId').toBeTruthy()

    await input.fill(SECOND_MESSAGE)
    const secondAsk = page.waitForRequest(isAskRequest)
    await sendButton.click()
    await secondAsk
    await expect.poll(() => askBodies.length, { timeout: 10_000 }).toBe(2)
    await expect(input).toBeEnabled({ timeout: 150_000 })

    expect(askBodies[1]).toMatchObject({
      question: SECOND_MESSAGE,
      contextId: firstContextId,
      maxTurns: 3,
    })
    await expect(page.locator('.json-view').first()).toContainText(`"contextId": "${firstContextId}"`)
    await expect(page.locator('.navigator-chat .nc-message-text').getByText(FIRST_MESSAGE, { exact: true })).toBeVisible()
    await expect(page.locator('.navigator-chat .nc-message-text').getByText(SECOND_MESSAGE, { exact: true })).toBeVisible()
    await expect(page.getByText(/发送失败/)).not.toBeVisible()
  })
})

async function readObserverConfig(request: APIRequestContext): Promise<ObserverConfig> {
  const response = await request.get(`${BFF_BASE_URL}/api/v1/observer/config`, {
    headers: { Accept: 'application/json' },
    timeout: 10_000,
  })
  expect(response.ok(), `Observer BFF config request failed: HTTP ${response.status()}`).toBeTruthy()
  const envelope = await response.json() as ApiEnvelope<ObserverConfig>
  expect(envelope.code, envelope.msg ?? 'Observer BFF config must return code=0').toBe(0)
  return envelope.data ?? {}
}

async function readAskContextId(response: Response): Promise<string | undefined> {
  expect(response.ok(), `ask request failed: HTTP ${response.status()}`).toBeTruthy()
  const envelope = await response.json() as ApiEnvelope<Record<string, unknown>>
  expect(envelope.code, envelope.msg ?? 'ask response must return code=0').toBe(0)
  return getStringField(envelope.data, 'contextId')
}

async function seedRealBffSettings(page: Page, settings: {
  agentId?: string
  upstreamUserId?: string
  modelConfigId?: string
  navigatorBaseUrl: string
}) {
  await page.addInitScript((value) => {
    window.localStorage.setItem('navigator-chat-widget-observer-settings', JSON.stringify(value))
  }, stripBlankValues({
    connectionMode: 'real',
    hookMode: 'enabled',
    bffBaseUrl: BFF_BASE_URL,
    navigatorBaseUrl: settings.navigatorBaseUrl,
    bffAgentId: settings.agentId,
    bffUpstreamUserId: settings.upstreamUserId,
    bffModelConfigId: settings.modelConfigId,
    bffUploadEnabled: true,
  }))
}

function isAskRequest(request: Request): boolean {
  const url = new URL(request.url())
  return request.method() === 'POST' && /\/api\/v1\/open\/agents\/[^/]+\/ask$/.test(url.pathname)
}

function getStringField(value: unknown, field: string): string | undefined {
  if (!value || typeof value !== 'object') return undefined
  const raw = (value as Record<string, unknown>)[field]
  return typeof raw === 'string' && raw.length > 0 ? raw : undefined
}

function stripBlankValues<T extends Record<string, unknown>>(value: T): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined && item !== null && item !== ''),
  )
}

function firstPresent(...values: Array<string | undefined>): string | undefined {
  return values.find((value) => value != null && value.trim().length > 0)?.trim()
}

function normalizeBaseUrl(value: string): string {
  return value.replace(/\/+$/, '')
}

function isTruthy(value: string | undefined): boolean {
  return value === '1' || value?.toLowerCase() === 'true'
}
