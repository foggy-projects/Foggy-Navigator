import { test, expect } from '@playwright/test';

test.describe('Tooltip Position Test', () => {
  test('verify tooltip displays below overflowed table cells', async ({ page }) => {
    page.setViewportSize({ width: 1280, height: 800 });

    // 访问页面
    console.log('访问 http://localhost:5174...');
    await page.goto('http://localhost:5174', { waitUntil: 'networkidle' });
    await page.screenshot({ path: 'screenshots/01-homepage.png' });

    // 检查是否需要登录
    const hasLogin = await page.locator('input[type="text"]').count() > 0;
    if (hasLogin) {
      console.log('检测到登录页面，执行登录...');
      await page.locator('input[type="text"]').first().fill('root');
      await page.fill('input[type="password"]', 'root123');
      await page.click('button[type="submit"], .el-button--primary');
      await page.waitForLoadState('networkidle');
      await page.screenshot({ path: 'screenshots/02-logged-in.png' });
      console.log('登录成功');
    }

    // 直接导航到设置页面（使用 hash 路由）
    console.log('导航到设置页面...');
    await page.goto('http://localhost:5174/#/settings', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: 'screenshots/03-settings-page.png' });
    console.log('已进入设置页面');

    // 获取所有标签页
    const tabs = page.locator('.el-tabs__item');
    const tabCount = await tabs.count();
    console.log(`找到 ${tabCount} 个标签页`);

    for (let i = 0; i < tabCount; i++) {
      const tabText = await tabs.nth(i).textContent();
      console.log(`标签 ${i}: "${tabText?.trim()}"`);
    }

    // 切换到 AI 模型标签
    console.log('\n切换到 AI 模型标签...');
    const aiModelTab = page.locator('.el-tabs__item').filter({ hasText: 'AI 模型' });
    if (await aiModelTab.count() > 0) {
      await aiModelTab.click();
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'screenshots/04-ai-models-tab.png' });
      console.log('已切换到 AI 模型标签');
    }

    // 等待表格加载（使用更宽松的等待条件）
    console.log('检查表格状态...');
    await page.waitForTimeout(1000);

    // 检查是否有数据
    const hasData = await page.locator('.el-table__body tbody tr').count() > 0;
    console.log(`表格有数据: ${hasData}`);

    if (hasData) {
      // 获取第一行的所有单元格
      const firstRowCells = page.locator('.el-table__body tr:first-child .el-table__cell');
      const cellCount = await firstRowCells.count();
      console.log(`\n第一行有 ${cellCount} 个单元格`);

      // 找到 API 地址列
      let apiAddressCellIndex = -1;
      let apiAddressText = '';

      for (let i = 0; i < cellCount; i++) {
        const cell = firstRowCells.nth(i);
        const text = await cell.textContent() || '';
        console.log(`单元格 ${i}: "${text.trim()}"`);

        if (text.includes('http://') || text.includes('https://')) {
          apiAddressCellIndex = i;
          apiAddressText = text.trim();
          console.log(`找到 API 地址列: 索引 ${i}`);
          break;
        }
      }

      if (apiAddressCellIndex >= 0) {
        const apiAddressCell = firstRowCells.nth(apiAddressCellIndex);

        console.log(`\nAPI 地址: "${apiAddressText}"`);
        console.log('悬停在 API 地址单元格上...');

        // 滚动到单元格可见位置
        await apiAddressCell.scrollIntoViewIfNeeded();
        await page.waitForTimeout(300);

        // 悬停
        await apiAddressCell.hover();
        await page.waitForTimeout(1000); // 等待 tooltip 动画

        // 截图显示 tooltip
        await page.screenshot({ path: 'screenshots/05-tooltip-hovered.png' });
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

            console.log('\n=== 验证结果 ===');

            // 验证 tooltip 在单元格下方
            if (tooltipBox.y > cellBox.y) {
              console.log('✓ PASS: Tooltip 显示在单元格下方');
              console.log(`  单元格顶部 Y 坐标: ${cellBox.y}`);
              console.log(`  单元格底部 Y 坐标: ${cellBox.y + cellBox.height}`);
              console.log(`  Tooltip 顶部 Y 坐标: ${tooltipBox.y}`);
              console.log(`  Tooltip 底部 Y 坐标: ${tooltipBottom}`);
              console.log(`  浏览器窗口高度: ${viewportHeight}`);

              // 验证 tooltip 不超出视口
              if (tooltipBottom <= viewportHeight) {
                console.log('✓ PASS: Tooltip 完全在浏览器可视区域内');
              } else {
                console.log(`✗ FAIL: Tooltip 超出浏览器底部 (${tooltipBottom} > ${viewportHeight})`);
                console.log('  超出像素:', tooltipBottom - viewportHeight);
              }
            } else {
              console.log('✗ FAIL: Tooltip 显示在单元格上方（应该向下显示）');
              console.log(`  单元格顶部 Y 坐标: ${cellBox.y}`);
              console.log(`  Tooltip 顶部 Y 坐标: ${tooltipBox.y}`);
              console.log(`  Tooltip 向上显示 ${cellBox.y - tooltipBox.y} 像素`);
            }
          }

          // 获取 tooltip 文本内容
          const tooltipText = await tooltip.first().textContent();
          console.log(`\nTooltip 内容: "${tooltipText?.trim()}"`);

          // 测试断言
          if (tooltipBox && cellBox) {
            // Tooltip 应该在单元格下方
            expect(tooltipBox.y).toBeGreaterThan(cellBox.y);
          }
        } else {
          console.log('未找到 tooltip 元素');
          await page.screenshot({ path: 'screenshots/05-no-tooltip.png' });
        }
      } else {
        console.log('未找到 API 地址列');
      }
    } else {
      console.log('表格没有数据，无法测试 tooltip');
      console.log('请先在应用中添加一些 AI 模型配置');
    }

    console.log('\n测试完成');
  });

  test('verify tooltip with Claude Workers tab', async ({ page }) => {
    page.setViewportSize({ width: 1280, height: 800 });

    // 登录
    await page.goto('http://localhost:5174', { waitUntil: 'networkidle' });
    const hasLogin = await page.locator('input[type="text"]').count() > 0;
    if (hasLogin) {
      await page.locator('input[type="text"]').first().fill('root');
      await page.fill('input[type="password"]', 'root123');
      await page.click('button[type="submit"], .el-button--primary');
      await page.waitForLoadState('networkidle');
    }

    // 直接导航到设置页面
    await page.goto('http://localhost:5174/#/settings', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);

    // 切换到 Claude Workers 标签
    console.log('切换到 Claude Workers 标签...');
    const workersTab = page.locator('.el-tabs__item').filter({ hasText: 'Claude Workers' });
    if (await workersTab.count() > 0) {
      await workersTab.click();
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'screenshots/06-workers-tab.png' });

      // 检查是否有数据
      const hasData = await page.locator('.el-table__body tbody tr').count() > 0;

      if (hasData) {
        // 获取第一行的单元格（地址列）
        const workerCells = page.locator('.el-table__body tr:first-child .el-table__cell');
        const workerCellCount = await workerCells.count();

        console.log(`Workers 表格第一行有 ${workerCellCount} 个单元格`);

        // 找到地址列
        let addressCellIndex = -1;
        for (let i = 0; i < workerCellCount; i++) {
          const text = await workerCells.nth(i).textContent() || '';
          console.log(`单元格 ${i}: "${text.trim()}"`);
          if (text.includes('http://') || text.includes('https://')) {
            addressCellIndex = i;
            console.log(`找到 Workers 地址列: 索引 ${i}`);
            break;
          }
        }

        if (addressCellIndex >= 0) {
          const addressCell = workerCells.nth(addressCellIndex);
          console.log('\n悬停在 Workers 地址单元格上...');
          await addressCell.scrollIntoViewIfNeeded();
          await page.waitForTimeout(300);
          await addressCell.hover();
          await page.waitForTimeout(1000);
          await page.screenshot({ path: 'screenshots/07-workers-tooltip.png' });

          // 获取 tooltip 位置
          const tooltip = page.locator('.el-popper, [role="tooltip"]');
          if (await tooltip.count() > 0) {
            const tooltipBox = await tooltip.first().boundingBox();
            const cellBox = await addressCell.boundingBox();

            if (tooltipBox && cellBox) {
              console.log('\n=== Workers Tab Tooltip 位置分析 ===');
              console.log(`Tooltip 位置: x=${tooltipBox.x}, y=${tooltipBox.y}`);
              console.log(`单元格位置: x=${cellBox.x}, y=${cellBox.y}`);

              if (tooltipBox.y > cellBox.y) {
                console.log('✓ Workers Tab: Tooltip 显示在单元格下方');
              } else {
                console.log('✗ Workers Tab: Tooltip 显示在单元格上方');
              }
            }
          }
        } else {
          console.log('Workers 表格中没有地址数据');
        }
      } else {
        console.log('Workers 表格没有数据');
      }
    } else {
      console.log('未找到 Claude Workers 标签页');
    }

    console.log('\nWorkers Tab 测试完成');
  });

  test('verify tooltip with smaller viewport', async ({ page }) => {
    // 使用更小的视口来测试边界情况
    page.setViewportSize({ width: 1280, height: 600 });

    // 登录
    await page.goto('http://localhost:5174', { waitUntil: 'networkidle' });
    const hasLogin = await page.locator('input[type="text"]').count() > 0;
    if (hasLogin) {
      await page.locator('input[type="text"]').first().fill('root');
      await page.fill('input[type="password"]', 'root123');
      await page.click('button[type="submit"], .el-button--primary');
      await page.waitForLoadState('networkidle');
    }

    // 直接导航到设置页面
    await page.goto('http://localhost:5174/#/settings', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);

    // 切换到 AI 模型标签
    const aiModelTab = page.locator('.el-tabs__item').filter({ hasText: 'AI 模型' });
    if (await aiModelTab.count() > 0) {
      await aiModelTab.click();
      await page.waitForTimeout(1000);
    }

    // 检查是否有数据
    const hasData = await page.locator('.el-table__body tbody tr').count() > 0;

    if (hasData) {
      // 获取第一行的所有单元格
      const firstRowCells = page.locator('.el-table__body tr:first-child .el-table__cell');
      const cellCount = await firstRowCells.count();

      // 找到 API 地址列
      let apiAddressCellIndex = -1;
      for (let i = 0; i < cellCount; i++) {
        const text = await firstRowCells.nth(i).textContent() || '';
        if (text.includes('http://') || text.includes('https://')) {
          apiAddressCellIndex = i;
          break;
        }
      }

      if (apiAddressCellIndex >= 0) {
        const apiAddressCell = firstRowCells.nth(apiAddressCellIndex);

        // 悬停在单元格上
        await apiAddressCell.scrollIntoViewIfNeeded();
        await page.waitForTimeout(300);
        await apiAddressCell.hover();
        await page.waitForTimeout(1000);

        // 截图
        await page.screenshot({ path: 'screenshots/08-tooltip-600vh.png' });

        // 获取 tooltip 位置
        const tooltip = page.locator('.el-popper, [role="tooltip"]');
        if (await tooltip.count() > 0) {
          const tooltipBox = await tooltip.first().boundingBox();
          const cellBox = await apiAddressCell.boundingBox();
          const viewportHeight = page.viewportSize()?.height || 600;

          console.log('\n=== 600px 视口 Tooltip 位置分析 ===');
          if (tooltipBox && cellBox) {
            const tooltipBottom = tooltipBox.y + tooltipBox.height;
            console.log(`Tooltip Y: ${tooltipBox.y} - ${tooltipBottom}`);
            console.log(`单元格 Y: ${cellBox.y} - ${cellBox.y + cellBox.height}`);
            console.log(`视口高度: ${viewportHeight}`);

            if (tooltipBox.y > cellBox.y) {
              console.log('✓ Tooltip 显示在单元格下方');
              if (tooltipBottom > viewportHeight) {
                console.log(`⚠ Tooltip 超出视口底部 (${tooltipBottom - viewportHeight}px)`);
              } else {
                console.log('✓ Tooltip 在视口内');
              }
            } else {
              console.log('✗ Tooltip 显示在单元格上方');
            }
          }
        }
      }
    } else {
      console.log('表格没有数据，无法测试 tooltip');
    }

    console.log('\n小视口测试完成');
  });
});