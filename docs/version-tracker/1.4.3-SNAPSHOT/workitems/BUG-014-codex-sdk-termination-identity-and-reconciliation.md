---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-014
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-22
open_questions: []
---

# Delivery Spec: Codex SDK Worker 受控中止身份、重试与退出收口修复

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex SDK Worker 中止身份配置冲突、未确认 operation 阻塞和 CLI 已退出后任务无法收口的唯一交付契约。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-014-codex-sdk-termination-identity-and-reconciliation.md

## Goal

- version_goal: 使 Navigator 管理的 Codex SDK 任务具备可部署、可诊断、可重试且基于真实进程证据的中止闭环。
- target_outcome: 普通中止和已绑定 PID 手工中止均能通过独立 PhysicalWorker ID 校验；未确认请求可显式重试；CLI 已退出时任务基于精确 Worker/task/process 证据收口为 `ABORTED`，不再永久停留在 `CANCEL_REQUESTED`。

## Scope

- in_scope:
  - 解耦 `CODEX_NAVIGATOR_WORKER_ID` 与 `CODEX_NAVIGATOR_WORKER_CREDENTIAL`；终止操作继续使用既有 `CODEX_WORKER_TOKEN` HMAC 和 PhysicalWorker ID 精确匹配。
  - 增加不泄露身份或凭据的 termination readiness；安装、升级和配置文档支持显式写入并保留 PhysicalWorker ID 与持久 receipt ledger。
  - Java 控制面保留 Worker 返回的安全终止错误码；未确认 SDK operation 可经用户明确确认后安全重试，成功日志不得把未派发或未确认请求描述为已取消。
  - 对已经 `CANCEL_REQUESTED`、Worker task 404 且精确进程快照证明绑定 CLI 不存在的 SDK 任务，收口为 `ABORTED` 并同步 operation/session/task 投影；不能证明时继续 fail closed。
  - 对任务中止、再次中止和 PID 终止的前端交互提供可行动状态与稳定错误提示。
  - 运行分层自动化、前端构建和 SDK Worker `full` release smoke；仅发布 SDK Worker，部署目标 Navigator 后端/前端与既有 PhysicalWorker，并恢复现场任务 `20260722-6cfb`。
- affected_modules:
  - `tools/codex-agent-worker`
  - `addons/codex-worker-agent`
  - `session-module`
  - `packages/navigator-frontend`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 现有 PhysicalWorker 配置、Worker bearer token、持久 termination receipt ledger、OBS Codex SDK Worker 发布链路、目标环境 `dev-kvm-jdk17-2.foggysource.com`。

## Non-Goals

- out_of_scope:
  - 不实现或放开 WorkerGateway 长期 credential 向 Codex CLI、Shell 或 MCP 子进程转发。
  - 不修改或发布 Codex app-server Worker。
  - 不新增数据库 schema、跨 Worker 分布式 receipt 存储、任意 PID/进程树模糊终止或 watchdog 自动终止。
  - 不通过直接修改数据库把任务伪造为终态；现场恢复必须来自已批准取消状态和可验证的 Worker/task/process 缺失证据。
  - 不在本工作项内整改 Java root password 的启动参数注入方式；只保留独立安全风险和轮换要求。
- do_not_touch: Claude/Gemini Worker、上游 TMS/SIM 仓库、Gateway external/production gate、既有 App Server exact turn 重试语义。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| PhysicalWorker ID 是非秘密终止目标，可独立于 Gateway credential 配置 | 当前成对约束使终止能力与禁止转发的长期凭据互相阻塞 | `CODEX_NAVIGATOR_WORKER_CREDENTIAL` 仍保持 fail-closed，且继续从所有模型可控子进程环境移除 |
| 终止签名继续使用 `CODEX_WORKER_TOKEN` | Java 与 Worker 已有 HMAC 协议和 token 轮换路径，不引入第二套终止 secret | token、签名、credential 和原始 operation 不得出现在 UI、普通日志或证据中 |
| `/health` 新增独立 termination readiness，保留既有 execution `ready` 兼容语义 | standalone Worker 仍可执行任务，同时 Navigator 部署可明确拒绝/告警不可中止实例 | readiness 只能返回布尔值和稳定原因码，不返回 Worker ID、路径或 secret |
| SDK 再次中止必须是用户明确确认的新 operation | 不能静默重放一次性 capability，也不能让旧 `UNCONFIRMED` operation 永久阻塞 | 新 operation 前关闭/取代旧活动记录；继续保持单任务同一时刻至多一个 destructive operation |
| `CANCEL_REQUESTED` + Worker task 404 + 精确绑定进程不存在可收口为 `ABORTED` | 已有用户取消意图且两级远端证据均证明执行已不存在 | 任一查询失败、绑定歧义或进程仍存在时不得终态化，只能保留可恢复诊断 |
| SDK Worker 单独升版、发布和升级；Java/前端另行部署 | 只有 SDK Worker 目录包含 packaged runtime 变化 | app-server Worker 版本和 OBS latest 不变 |

