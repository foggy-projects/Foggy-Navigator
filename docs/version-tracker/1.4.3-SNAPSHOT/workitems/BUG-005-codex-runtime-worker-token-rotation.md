---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-005
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-20
open_questions: []
---

# Delivery Spec: Codex 历史 Runtime 的 Worker Token 轮换兼容

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 App Server Worker 凭据轮换后历史任务仍可安全查询和中止的目标、边界与验收要求。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-005-codex-runtime-worker-token-rotation.md

## Goal

- version_goal: 完成 BUG-004 真实中止闭环在已部署环境中的凭据轮换兼容，避免历史任务因 runtime revision 保存旧或空 Worker token 而失去治理能力。
- target_outcome: endpoint profile 在同一 Worker、同一 URL 下轮换 token 后，绑定旧 runtime revision 的任务继续使用原 runtime、instance 和 endpoint，但通过当前 endpoint credential 调用 Worker；不满足精确归属条件时 fail closed。

## Scope

- in_scope:
  - App Server `ENDPOINT_SYNC` runtime 的任务绑定解析与 Worker HTTP credential 选择。
  - endpoint/runtime 非敏感 token 配置状态诊断。
  - token 从空值或旧值轮换为当前值后，历史任务状态查询、中止检查和其他 pinned-runtime 调用的自动化回归。
- affected_modules: `addons/codex-worker-agent`；版本工作项文档。
- external_dependencies: Codex App Server Worker 的 Bearer 鉴权合同；现有 endpoint profile 与 runtime revision 数据。

## Non-Goals

- out_of_scope: 改变 task/runtime/instance affinity，迁移任务到新 runtime，改变 endpoint URL，关闭 Worker 鉴权，杀死共享 App Server 进程，部署或发布 Worker，直接修复现场数据库。
- do_not_touch: 其他工作区、Worker `.env` 或真实凭据、前端交互、数据库 schema、BUG-004 历史证据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| runtime、revision、instance 和 endpoint URL 继续使用任务绑定值 | 状态型 App Server 不能因凭据轮换漂移到另一实例 | 不重新路由、不选择最新 revision |
| 仅当 endpoint profile 仍存在，且 endpointId、workerId、规范化 URL 均与绑定 runtime 一致时使用当前 endpoint token | token 是可轮换 credential，不应成为历史任务永久失联的身份快照 | 任一归属条件不满足时继续使用安全失败语义，不跨 endpoint 借用 token |
| endpoint profile 当前 token 为空时不得回退到已撤销的旧非空 token | 轮换/清除必须能撤销访问，不能形成隐式 credential fallback | Worker 鉴权开启时请求会明确失败；不得静默降级为旧 token |
| tokenConfigured 只暴露布尔状态 | 支持现场诊断且不泄露 secret | API、日志、异常和文档不得输出 token/ciphertext |

## Acceptance Criteria

- [x] AC-1: 同一 endpoint/worker/URL 的 token 轮换后，绑定旧 runtime revision 的客户端使用当前 endpoint token，并保持原 runtime revision、instanceId 和 endpoint URL。
- [x] AC-2: endpoint 当前 token 被清除时，历史 runtime 不回退到旧 token；endpoint 缺失、worker 不一致或 URL 不一致时不得借用当前 token。
- [x] AC-3: runtime 列表以非敏感布尔字段显示实际调用 credential 是否已配置，不返回 token 或 ciphertext。
- [x] AC-4: `termination-inspection` 等 pinned-runtime 调用无需修改 API path 或浏览器合同即可恢复 Bearer 鉴权。
- [x] AC-5: 自动化回归覆盖空到非空、旧到新、清除、endpoint 缺失、worker/URL 不匹配及既有 affinity 失败语义。

## Contract / Data / Security Constraints

- API or event contract: 仅向 runtime DTO 增加向后兼容的 `tokenConfigured` 布尔字段；现有任务与中止 API 不改 path、方法或成功响应语义。
- data and migration: 无 schema 或数据迁移；不得批量改写历史 runtime ciphertext。
- compatibility and rollback: 回滚恢复 revision token 快照行为；修复不能改变新任务 runtime 选择、routing policy 或 capability 判断。
- permissions and secrets: endpoint profile 是当前 Worker HTTP credential 权威源；只在精确归属匹配时读取，禁止日志和响应泄露 credential。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2/AC-5 | critical | `CodexRuntimeRegistryServiceTest` 凭据轮换与 fail-closed 单元测试 | 精确 Maven 命令、测试数量与结果 |
| AC-3/AC-4 | major | runtime DTO 与 Controller/Client 相关回归；确认既有 termination inspection 测试通过 | 测试断言与命令结果 |
| 全部 | major | `git diff --check` 与 scoped review | changed paths、deviations、residual risks |

