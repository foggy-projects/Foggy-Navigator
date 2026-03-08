---
name: coding-agent-e2e-browser
description: L4 浏览器自动化测试 — 使用 Playwright 测试 coding-agent 前端管理控制台的页面功能和 API 可用性。注意：这是浏览器 E2E 测试（L4），不是 API 集成测试（L3），API 集成测试请使用 /ca-tests。触发词：/test-coding-agent, 提及"浏览器测试 coding-agent"、"coding-agent 前端测试"、"coding-agent E2E"。
allowed-tools: Bash, Read, Write, Edit
---

# Coding Agent 浏览器 E2E 测试（L4）

使用 Playwright 自动化测试 coding-agent 前端管理控制台，验证页面功能和 API 可用性。

> **层级说明**：这是 **L4 浏览器 E2E 测试**，通过真实浏览器操作验证前端 UI。
> - API 级别的 L3 集成测试 → `/ca-tests`（coding-agent-integration-tests）
> - 全局测试规范 → `/tg`（testing-guide）

## 使用场景

当用户需要以下操作时使用：
- 通过浏览器验证 coding-agent 前端是否正常工作
- 测试登录、页面导航、API 调用的 UI 交互
- 生成页面截图用于文档
- 执行自定义浏览器测试场景

## 执行流程

### 1. 启动服务

```bash
cd addons/coding-agent && ./start-and-test.sh start
```

等待服务启动完成（约 20-30 秒）。

### 2. 打开浏览器并登录

```bash
playwright-cli open http://localhost:8112
```

获取登录页面后：
1. 从启动日志中提取自动生成的密码（格式：`Using generated security password: xxx`）
2. 填写登录表单：
   - 用户名：`user`
   - 密码：从日志提取的密码
3. 点击登录按钮

### 3. 执行基础功能测试

按以下顺序测试各页面：

#### a. 系统监控页面
- 验证 URL：`#/dashboard`
- 检查统计卡片是否显示
- 截图：`playwright-cli screenshot`

#### b. 会话管理页面
- 点击"会话管理"菜单
- 验证 URL：`#/conversations`
- 检查"创建会话"按钮和表格
- 截图保存

#### c. 容器管理页面
- 点击"容器管理"菜单
- 验证 URL：`#/containers`
- 检查"刷新"按钮和表格
- 验证无 404 错误
- 截图保存

#### d. 事件日志页面
- 点击"事件日志"菜单
- 验证 URL：`#/events`
- 检查筛选器和查询按钮
- 验证无 404 错误
- 截图保存

### 4. 验证 API 可用性

使用 curl 测试关键 API：

```bash
# 提取密码变量
PASSWORD=$(tail -200 /tmp/coding-agent.log | grep "security password" | awk '{print $NF}')

# 测试容器 API
curl -s http://localhost:8112/api/v1/containers -u user:$PASSWORD

# 测试事件 API
curl -s http://localhost:8112/api/v1/events?limit=10 -u user:$PASSWORD

# 测试会话 API
curl -s http://localhost:8112/api/v1/conversations -u user:$PASSWORD
```

所有 API 应返回 JSON 数组（可能为空 `[]`）。

### 5. 清理资源

```bash
cd addons/coding-agent && ./start-and-test.sh stop
```

这将：
- 关闭 playwright 会话
- 停止后端服务
- 停止前端开发服务器（如果运行）

### 6. 生成测试报告

总结测试结果：
- ✅ 成功项
- ❌ 失败项
- ⚠️ 警告项
- 📊 性能指标（如页面加载时间）
- 📸 截图路径

## 输入要求

用户可以提供：
- **测试模式**（可选）：
  - `basic` - 基础功能测试（默认）
  - `full` - 完整功能测试（包括创建会话等）
  - `screenshot` - 仅生成截图
  - `api` - 仅测试 API
- **自定义测试步骤**（可选）：特定的交互或验证步骤

## 输出格式

```markdown
## Coding Agent 测试报告

**测试时间**: {timestamp}
**测试环境**: http://localhost:8112

### 测试结果

#### 页面功能
- [✅/❌] 登录页面
- [✅/❌] 系统监控
- [✅/❌] 会话管理
- [✅/❌] 容器管理
- [✅/❌] 事件日志

#### API 可用性
- [✅/❌] GET /api/v1/containers
- [✅/❌] GET /api/v1/events
- [✅/❌] GET /api/v1/conversations

#### 错误/警告
- {错误描述}

#### 截图
- Dashboard: `.playwright-cli/page-xxx.png`
- Containers: `.playwright-cli/page-xxx.png`
- Events: `.playwright-cli/page-xxx.png`

### 建议
- {改进建议}
```

## 约束条件

- 服务必须在 `localhost:8112` 运行
- 使用 `application-docker.yml` 配置启动
- 测试前必须确保服务完全启动
- 所有操作需要在 `D:\foggy-projects\Foggy-Navigator` 目录下执行
- 测试完成后必须清理所有资源（浏览器、后端服务、前端服务）

## 决策规则

- 如果服务未启动 → 先执行启动脚本
- 如果端口 8112 被占用 → 先停止旧服务
- 如果登录失败 → 检查日志中的密码是否正确
- 如果出现 404 错误 → 立即报告 API 未实现
- 如果浏览器无法打开 → 检查 playwright-cli 是否安装
- 如果测试被中断 → 执行清理步骤
- 如果用户要求自定义测试 → 在基础测试后追加自定义步骤

## 故障排查

### 服务启动失败
1. 检查 MySQL 是否运行（端口 13309）
2. 检查 `OPENAI_API_KEY` 配置
3. 查看完整启动日志

### 页面加载缓慢
1. 等待至少 30 秒让 JPA 初始化完成
2. 检查数据库连接状态

### API 返回 404
1. 验证 Controller 是否正确注册
2. 检查 `@RequestMapping` 路径
3. 查看 Spring Boot 启动日志中的映射信息
