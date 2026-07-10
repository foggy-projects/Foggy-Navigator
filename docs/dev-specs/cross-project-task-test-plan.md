# 跨项目任务平台能力测试方案

## 测试边界

验证 Navigator 跨项目任务页面和 HTTP API 的生命周期：

`创建 → 启动 → Phase 执行 → 审查/交接 → 推进 → 完成或取消`

`cross-project-task` Agent Skill 已移除。测试不得要求 Claude/Codex 通过 Skill、裸 `@Agent` 或自然语言自动编排跨项目任务；平台 UI/API 仍作为独立功能保留。

## 前置条件

| 条件 | 检查方式 |
|------|---------|
| 后端运行中 | `curl http://localhost:8112/actuator/health` |
| 前端运行中 | 浏览器打开 `http://localhost:5174` |
| Claude Worker 运行中 | `curl http://localhost:3031/health` |
| Worker 已注册且 ONLINE | Workers 页面可见至少一个在线 Worker |
| Worker 下已有两个 Git 工作目录 | Workers 页展开目录树验证 |
| 已注册两个 Coding Agent | 分别绑定到两个工作目录 |
| 已登录 | 能访问 `/cross-tasks` |

## 场景一：Agent 和目录准备

1. 在 Workers 页面注册 `test-api-agent`，绑定后端目录。
2. 注册 `test-frontend-agent`，绑定前端目录。
3. 确认两个 Agent 都有可用的默认模型配置。

预期：两个 Agent 可被跨项目任务表单精确选择，不依赖名称模糊匹配。

## 场景二：通过 UI 创建和启动

1. 打开 `/cross-tasks`。
2. 创建任务“添加用户注册功能”。
3. 添加两个阶段：
   - `test-api-agent` 实现后端注册接口。
   - `test-frontend-agent` 根据交接信息实现注册页面。
4. 保存后启动任务。

预期：

- 任务状态从 `DRAFT` 进入 `RUNNING`。
- 第一阶段进入 `RUNNING`，第二阶段保持 `PENDING`。
- 页面显示稳定的 `contextId` 和阶段顺序。

## 场景三：通过 API 创建和启动

使用登录态 Bearer Token 调用：

```bash
curl -s -X POST http://localhost:8112/api/v1/cross-project-tasks \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @cross-project-task.json
```

请求体包含标题、描述，以及带精确 `agentId`、`directoryId` 和 Prompt 的两个 phases。记录返回的 `contextId`，然后调用：

```bash
curl -s -X POST http://localhost:8112/api/v1/cross-project-tasks/$CONTEXT_ID/start \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN"
```

预期：API 返回成功，UI 能看到同一任务，且不会生成或部署任何 Agent Skill。

## 场景四：Phase 完成、审查和推进

1. 等待当前 Phase 对应的 ClaudeTask 完成。
2. 检查任务进入 `PAUSED` 或待审查状态。
3. 查看结果、成本和交接信息。
4. 通过 UI 或 API 推进下一阶段。

预期：

- 已完成阶段保持 `COMPLETED`。
- 下一阶段获得明确的上游交接上下文并进入 `RUNNING`。
- 最后一阶段推进后任务进入 `COMPLETED`。
- 重复推进请求不会启动重复 Phase。

## 场景五：取消和异常

覆盖以下情况：

- `DRAFT`、`RUNNING`、`PAUSED` 任务取消。
- Agent、Worker 或目录不可用。
- 工作目录不是 Git 仓库，无法创建 worktree。
- Phase 执行失败。
- 非任务所有者访问或修改任务。

预期：状态和错误信息明确，未执行阶段被跳过，权限边界不因 API 直调而放宽。

## 回归检查

- `/cross-tasks` 页面刷新后状态一致。
- 列表分页、详情、阶段顺序和成本汇总正确。
- `CrossProjectTaskControllerTest` 与 `CrossProjectTaskServiceTest` 通过。
- `~/.agents/skills`、`~/.agent/skills` 和 `~/.claude/skills` 中不存在 `cross-project-task/SKILL.md`。
- 普通 Codex/Claude Prompt 不会发现或自动调用跨项目任务 Skill。
