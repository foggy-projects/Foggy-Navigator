/**
 * Coding Agent E2E Test: Create File Test
 *
 * 测试场景：
 * 1. 访问前端页面
 * 2. 登录（如需要）
 * 3. 记录当前页面状态
 * 4. 截图并等待用户手动测试
 *
 * 说明：由于前端 UI 可能频繁变化，此脚本采用半自动方式
 * 自动化部分：启动浏览器、导航到页面、登录、截图
 * 手动部分：创建会话、发送消息、验证结果
 */

import { chromium } from 'playwright';

const BASE_URL = 'http://localhost:8112';
const USERNAME = 'root';
const PASSWORD = 'root123';

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runTest() {
  console.log('\n=== Coding Agent E2E 测试 ===\n');
  console.log('启动浏览器...');

  const browser = await chromium.launch({
    headless: false,
    slowMo: 500  // 慢速模式，便于观察
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 }
  });

  const page = await context.newPage();

  // 监听控制台日志
  page.on('console', msg => {
    console.log(`[浏览器控制台] ${msg.type()}: ${msg.text()}`);
  });

  // 监听页面错误
  page.on('pageerror', error => {
    console.error(`[页面错误] ${error.message}`);
  });

  try {
    // Step 1: 访问页面
    console.log('\nStep 1: 访问前端页面...');
    console.log(`URL: ${BASE_URL}`);

    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');
    await sleep(2000);

    // 截图 - 初始页面
    await page.screenshot({
      path: 'tests/e2e/screenshots/01-initial-page.png',
      fullPage: true
    });
    console.log('✓ 截图已保存: tests/e2e/screenshots/01-initial-page.png');

    // Step 2: 检查并尝试登录
    console.log('\nStep 2: 检查登录状态...');

    await sleep(1000);
    const pageContent = await page.content();

    // 检查是否有登录表单
    const hasLoginForm = pageContent.includes('登录') ||
                        pageContent.includes('Login') ||
                        pageContent.includes('username') ||
                        pageContent.includes('password');

    if (hasLoginForm) {
      console.log('检测到登录表单，尝试登录...');

      // 截图 - 登录页面
      await page.screenshot({
        path: 'tests/e2e/screenshots/02-login-page.png',
        fullPage: true
      });

      try {
        // 尝试查找用户名输入框
        const usernameSelectors = [
          'input[name="username"]',
          'input[type="text"]',
          'input[placeholder*="用户"]',
          'input[placeholder*="username"]'
        ];

        let usernameInput = null;
        for (const selector of usernameSelectors) {
          const el = await page.locator(selector).first();
          if (await el.isVisible().catch(() => false)) {
            usernameInput = el;
            console.log(`找到用户名输入框: ${selector}`);
            break;
          }
        }

        if (usernameInput) {
          await usernameInput.fill(USERNAME);
          console.log(`✓ 已填写用户名: ${USERNAME}`);
        }

        // 尝试查找密码输入框
        const passwordInput = await page.locator('input[type="password"]').first();
        if (await passwordInput.isVisible().catch(() => false)) {
          await passwordInput.fill(PASSWORD);
          console.log(`✓ 已填写密码`);
        }

        // 查找并点击登录按钮
        const loginButton = await page.locator('button[type="submit"]').first();
        if (await loginButton.isVisible().catch(() => false)) {
          await loginButton.click();
          console.log('✓ 已点击登录按钮');

          await page.waitForLoadState('networkidle');
          await sleep(3000);

          // 截图 - 登录后
          await page.screenshot({
            path: 'tests/e2e/screenshots/03-after-login.png',
            fullPage: true
          });
          console.log('✓ 登录完成，截图已保存');
        }
      } catch (error) {
        console.log(`自动登录失败: ${error.message}`);
        console.log('请手动登录...');
      }
    } else {
      console.log('未检测到登录表单，可能已登录或使用静态页面');
    }

    // Step 3: 显示测试说明
    console.log('\n=== 手动测试步骤 ===\n');
    console.log('浏览器已打开，请按以下步骤进行手动测试：\n');
    console.log('1. 如果未登录，请先登录');
    console.log(`   - 用户名: ${USERNAME}`);
    console.log(`   - 密码: ${PASSWORD}\n`);
    console.log('2. 创建新会话/容器');
    console.log('   - 点击"创建"或"新建"按钮');
    console.log('   - 填写会话名称: "测试会话-创建文件"');
    console.log('   - 确认创建并等待容器启动（约10-20秒）\n');
    console.log('3. 发送测试消息');
    console.log('   - 在消息输入框中输入:');
    console.log('     "请创建一个名为 test-hello.txt 的文件，内容为 helloworld"');
    console.log('   - 点击发送按钮');
    console.log('   - 等待 Agent 执行（约30-60秒）\n');
    console.log('4. 验证结果');
    console.log('   - 查看 Agent 的响应消息');
    console.log('   - 确认文件创建成功');
    console.log('   - 可以通过容器日志或文件浏览器查看文件内容\n');
    console.log('===================\n');
    console.log('测试完成后，按 Ctrl+C 退出，浏览器将保持打开状态。');
    console.log('所有截图保存在: tests/e2e/screenshots/\n');

    // 保持浏览器打开，等待手动测试
    await sleep(300000); // 等待 5 分钟

  } catch (error) {
    console.error('\n测试执行失败:', error.message);
    await page.screenshot({
      path: 'tests/e2e/screenshots/error.png',
      fullPage: true
    });
    console.log('错误截图已保存: tests/e2e/screenshots/error.png');
  } finally {
    console.log('\n测试脚本结束，浏览器保持打开状态...');
    // 不自动关闭浏览器
  }
}

// 运行测试
runTest().catch(error => {
  console.error('测试脚本错误:', error);
  process.exit(1);
});
