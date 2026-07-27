---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-024
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: project-owner
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Codex SDK Worker 自定义网关本地压缩路由

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 固定 Codex SDK Worker 自定义网关压缩失败的修复边界、验收标准与证据义务。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-024-codex-sdk-worker-custom-gateway-compaction.md`

## Goal

- version_goal: 保持 Codex 长任务可持续执行，并让自定义网关调用遵守其真实 Responses API 能力。
- target_outcome: 删除 Worker 强制的 140k 自动压缩阈值；自定义网关被 Codex CLI 识别为 custom provider，自动压缩继续由 CLI 管理并走普通 `/responses`，不调用 `/responses/compact`。
- critical_outcomes: 默认压缩阈值回归模型默认值；请求级自定义网关不再冒充 OpenAI provider；标准 OpenAI 路径保持兼容。
- success_is_sufficient_when: focused regression、Worker 全量测试、类型检查和构建通过，且配置级证据证明 custom provider 选择与 140k 默认值移除。

## Scope

- in_scope: Codex SDK Worker 的 SDK/CLI 配置生成、回归测试、交付记录。
- affected_modules: `tools/codex-agent-worker`
- external_dependencies: 已锁定的 `@openai/codex-sdk` 与其 Codex CLI 配置契约。

## Non-Goals

- out_of_scope: 禁用 CLI 自动压缩；为网关实现 `/responses/compact`；修改 Codex app-server Worker、Java 路由或其他上游仓库；发布或部署 Worker。
- do_not_touch: 其他工作树脏改动、凭据、运行中 Worker、生产配置。
- non_blocking_or_waivable_items: 真实付费模型长上下文 canary 不纳入本次自动验证。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 删除 Worker 默认 `model_auto_compact_token_limit=140000` | 让 CLI 使用模型默认阈值，避免 Worker 过早压缩 | 保留调用方显式 override 能力 |
| 请求提供自定义 `base_url` 时选择非保留 custom provider | 避免 CLI 将网关当作 OpenAI 并调用 `/responses/compact` | provider 使用 Responses wire API 和现有 `CODEX_API_KEY` |
| 不向 SDK 传递顶层 `baseUrl` | SDK 会把它翻译为 `openai_base_url`，重新触发 OpenAI provider 路径 | base URL 仍写入 custom provider 配置与任务隔离环境 |

## Acceptance Criteria

- [x] AC-1: 未显式配置时，生成的 Codex config 不包含 `model_auto_compact_token_limit`。
- [x] AC-2: 显式传入该阈值时仍原样传播。
- [x] AC-3: 提供自定义 base URL 时生成唯一 custom provider，包含 `base_url`、`wire_api=responses`、`env_key=CODEX_API_KEY`，并且 SDK 顶层 `baseUrl` 未设置。
- [x] AC-4: 未提供自定义 base URL 时保持标准 OpenAI SDK 路径。
- [x] AC-5: Worker focused tests、全量 tests、typecheck 与 build 实际通过。

## Contract / Data / Security Constraints

- API or event contract: Worker HTTP 请求与 SSE 事件契约不变。
- data and migration: 无数据库或持久化迁移。
- compatibility and rollback: 单提交可回滚；显式 compact threshold override 保持兼容。
- permissions and secrets: 不新增、记录或持久化密钥；custom provider 只引用现有任务级 `CODEX_API_KEY` 环境变量名。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/AC-2 | must-pass | major | unit | 新增回归测试 | 精确命令与结果 |
| AC-3/AC-4 | must-pass | major | unit/config inspection | 新增回归测试 | 精确命令与结果 |
| AC-5 | must-pass | major | Worker test/typecheck/build | 当次输出 | 精确命令与结果 |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused test、typecheck、build，单项预期 `<5m`
- medium_validation: Worker 全量测试，预期 `5-30m`
- expensive_validation: none
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: 同一 source state 的 focused/full Worker test、typecheck、build 输出。
- stop_when_evidence_is_sufficient: AC-1 至 AC-5 均有通过证据，diff 无范围外变更。
- validation_not_required: 付费长上下文 canary、打包发布、OBS 上传、Worker 安装或重启、Java/前端测试。

## Waiver Policy

- waivable_items: 真实网关长上下文 canary。
- authorized_role: project-owner
- non_waivable_guards: 不泄露密钥；custom provider 配置与默认阈值回归测试必须通过。
- required_risk_record: 未执行 live canary 时记录配置级验证边界。

## Bug Context

- bug_source: user-report
- severity: major
- environment: Codex SDK Worker 使用不支持 `/responses/compact` 的自定义 Responses 网关。
- current_behavior: Worker 强制 140k 阈值；CLI 将自定义网关识别为 OpenAI provider，压缩时调用 `/responses/compact` 并以 `CODEX_INVALID_REQUEST` 失败。
- expected_behavior: CLI 使用模型默认压缩阈值，并通过 custom provider 的本地压缩路径使用普通 `/responses`。
- reproduction_steps: 自定义 base URL 启动长上下文任务并达到自动压缩阈值。
- reproduction_status: confirmed
- existing_evidence: 用户提供的实际链路分析；源码中的 140k 默认值；SDK 将顶层 `baseUrl` 转换为 `openai_base_url` 的本地依赖文档与实现。
- existing_tests: SDK wrapper 配置和任务环境已有单元测试基础。
- regression_protection: required
- waiver_reason_and_risk: live 长上下文复现成本高且可能产生付费调用，使用确定性配置级回归保护。

## Risks and Open Questions

- known_risks: 本次不执行真实长上下文网关 canary；依赖升级若改变 provider/compaction 语义需重新验证。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得主动运行预计超过 30 分钟或包含 authority/replay/rehearsal/source-seal 的大型链路。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 删除 SDK Worker 的 140k 默认压缩阈值；请求级/Worker 级自定义 base URL 改为显式 `navigator_gateway` custom Responses provider，不再使用 SDK 顶层 `baseUrl`/`openai_base_url` 路径；保留显式阈值 override。
- changed_paths: `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`；`tools/codex-agent-worker/tests/sdk-wrapper.test.ts`；本 canonical work item。
- tests_and_results: `node --import tsx --test --test-name-pattern='buildCodexConfig|start and resume both preserve Shell' tests/sdk-wrapper.test.ts` PASS（3/3）；`npm test` PASS（248 passed / 1 skipped）；`npm run typecheck` PASS；`npm run build` PASS。
- manual_or_experience_evidence: 配置级回归断言证明 custom provider 字段完整且 `CodexOptions.baseUrl` 未设置。
- deviations: none
- residual_risks: 未执行真实自定义网关长上下文付费 canary；依赖升级若改变 provider/compaction 语义需复核。
- reused_evidence: 同一 source state 的 focused/full Worker test、typecheck 与 build 输出。
- omitted_validation_and_reason: 未运行 package/publish/deploy、Worker 重启和 live 长上下文 canary；均不属于批准范围，live canary 成本高且可能产生付费调用。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 2026-07-27 project-owner approved recommendation.
- architecture / glossary: `tools/codex-agent-worker/node_modules/@openai/codex-sdk/README.md`
- related work items: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-013-codex-app-server-long-thread-tool-loss.md`
