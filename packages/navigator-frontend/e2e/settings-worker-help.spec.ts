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

test('documents Codex App Server Worker installation and Endpoint synchronization', async ({ page }) => {
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
  await expect(drawer.getByText(/CODEX_APP_SERVER_WORKER_TOKEN=/)).toBeVisible()
  await expect(drawer.getByText(/CODEX_APP_SERVER_STATE_KEY=<首装自动生成的32字节base64密钥>/)).toBeVisible()
  await expect(drawer.getByText(/CODEX_HOME=<install-dir>\/codex-home/)).toBeVisible()
  await expect(drawer.getByText(/模型 API Key 由 Navigator ModelConfig 按任务下发/)).toBeVisible()
  await expect(drawer.getByText(/切到「Codex App Server」Tab/)).toBeVisible()
  await expect(drawer.getByText(/添加 .*Endpoint/)).toBeVisible()
  await expect(drawer.getByText(/点击同步，由平台读取 capability 并派生 Dark Runtime/)).toBeVisible()
  await expect(drawer.getByText(/注册 Runtime ID/)).toHaveCount(0)
  await expect(drawer.getByText('平台接入与 Ultra', { exact: true })).toBeVisible()
})
