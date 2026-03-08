import { test, expect } from '@playwright/test';

test('Worker CLI 进程 tab should be default active', async ({ page }) => {
  // Navigate to login page
  await page.goto('http://localhost:5175/#/login');
  
  // Login with root/root123
  await page.fill('input[placeholder="用户名"]', 'root');
  await page.fill('input[placeholder="密码"]', 'root123');
  await page.click('button[type="submit"]');
  
  // Wait for navigation to main page
  await page.waitForURL('http://localhost:5175/#/chat', { timeout: 10000 });
  
  // Navigate to Workers page (assuming URL or navigation method)
  await page.goto('http://localhost:5175/#/workers');
  
  // Wait for workers to load
  await page.waitForSelector('.worker-card', { timeout: 10000 });
  
  // Click on the first worker
  const firstWorker = await page.locator('.worker-card').first();
  await firstWorker.click();
  
  // Wait for worker panel to open
  await page.waitForSelector('.worker-tabs', { timeout: 5000 });
  
  // Check if "CLI 进程" tab is active
  const cliProcessTab = await page.locator('.el-tabs__item:has-text("CLI 进程")');
  const agentsTab = await page.locator('.el-tabs__item:has-text("Coding Agents")');
  
  // Check active class
  const cliProcessIsActive = await cliProcessTab.evaluate(el => 
    el.classList.contains('is-active')
  );
  const agentsIsActive = await agentsTab.evaluate(el => 
    el.classList.contains('is-active')
  );
  
  console.log('CLI 进程 tab is active:', cliProcessIsActive);
  console.log('Coding Agents tab is active:', agentsIsActive);
  
  // Take screenshot for visual verification
  await page.screenshot({ path: 'test-result-worker-tab.png' });
  
  // Assert that CLI 进程 tab is active
  expect(cliProcessIsActive).toBe(true);
  expect(agentsIsActive).toBe(false);
  
  console.log('Test passed: CLI 进程 tab is default active!');
});
