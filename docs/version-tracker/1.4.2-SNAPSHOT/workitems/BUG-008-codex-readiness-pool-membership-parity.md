---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-008
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: Codex readiness 与 Worker Pool 成员校验一致性

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Issue #149 的修复目标：Codex owner-smoke/readiness 与首次任务启动必须对同一执行 PhysicalWorker 应用同一启用 Pool 成员约束。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-008-codex-readiness-pool-membership-parity.md`

## Goal

- version_goal: 补齐 external runtime 的 Worker Pool、Directory 与 upstream-user 可追溯资源约束，保持 fail-closed。
- target_outcome: 对 `OPENAI_CODEX` 的 pooled Agent，若 Directory 选定的执行 PhysicalWorker 不是该 Pool 的启用成员，`owner-smoke` / `verify-agent-readiness` 在任何 task/token/provider 副作用前明确失败；通过的同一绑定可继续进入首次 ask 的既有预绑定流程。

## Scope

- in_scope: 共享 Codex Pool Worker 选择/校验规则；Open API readiness 对执行 Worker 的 Pool 成员检查与脱敏诊断；相关 Java 单元/服务回归；本 work item 的实现和验证证据。
- affected_modules: `business-agent-module`、`addons/codex-worker-agent`、`addons/claude-worker-agent`（Open API readiness/controller 已有编排边界）。
- external_dependencies: 仅使用仓内 JPA Pool/member、Directory 和 Codex Worker 既有模型；不调用或修改上游项目。

## Non-Goals

- out_of_scope: 不削弱或移除 launcher 的最终 Pool/owner/backend 安全校验；不改变 Tenant、ClientApp、upstream user、Directory 可见性或模型 grant 语义；不实现 Pool 成员自动修复/自动加入；不改变 CLI 参数和 HTTP path；不执行真实 upstream ask 或创建测试租户资源。
- do_not_touch: 当前工作区其他未提交文件；同级上游仓库；已安装 Worker、运行中进程与任何凭据配置。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Directory 为 Codex 解析出 PhysicalWorker 时，该 Worker 是 Pool 路由的受约束执行目标 | 当前 ask 已把该 Worker 传入预绑定，launcher 已按此拒绝非成员 | readiness 必须先对同一目标 fail-closed，不得悄悄改为另选 Pool 成员 |
| Pool 可用性、后端一致性和启用成员资格由共享规则提供 | 目前 launcher 独有实现导致 readiness 漂移 | launcher 保留最终重新校验，以覆盖 readiness 与 launch 之间的配置变化 |
| readiness 增加稳定、可操作的成员资格检查 | owner-smoke 要在首次 ask 前暴露阻塞原因 | 诊断仅输出现有已脱敏的资源标识；不输出 token、key、base URL 凭据 |
| 无指定 PhysicalWorker 的 Pool 路由保留既有首次启用成员选择 | Issue 只涉及 Directory/role routing 约束与 Pool 不一致 | 不引入调度策略变更 |

## Acceptance Criteria

- [ ] AC-1: Pool Worker 选择与指定 Worker 的启用成员校验不再只存在于 `CodexBusinessAgentWorkerTaskLauncher`；readiness 和 launcher 使用一致规则。
- [ ] AC-2: `OPENAI_CODEX` pooled Agent 的 Directory/role routing 解析出 PhysicalWorker 后，readiness 对该 Worker 的 Pool owner、backend 与启用成员资格 fail-closed。
- [ ] AC-3: 非成员、禁用成员、空启用成员 Pool 分别给出动作明确的 readiness FAIL；正常启用成员返回 OK，并保留已解析执行 Worker 诊断。
- [ ] AC-4: readiness 失败发生在 Open API task-scoped token、Worker lease、Provider task 创建之前。
- [ ] AC-5: launcher 继续拒绝非启用成员，且不存在弱化 tenant/owner/pool/backend enforcement 的回归。
- [ ] AC-6: 有自动化回归覆盖 Issue #149 的最小组合：Agent 指向 Pool、Directory 指向同后端但非启用成员的 PhysicalWorker；并运行相关模块测试通过。

## Contract / Data / Security Constraints

- API or event contract: 仅在既有 readiness `checks` 增加/失败一个 Pool membership 检查；不改 HTTP path、CLI 参数或 ask payload。检查 code/message 必须稳定且可行动。
- data and migration: 无 schema 或数据迁移；不写入/修复 Pool 成员。
- compatibility and rollback: 过去会 readiness OK、随后 ask 400 的错配改为 readiness FAIL，属于预期 fail-closed 修复；移除本次代码可回退旧行为，无数据回滚。
- permissions and secrets: 不放宽任何 owner/pool/member 校验；日志、测试和文档不得输出 credentials、token 或真实租户标识。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/5 | critical | shared resolver/service and launcher tests | 指定成员通过、非成员/禁用成员拒绝、Pool backend/owner 约束未退化 |
| AC-2/3/4 | critical | readiness service tests | Directory Worker 非成员/禁用成员为 FAIL，启用成员为 OK，且失败不进入 token/provider 边界 |
| AC-6 | major | Maven module dependency-chain tests | 精确命令、测试数量/exit code、失败前后或新增回归证据 |
| delivery hygiene | major | `git diff --check` and work item review | 无无关修改；实现结果、路径、命令和残余风险回写 |

