import { expect, test } from '@playwright/test'

test('mobile widget renders history, tool cards, actions, reports, and suspension decisions', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Navigator Chat Widget 观测页' })).toBeVisible()
  await page.getByRole('button', { name: 'Mobile' }).click()
  await expect(page.locator('.navigator-mobile-chat')).toBeVisible()

  await page.getByLabel('打开历史会话').click()
  await expect(page.locator('.nmc-history-sheet')).toBeVisible()
  await expect(page.getByText('移动端签收确认')).toBeVisible()
  await page.locator('.nmc-history-content').first().click()
  await expect(page.getByText('已准备好签收详情入口。')).toBeVisible()

  await page.locator('.nmc-textarea').fill('打开签收工作台')
  await page.locator('.nmc-send-button').click()
  await expect(page.getByText('已准备移动端业务入口')).toBeVisible()
  await expect(page.getByText('业务能力调用完成')).toBeVisible()
  await expect(page.getByText('TMS business function completed.')).toBeVisible()

  await page.locator('.nmc-action-button').filter({ hasText: '打开签收工作台' }).first().click()
  await expect(page.getByText('OPEN_TMS_PAGE: 打开签收工作台')).toBeVisible()

  await page.locator('.nmc-report-card').filter({ hasText: 'TMS business function completed.' }).click()
  await expect(page.locator('.nmc-report-sheet')).toBeVisible()
  await expect(page.getByText('report-mobile-tool')).toBeVisible()
  await page.getByRole('button', { name: '查看完整 Markdown' }).click()
  await expect(page.getByText('Mock BFF returned the full Markdown only after the viewer requested it.')).toBeVisible()
  await page.getByLabel('关闭执行报告').click()

  await page.getByRole('button', { name: '打开审批' }).click()
  const suspensionSheet = page.locator('.nmc-bottom-sheet').filter({ hasText: '确认打开签收工作台' })
  await expect(suspensionSheet).toBeVisible()
  await expect(suspensionSheet).not.toContainText('task_scoped_token')
  await expect(suspensionSheet).not.toContainText('adapterConfigJson')
  await page.getByRole('button', { name: '批准' }).click()
  await expect(page.getByText('approved: sus-mobile-observer')).toBeVisible()
})
