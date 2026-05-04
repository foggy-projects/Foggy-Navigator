# 最小接入 Checklist

## 文档作用

- doc_type: integration-checklist
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-04
- intended_for: upstream-backend-developer | upstream-frontend-developer | platform-admin
- purpose: 为上游提供从零到一的最小接入检查清单

## Checklist

### Phase 1：平台初始化（平台管理员）

| # | 步骤 | API / 工具 | SDK 状态 |
| --- | --- | --- | --- |
| 1 | 注册第三方系统 | `NavigatorClient.register()` | ✅ SDK 已有 |
| 2 | 签发 Provisioning Credential | `POST /api/v1/admin/client-apps/provisioning-credentials` | ❌ SDK 待补齐 |
| 3 | 创建 Client Application | `POST /api/v1/client-apps` | ❌ SDK 待补齐 |
| 4 | 签发 Runtime Credential | `POST /api/v1/client-apps/{id}/runtime-credentials` | ❌ SDK 待补齐 |
| 5 | 注册 Biz Worker Identity | `POST /api/v1/business-agent/worker-identities` | ❌ SDK 待补齐 |
| 6 | 创建 Biz Worker Pool | `POST /api/v1/business-agent/worker-pools` | ❌ SDK 待补齐 |
| 7 | 将 Worker 加入 Pool | `POST /api/v1/business-agent/worker-pools/{poolId}/members` | ❌ SDK 待补齐 |

### Phase 2：授权配置（平台管理员）

| # | 步骤 | API / 工具 | SDK 状态 |
| --- | --- | --- | --- |
| 8 | 授权 LLM 模型给 ClientApp | `POST /api/v1/client-apps/{id}/model-config-grants` | ❌ SDK 待补齐 |
| 9 | 设置默认模型 | `PUT /api/v1/client-apps/{id}/model-config-grants/{grantId}/default` | ❌ SDK 待补齐 |
| 10 | 创建 Skill | `POST /api/v1/business-agent/skills` | ❌ SDK 待补齐 |
| 11 | 授权 Skill 给 ClientApp | `POST /api/v1/business-agent/client-apps/{id}/skill-grants` | ❌ SDK 待补齐 |
| 12 | 授权 upstream user | `POST /api/v1/business-agent/client-apps/{id}/upstream-users` | ❌ SDK 待补齐 |

### Phase 3：业务函数注册（上游后端 / 平台管理员）

| # | 步骤 | API / 工具 | SDK 状态 |
| --- | --- | --- | --- |
| 13 | 注册 BusinessObject | `POST /api/v1/business-agent/business-objects` | ❌ SDK 待补齐 |
| 14 | 导入 BusinessFunction（Manifest） | `POST /api/v1/business-agent/functions/import` | ❌ SDK 待补齐 |
| 15 | 绑定函数到 Skill allowlist | `POST /api/v1/business-agent/skills/{skillId}/functions` | ❌ SDK 待补齐 |
| 16 | 授权函数给 ClientApp（Function Grant） | `POST /api/v1/business-agent/client-apps/{id}/function-grants` | ❌ SDK 待补齐 |

### Phase 4：任务与会话（上游后端）

| # | 步骤 | API / 工具 | SDK 状态 |
| --- | --- | --- | --- |
| 17 | 创建 Business Task | `POST /api/v1/business-agent/tasks` | ❌ SDK 待补齐 |
| 18 | 查询 Task 状态 | `GET /api/v1/business-agent/tasks/{taskId}` | ❌ SDK 待补齐 |
| 19 | 发起普通 Agent 任务（轮询消息） | `client.agents().ask()` / `getTaskMessages()` | ✅ SDK 已有 |
| 20 | 会话回放 | `client.agents().getSessionMessages()` | ✅ SDK 已有 |

### Phase 5：前端组件接入（上游前端）

| # | 步骤 | 组件 / 工具 | 状态 |
| --- | --- | --- | --- |
| 21 | 安装 `@foggy/chat` | `npm install @foggy/chat` | ✅ 已有 |
| 22 | 集成 ChatPanel + MessageInput | `<ChatPanel>` 组件 | ✅ 已有 |
| 23 | 集成 ToolCallBlock | 工具调用展示 | ✅ 已有 |
| 24 | 集成 ChatPanel/MessageList 审批渲染 | 审批 UI | ✅ 已有 |
| 25 | 配置 SSE 订阅（通过 BFF） | `createSseClient()` | ✅ 已有 |
| 26 | 或使用 NavigatorChat（快速集成） | `<NavigatorChat>` 组件 | ✅ 已有 |

### Phase 6：审批链路验证（上游后端 / 前端）

| # | 步骤 | API / 工具 | SDK 状态 |
| --- | --- | --- | --- |
| 27 | 调用需审批的函数 → 收到 SUSPENDED | Worker invoke_business_function | 内部自动 |
| 28 | 前端展示审批卡片 | ChatPanel/MessageList 内置审批渲染 | ✅ 已有 |
| 29 | 通过 BFF 转发 Resume 请求 | `POST /api/v1/business-agent/suspensions/{suspendId}/resume` | ❌ SDK 待补齐 |
| 30 | 验证审批后函数正确执行 | 查看 Task 状态和消息 | — |

### Phase 7：审计验证（平台管理员）

| # | 步骤 | 说明 |
| --- | --- | --- |
| 31 | 验证审计日志包含 clientAppId、upstreamUserId | 数据库查询 `BusinessFunctionRuntimeAuditEntity` |
| 32 | 验证审计日志不包含 secret、token、adapter config | 安全合规检查 |
| 33 | 验证 invoke / resume / tool-message 都有审计记录 | 全链路审计覆盖 |

## 能力覆盖汇总

| 层级 | 已有 SDK/组件支持 | 仍需 REST 兜底 |
| --- | --- | --- |
| 系统注册 | ✅ `NavigatorClient.register()` | — |
| Worker/Employee 管理 | ✅ `WorkerApi` / `EmployeeApi` | — |
| Agent 任务交互 | ✅ `AgentApi`（ask/poll/messages/sessions） | — |
| 前端聊天 UI | ✅ `@foggy/chat` ChatPanel | — |
| 前端审批 UI | ✅ `@foggy/chat` ChatPanel/MessageList 内置审批渲染 | — |
| 前端快速集成 | ✅ `@foggy/navigator-chat-widget` NavigatorChat | — |
| ClientApp CRUD | — | ❌ REST 兜底 |
| Provisioning/Runtime Credential | — | ❌ REST 兜底 |
| Skill / User / Model Grant | — | ❌ REST 兜底 |
| BusinessObject / Function 注册 | — | ❌ REST 兜底 |
| Function Grant | — | ❌ REST 兜底 |
| Business Task 创建/查询 | — | ❌ REST 兜底 |
| Approval Resume | — | ❌ REST 兜底 |
| Worker Pool 管理 | — | ❌ REST 兜底 |

## 下一阶段建议

1. **补齐 `navigator-open-sdk` 的 Business Agent API 封装**：ClientApp CRUD、Grant 管理、Task、Approval Resume。
2. **补前端组件与审批流 Demo**：完整的 Business Agent 审批流前端集成示例。
3. **补 REST Adapter 更多 transport 类型**：当前仅支持 REST，后续扩展 RPC/MQ/MCP。
4. **补 fsscript 运行时集成**：`run_business_script` 当前为占位工具。
