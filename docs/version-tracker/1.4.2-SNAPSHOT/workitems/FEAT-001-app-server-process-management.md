---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.2-SNAPSHOT
ticket: FEAT-001-app-server-process-management
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository user
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: Codex App Server process management visibility

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 在 Workers 页面现有“CLI 进程”视图中纳入已配置的 Codex App Server Worker 运行态。
- canonical_path: docs/version-tracker/1.4.2-SNAPSHOT/workitems/FEAT-001-app-server-process-management.md

## Goal

- version_goal: 让 Worker 管理台能够观察 Codex App Server 的受管运行时，不再只显示 SDK Codex CLI 进程。
- target_outcome: 选择物理 Worker 后，页面合并显示其已配置 App Server endpoint 的安全进程快照、来源 endpoint 和关联任务；共享 PID 语义清晰且不放宽终止授权。

## Scope

- in_scope: 为控制平面增加读取并聚合该物理 Worker App Server endpoint 的 `/api/v1/processes` 快照；前端请求、类型和进程表展示该快照；补充针对聚合与 UI 请求/展示的自动化回归。
- affected_modules: `addons/codex-worker-agent`; `packages/navigator-frontend`; 本 work item。
- external_dependencies: 已存在的、已授权配置的 `codex-app-server-worker` HTTP API 与 endpoint 配置记录。

## Non-Goals

- out_of_scope: 更改 App Server pool/child 生命周期、endpoint 配置模型、Worker 发布部署或数据库迁移；改变普通 Codex/Claude/Gemini 进程管理。
- do_not_touch: 不向浏览器泄露 endpoint token、命令行、cwd、环境变量或 process identity；不按 PID 自动终止、不为共享 PID 增加未授权的前端 kill 动作。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 复用 App Server 现有受管快照而非扫描 OS 进程 | 该 API 已刻意只投影稳定的 task-to-runtime 身份 | 不泄露命令、路径、环境与 provider 输出 |
| 将 App Server 标记为独立 `codex-app-server` 进程类型 | PID 可能由多个 Thread/任务共享，不能伪装成普通 Codex CLI | UI 显示为共享运行时，并列出关联任务数/来源 |
| 此次仅提供观察与安全状态 | 共享 PID 的手动 kill 必须依赖服务端签名 capability 和唯一所有者证明 | 既有任务 abort / 受控 PID 终止安全边界保持不变 |

## Acceptance Criteria

- [ ] AC-1: 控制平面仅对当前用户拥有的物理 Worker 的已配置 App Server endpoint 查询进程快照，单 endpoint 失败不阻断其他 endpoint 的正常结果，且错误不含凭据。
- [ ] AC-2: Workers 的“CLI 进程”标签合并显示 App Server 记录，清楚标注为共享 App Server 运行时、关联 Navigator task 及 endpoint 展示名。
- [ ] AC-3: App Server 行不提供普通 PID kill / force kill，且现有 Claude、Codex SDK、Gemini 的读取和操作行为不退化。
- [ ] AC-4: 后端针对 endpoint 聚合与前端针对请求/展示的回归测试通过；前端生产构建通过。

## Contract / Data / Security Constraints

- API or event contract: 新增同源控制平面读取 API；Worker 内部 `/api/v1/processes` 协议保持不变。
- data and migration: 不新增表、不迁移数据。
- compatibility and rollback: 未配置 endpoint 时返回空列表；移除新控制平面路由和前端请求即可回滚。
- permissions and secrets: 继承物理 Worker ownership 校验；endpoint token 仅在服务端解密后供 HTTP client 使用，禁止返回或记录。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 / AC-3 | major | 后端定向单元或 Web 层测试 | 实际命令和结果 |
| AC-2 | major | 前端单测 | 实际命令和结果 |
| AC-4 | major | `bash scripts/build-frontend.sh` | 实际命令和结果 |

## Risks and Open Questions

- known_risks: endpoint 暂时不可达时，只显示其他可达来源；真实 App Server PID 的内存与启动时间不属于安全快照，UI 必须显示为未知而非猜测。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 新增控制平面只读聚合端点 `GET /api/v1/codex-app-server-workers/{workerId}/processes`。它按 endpoint + resident PID 合并 Worker 的 task-to-runtime 快照，只投影 PID、固定进程类型、endpoint origin 与关联 task；前端将其合并到“CLI 进程”表，显示共享运行时/任务数，并禁止该行的普通 PID kill。
- changed_paths: `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexAppServerProcessController.java`; `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexAppServerProcessService.java`; `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexAppServerProcessServiceTest.java`; `packages/navigator-frontend/src/api/claudeWorker.ts`; `packages/navigator-frontend/src/types/index.ts`; `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`; `packages/navigator-frontend/src/views/__tests__/ClaudeWorkerView.integration.test.ts`; 本 work item。
- tests_and_results:
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexAppServerProcessServiceTest,CodexWorkerControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` — exit 0；`CodexAppServerProcessServiceTest` 1/1、`CodexWorkerControllerTest` 3/3 passed。
  - `packages/navigator-frontend/node_modules/.bin/vue-tsc -b --noEmit`（cwd: `packages/navigator-frontend`）— exit 0。
  - `packages/navigator-frontend/node_modules/.bin/vitest run src/views/__tests__/ClaudeWorkerView.integration.test.ts` — not runnable: current Node.js is `18.19.1`; installed Vite 7 transform aborts with `TypeError: crypto.hash is not a function` before tests are collected.
  - `bash scripts/build-frontend.sh` — not runnable: script reports `pnpm not found! Use Node 22.23.1 and run: corepack enable`.
- manual_or_experience_evidence: 静态检查确认 App Server 行显示“共享 · N 任务”和 endpoint tooltip，操作列仅显示“任务取消”，不会渲染 PID 终止/强制终止按钮。
- deviations: none
- residual_risks: 未在浏览器中实际执行 UI 回归或生产构建；需要使用 Node 22.23.1、启用 Corepack/pnpm 后重跑前端 Vitest 和 `bash scripts/build-frontend.sh`。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 2026-07-16 user request: "补下吧~"
- architecture / glossary: docs/02-modules/worker-workspace-center.md; docs/terminology-glossary.md
- related work items: docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-007-app-server-single-instance-containment.md
