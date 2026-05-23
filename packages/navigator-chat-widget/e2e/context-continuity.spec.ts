import { expect, test } from '@playwright/test'

test('reuses the returned contextId for the next business assistant message', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Navigator Chat Widget 观测页' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Mock' })).toHaveClass(/active/)

  const input = page.locator('.navigator-chat textarea').first()
  const sendButton = page.getByRole('button', { name: '发送' })
  const latestAskBody = page.locator('.json-view').first()

  await input.fill('查询运单 123')
  await sendButton.click()

  await expect(latestAskBody).toContainText('"question": "查询运单 123"')
  await expect(page.getByText('观测页收到 ask 请求，attachments=0。')).toBeVisible()
  await expect(input).toBeEnabled()

  await input.fill('继续查看路由信息')
  await sendButton.click()

  await expect(latestAskBody).toContainText('"question": "继续查看路由信息"')
  await expect(latestAskBody).toContainText('"contextId": "ctx-observer"')
  const chatMessages = page.locator('.navigator-chat .nc-message-text')
  await expect(chatMessages.getByText('查询运单 123', { exact: true })).toBeVisible()
  await expect(chatMessages.getByText('继续查看路由信息', { exact: true })).toBeVisible()
})
