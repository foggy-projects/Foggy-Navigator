# 跨项目任务 — 手动测试方案

## 测试目标

验证跨项目任务（Cross-Project Task）从创建到完成的完整生命周期：
Agent 注册 → 目录绑定 → 任务创建 → 启动 → Phase 完成 → 审查 → 推进 → 任务完成

## 前置条件

| 条件 | 检查方式 |
|------|---------|
| 后端运行中 (8112) | `curl http://localhost:8112/actuator/health` |
| 前端运行中 (5174) | 浏览器打开 `http://localhost:5174` |
| Claude Worker 运行中 (3031) | `curl http://localhost:3031/health` |
| Worker 已注册且 ONLINE | Workers 页面可见至少 1 个 ONLINE Worker |
| Worker 下已有 ≥2 个工作目录 | Workers 页左侧树展开可见目录 |
| 已登录 (root/root123) | 能正常访问页面 |

---

## 测试场景 1：Agent 注册 + 目录绑定

### 1.1 注册 Agent

**步骤**：
1. 进入 Workers 页面 (`/workers`)
2. 左侧树选中一个 ONLINE 的 Worker（不选目录）
3. 中间面板找到 **Coding Agents** 区域，确认显示"暂无 Agent，点击上方注册"
4. 点击 **[+ 注册 Agent]**
5. 填写表单：
   - 名称：`test-api-agent`
   - 描述：`负责 API 层开发`
   - 默认工作目录：选择 Worker 下的一个目录（如 `foggy-navigator`）
   - 默认分支：留空
6. 点击 **注册**

**预期结果**：
- [x] 弹窗关闭，提示"Agent 注册成功"
- [x] Coding Agents 区域出现 `test-api-agent` 卡片
- [x] 卡片显示：目录名、"0 个授权目录"

### 1.2 注册第二个 Agent

**步骤**：
1. 再次点击 **[+ 注册 Agent]**
2. 填写表单：
   - 名称：`test-frontend-agent`
   - 描述：`负责前端开发`
   - 默认工作目录：选择另一个目录
   - 默认分支：留空
3. 点击 **注册**

**预期结果**：
- [x] 列表现在有 2 个 Agent 卡片

### 1.3 目录绑定

**步骤**：
1. 在 `test-api-agent` 卡片上点击 **[目录]** 按钮
2. 弹窗显示"暂无已绑定目录"
3. 下方 Select 选择一个目录 → 点击 **绑定**
4. 再绑定第 2 个目录
5. 验证列表刷新，显示 2 个已绑定目录
6. 对其中一个点击 **[解绑]** → 确认仅剩 1 个
7. 关闭弹窗

**预期结果**：
- [x] 绑定/解绑操作即时生效
- [x] 卡片上"N 个授权目录"数量同步更新

### 1.4 编辑 Agent

**步骤**：
1. 在 `test-api-agent` 卡片上点击 **[编辑]**
2. 修改描述为：`API + 数据库层开发`
3. 点击 **保存**

**预期结果**：
- [x] 卡片描述更新

---

## 测试场景 2：创建跨项目任务

### 2.1 进入跨项目任务页面

**步骤**：
1. 浏览器访问 `http://localhost:5174/#/cross-tasks`
2. 左侧应显示"暂无跨项目任务"

### 2.2 创建 2 阶段任务

**步骤**：
1. 点击左上角 **[+ 创建]**
2. 填写表单：

| 字段 | 值 |
|------|---|
| 标题 | `添加用户注册功能` |
| 描述 | `先实现后端 API，再实现前端页面` |

**阶段 0**：

| 字段 | 值 |
|------|---|
| 名称 | `后端 API 实现` |
| Agent | 选择 `test-api-agent` |
| 目录 | （留空，使用 Agent 默认目录） |
| 任务 | `在项目中添加 POST /api/v1/users/register 接口。要求：接收 username + password，返回 userId。写一个简单的内存实现即可，不需要数据库。完成后输出交接信息，说明新增了哪些文件和接口路径。` |
| 分支 | `feat/user-register-api` |

3. 点击 **[+ 添加阶段]**

**阶段 1**：

