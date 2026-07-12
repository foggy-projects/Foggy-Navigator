# OPT-005 Codex App Server 独立 Provider 实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 将 OPT-005 已确认设计拆成可一次性执行、验证和回写的跨模块实施步骤。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- status: completed-verified
- requirement: [OPT-005 requirement](./OPT-005-codex-app-server-independent-provider.md)
- progress: [OPT-005 progress](./OPT-005-codex-app-server-independent-provider.md#progress-tracking)
- execution_mode: multi-agent-coordinated-cross-module

## 与版本目标的关系

本计划取代 OPT-001 后续“同一 `codex-worker` Provider 内部选择 SDK/App Server runtime”的演进方式。OPT-001 已完成的 App Server Worker 幂等接受、task store、pool、事件桥接和 instance affinity 能力继续复用；OPT-006 已完成的 Endpoint Profile/Runtime 同步作为唯一 App Server 控制面基线继续复用。

## 总体原则

1. 先冻结命名和单一配置源，再修改路由；App Server 单一人工写源固定为 `CodexAppServerEndpoint`。
2. `workerBackend` 决定 A2A Provider，Session 绑定后不重选。
3. Physical Worker 是 Endpoint Profile 的 owner/capability 归属和 UI 入口，不持久化 `codexAppServerConfig`；Runtime 仅由 Endpoint 同步派生。
4. SDK 与 App Server 共享公共 Codex 投影代码，但不共享 Provider 身份和 fallback。
5. 不实现旧数据双读、fallback 或旧会话恢复；一次性 Task provider 分类迁移不构成兼容路由。
6. 当前工作区有未提交代码；执行时必须逐文件审阅、整合，禁止 reset、checkout 或覆盖用户改动。

## Ownership

| Owner | 职责 | 开工条件 |
|---|---|---|
| `navigator-common` | Backend 常量、Backend/Provider 一一映射、共享 DTO | 命名冻结后立即开工 |
| `navigator-spi` / `session-module` | Provider 发现、解析、Session 绑定、Task 分派 | Backend/Provider 映射冻结后开工 |
| `addons/codex-worker-agent` | 两个 Provider、公共 Codex 执行投影、App Server client/runtime 内部状态 | Session 契约确定后开工 |
| `addons/claude-worker-agent` | 保持 SDK Worker 配置和 Physical Worker owner/capability 归属 | Endpoint/Profile 归属规则确定后核对 |
| `packages/navigator-frontend` | AI 模型后端、物理 Worker 双页签、Provider 标签、新会话提示 | 后端 Form/DTO 契约可编译后开工 |
| `tools/codex-agent-worker` | SDK-only 能力与 Ultra fail-closed | 可并行核对 |
| `tools/codex-app-server-worker` | App Server-only 能力与 manifest | 可并行核对 |
| docs/tests | 架构口径、自动化、体验证据、progress | 随各阶段同步 |

## Code Inventory

| Module | Path | Role | Expected change | Notes |
|---|---|---|---|---|
| common | `navigator-common/src/main/java` | Worker Backend 与配置模型 | update | 具体类落点按现有结构决定 |
| SPI | `navigator-spi/src/main/java/com/foggy/navigator/spi/agent` | A2A Provider 契约 | read-only-analysis/update-if-needed | 优先不扩接口 |
| session | `session-module/src/main/java/.../registry` | Provider 发现与优选 | update | 新 Provider 必须可独立解析 |
| session | `session-module/src/main/java/.../service` | Session 绑定与 Task 分派 | update | 删除跨协议二次选择 |
| Codex addon | `addons/codex-worker-agent/src/main/java/.../adapter` | SDK/App Server Provider | update/create | 可同 addon 注册两个 Provider Bean |
| Codex addon | `addons/codex-worker-agent/src/main/java/.../service` | Task、client、runtime 与投影 | update | 提取公共逻辑，路由入口分离 |
| Endpoint Profile | `addons/codex-worker-agent/src/main/java/.../CodexAppServerEndpoint*` | App Server endpoint/token 唯一人工写源 | preserve/update | owner-bound CRUD、加密、同步与探测 |
| Runtime registry | `addons/codex-worker-agent/src/main/java/.../CodexRuntime*` | 同步派生 Runtime 与 affinity | update | 删除公开手工 endpoint/token 注册，保留状态/退役/归档 |
| Frontend types | `packages/navigator-frontend/src/types` | Backend、Worker DTO | update | 增加 App Server 类型和字段 |
| Model settings | `packages/navigator-frontend/src/views/SettingsView.vue` | AI 模型后端入口 | update | 独立选项、目录和授权 Worker |
| Worker settings | `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | SDK 配置与 App Server Endpoint 页签 | update | App Server 页签复用 Endpoint Manager，不新增配置写源 |
| Frontend routing | `packages/navigator-frontend/src/utils/workerBackend.ts` | Backend/Provider 映射 | update | 一一映射 |
| Endpoint baseline | `addons/codex-worker-agent/.../CodexAppServerEndpoint*` | OPT-006 endpoint CRUD/sync | preserve/update | 作为唯一人工可写配置源，不得删除或复制 |
| Migrations | `docs/migration/2026-07-12-codex-app-server-endpoints.sql`、Provider split migration | Endpoint schema 与 Task provider 分类 | preserve/update | 生产 `ddl-auto=validate` 前置，需 MySQL 8.0/8.4 验证 |
| Workers | `tools/codex-agent-worker`、`tools/codex-app-server-worker` | 执行协议 | update-if-needed | 各自 fail-closed，不互相 fallback |
| Architecture docs | `docs/a2a-agent-architecture.md` 等 | 长期架构口径 | update | 明确 A2A Provider 与 LLM Provider 区别 |

## 实施步骤

### Step 1：冻结契约与清理冲突设计

- 固定 `OPENAI_CODEX_APP_SERVER`、`codex-app-server-worker`、`CodexAppServerEndpoint`。
- 全仓检索 Backend/Provider 映射和 Codex runtime 判断点。
- 审阅当前未提交 Endpoint/Runtime 改动，给出保留、改造、删除清单。
- 确认 Endpoint Profile 为 endpoint/token 唯一人工写源，Physical Worker 仅承载归属和页面入口。

完成门：不存在两套可写 endpoint/token 方案，所有后续步骤使用同一命名。

### Step 2：收口 Endpoint Profile 与 Runtime 控制面

- 保留 OPT-006 Endpoint Entity/Form/DTO/Service 作为 App Server 唯一人工配置源。
- App Server token 加密、更新留空保持、显式清除和 DTO 掩码规则保持不变。
- Runtime 只接受 Endpoint 同步派生的连接快照、capability/revision/instance 状态，不开放手工 endpoint/token 注册。
- readiness、连接测试和 Worker capability 过滤分别按 SDK 配置与 App Server Endpoint/manifest 判断。

完成门：后端测试证明 SDK 配置与 Endpoint Profile 互不覆盖，Endpoint CRUD/sync/删除与 Runtime 路由状态一致，系统不存在第二写源。

### Step 3：拆分 Backend 与 A2A Provider

- 增加新 Worker Backend 常量及映射。
- 注册 `codex-app-server-worker` Provider。
- `codex-worker` 强制 SDK client；新 Provider 强制 App Server client。
- 提取并复用公共 Codex 消息、SSE、Task 投影逻辑。
- 删除模型档位/灰度策略在两个 Provider 之间二次选择的入口。

完成门：Provider resolver 和 Task dispatch 测试证明严格一一映射且无 fallback。

### Step 4：收口 Session、Task 与 Runtime 状态

- Session 创建时根据 ModelConfig 绑定真实 Provider。
- SDK state 只保存 SDK Thread；App Server state 保存其 Thread/Turn/runtime affinity。
- status/subscribe/abort/delete 按 Provider 路由。
- App Server runtime registry 只保留 Provider 内部 capability/revision/instance 状态。
- 删除不再需要的 `codexRuntimeType` 跨 Provider 判别和旧数据分支。

完成门：两种会话均可独立完成创建、续接、刷新、取消和删除，跨 Provider 请求明确拒绝。

### Step 5：拆分 AI 模型与物理 Worker UI

- AI 模型 Worker 后端增加 App Server。
- SDK/App Server 模型与 reasoning 目录独立过滤，Ultra 仅 App Server。
- 物理 Worker 编辑页增加 `Codex App Server` 页签，并嵌入 owner-bound Endpoint Profile 管理。
- SDK 配置与 Endpoint Profile 独立保存、清除、测试连接和显示状态。
- 会话/任务列表显示真实 Provider 标签。
- 跨 Provider 模型选择转为创建新会话提示，不发送错误续接请求。

完成门：前端单测、构建与 Playwright 桌面/320px 全部通过。

### Step 6：删除旧双 Runtime 外部路由语义

- 删除同一 `codex-worker` Provider 内 SDK/App Server 自动选择和 fallback。
- 删除公开手工 Runtime endpoint/token 注册 API 和页面；保留 Endpoint CRUD/sync，Runtime 收敛为同步派生状态与 affinity。
- 更新旧测试断言和架构文档；不保留兼容分支。
- 核对 Worker 本身仍各自 fail-closed。

完成门：全仓检索不存在把 App Server 作为 `codex-worker` 内部可替换 runtime 的活动路由代码。

### Step 7：全链路验证与文档回写

- 执行 Java、TypeScript、前端单测和构建。
- 执行 SDK 与 App Server 两条真实任务链路。
- 执行跨 Provider 负例和无 fallback 故障测试。
- 执行 UI Playwright 与刷新/SSE/取消/删除验证。
- 更新 requirement 的 Progress Tracking、代码触点、测试结果和风险。
- 完成实现自检、正式质量检查、覆盖审计与验收。

## 验证命令基线

执行者可按实际测试类补充或拆分命令，但不得跳过失败测试：

```bash
mvn test -pl session-module,addons/codex-worker-agent,addons/claude-worker-agent -am -DfailIfNoTests=false

cd tools/codex-agent-worker
npm test
npm run typecheck
npm run build

cd ../codex-app-server-worker
npm test
npm run typecheck
npm run build

cd ../../packages/navigator-frontend
pnpm test

cd ../..
bash scripts/build-frontend.sh
```

Playwright 必须覆盖 requirement 中列出的四类体验用例，并把证据写入 `docs/version-tracker/1.4.0-SNAPSHOT/evidence/`。

## 完成定义

- Requirement 中所有验收项有代码和测试映射。
- 编译、全部相关测试、前端构建和 Playwright 均通过。
- 两条真实 Worker 链路通过；跨 Provider 和 fallback 负例通过。
- 当前工作区既有改动已被明确整合或说明，没有被覆盖或静默遗失。
- Progress Tracking 已回写实际代码路径、测试命令、结果、风险和体验证据。
- `foggy-implementation-quality-gate`、`foggy-test-coverage-audit`、`foggy-acceptance-signoff` 已按顺序执行。

## Execution Result

- completed_at: `2026-07-12`
- result: `all-seven-steps-completed`
- automated_gate: `java-2095; pc-237; mobile-55; sdk-worker-124; app-worker-244-pass-7-skip; playwright-7; mysql-8.0-and-8.4-pass`
- real_chain: `sdk-and-app-ultra-completed; unified-sse-pass; bidirectional-no-fallback-pass; cleanup-pass`
- quality_record: `../quality/OPT-005-codex-app-server-independent-provider-implementation-quality.md`
- coverage_record: `../coverage/OPT-005-codex-app-server-independent-provider-coverage-audit.md`
- acceptance_record: `../acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md`
- production_enablement: `not-approved`

## 非目标

- 不提供旧 Session/Thread/ModelConfig 的跨 Provider 恢复；仅允许一次性 Task `provider_type` 分类迁移。
- 不提供 SDK 与 App Server Thread 转换。
- 不扩展 `codex-biz-worker`。
- 不修改其他 Provider 的执行语义。

## 参考

- [Requirement](./OPT-005-codex-app-server-independent-provider.md)
- [Execution Prompt](./OPT-005-codex-app-server-independent-provider-execution-prompt.md)