## Acceptance Criteria

- [x] AC-1: Worker 允许只配置合法的 `CODEX_NAVIGATOR_WORKER_ID`；不配置 Gateway credential 时 execution health 保持兼容，签名中止可精确校验 Worker ID，模型可控子进程仍无法看到 Worker ID/token/credential。
- [x] AC-2: health 返回安全的 termination readiness；缺 Worker ID、缺 Worker token 或 receipt ledger 不可用时给出稳定原因码；安装/升级流程可显式写入并保留 Worker ID，且不回显 secret。
- [x] AC-3: Worker 的安全 `TERMINATION_*` 拒绝码可到达 Java/UI；未确认 SDK operation 不再产生误导成功日志，用户确认“再次中止”后会关闭旧 operation 并派发新的一次性 capability。
- [x] AC-4: 已 `CANCEL_REQUESTED` 的 SDK 任务在 Worker task 404 且精确进程快照证明不存在时变为 `ABORTED`，相关 termination operation 标记 observed，session/task 投影同步；证据不充分时状态不变。
- [x] AC-5: 普通中止、再次中止、平台管理员 PID 终止和错误提示具备 Worker/Java/前端自动化回归；前端生产构建通过。
- [x] AC-6: Codex SDK Worker 使用新版本完成 all-platform `full` package smoke 并从 clean、pushed commit 发布；远端 `latest.json`、archive、checksum、bootstrap 与 evidence 校验通过，app-server Worker 明确未发布。
- [x] AC-7: 目标 Navigator 后端/前端和 PhysicalWorker 完成归属确认后的部署；health 显示 termination ready，现场任务 `20260722-6cfb` 通过受控路径收口，两个中止入口至少各完成一次安全 live 验证或明确记录环境阻断。

## Contract / Data / Security Constraints

- API or event contract: 现有 task cancel、PID kill path 与终态枚举保持兼容；可新增 SDK termination inspection/retry 或 health 可选字段，但旧客户端必须可忽略。Worker 拒绝仅透出白名单稳定码。
- data and migration: 无 schema 迁移；复用 `termination_operations`，重试必须保留旧 operation 审计记录并创建新 operation。
- compatibility and rollback: Worker/Java/前端可按提交整体回滚；回滚后 termination readiness 和安全重试能力消失，但不得破坏历史任务数据。旧 Worker 缺 PhysicalWorker ID 时继续 fail closed。
- permissions and secrets: 不读取、输出、提交或记录 JWT、Worker token、Gateway credential、root password、签名 capability 或原始命令行。目标配置变更只记录键名、是否配置和稳定资源 ID。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical | Worker config/health/termination/env-isolation/release-installer tests；typecheck | failure-first test、精确命令与结果 |
| AC-3/AC-4 | critical | CodexTaskService、Worker client/controller、termination operation service focused tests；affected Maven reactor | failure-first test、状态/operation 断言、精确命令与结果 |
| AC-5 | major | frontend API/view tests；`bash scripts/build-frontend.sh` | test/build 输出和必要的 UI 行为证据 |
| AC-6 | critical | `npm run package:release -- --platform all --smoke full`；发布脚本远端校验 | `smoke-result.json`、manifest、SHA-256、远端 latest 和 archive 校验 |
| AC-7 | critical | 进程 command line/cwd 归属确认；目标 health、cancel/PID live smoke、任务和 operation 只读核验 | 脱敏命令、时间、稳定状态码和结果；不保存 secret |

验证顺序：focused（<5m）→ affected Worker/Java/frontend lane（5-30m）→ 一次 final full package/build/deploy run（预计 30-60m）。昂贵验证最多两次；仅当源码、依赖、版本、release 脚本或候选字节改变时重跑。连续两次因非产品环境失败则设置 `NEEDS_REPLAN`，不无限重试。已生成且输入未变化的 checksum、archive 和 focused test 证据可复用。

## Bug Context

