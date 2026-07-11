import { expect, test, type Page } from '@playwright/test'

async function mockSettingsApi(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('navigator_token', 'settings-help-e2e-token')
    localStorage.setItem(
      'navigator_user',
      JSON.stringify({ userId: 'settings-help-e2e', username: 'root', roles: ['SUPER_ADMIN'] }),
    )
  })
  await page.route('**/api/v1/**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: [] }),
    })
  })
}

test('documents Codex App Server Worker installation and runtime registration', async ({ page }) => {
  await mockSettingsApi(page)
  await page.goto('/#/settings')

  await page.getByRole('button', { name: /Worker 安装帮助/ }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer.getByText('Claude / Codex / App Server Worker 安装与配置', { exact: true })).toBeVisible()
  await drawer.getByRole('tab', { name: 'Codex App Server', exact: true }).click()

  await expect(drawer.getByText('tools/codex-app-server-worker', { exact: true })).toBeVisible()
  await expect(drawer.getByText(/irm https:\/\/obs-fe55\.obs\.cn-north-4\.myhuaweicloud\.com\/codex-app-server-worker\/install\.ps1 \| iex/)).toBeVisible()
  await expect(drawer.getByText(/curl -fsSL https:\/\/obs-fe55\.obs\.cn-north-4\.myhuaweicloud\.com\/codex-app-server-worker\/install\.sh \| bash/)).toBeVisible()
  await expect(drawer.getByText(/重复执行同一条一键安装命令即可安全升级最新版/)).toBeVisible()
  await expect(drawer.getByText(/codex-app-server-worker-<version>/)).toHaveCount(0)
  await expect(drawer.getByText(/CODEX_APP_SERVER_WORKER_PORT=3062/)).toBeVisible()
  await expect(drawer.getByText('平台接入与 Ultra', { exact: true })).toBeVisible()
})