## Bug Context

- bug_source: user-report / GitHub Issue #149
- severity: major
- environment: trusted local/internal-dev Open API upstream integration，`codex-biz-worker`，`OPENAI_CODEX`，Directory 与 Codex execution role 为独立本地角色端点。
- current_behavior: `owner-smoke` 报告资源、模型、Directory Worker 与角色路由 OK；首次 ask 在 Worker 预选择阶段因 Directory PhysicalWorker 不是启用 Pool 成员而失败。
- expected_behavior: readiness 与 ask 使用同一 Pool membership 约束，错配在 ask 前以可操作 check 失败。
- reproduction_steps: 配置 enabled pooled Agent、enabled upstream-user/model/directory grants；令 Directory 解析出健康 Codex PhysicalWorker，但不把它作为该 Pool 的启用成员；执行 readiness 后执行同绑定 ask。
- reproduction_status: confirmed (静态完整调用链与 launcher 单元测试已确认；未执行会创建真实 task 的 live ask)。
- existing_evidence: `OpenApiController` 对 Codex 优先将 Directory Worker 注入选择请求；`BusinessAgentTaskService.prepareOpenApiTaskScopedToken` 调用 launcher 预选 Worker；launcher 对指定 Worker 严格筛选 enabled members。已执行 `mvn -pl addons/codex-worker-agent -am -Dtest=CodexBusinessAgentWorkerTaskLauncherTest -Dsurefire.failIfNoSpecifiedTests=false test`，6 tests passed。
- existing_tests: `CodexBusinessAgentWorkerTaskLauncherTest.launchRejectsPreselectedWorkerThatIsNotEnabledPoolMember`。
- regression_protection: required.
- waiver_reason_and_risk: live upstream ask 会创建 task/token，未获本轮运行态写入授权；自动化覆盖必须替代该 live 验证。

## Risks and Open Questions

- known_risks: readiness 与 launch 间 Pool 配置仍可能变化，因此 launcher 的二次校验必须保留；若共享规则抽取跨模块依赖方向不成立，必须使用已有 business-agent service 边界而非复制逻辑。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md` 和所涉模块的当前实现/测试。
- 测试先行；在 scope 内自主决定具体类、函数和实现结构，但不得复制一套独立的 Pool 成员规则。
- 如需改变 Directory 强约束、Pool 调度语义、兼容边界、外部 API 或安全校验，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的检查通过。
- 完成后填写 `Implementation Result`，将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 新增 `BizWorkerPoolWorkerSelector`，将 Pool 可用性、owner 范围、backend 一致性、启用成员筛选与指定 PhysicalWorker 强约束收敛为一个业务模块服务。Codex launcher 改为复用该服务，仍在 launch 前重新校验。`OpenApiAgentReadinessService` 在解析 Directory/role execution worker 后、模型和后续 ask 边界之前，对 `OPENAI_CODEX` pooled Agent 添加 `WORKER_POOL_MEMBERSHIP` 检查；错配返回可行动的 FAIL，不会静默改选其他成员。
- changed_paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerPoolWorkerSelector.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BizWorkerPoolWorkerSelectorTest.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncherTest.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessServiceTest.java`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-008-codex-readiness-pool-membership-parity.md`
- tests_and_results:
  - `mvn clean -pl addons/claude-worker-agent,addons/codex-worker-agent -am -Dtest=BizWorkerPoolWorkerSelectorTest,CodexBusinessAgentWorkerTaskLauncherTest,OpenApiAgentReadinessServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过；共享选择器 5、readiness 24、Codex launcher 6 个测试均为 0 failures / 0 errors。
  - `mvn test -pl addons/claude-worker-agent,addons/codex-worker-agent -am`：完成；business-agent 78、claude-worker-agent 51、codex-worker-agent 26 份 Surefire 报告均未发现 `<failure>` 或 `<error>`。
  - `git diff --check`：通过；仅有既存 Codex launcher 测试文件的 CRLF→LF Git 提示，无空白错误。
- manual_or_experience_evidence: Issue #149 的最小组合已自动化：pooled `OPENAI_CODEX` Agent 的 Directory 指向同后端但非启用成员的 PhysicalWorker 时，readiness 返回 `WORKER_POOL_MEMBERSHIP` FAIL，含可执行修复动作；共享选择器还覆盖禁用成员、无启用成员与 backend 不匹配。readiness 服务不持有 task-scoped token、Worker lease 或 provider task 创建依赖，成员检查在该类后续模型/运行态诊断前执行；launcher 保留最终 fail-closed 校验。
- deviations: none
- residual_risks: 未执行真实 upstream ask，避免创建 task/token；Pool 配置可在 readiness 和 launch 之间变化，但 launcher 的二次共享校验会拒绝错配。
- readiness: READY_FOR_SIGNOFF

## References

- issue: `https://github.com/foggy-projects/Foggy-Navigator/issues/149`
- related work items: `GOV-002-biz-worker-and-upstream-user-boundary.md`
- affected implementation: `A2AgentResourceResolver`; `OpenApiAgentReadinessService`; `OpenApiController`; `BusinessAgentTaskService`; `CodexBusinessAgentWorkerTaskLauncher`