- bug_source: user-report
- severity: critical
- environment: `dev-kvm-jdk17-2.foggysource.com`；Navigator PhysicalWorker `36508966`；Codex SDK Worker `1.0.18`；任务 `20260722-6cfb`。
- current_behavior: Worker execution health 为 ready，但缺少终止所需 PhysicalWorker ID；普通中止返回 503 并被折叠为 `ServiceUnavailable`，旧 operation 阻塞再次中止和手工 PID kill；CLI 已退出后 task registry/watchdog 仍保留非终态，Navigator 永久停在 `CANCEL_REQUESTED`。
- expected_behavior: 部署态明确展示并满足 termination readiness；两个中止入口可执行或返回可行动的稳定拒绝码；已取消且精确证明执行不存在的任务收口为 `ABORTED`。
- reproduction_steps:
  1. 对运行中的 SDK task 调用 task cancel。
  2. Worker 因 `CODEX_NAVIGATOR_WORKER_ID` 未配置返回 503，Java 记录 `RUNNING/UNCONFIRMED`。
  3. 在 operation TTL 内再次中止或点击绑定 PID 的“终止/强制”，观察旧 operation 阻塞新请求。
  4. CLI 已退出后查询 Worker/task，观察 Worker 仍记住非终态 task，Navigator 继续 `CANCEL_REQUESTED`。
- reproduction_status: confirmed
- existing_evidence: 目标后端脱敏日志、MySQL task/operation 状态、Worker `/health`、源码配置和状态机核对。
- existing_tests: BUG-004/006/012/013 已覆盖真实取消、历史 reconciliation、平台 PID 授权和非终态诊断，但未覆盖 ID/credential 解耦及已取消任务缺失时的 `ABORTED` 收口。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - Worker 升级会重启并清空内存 task registry；现场任务恢复必须依赖持久 Navigator task/operation 和精确进程缺失核验，不能依赖旧内存状态。
  - 发布要求 commit/push 后 clean worktree；Java/前端部署不等同于 Worker OBS 发布。
  - 对话中暴露的 Bearer JWT 和进程命令行中的 root password 需要环境 owner 独立轮换；本实现不得复用或记录这些值。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `codex-worker-deploy` skill。
- 按用户确认的 1～5 顺序推进；在 scope 内自主决定具体文件、类和实现结构。
- 对稳定可复现路径先增加失败回归，再修复并运行通过。
- 如需改变目标、范围、兼容、安全边界、数据库结构或任意 PID 权限，设置 `NEEDS_REPLAN` 并停止扩展。
- 记录 changed paths、精确测试/构建/发布/部署命令和结果、偏差及残余风险。
- 完成后将本文件更新为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - Codex SDK Worker 终止目标身份与 Gateway credential 已解耦；新增独立 termination readiness、持久 receipt ledger 探针以及安装/升级时的 Worker ID/ledger 保留逻辑，Worker 版本升至 `1.0.19`。
  - Java 控制面保留白名单 `TERMINATION_*` 拒绝码，普通取消不再把未派发/未确认请求报告为成功；SDK `CANCEL_REQUESTED` 支持显式再次中止，并在 Worker task 404 与精确 CLI 进程缺失同时成立时收口 `ABORTED`。
  - 前端为普通中止、SDK 再次中止和绑定 PID 终止展示稳定可行动错误，SDK 重试必须经过用户确认。
- changed_paths:
  - Worker runtime/release: `tools/codex-agent-worker/{src,tests,scripts,release,docs,.env.example,package.json,package-lock.json}`。
  - Java: `addons/codex-worker-agent/src/{main,test}`、`session-module/src/{main,test}`。
  - Frontend: `packages/navigator-frontend/src/api/{claudeWorker.ts,unifiedTask.ts}`、`packages/navigator-frontend/src/views/ClaudeWorkerView.vue` 及其集成测试。
  - Delivery tracking: `docs/version-tracker/1.4.3-SNAPSHOT/README.md` 与本工作项。
