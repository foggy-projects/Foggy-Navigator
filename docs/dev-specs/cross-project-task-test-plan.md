# 跨项目任务 — 测试方案

## 测试目标

验证通过 Claude Code 个人技能（`cross-project-task`）编排跨项目任务的完整生命周期：
Agent 注册 → 目录绑定 → Claude Code 发现 Agent → 创建任务 → 启动 → Phase 执行 → 审查推进 → 任务完成

## 前置条件

| 条件 | 检查方式 |
|------|---------|
| 后端运行中 (8112) | `curl http://localhost:8112/actuator/health` |
| 前端运行中 (5174) | 浏览器打开 `http://localhost:5174` |
| Claude Worker 运行中 (3031) | `curl http://localhost:3031/health` |
| Worker 已注册且 ONLINE | Workers 页面可见至少 1 个 ONLINE Worker |
| Worker 下已有 ≥2 个工作目录 | Workers 页左侧树展开可见目录 |
| 已注册 ≥2 个 Coding Agent | Workers 页 Coding Agents 区域可见 |
| 个人技能已就位 | `ls ~/.claude/skills/cross-project-task/SKILL.md` |
| 已登录 (root/root123) | 能正常访问页面 |

> **注意**：`NAVIGATOR_TOKEN` 由后端派发任务时自动生成（JWT），无需用户手动配置。

---

## 测试场景 1：Agent 注册 + 目录绑定（UI 操作）

### 1.1 注册 Agent

**步骤**：
1. 进入 Workers 页面 (`/workers`)
2. 左侧树选中一个 ONLINE 的 Worker（不选目录）
3. 中间面板找到 **Coding Agents** 区域，确认显示"暂无 Agent，点击上方注册"
4. 点击 **[+ 注册 Agent]**
5. 填写表单：
   - 名称：`test-api-agent`
   - 描述：`负责 API 层开发`
   - 默认工作目录：选择 Worker 下的一个目录
   - 默认分支：留空
6. 点击 **注册**

**预期结果**：
- [x] 弹窗关闭，提示"Agent 注册成功"
- [x] Coding Agents 区域出现 `test-api-agent` 卡片

### 1.2 注册第二个 Agent

同上流程，注册 `test-frontend-agent`（描述：`负责前端开发`，选另一个目录）。

**预期结果**：
- [x] 列表现在有 2 个 Agent 卡片

---

## 测试场景 2：NAVIGATOR_TOKEN 注入验证

### 2.1 验证环境变量注入

**步骤**：
1. 在 Workers 页面选择一个目录，输入 prompt：
   ```
   运行命令 printenv NAVIGATOR_TOKEN，告诉我输出结果的前 20 个字符
   ```
2. 等待任务完成

**预期结果**：
- [x] Claude Code 输出 JWT token 前缀（`eyJhbGciOiJIUzM4NCJ9...`）
- [x] 证明环境变量已成功注入到 CLI 子进程

### 2.2 验证技能能调通 Navigator API

**步骤**：
1. 创建新任务，prompt：
   ```
   运行命令 curl -s http://localhost:8112/api/v1/coding-agents -H "Authorization: Bearer $NAVIGATOR_TOKEN"，告诉我返回结果
   ```
2. 等待任务完成

**预期结果**：
- [x] 返回 HTTP 200，JSON 包含已注册的 Agent 列表
- [x] 证明 `NAVIGATOR_TOKEN` 可作为有效凭证调用 Navigator API

---

## 测试场景 3：Claude Code 通过技能发现 Agent

**步骤**：
1. 在 Workers 页面选择一个目录，输入 prompt：
   ```
   使用 cross-project-task 技能，列出当前可用的 Coding Agent。
   ```
2. 等待任务完成

**预期结果**：
- [ ] Claude Code 调用 `GET /api/v1/coding-agents`
- [ ] 向用户展示 Agent 名称、描述、默认目录信息
- [ ] 至少包含 `test-api-agent` 和 `test-frontend-agent`

---

## 测试场景 4：Claude Code 通过技能创建 + 启动跨项目任务

这是核心端到端场景，验证 Claude Code 能通过对话完成任务编排。

### 4.1 创建并启动 2 阶段任务

**步骤**：
1. 在 Workers 页面选择一个目录，输入 prompt：
   ```
   使用 cross-project-task 技能，创建一个跨项目任务：
   - 标题：添加用户注册功能
   - 描述：先实现后端 API，再实现前端页面
   - 阶段1：让 test-api-agent 在其默认目录添加 POST /api/v1/users/register 接口，写一个简单的内存实现，完成后输出交接信息
   - 阶段2：让 test-frontend-agent 在其默认目录根据上游 API 信息创建一个简单的用户注册页面，完成后输出交接信息
   先列出可用 Agent 确认，然后创建并启动任务。
   ```
2. 等待 Claude Code 执行

**预期结果**：
- [ ] Claude Code 先调 `GET /api/v1/coding-agents` 获取 Agent 列表
- [ ] 展示 Agent 信息（或直接按 prompt 规划）
- [ ] 调 `POST /api/v1/cross-project-tasks` 创建任务，body 包含 2 个 phases
- [ ] 调 `POST /api/v1/cross-project-tasks/{contextId}/start` 启动任务
- [ ] 返回 contextId 和查看地址 `http://localhost:5174/#/cross-tasks`

### 4.2 在 UI 验证任务已创建

**步骤**：
1. 浏览器访问 `http://localhost:5174/#/cross-tasks`

