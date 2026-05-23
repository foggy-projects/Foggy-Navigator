import { expect, test } from '@playwright/test'

test('real BFF mode keeps contextId when sending the next business assistant message', async ({ page }) => {
  const askBodies: unknown[] = []

  await page.route('http://127.0.0.1:5181/api/v1/observer/config', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: {
          authMode: 'client-app-runtime',
          authSource: 'e2e',
          bffBaseUrl: 'http://127.0.0.1:5181',
          navigatorBaseUrl: 'http://127.0.0.1:8112',
          agentId: 'tms.navigator.agent',
          upstreamUserId: 'tms-user-1',
          modelConfigId: 'cfg-real-bff',
          clientAppId: 'client-real-bff',
          clientAppName: 'Real BFF E2E',
          authUsername: 'tenant-admin',
          tenantId: 'tenant-tms',
          grantSteps: ['e2e-runtime'],
          attachmentUploadUrl: 'http://127.0.0.1:5181/api/v1/observer/attachments',
        },
      }),
    })
  })

  await page.route('http://127.0.0.1:5181/api/v1/open/agents/tms.navigator.agent/ask', async (route) => {
    askBodies.push(route.request().postDataJSON())
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: {
          taskId: `task-real-bff-${askBodies.length}`,
          agentId: 'tms.navigator.agent',
          status: 'COMPLETED',
          contextId: 'ctx-real-bff',
          terminal: true,
          terminalStatus: 'COMPLETED',
          result: 'Real BFF 已收到请求。',
        },
      }),
    })
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'Real BFF' }).click()
  await expect(page.getByText('client-app-runtime')).toBeVisible()

  const input = page.locator('.navigator-chat textarea').first()
  const sendButton = page.getByRole('button', { name: '发送' })

  await input.fill('查询运单 123')
  await sendButton.click()
  await expect.poll(() => askBodies.length).toBe(1)

  await input.fill('继续查看路由信息')
  await sendButton.click()
  await expect.poll(() => askBodies.length).toBe(2)

  expect(askBodies[0]).toMatchObject({
    question: '查询运单 123',
    maxTurns: 3,
  })
  expect(askBodies[1]).toMatchObject({
    question: '继续查看路由信息',
    contextId: 'ctx-real-bff',
    maxTurns: 3,
  })
})