## Bug Context

- bug_source: user-report
- severity: critical
- environment: `dev-kvm-jdk17.foggysource.com`，历史 App Server 任务 `20260719-77a3`。
- current_behavior: 浏览器 Bearer 已通过 Navigator 鉴权，但 `termination-inspection` 被 Worker 以 `Missing or invalid Authorization header` 拒绝并包装为 `A600`；历史任务无法再次中止。
- expected_behavior: 合法用户可以对精确绑定的历史任务执行只读检查和授权中止，Worker 收到当前有效 Bearer。
- reproduction_steps:
  1. 用空或旧 token 的 endpoint profile 同步 runtime 并创建 App Server 任务。
  2. 在相同 Worker 和 URL 上配置/轮换 Worker token，再同步 endpoint。
  3. 对旧任务调用 `GET /api/v1/tasks/{taskId}/termination-inspection`。
  4. 观察旧实现仍使用历史 runtime token，Worker 返回 401 或 403。
- reproduction_status: confirmed
- existing_evidence: Worker 401 文本只在 Authorization 缺失/格式非法时返回；Java 客户端仅在绑定 token 非空时添加 Bearer；runtime revision 创建时复制 endpoint token，旧 revision 不随 endpoint 更新。
- existing_tests: 已覆盖 Worker 客户端添加 Bearer、runtime affinity 与 BUG-004 termination inspection，但未覆盖 endpoint token 轮换后的历史任务。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - endpoint URL 规范化必须复用现有保存语义，避免仅因尾部斜杠产生误匹配或跨地址借用 token。
  - token 清除代表撤销，不得为了可用性回退旧 snapshot。
  - 现场旧任务是否还存在原生 turn 由 Worker 状态决定；本修复只恢复安全调用能力，不伪造终态。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 对可稳定复现的凭据轮换缺陷先建立失败回归，再修复并运行通过。
- 如需改变目标、范围、兼容、安全边界或数据库结构，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: `ENDPOINT_SYNC` runtime 在 endpointId、workerId 和规范化 URL 精确匹配时，构造 Worker client 与任务绑定时改用 endpoint profile 的当前 credential；保留原 runtime revision、instanceId 和 endpoint URL。endpoint token 清除会立即生效，不回退历史 snapshot。runtime DTO 新增只读 `tokenConfigured` 布尔诊断字段。
- changed_paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexRuntimeRegistryService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/dto/CodexRuntimeDTO.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexRuntimeRegistryServiceTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-005-codex-runtime-worker-token-rotation.md`
- tests_and_results:
  - failure-first: `mvn -pl addons/codex-worker-agent -am -Dtest=CodexRuntimeRegistryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`；修复前 100 tests、3 failures，分别证明空 snapshot 未采用当前 token、旧 snapshot 未采用轮换后 token、token 清除后错误回退旧 token。
  - focused regression: `mvn -pl addons/codex-worker-agent -am -Dtest=CodexRuntimeRegistryServiceTest,CodexAppServerEndpointServiceTest,CodexWorkerClientTest,CodexTaskExtensionControllerTest,CodexTaskServiceTest,CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test`；341 tests，0 failures/errors/skips，BUILD SUCCESS，完成于 2026-07-20 13:45:45 +08:00。
  - full module reactor: `mvn -pl addons/codex-worker-agent -am test`；Codex Worker Agent 465 tests，0 failures/errors/skips，全部依赖模块 SUCCESS，BUILD SUCCESS，完成于 2026-07-20 13:47:36 +08:00。
  - hygiene: `git diff --check` 通过。
- manual_or_experience_evidence: 当前执行环境连接 `dev-kvm-jdk17.foggysource.com` 超时，未声称完成现场调用验证；部署后需对任务 `20260719-77a3` 重试 `termination-inspection` 和授权中止。实现不修改现有 API path、浏览器 Authorization 合同或 Worker 协议。
- deviations: none
- residual_risks:
  - 修复需部署并重启 Navigator Java 后端后才会影响现场请求；无需发布 Codex Worker。
  - 历史任务对应的原生 turn 是否仍在 Worker 中取决于现场状态；本修复恢复鉴权调用能力，不伪造任务状态。
  - 现场 live smoke 因网络不可达待部署环境补验。
  - 用户在问题描述中暴露的 Bearer JWT 应立即轮换；仓库未写入该凭据。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-20 报告 Worker 与平台更新后历史任务仍无法中止。
- architecture / glossary: endpoint profile、runtime revision、runtime instance affinity、Worker HTTP credential。
- related work items: `BUG-004-codex-cancel-execution-and-retry-confirmation.md`。