**预期结果**：
- [ ] 左侧列表出现"添加用户注册功能"任务
- [ ] 状态为 **RUNNING**（已被 Claude Code 启动）
- [ ] 详情显示 2 个阶段，阶段 0 状态为 **RUNNING**

---

## 测试场景 5：Phase 执行 + 审查推进（UI 操作）

### 5.1 等待 Phase 0 完成

**步骤**：
1. 等待 Claude Worker 执行阶段 0 任务
2. 在 `/cross-tasks` 页面刷新

**预期结果**：
- [ ] 阶段 0 状态变为 **AWAITING_REVIEW**
- [ ] 任务状态变为 **PAUSED**
- [ ] 阶段 0 出现"交接信息"区域
- [ ] 显示费用和耗时信息

### 5.2 审查 + 推进到下一阶段

**步骤**：
1. 确认阶段 0 有交接信息
2. 点击 **[发送到下一阶段]** → 确认

**预期结果**：
- [ ] 阶段 0 状态变为 **COMPLETED**
- [ ] 阶段 1 状态变为 **RUNNING**
- [ ] 阶段 1 的 prompt 中自动注入了阶段 0 的交接信息

### 5.3 等待 Phase 1 完成 + 最终推进

**步骤**：
1. 等待阶段 1 完成 → 状态变为 **AWAITING_REVIEW**
2. 点击 **[发送到下一阶段]**

**预期结果**：
- [ ] 阶段 1 状态变为 **COMPLETED**
- [ ] 任务状态变为 **COMPLETED**
- [ ] 显示总成本

---

## 测试场景 6：Claude Code 创建单阶段任务

**步骤**：
1. 在 Workers 页面输入 prompt：
   ```
   使用 cross-project-task 技能，创建一个单阶段任务：
   - 标题：创建 README
   - 描述：为项目添加 README 文件
   - 阶段：让 test-api-agent 在其默认目录创建一个 README.md 文件，内容为项目简介
   创建并启动。
   ```
2. 等待完成

**预期结果**：
- [ ] 创建成功，单阶段任务
- [ ] 在 `/cross-tasks` 页面可见
- [ ] Phase 执行完成后任务直接可推进到 COMPLETED

---

## 测试场景 7：异常流程

### 7.1 取消任务（UI）

**步骤**：
1. 在 `/cross-tasks` 找到一个 DRAFT 或 PAUSED 状态的任务
2. 点击 **[取消]** → 确认

**预期结果**：
- [x] 任务状态变为 **CANCELLED**
- [x] 所有 PENDING 阶段变为 **SKIPPED**

### 7.2 降级处理（非 Navigator 派发场景）

**说明**：如果用户直接在本地运行 Claude Code（不通过 Navigator 派发），`NAVIGATOR_TOKEN` 不存在。

**预期结果**：
- [ ] Claude Code 技能检测到环境变量缺失，给出友好提示
- [ ] 不会因为 API 401 而崩溃

---

## 简化流程图

```
注册 Agent x2 (Workers 页)
    ↓
绑定目录 (Workers 页)
    ↓
在 Workers 页派发任务给 Claude Code，prompt 描述需求
    ↓
Claude Code 通过 cross-project-task 技能:
  1. curl GET /api/v1/coding-agents → 发现 Agent
  2. curl POST /api/v1/cross-project-tasks → 创建任务
  3. curl POST /api/v1/cross-project-tasks/{id}/start → 启动
    ↓
Phase 0 RUNNING (test-api-agent)
    ↓
Phase 0 完成 → AWAITING_REVIEW, 任务 PAUSED
    ↓
人工审查 + 推进 (UI /cross-tasks)
    ↓
Phase 1 RUNNING (test-frontend-agent)
    ↓
Phase 1 完成 → 推进 → 任务 COMPLETED
```

---

## 数据流验证（补充）

### Worker API 透传验证

```bash
curl -N -X POST http://localhost:3031/api/v1/query \
  -H "Authorization: Bearer <worker-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "运行命令 printenv NAVIGATOR_TOKEN 并告诉我结果",
    "cwd": "<project-path>",
    "navigator_api_key": "test-key-12345"
  }'
```

**预期结果**：
- [x] Claude Code 输出包含 `test-key-12345`
- [x] 证明 Worker 正确将 `navigator_api_key` 注入到 `NAVIGATOR_TOKEN` 环境变量

---

## 注意事项

1. **Worktree 创建**：每个阶段启动时会自动创建 git worktree。如果目录本身不是 git 仓库，worktree 创建会失败。确保测试目录是有效的 git 仓库。
2. **交接信息**：Claude 生成的交接信息取决于 prompt 中的指令。如果 Agent 没有按预期生成交接信息，可手动编辑。
3. **权限模式**：跨项目任务中 Phase 创建的 ClaudeTask 使用 `bypassPermissions` 模式。
4. **费用追踪**：需要 Worker 端报告 token 用量，费用才会在阶段和任务上显示。
5. **NAVIGATOR_TOKEN**：该环境变量由后端自动生成（JWT token），在派发任务时注入。只要用户已登录，token 就会自动生成，无需手动配置。技能应检测变量是否存在（未通过 Navigator 派发时不存在）并给出友好提示。
6. **技能触发**：Claude Code 需要在 `~/.claude/skills/cross-project-task/SKILL.md` 存在时才会识别该技能。prompt 中建议明确提及"使用 cross-project-task 技能"以确保触发。
