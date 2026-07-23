---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-016
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-23
open_questions: []
---

# Delivery Spec: Open API safe ask request-scoped empty surfaces

## Document Purpose

- intended_for: implementation / CLI release candidate / independent-signoff
- purpose: 修复 Navigator Open API ask 中显式空 Codex tool 与 Navigator BusinessFunction scope 被折叠、混淆或被 ClientApp grants 回填的问题，并修复 runtime token profile 的文件权限回退。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-016-openapi-safe-ask-request-scoped-empty-surfaces.md`

## Goal

- version_goal: 为 SIM standalone safe ask 提供可审计、request-scoped、fail-closed 的零工具/零 BusinessFunction 契约。
- target_outcome: 普通 ask 中 `allowedTools=[]` 与独立的 `allowedFunctions=[]` 均被原样保留；专用 safe-smoke endpoint 以不 dispatch Worker/模型的方式证明零 runtime tool surface，并创建后立即吊销一个 `function_scope_json=[]` 的 task-scoped token；task 回包给出脱敏 effective counts 和 scope source；Linux/WSL profile 原子写入后保持 `0600`。

## Scope

- in_scope:
  - CLI 新增 `--allowed-functions none|<csv>`，其中 `none` 编码为显式空数组；`--allowed-tools ''` 也必须编码为显式空数组。
  - CLI/API 新增 dedicated `safe-ask` / `/safe-smoke`，强制 `maxTurns=1`、`allowedTools=[]`、`allowedFunctions=[]`，且不 dispatch Worker/模型。
  - Open API ask 区分未提供、显式为空和非空 allowlist，并在 task 创建/Worker dispatch 前校验未知 function code 与 ClientApp grant ownership mismatch。
  - 显式 `allowedFunctions=[]` 覆盖 grant-derived scope，使 task-scoped token 的 `function_scope_json` 为 `[]`。
  - 返回 sanitized `effectiveToolCount`、`effectiveFunctionCount`、各自 scope source，以及 task token function scope 是否为空。
  - runtime token `--write-profile` 在 POSIX 文件系统同目录原子替换并保持 owner-only `0600`。
  - 增加 CLI、Open API、token policy 和 profile permission 回归测试，并产出本地 CLI release candidate 标识。
- affected_modules:
  - `navigator-open-sdk`
  - `addons/claude-worker-agent`
  - `business-agent-module`
  - `addons/codex-worker-agent`（仅 request tool-surface 空集合传播，不修改 Worker 资源）
  - `tools/codex-agent-worker`（仅在真实运行时适配器仍把显式空集合解释为默认工具时修改源码与测试）
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none；不读取 SIM runtime credential，不创建 task，不提交 ask。

## Non-Goals

- out_of_scope:
  - 删除或修改 ClientApp 其他 Agent 使用的全局 BusinessFunction grants。
  - 新建、修改或重绑 Worker、Directory、Agent、ModelConfig、WorkerPool 或 BizWorkerIdentity。
  - 启用 external Gateway、Gateway strict、production 或真实上游业务访问。
  - 提供 system-admin grant/ungrant 作为本 BUG 的替代路径。
- do_not_touch:
  - 当前脏工作树中的 Codex Worker 升级改动、任何 sibling workspace、真实 profile/token/credential、数据库授权数据或运行中的 Worker 资源。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 普通 ask 采用方案 A，新增独立 `allowedFunctions` | `allowedTools` 不能表达 Navigator BusinessFunction inventory scope | 字段缺失继续使用 grant-derived scope；显式空数组强制为零 |
| 严格零工具 safe ask 采用方案 B，新增 no-runtime safe-smoke endpoint | Codex SDK/runtime 的 `allowedTools` 不是可证明覆盖所有 native tool registration 的 request-scoped 安全边界；只把 Business MCP wrapper 置空不能诚实声明原生工具数为零 | endpoint 不调用 Worker/model task submission；返回 `toolScopeKind=NO_RUNTIME_MODEL_TOOL_SURFACE` 和 `runtimeDispatched=false` |
| CLI `none` 是空 allowlist 的可读别名 | shell 中空字符串容易被配置 fallback 或 wrapper 吞掉 | payload 永远编码 `[]`，不编码 `null` |
| requested function 必须是当前 ClientApp 已启用 grant 的子集 | request 只能收窄，不能扩大授权 | unknown code 与 ownership/grant mismatch 使用不同 fail-closed 类别 |
| counts/source 由服务端解析后的 effective scope 生成 | 客户端回显请求不能证明实际执行 scope | 不返回 function 清单、token 或 credential |
| POSIX profile 使用同目录临时文件和 atomic replace | 防止权限窗口、部分写入及既有 `0664` 继承 | Windows 保持兼容 fallback；POSIX 最终 mode 必须为 `0600` |

## Acceptance Criteria

- [x] AC-1: Codex/request tool allowlist 与 Navigator BusinessFunction allowlist 使用独立字段、独立 source、独立 kind 和独立 effective count。
- [x] AC-2: 未提供字段保持 grant/default 语义；显式空数组在 CLI、SDK、JSON、metadata、Worker launch context 和 token scope 中保持 `[]`，不得折叠为 `null` 或缺失。
- [x] AC-3: `allowedFunctions=[]` 覆盖 grant-derived function scope，task-scoped token 持久化 scope 为 `[]`；sanitized response 为 `effectiveFunctionCount=0` 且明确 request-empty source。
- [x] AC-4: 未提供、显式为空、未知 function code、ownership/grant mismatch 具有明确且不同的 fail-closed 语义；校验失败时不创建 Worker task 或 dispatch Worker。
- [x] AC-5: `maxTurns=1` dedicated safe-smoke payload 通过 no-runtime contract 证明 `effectiveToolCount=0`、`effectiveFunctionCount=0`、task-scoped token function scope 为空且 token 已吊销；本修复会话未使用 SIM credential 实际提交 ask。
- [x] AC-6: Linux/WSL `runtime token --write-profile` 同目录原子替换已有 profile，并将最终权限保持为 `0600`。
- [x] AC-7: 未变更全局 grants、运行资源、Gateway/external/strict/production 开关或真实业务数据。

## Contract / Data / Security Constraints

- API or event contract: 新增可选 `allowedFunctions` / `allowed_functions`；absent 向后兼容，explicit empty 是新的收窄语义。错误响应必须稳定区分 `UNKNOWN_FUNCTION_CODE` 与 `FUNCTION_SCOPE_OWNERSHIP_MISMATCH`。
- data and migration: 不新增 schema migration；继续使用现有 `function_scope_json`，显式空集合写为 JSON `[]`。
- compatibility and rollback: 旧客户端不发送字段时行为不变；回滚会失去 request-scoped function 收窄能力，因此 SIM 必须继续 fail closed。
- permissions and secrets: 所有证据只记录 count/source/boolean/build provenance，不读取或回显 token、function inventory、profile 内容或 grant 明细。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| CLI/SDK payload | critical | omitted、empty、none、non-empty serialization tests | exact focused Maven result and sanitized request assertions |
| Open API propagation | critical | empty arrays preserved; response diagnostics mapped | controller focused test result |
| token policy | critical | grant-derived、explicit empty、subset、unknown、mismatch | policy/service test result and `function_scope_json=[]` assertion |
| runtime adapter | critical | explicit empty produces zero exposed request tool names | focused Java/TypeScript test if affected |
| profile security | critical | pre-existing `0664` becomes `0600`; atomic same-directory replacement | POSIX-gated test result and no secret output |
| hygiene/build | major | changed-module tests, package, `git diff --check`, secret scan | exact commands/results and local build provenance |

## Bug Context

- bug_source: user-report
- severity: critical
- environment: SIM using navigator-upstream-cli `1.0.23+aa4a944e7f25`, source commit `aa4a944e7f2510fe1cfff623d92732df573fb9cf`, preparing foggy-world-sim `v0.0.828` standalone safe ask.
- current_behavior: CLI CSV parsing collapses empty values to `null`; Open API has no request-scoped BusinessFunction allowlist; task token snapshots all enabled ClientApp grants; profile atomic move may leave group-readable `0664` permissions.
- expected_behavior: explicit empty tool/function surfaces remain empty end-to-end, override grant-derived scope, and are proven by sanitized effective diagnostics; profile writes remain atomic and owner-only.
- reproduction_steps: build an ask with `--allowed-tools ''`; observe `allowedTools` omitted and no `allowedFunctions` contract; issue an Open API task token and observe grant-derived function scope; update a group-readable profile and observe non-`0600` result.
- reproduction_status: confirmed by source inspection; SIM live ask intentionally not executed.
- existing_evidence: user-provided CLI build provenance and fail-closed readiness report; current source paths identified in CLI, Open API controller and token policy.
- existing_tests: cover non-empty `allowedTools` and default grant snapshot, but not explicit empty or request-scoped function scope.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 普通 ask 的 `effectiveToolCount` 仅描述 Navigator Business MCP wrappers，不等价于所有 Codex native tools；严格零工具证明只由 no-runtime safe-smoke endpoint 提供。
  - A local build from an uncommitted dirty worktree cannot honestly claim a clean release gitCommit; build output must identify `gitDirty=true` until a later authorized commit/release step.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范以及 `navigator-runtime-provisioning` 专项技能。
- 在本契约范围内自主选择最小实现；若需要修改全局 grants、运行资源、binding、Gateway/external/production 或真实 credential，设置 `NEEDS_REPLAN` 并停止扩展。
- 不使用 SIM credential，不创建 runtime token/task，不提交探测 ask；以自动化和脱敏本地证据完成修复。
- 记录精确 changed paths、验证命令/结果、build provenance、偏差和残余风险。
- 完成后填写 `Implementation Result`，状态改为 `READY_FOR_SIGNOFF`；不得自行写 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - `navigator-upstream-cli` / SDK source 升级为本地 `1.0.24` candidate；provenance 明确记录已发布版本仍为 `1.0.23`、`SOURCE_NEWER_THAN_PUBLISHED`。普通 ask 支持 `--allowed-tools <csv|none>` 和 `--allowed-functions <csv|none>`，显式空字符串或 `none` 均序列化为 `[]`，字段未提供时保持 omitted。
  - 新增 `runtime safe-ask` 和 legacy top-level `safe-ask`，固定调用 `POST /api/v1/open/agents/{agentId}/safe-smoke`，强制发送 `maxTurns=1`、`allowedTools=[]`、`allowedFunctions=[]`，拒绝非空 override。
  - safe-smoke 只做现有 ClientApp/upstream user/Agent/skill/model 授权解析；创建一个精确 `function_scope_json=[]` 的 synthetic task token，校验后立即吊销，不调用 task submission、Worker 或模型，且不返回明文 token。
  - 普通 ask 的 request-scoped BusinessFunction policy 支持 grant-derived、explicit empty 和 allowlist subset；unknown function code 与 ClientApp ownership/grant mismatch 使用不同错误类别。
  - Open API/SDK task response 新增 sanitized `effectiveToolCount`、`effectiveFunctionCount`、`toolScopeSource`、`toolScopeKind`、`functionScopeSource`、`taskTokenFunctionScopeEmpty`、`runtimeDispatched`、`taskTokenStatus`。
  - Codex Java launch context 与 TypeScript Business MCP adapter 保留显式空 tool list；undefined 仍保持旧的默认 wrapper 语义。
  - POSIX profile 写入改为同目录 `0600` 临时文件、atomic replace、最终再次收紧为 `0600`；POSIX 不支持原子替换时 fail closed，Windows 保留兼容 fallback。
- changed_paths:
  - CLI/SDK: `navigator-open-sdk/pom.xml`, `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/{api,cli,model}/**`, provenance resource and CLI tests.
  - Open API: `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/{controller/openapi,model/dto,model/form}/**` and controller mapping tests.
  - Business authorization/token: `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/**`, worker launch request, policy/lifecycle/task tests and two E2E fixture compile updates.
  - Codex adapter: `addons/codex-worker-agent/**`, `tools/codex-agent-worker/src/business-mcp/navigator-business-mcp-server.ts` and its test.
  - Packaging/docs: `tools/navigator-upstream-cli/dist/package.{sh,ps1}` and this canonical work item.
- tests_and_results:
  - PASS — `mvn -q -pl navigator-open-sdk,addons/claude-worker-agent,business-agent-module -am -DskipTests compile`.
  - PASS — relevant Maven `test-compile` after fixture updates.
  - PASS — focused Maven suite: `UpstreamCliTest` 136, `OpenApiControllerMessageMappingTest` 48, `BusinessAgentTaskServiceTest` 35, `BusinessTaskScopedTokenLifecycleServiceTest` 34, `BusinessTaskScopedTokenPolicyServiceTest` 12, `CodexBusinessAgentWorkerTaskLauncherTest` 8; total 273, failures/errors/skips 0.
  - PASS — `tools/codex-agent-worker`: `node --import tsx --test tests/navigator-business-mcp.test.ts` 12/12 passed；完整 `npm test` 路径共 234 tests，233 passed、1 skipped、0 failed；`npm run typecheck` passed。
  - PASS — `mvn -q -pl navigator-open-sdk -am -DskipTests package`; produced `navigator-open-sdk-1.0.24.jar` and sources jar.
  - PASS — `git diff --check`; only working-copy CRLF normalization warnings.
  - BLOCKED (unrelated baseline) — full changed-module Maven reactor stopped in pre-existing `navigator-common` frozen authorization catalog checks: expected source count 201 vs actual 234 and evidence manifest length 431924 vs 400542. No attempt was made to rewrite unrelated frozen evidence.
- manual_or_experience_evidence:
  - Read-only `8112/actuator/health` returned `UP`; `8112/actuator/info` identifies the currently running old build as `1.0.0-SNAPSHOT`, commit `aa4a944`, `dirty=false`. No restart or mutation was performed.
  - Automated sanitized safe-smoke response assertions prove: `effectiveToolCount=0`, `toolScopeKind=NO_RUNTIME_MODEL_TOOL_SURFACE`, `toolScopeSource=SAFE_SMOKE_NO_RUNTIME`, `effectiveFunctionCount=0`, `functionScopeSource=REQUEST_EXPLICIT_EMPTY`, `taskTokenFunctionScopeEmpty=true`, `taskTokenStatus=REVOKED`, `runtimeDispatched=false`.
  - Token lifecycle test asserts stored `function_scope_json` is exactly `[]`; profile test starts with mode `0664`, writes atomically, and asserts final mode `0600` with no residual temp file.
  - No SIM credential was read; no live runtime token, task, ask, Worker, Directory, Agent/model binding, global grant or Gateway setting was created or changed.
- deviations:
  - The approved initial preference for option A alone was insufficient for a defensible `effectiveToolCount=0` across all Codex native tools. Official Codex `0.144.1` source exposes tool registration paths not governed by this Open API `allowedTools` contract, so strict safe verification uses option B (dedicated no-runtime endpoint) while retaining option A for BusinessFunction narrowing and Business MCP wrapper propagation.
  - No distributable archive was published and no release commit was created; provenance is a dirty local candidate until an authorized release step.
- residual_risks:
  - The safe-smoke task ID is a terminal synthetic evidence ID, not a pollable Worker task.
  - Ordinary ask with empty Business MCP wrappers may still expose Codex native capabilities according to runtime configuration; callers requiring strict zero must use `safe-ask`.
  - The running 8112 instance does not include this change until Navigator is rebuilt/deployed and 8112 restarted. The dedicated safe-smoke path does not require a Worker restart because it never dispatches one; using the ordinary ask adapter change requires the corresponding updated Worker deployment.
- readiness: READY_FOR_SIGNOFF

## References

- related schema repair: `BUG-007-task-token-function-scope-schema-contract.md`
- related runtime MVP: `GOV-001-dev-s1-s2-integration-mvp.md`
- upstream CLI release baseline: `REL-001-navigator-upstream-cli-1.0.22.md`
- Codex 0.144.1 Plan tool registration: `https://github.com/openai/codex/blob/rust-v0.144.1/codex-rs/core/src/tools/spec_plan.rs`
- Codex 0.144.1 configuration surface: `https://github.com/openai/codex/blob/rust-v0.144.1/codex-rs/config/src/config_toml.rs`