| 字段 | 值 |
|------|---|
| 名称 | `前端页面实现` |
| Agent | 选择 `test-frontend-agent` |
| 目录 | （留空，使用 Agent 默认目录） |
| 任务 | `根据上游阶段提供的 API 信息，创建一个简单的用户注册页面。包含用户名和密码输入框，以及提交按钮。调用上游提供的 API 接口。完成后输出交接信息。` |
| 分支 | `feat/user-register-ui` |

4. 点击 **创建**

**预期结果**：
- [x] 弹窗关闭，提示"跨项目任务已创建"
- [x] 左侧列表出现任务，状态标签 **DRAFT**
- [x] 右侧详情显示 2 个阶段，均为 **PENDING** 状态
- [x] 阶段 0 显示：📁 目录名、🤖 test-api-agent、🌿 feat/user-register-api
- [x] 阶段 1 显示：📁 目录名、🤖 test-frontend-agent、🌿 feat/user-register-ui

---

## 测试场景 3：启动任务 + Phase 0 执行

### 3.1 启动任务

**步骤**：
1. 在任务详情页，点击 **[开始执行]** 按钮
2. 等待状态变化

**预期结果**：
- [x] 任务状态变为 **RUNNING**
- [x] 阶段 0 状态变为 **RUNNING**
- [x] 阶段 0 产生 worktree 目录（分支 `feat/user-register-api`）

### 3.2 等待 Phase 0 完成

**步骤**：
1. 等待 Claude Worker 执行任务（可在 Workers 页面观察任务状态）
2. 阶段 0 执行完毕后，刷新跨项目任务页面

**预期结果**：
- [x] 阶段 0 状态变为 **AWAITING_REVIEW**
- [x] 任务状态变为 **PAUSED**
- [x] 阶段 0 出现"交接信息"区域（可能显示 Claude 生成的 handoff 内容）
- [x] 显示费用和耗时信息
- [x] "查看 Phase 会话"链接可点击

### 3.3 检查 SSE 通知

**预期结果**：
- [x] 如果初始会话存在，ChatView 会收到 TASK_COMPLETED 通知卡片

---

## 测试场景 4：审查 + 编辑交接 + 推进

### 4.1 审查当前阶段

**步骤**：
1. 点击 **[审查当前阶段]** 按钮

**预期结果**：
- [x] 页面跳转到初始会话 (ChatView)
- [x] 会话中出现一条 review 消息，包含阶段完成摘要
- [x] Claude 可以在初始会话中进一步讨论

### 4.2 编辑交接信息（可选）

**步骤**：
1. 返回 `/cross-tasks` 页面
2. 在阶段 0 的交接信息区域，点击 **[编辑交接]**
3. 修改或补充交接内容，例如追加："注意：接口返回的 userId 格式为 UUID"
4. 点击 **保存**

**预期结果**：
- [x] 交接信息更新成功
- [x] 显示区域反映新内容

### 4.3 推进到下一阶段

**步骤**：
1. 确认阶段 0 有交接信息（非空）
2. 点击 **[发送到下一阶段]**
3. 确认弹窗点击"确定"

**预期结果**：
- [x] 阶段 0 状态变为 **COMPLETED**
- [x] 阶段 1 状态变为 **RUNNING**
- [x] 任务状态变为 **RUNNING**
- [x] 阶段 1 的 prompt 中自动注入了阶段 0 的交接信息

---

## 测试场景 5：Phase 1 完成 → 任务完成

### 5.1 等待 Phase 1 完成

**步骤**：
1. 等待 Claude Worker 执行阶段 1 任务
2. 完成后刷新页面

**预期结果**：
- [x] 阶段 1 状态变为 **AWAITING_REVIEW**
- [x] 任务状态变为 **PAUSED**

### 5.2 最终推进

**步骤**：
1. （可选）审查阶段 1 + 编辑交接
2. 点击 **[发送到下一阶段]**

**预期结果**：
- [x] 阶段 1 状态变为 **COMPLETED**
- [x] 任务状态变为 **COMPLETED**（因为是最后一个阶段）
- [x] 显示总成本（totalCostUsd）
- [x] "开始执行"按钮消失
- [x] "取消"按钮消失

