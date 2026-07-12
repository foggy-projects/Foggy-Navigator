# OPT-005 Ultra 执行提示词

## 文档作用

- doc_type: execution-prompt
- intended_for: Codex Ultra execution-agent
- purpose: 在一个新会话中完整实现、验证并验收 Codex App Server 独立 Provider 与 Worker 配置拆分。

## 开工提示词

你正在 `/home/sa/workspace/Foggy-Navigator` 中执行 `1.4.0-SNAPSHOT / OPT-005`。

目标：一次性完成 Codex SDK Worker 与 Codex App Server Worker 的独立 Worker Backend、独立 A2A Provider、独立 Session、Endpoint/Runtime 控制面收口和完整 UI/测试闭环。

先完整阅读：

1. `CLAUDE.md`
2. `docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-005-codex-app-server-independent-provider.md`
3. `docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-005-codex-app-server-independent-provider-plan.md`
4. `docs/version-tracker/1.4.0-SNAPSHOT/README.md`
5. `docs/a2a-agent-architecture.md`

必须使用并遵循适用的项目技能：`agent-framework`、`session-module`、`claude-worker-agent`、`coding-agent-frontend`、`webapp-testing`；编码完成后使用 `foggy-implementation-quality-gate`、`foggy-test-coverage-audit`、`foggy-acceptance-signoff`。技能要求与本文冲突时，以用户确认的 OPT-005 需求边界为准。

### 核心决策

- SDK：`workerBackend=OPENAI_CODEX`，`providerType=codex-worker`。
- App Server：`workerBackend=OPENAI_CODEX_APP_SERVER`，`providerType=codex-app-server-worker`。
- 两个 Provider 不得互相 fallback。
- Session 创建时绑定真实 Provider；跨 Provider 必须新建 Session。
- SDK Worker 的 `codexConfig` 与 App Server 的 Endpoint Profile 完全独立。
- `CodexAppServerEndpoint` 是 App Server endpoint/token 唯一人工配置源；Physical Worker 只提供 owner/capability 归属和管理页签，不新增 `codexAppServerConfig`。
- Runtime registry 只能保存 Endpoint 同步派生的 App Server capability/revision/instance/连接快照与 affinity，不提供手工 endpoint/token 注册或编辑入口。
- Ultra 只属于 App Server Backend。
- 不考虑旧 Session/Thread 恢复、双读、N-1 或兼容 API；允许一次性迁移按既有 runtime 类型回填 Task `provider_type`，但不得形成兼容路由或 fallback。
- 不自动扩展 `codex-biz-worker`。

### 工作区保护

当前工作区在本提示词生成时已经存在未提交代码，包括 `CodexAppServerEndpoint*`、Runtime Entity/DTO/Service、前端 Runtime Manager 和 migration 等改动。这些改动属于现有工作：

- 不得执行 `git reset --hard`、`git checkout --` 或任何覆盖式清理。
- 开工先运行 `git status --short` 和相关 diff，逐项判断保留、改造或删除。
- 根据“Endpoint Profile 是唯一 endpoint/token 人工写源”审查和复用这些改动；不得删除已交付的 Endpoint CRUD/sync，也不得复制为 Physical Worker 内嵌配置。
- 如需删除不符合新设计的未提交文件，必须先确认其功能已被新实现覆盖，并在 progress 中记录。

### 执行步骤

严格按 Implementation Plan 的 Step 1–7 执行，不停留在分析或只完成部分模块：

1. 冻结契约并审阅现有 dirty 改动。
2. 收口 SDK 配置与 App Server Endpoint Profile，保留 token 加密/掩码和同步，移除公开手工 Runtime 写入口。
3. 增加 App Server Worker Backend 与独立 A2A Provider，删除跨 Provider fallback。
4. 收口 Session、Task、Thread/Turn 与 App Server 内部 instance affinity。
5. 完成 AI 模型设置、物理 Worker SDK/Endpoint 双页签、Provider 标签和跨 Provider 新会话交互。
6. 删除旧同 Provider 双 Runtime 活动路由和公开手工 Runtime endpoint/token 写源；保留 Endpoint CRUD/sync 单一写源。
7. 完成全链路测试、体验验证、质量审计、验收和文档回写。

允许在同一个 addon 内注册两个 Provider，并抽取公共 Codex 组件；不要为了“独立 Provider”复制整套 Task/SSE/投影代码，也不要无必要创建新的 Maven addon。

### 完成要求

- 编译通过。
- Java 相关模块全部测试通过。
- 两个 Worker 的 test/typecheck/build 全部通过。
- 前端单测和 `bash scripts/build-frontend.sh` 通过。
- Playwright 覆盖：AI 模型后端拆分、物理 Worker SDK/Endpoint 页签、跨 Provider 创建新会话、桌面和 320px。
- 真实 SDK Worker 与 App Server Worker 各完成至少一条 Worker -> Java -> SSE -> PC 链路。
- 验证任一 Provider 不可用时绝不 fallback 到另一 Provider。
- 测试失败必须修复并重跑；外部环境确实不可用时只能标记 blocked/not-run，不得声称完成。
- 不提交或推送，除非用户在新会话中明确要求。

### 实现自检与回写

编码完成后先做轻量自检：范围是否收口、是否漏改/误改、是否存在重复配置源、临时代码或旧路由残留、测试和风险是否完整记录。

把实际完成情况回写到：

`docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-005-codex-app-server-independent-provider.md#progress-tracking`

然后依次执行正式实现质量检查、测试覆盖审计和功能验收。最终报告必须给出：

- 完成内容
- 关键代码路径
- 删除或整合的既有 dirty 改动
- 测试命令与结果
- Playwright/真实链路证据
- 剩余风险或 blocker
- 是否达到 acceptance-ready