- tests_and_results:
  - `cd tools/codex-agent-worker && npm test && npm run typecheck`：通过；228 项（227 passed，1 项 Windows-only skip），typecheck 通过。
  - `mvn -pl addons/codex-worker-agent,session-module -am -Dtest=CodexWorkerClientTest,CodexWorkerControllerTest,CodexTaskServiceTest,CodexTaskExtensionControllerTest,TaskControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过；所选测试合计 227 项，0 failure/error。
  - `pnpm --filter @foggy/navigator-frontend exec vitest run src/views/__tests__/ClaudeWorkerView.integration.test.ts`：通过；48/48。
  - `bash scripts/build-frontend.sh`：通过；类型检查、workspace 前端测试和生产构建均成功，Navigator frontend 273/273。
  - `mvn -q -pl addons/codex-worker-agent,session-module -am test`：未通过；未改动的 `navigator-common` 冻结 catalog 基线有 2 项既有差异（期望 201/实际 234，以及冻结证据字节不一致），受影响聚焦测试已独立通过。
  - `cd tools/codex-agent-worker && npm run package:release -- --platform all --smoke full`：通过；执行 archive structure、SHA-256 sidecar、forbidden-file scan、候选包 `npm ci` 与候选 health，`packageVerificationSkipped=false`。
  - `cd tools/codex-agent-worker && npm run publish:obs -- --obsutil <temporary-wrapper>`：通过；发布脚本逐项回读并校验远端 `latest.json`、三平台 archive、checksum、`install.sh`、`install.ps1` 与 release evidence。
  - 目标后端 `mvn package -pl launcher -am -DskipTests`：14/14 reactor `BUILD SUCCESS`；目标前端 `bash scripts/start-build-frontend.sh`：通过。
  - `git diff --check`：通过。
- release_and_deployment_evidence:
  - 源提交 `7d9b222d9c744ef4be5a6bd0a4d3f0b902ba0970` 已推送到 `origin/main`，发布时 Worker worktree clean，release evidence 记录 `gitDirty=false`。
  - Codex SDK Worker `1.0.19` all-platform 产物：Linux/macOS SHA-256 `57ae605262ccde49a63adcba1c40eea3c240ec6fa074508f49b7d5e398038e00`；Windows SHA-256 `89f4d2bb388eab79064b0a179370fd91d314fa8eb19cd5deb674181aa085ad46`。
  - OBS 远端 manifest 为 `product=codex-agent-worker`、`schemaVersion=1`、`version=1.0.19`、上述 git commit 与 archive hash/bytes；Codex app-server Worker 未修改、未发布。
  - 目标 Navigator 进程归属已由 PID、cwd 与 JAR 路径共同确认；部署后 commit 为 `7d9b222d...`，后端 cwd 为 `/home/sa/Foggy-Navigator`、JAR 为 `launcher/target/launcher-1.0.0-SNAPSHOT.jar`、health `UP`。前端 Nginx 挂载同仓库 `packages/navigator-frontend/dist`，HTTP 200。
  - PhysicalWorker `36508966` 的 Codex SDK Worker 已从 `1.0.18` 升级到 `1.0.19`；2026-07-23 live health 为 `ready=true`、`termination_ready=true`、`termination_reasons=[]`，Worker ID、termination auth 与持久 replay ledger 三项均 ready；Codex SDK `0.145.0` 满足最低 `0.144.1`。
- manual_or_experience_evidence:
  - 2026-07-23 现场任务 `20260722-6cfb` 初始为 `CANCEL_REQUESTED` / `TERMINATION_OPERATION_PENDING`，其 provider task/PID 已不在精确 Worker 进程快照中；两个现存 PID 均绑定其他任务。
  - 调用正式 `POST /api/v1/tasks/20260722-6cfb/termination-retry` 后首次响应即为 `providerState=interrupted`、`status=ABORTED`；统一任务查询同步为 `ABORTED` 且 `errorMessage=null`，未终止任何无关进程。
  - termination operation 只读核验显示：最新 `REMOTE_CANCEL` 为 `ABORTED/OBSERVED` 并具有 `observedAt`；更早的未确认 operation 保留为 `FAILED/UNCONFIRMED`、`TERMINATION_OPERATION_EXPIRED` 审计记录。
  - 终态后再次调用普通 cancel 返回 `Task already in terminal state: ABORTED`，验证普通入口幂等。会话容器保持可继续的 `ACTIVE`，其 `updatedAt` 与任务收口同步更新；任务投影为权威终止状态。
  - 目标任务已无绑定 PID，正向 live PID kill 不具备安全对象。按 fail-closed 边界，以目标 taskId 请求终止一个属于其他任务的 PID，后端返回 PID/task binding 无法确认的拒绝；调用前后两个 PID 和 task binding 完全不变，构成第二入口的 live 安全验证及环境阻断证据。
- deviations:
  - 未为了正向 PID smoke 创建或终止额外 live 任务；现场目标进程已退出，且仅有的两个 PID 属于其他任务。自动化已覆盖绑定 PID 的成功路径，live 环境采用明确 mismatch 拒绝和进程集合不变证明，避免扩大破坏性范围。
- residual_risks:
  - `navigator-common` 冻结 catalog 基线失败不属于 BUG-014，独立 signoff 需明确接受或由其 owner 更新冻结证据。
  - Worker `npm ci` 报告 1 项 low-severity 依赖漏洞；未影响本次构建、health 或终止链路，但应进入依赖维护队列。
  - 已暴露的 Bearer JWT 与 Java 启动参数中的 root password 仍需环境 owner 轮换。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户 2026-07-22 报告 CLI 已中止但 Navigator 无法进入中止状态，task 与 PID 两组中止按钮均失效，并批准按诊断方案 1～5 实施。
- architecture / glossary: PhysicalWorker ID、Worker bearer token、Gateway credential、termination operation、`CANCEL_REQUESTED`、verified process absence。
- related work items: BUG-004、BUG-006、BUG-012、BUG-013；1.4.2-SNAPSHOT GOV-004 lifecycle runbook。