---

## 测试场景 6：异常流程

### 6.1 取消任务

**步骤**：
1. 创建一个新的跨项目任务（DRAFT 状态）
2. 点击 **[取消]** → 确认

**预期结果**：
- [x] 任务状态变为 **CANCELLED**
- [x] 所有 PENDING 阶段变为 **SKIPPED**

### 6.2 Phase 失败

**步骤**：
1. 创建一个任务，阶段 prompt 故意写"不可能完成的任务：立即返回错误"
2. 启动任务
3. 等待 Claude Worker 任务失败（或手动在 Workers 页面中止）

**预期结果**：
- [x] 阶段状态变为 **FAILED**
- [x] 任务状态变为 **PAUSED**（等待人工介入）

### 6.3 创建时使用直接指定目录（不选 Agent）

**步骤**：
1. 创建任务时，某阶段不选 Agent，只选目录
2. 创建并启动

**预期结果**：
- [x] 系统从目录自动推导 workerId
- [x] 阶段正常执行

---

## 测试场景 7：数据验证（API 层）

可以用 curl 直接调用 API 辅助验证。

### 7.1 创建任务 API

```bash
curl -X POST http://localhost:8112/api/v1/cross-project-tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "title": "API 测试任务",
    "description": "通过 API 创建的测试任务",
    "phases": [
      {
        "phaseName": "阶段A",
        "prompt": "echo hello",
        "agentId": "<agent-id>",
        "worktreeBranch": "test/phase-a"
      },
      {
        "phaseName": "阶段B",
        "prompt": "echo world",
        "agentId": "<agent-id>"
      }
    ]
  }'
```

### 7.2 查询任务详情 API

```bash
curl http://localhost:8112/api/v1/cross-project-tasks/<contextId> \
  -H "Authorization: Bearer <token>"
```

### 7.3 分页列表 API

```bash
curl "http://localhost:8112/api/v1/cross-project-tasks/page?page=0&size=20" \
  -H "Authorization: Bearer <token>"
```

---

## 简化流程图

```
注册 Agent x2 (Workers 页)
    ↓
绑定目录 (Workers 页)
    ↓
创建跨项目任务 (/cross-tasks)
  ├ 阶段0: test-api-agent → feat/user-register-api
  └ 阶段1: test-frontend-agent → feat/user-register-ui
    ↓
开始执行 → Phase 0 RUNNING
    ↓
Phase 0 完成 → AWAITING_REVIEW, 任务 PAUSED
    ↓
审查 + 编辑交接 + 推进
    ↓
Phase 1 RUNNING
    ↓
Phase 1 完成 → AWAITING_REVIEW, 任务 PAUSED
    ↓
推进 → 任务 COMPLETED
```

## 测试场景 8：NAVIGATOR_API_KEY 注入 + 个人技能

验证任务派发时自动注入 `NAVIGATOR_API_KEY` 环境变量，以及 Claude Code 个人技能能通过该变量调用 Navigator API。

### 前置条件

| 条件 | 检查方式 |
|------|---------|
| 用户有有效 API Key | Settings 页面查看或 `curl http://localhost:8112/api/v1/users/<userId>/api-keys -H "Authorization: Bearer <token>"` |
| 个人技能已就位 | `ls ~/.claude/skills/cross-project-task/SKILL.md` |

如果没有 API Key，先创建一个：
```bash
curl -X POST "http://localhost:8112/api/v1/users/<userId>/api-keys" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"claude-code-skill"}'
```

### 8.1 验证环境变量注入

**步骤**：
1. 重启后端 (`start-launcher.ps1`) 和 Worker (`tools/claude-agent-worker/start.ps1`)
2. 在 Workers 页面创建一个新任务，prompt 为：
   ```
   运行命令 echo $NAVIGATOR_API_KEY | head -c 10，告诉我输出结果（只需要前 10 个字符）
   ```
3. 等待任务完成

**预期结果**：
- [ ] Worker 日志中可见 `navigator_api_key` 字段（非 null）
- [ ] Claude Code 输出中显示 API Key 的前缀（如 `fnk-` 开头）
- [ ] 证明环境变量已成功注入到 CLI 子进程

