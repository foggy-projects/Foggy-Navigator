import { chromium, type Page, type Browser, type Locator } from 'playwright';

// 截图路径
const SCREENSHOT_DIR = './screenshots';

async function main() {
  const browser: Browser = await chromium.launch({ headless: false });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page: Page = await context.newPage();

  try {
    console.log('访问 http://localhost:5174...');
    await page.goto('http://localhost:5174', { waitUntil: 'networkidle' });
    await page.screenshot({ path: `${SCREENSHOT_DIR}/01-homepage.png` });
    console.log('页面加载完成');

    // 检查是否需要登录
    const hasLogin = await page.locator('input[type="text"], input[type="username"]').count() > 0;
    if (hasLogin) {
      console.log('检测到登录页面，执行登录...');
      const input = page.locator('input[type="text"], input[type="username"]').first();
      await input.fill('root');
      await page.fill('input[type="password"]', 'root123');
      await page.click('button[type="submit"], .el-button--primary');
      await page.waitForLoadState('networkidle');
      await page.screenshot({ path: `${SCREENSHOT_DIR}/02-logged-in.png` });
      console.log('登录成功');
    }

    // 等待设置图标出现
    console.log('查找设置图标...');
    await page.waitForSelector('button:has-text("设置"), [class*="Setting"]', { timeout: 10000 });

    // 点击设置图标
    console.log('点击设置图标...');
    const settingButton = page.locator('button:has-text("设置"), [class*="Setting"]').first();
    await settingButton.click();
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: `${SCREENSHOT_DIR}/03-settings-page.png` });
    console.log('已进入设置页面');

    // 切换到 AI 模型标签（API 地址列在这里）
    console.log('切换到 AI 模型标签...');
    const aiModelTab = page.locator('.el-tabs__item').filter({ hasText: 'AI 模型' });
    if (await aiModelTab.count() > 0) {
      await aiModelTab.click();
      await page.waitForTimeout(500);
      await page.screenshot({ path: `${SCREENSHOT_DIR}/04-ai-models-tab.png` });
      console.log('已切换到 AI 模型标签');
    }

    // 等待表格加载
    console.log('等待表格加载...');
    await page.waitForSelector('.el-table__body-wrapper', { timeout: 5000 });

    // 获取表格中的所有行
    const rows = await page.locator('.el-table__body tr').count();
    console.log(`表格共有 ${rows} 行`);

    // 获取第一行的所有单元格
    const firstRowCells = page.locator('.el-table__body tr:first-child .el-table__cell');
    const cellCount = await firstRowCells.count();
    console.log(`第一行有 ${cellCount} 个单元格`);

    // 找到 API 地址列（通常是包含 URL 的列）
    // 检查每个单元格的文本内容
    let apiAddressCell: Locator | null = null;
    let apiAddressIndex = -1;

    for (let i = 0; i < cellCount; i++) {
      const cell = firstRowCells.nth(i);
      const text = await cell.textContent() || '';
      console.log(`单元格 ${i}: "${text.trim()}"`);

      // 检查是否包含 URL
      if (text.includes('http://') || text.includes('https://')) {
        apiAddressCell = cell;
        apiAddressIndex = i;
        console.log(`找到 API 地址列: 索引 ${i}`);
        break;
      }
    }

    if (apiAddressCell) {
      console.log('\n悬停在 API 地址单元格上...');
      await apiAddressCell.hover();
      await page.waitForTimeout(500); // 等待 tooltip 动画

      // 截图显示 tooltip
      await page.screenshot({ path: `${SCREENSHOT_DIR}/05-tooltip-hovered.png` });
      console.log('已截图 tooltip');

      // 获取 tooltip 元素的位置信息
      const tooltip = page.locator('.el-popper, [role="tooltip"], .el-tooltip__popper, [class*="tooltip"]');
      const tooltipCount = await tooltip.count();
      console.log(`找到 ${tooltipCount} 个 tooltip 元素`);

      if (tooltipCount > 0) {
        const tooltipBox = await tooltip.first().boundingBox();
        const cellBox = await apiAddressCell.boundingBox();

        console.log('\n=== Tooltip 位置分析 ===');
        console.log(`Tooltip 位置: x=${tooltipBox?.x}, y=${tooltipBox?.y}, width=${tooltipBox?.width}, height=${tooltipBox?.height}`);
        console.log(`单元格位置: x=${cellBox?.x}, y=${cellBox?.y}, width=${cellBox?.width}, height=${cellBox?.height}`);

        if (tooltipBox && cellBox) {
          const tooltipBottom = tooltipBox.y + tooltipBox.height;
          const viewportHeight = page.viewportSize()?.height || 800;

          if (tooltipBox.y > cellBox.y) {
            console.log('\n✓ Tooltip 显示在单元格下方（正常）');
            console.log(`  单元格顶部 Y 坐标: ${cellBox.y}`);
            console.log(`  单元格底部 Y 坐标: ${cellBox.y + cellBox.height}`);
            console.log(`  Tooltip 顶部 Y 坐标: ${tooltipBox.y}`);
            console.log(`  Tooltip 底部 Y 坐标: ${tooltipBottom}`);
            console.log(`  浏览器窗口高度: ${viewportHeight}`);

            if (tooltipBottom > viewportHeight) {
              console.log(`\n⚠ 警告: Tooltip 底部 (${tooltipBottom}) 超出浏览器窗口 (${viewportHeight})`);
              console.log('  超出像素:', tooltipBottom - viewportHeight);
            } else {
              console.log('\n✓ Tooltip 完全在浏览器可视区域内');
            }
          } else {
            console.log('\n✗ Tooltip 显示在单元格上方（可能超出浏览器顶部）');
            console.log(`  单元格顶部 Y 坐标: ${cellBox.y}`);
            console.log(`  Tooltip 顶部 Y 坐标: ${tooltipBox.y}`);
            console.log(`  Tooltip 向上显示 ${cellBox.y - tooltipBox.y} 像素`);
          }

          // 额外分析：检查 tooltip 是否在视口内
          const isFullyVisible = tooltipBox.y >= 0 && tooltipBottom <= viewportHeight;
          if (!isFullyVisible) {
            console.log('\n✗ Tooltip 不完全可见！');
          }
        }

        // 获取 tooltip 文本内容
        const tooltipText = await tooltip.first().textContent();
        console.log(`\nTooltip 内容: "${tooltipText?.trim()}"`);
      } else {
        console.log('未找到 tooltip 元素，可能需要更多时间加载或选择器不正确');
        await page.screenshot({ path: `${SCREENSHOT_DIR}/05-no-tooltip.png` });
      }
    } else {
      console.log('未找到 API 地址列，尝试悬停在所有单元格上...');
      for (let i = 0; i < Math.min(cellCount, 5); i++) {
        const cell = firstRowCells.nth(i);
        const text = await cell.textContent() || '';
        console.log(`\n悬停在单元格 ${i}: "${text.trim()}"`);
        await cell.hover();
        await page.waitForTimeout(800);
        await page.screenshot({ path: `${SCREENSHOT_DIR}/06-cell-${i}-hover.png` });
      }
    }

    console.log('\n=== 测试完成 ===');
    console.log('截图保存在:', SCREENSHOT_DIR);

    // 保持浏览器打开一段时间供查看
    console.log('\n浏览器将保持打开 10 秒供查看...');
    await page.waitForTimeout(10000);

  } catch (error) {
    console.error('测试失败:', error);
    await page.screenshot({ path: `${SCREENSHOT_DIR}/error.png` });
  } finally {
    await browser.close();
  }
}

main();