### 8.2 验证技能调用 Navigator API

**步骤**：
1. 创建一个新任务，prompt 为：
   ```
   使用 cross-project-task 技能，列出当前可用的 Coding Agent。
   ```
2. 等待任务完成

**预期结果**：
- [ ] Claude Code 调用 `curl http://localhost:8112/api/v1/coding-agents -H "X-API-Key: $NAVIGATOR_API_KEY"`
- [ ] 返回 200，得到 Agent 列表（至少包含之前注册的 Agent）
- [ ] Claude Code 向用户展示了 Agent 名称和描述

### 8.3 通过技能创建跨项目任务（端到端）

**步骤**：
1. 创建一个新任务，prompt 为：
   ```
   使用 cross-project-task 技能，创建一个跨项目任务：
   - 标题：技能测试任务
   - 描述：验证 Claude Code 个人技能能否编排跨项目任务
   - 阶段1：让 test-api-agent 在默认目录创建一个 hello.txt 文件
   先列出可用 Agent，然后创建并启动任务。
   ```
2. 等待任务完成

**预期结果**：
- [ ] Claude Code 先调用 `GET /api/v1/coding-agents` 获取 Agent 列表
- [ ] 然后调用 `POST /api/v1/cross-project-tasks` 创建任务
- [ ] 再调用 `POST /api/v1/cross-project-tasks/{contextId}/start` 启动任务
- [ ] 在 `/cross-tasks` 页面可以看到新创建的任务

### 8.4 API Key 缺失时的降级处理

**步骤**：
1. 临时禁用用户所有 API Key（Settings 页面或 API 撤销）
2. 创建新任务，prompt 同 8.2
3. 观察 Claude Code 行为

**预期结果**：
- [ ] `NAVIGATOR_API_KEY` 环境变量不存在
- [ ] Claude Code 技能检测到环境变量缺失，给出友好提示
- [ ] 不会因为 API 401 而崩溃

---

## 测试场景 9：数据流完整性验证（API 层 curl）

快速验证 NAVIGATOR_API_KEY 链路各节点。

### 9.1 直接调用 Worker API 验证透传

```bash
# 发送一个带 navigator_api_key 的查询请求给 Worker
curl -N -X POST http://localhost:3031/api/v1/query \
  -H "Authorization: Bearer <worker-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "运行命令 echo $NAVIGATOR_API_KEY 并告诉我结果",
    "cwd": "<project-path>",
    "navigator_api_key": "test-key-12345"
  }'
```

**预期结果**：
- [ ] Claude Code 输出包含 `test-key-12345`
- [ ] 证明 Worker 正确将 `navigator_api_key` 注入到 `NAVIGATOR_API_KEY` 环境变量

### 9.2 验证 API Key 认证有效

```bash
# 使用有效的 API Key 调用 Agent 列表接口
curl -s http://localhost:8112/api/v1/coding-agents \
  -H "X-API-Key: <your-api-key>"
```

**预期结果**：
- [ ] 返回 200，JSON 数组包含已注册的 Agent

---

## 注意事项

1. **Worktree 创建**：每个阶段启动时会自动创建 git worktree。如果目录本身不是 git 仓库，worktree 创建会失败。确保测试目录是有效的 git 仓库。
2. **交接信息**：Claude 生成的交接信息取决于 prompt 中的指令。如果 Agent 没有按预期生成交接信息，可手动编辑。
3. **权限模式**：跨项目任务中 Phase 创建的 ClaudeTask 使用 `bypassPermissions` 模式。
4. **费用追踪**：需要 Worker 端报告 token 用量，费用才会在阶段和任务上显示。
5. **初始会话**：triggerReview 需要 initialSessionId，如果从 UI 创建的任务可能没有 initialSessionId（UI 当前未传入），此功能需要通过 Tutor Agent 对话触发创建才有。
6. **NAVIGATOR_API_KEY**：该环境变量仅在用户有有效 API Key 时注入。如果用户没有 API Key 或所有 Key 已过期/禁用，则不注入（传 null）。个人技能应检测变量是否存在并给出友好提示。
