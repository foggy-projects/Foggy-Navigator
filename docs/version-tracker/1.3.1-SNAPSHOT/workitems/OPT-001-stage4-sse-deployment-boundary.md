---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage4
severity: major
status: ready-for-acceptance
owner: session-module/java-platform
created_at: 2026-06-25
---

# OPT-001 Stage 4: SSE 部署边界治理

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 `UnifiedSseEmitter` 的单实例边界、多实例演进策略、断连清理与任务状态推送测试闭环。

## Background

`session-module` 当前使用 `UnifiedSseEmitter` 承载用户级 SSE 连接，并通过内存 Map 维护：

- `userEmitters`：用户到当前 JVM 内活跃 `SseEmitter` 的映射。
- `userSubscriptions`：用户订阅的 sessionId 集合。
- `sessionToUsers`：sessionId 到订阅用户的反向索引。

该设计适合单实例和开发环境。多实例部署时，这三组索引不会跨 JVM 共享；如果用户 SSE 连接、订阅 REST 请求、任务事件生产者落到不同应用实例，SSE 实时通知可能丢失或订阅关系不一致。

## Review Findings

1. `UnifiedSseEmitter` 已在 completion、timeout、error 和 heartbeat 失败路径清理订阅关系，但普通业务事件发送失败后只移除坏 emitter，缺少对空连接用户的统一订阅清理。
2. `createEmitter` 之前使用 `computeIfAbsent(...).add(...)`，连接加入与 map 映射更新不在同一临界区，重连和清理并发时存在误清订阅或新连接挂到旧列表的风险。
3. `TaskUpdateNotifier` 是任务状态面板的实时补偿入口，但缺少 `task_status_change`、`task_completion` 和缺失 userId/session 的跳过行为单测。
4. 架构文档已经提示 `UnifiedSseEmitter` 为内存态，但缺少本版本可执行的生产部署约束和非粘性多实例的明确非目标。

## Target Outcome

- 明确 `UnifiedSseEmitter` 为单 JVM 内存态实现，当前版本不承诺非粘性多实例下的 SSE 投递一致性。
- 本版本选定生产约束：单实例，或负载均衡按用户/会话粘性路由 `/api/v1/sse/**` 并保证事件生产者同实例亲和。
- 非粘性多实例作为后续演进：引入外部事件总线或集中通知服务，由全局订阅注册表定位持有 emitter 的实例。
- 普通业务事件发送失败、心跳失败、completion/timeout/error 均复用统一清理逻辑。
- 任务状态推送的直接 userId 路径和 parent session 反查 userId 路径有单测覆盖。

## Scope / Ownership

| Area | Owner | Touchpoints |
| --- | --- | --- |
| SSE emitter lifecycle | `session-module` | `UnifiedSseEmitter` |
| Task status push | `session-module` | `TaskUpdateNotifier` |
| SSE API boundary | `session-module` | `UnifiedSseController` |
| Architecture docs | `docs` | `docs/a2a-agent-architecture.md`、`OPT-001` 主工作项 |

## Deployment Boundary Decision

当前版本采用保守部署策略：

1. 支持边界：单应用实例，或具备用户/会话粘性的多实例部署。
2. 粘性范围：至少覆盖 `/api/v1/sse/**`，并要求订阅、取消订阅和相关任务事件生产链路与该用户 SSE 连接保持同实例亲和。
3. 恢复语义：浏览器断线重连后必须重新建立 SSE 连接并重新订阅活跃 session；错过的消息和任务状态以数据库查询、任务详情查询或 provider resync 作为最终补偿，不依赖 SSE replay。
4. 非目标：当前阶段不实现跨 JVM 订阅共享、不实现 SSE 事件持久化 replay、不承诺非粘性多实例实时投递。
5. 后续演进：如果需要非粘性横向扩容，优先引入外部事件总线或集中通知服务，再把 `userSubscriptions` / `sessionToUsers` 从进程内状态迁出。

## Implementation Plan

### Stage 4.1 - Boundary Docs and Cleanup Hardening

- [x] 记录 `UnifiedSseEmitter` 的单 JVM 内存态边界。
- [x] 选定本版本生产策略：单实例或粘性会话；非粘性多实例待事件总线/集中通知服务。
- [x] 将普通事件发送失败后的空连接用户清理收敛到统一方法。
- [x] 将连接建立的 emitter 列表更新改为 map compute 内完成，降低重连/清理并发竞争。

### Stage 4.2 - Focused Regression Tests

- [x] 覆盖 `sendSessionEvent` 发送失败后的 emitter 清理和订阅清理。
- [x] 覆盖 heartbeat 失败后的 emitter 清理和订阅清理。
- [x] 覆盖断连清理后重连并重新订阅。
- [x] 覆盖 `TaskUpdateNotifier` 的 `task_status_change`、`task_completion` 和缺失 userId/session 跳过行为。

### Stage 4.3 - Regression and Check-in

- [x] 运行 SSE 定向回归并记录结果。
- [x] 运行 `session-module` 全量测试并记录结果。
- [x] 回写 `OPT-001` 主工作项 Stage 4 状态。
- [x] 根据测试结果判断是否需要正式质量门；本切片为轻量自检收口。

## Acceptance Criteria

| Criteria | Status | Evidence |
| --- | --- | --- |
| 单实例内存态边界已文档化 | done | 本文档和 `docs/a2a-agent-architecture.md` |
| 多实例策略已选定 | done | 当前版本选择单实例/粘性会话，非粘性多实例进入后续事件总线/集中通知服务 |
| 发送失败清理有单测 | done | `UnifiedSseEmitterTest#sendSessionEvent_failedEmitter_cleansSubscriptions` |
| 心跳失败清理有单测 | done | `UnifiedSseEmitterTest#heartbeat_failedEmitter_cleansSubscriptions` |
| 重连重新订阅有单测 | done | `UnifiedSseEmitterTest#reconnectAfterCleanup_canSubscribeAgain` |
| 任务状态补偿推送有单测 | done | `TaskUpdateNotifierTest` |
| session 全量回归通过 | done | `mvn test -pl session-module -am`：250 tests，0 failures，0 errors，0 skipped |

## Constraints / Non-Goals

- 不改变现有 SSE API path、事件名和 payload 语义。
- 不引入消息队列、Redis pub/sub 或集中通知服务实现；本阶段只明确边界和后续演进路线。
- 不实现 SSE replay；丢失状态以持久化查询和 provider resync 补偿。
- 不改前端交互形态；浏览器重连后的重新订阅仍沿用现有 `/api/v1/sse/subscribe`。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 4 子计划落档 | done | 本文档记录执行范围、部署边界和验收标准。 |
| 架构文档同步 | done | `docs/a2a-agent-architecture.md` 已补单 JVM 内存态、粘性会话约束、重连恢复和非粘性多实例非目标。 |
| `UnifiedSseEmitter` 清理硬化 | done | 普通发送失败、心跳失败和 callback 清理复用统一空连接用户清理逻辑。 |
| `TaskUpdateNotifier` 状态推送补测 | done | 新增任务状态变更和任务完成事件推送测试。 |
| 主 OPT-001 回写 | done | `OPT-001-java-architecture-risk-governance.md` 已回写 Stage 4 checklist、测试证据和 execution check-in。 |

### Testing Progress

| Scope | Command summary | Result |
| --- | --- | --- |
| SSE focused tests | `mvn test -pl session-module -am '-Dtest=UnifiedSseEmitterTest,UnifiedSseControllerTest,SessionEventListenerTest,TaskUpdateNotifierTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'` | PASS：26 tests，0 failures，0 errors，0 skipped |
| session-module full regression | `mvn test -pl session-module -am` | PASS：250 tests，0 failures，0 errors，0 skipped |

### Experience Progress

experience: N/A。该切片为后端 SSE 生命周期、任务状态推送和部署边界文档治理；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

| Item | Status | Notes |
| --- | --- | --- |
| Completed work summary | done | 完成 SSE 单 JVM 边界文档化、粘性会话策略选定、发送失败清理硬化、任务状态推送补测和回归验证。 |
| Touched code paths listed | done | `UnifiedSseEmitter`、`UnifiedSseEmitterTest`、`TaskUpdateNotifierTest`、`docs/a2a-agent-architecture.md`、本文档和 OPT-001 主工作项。 |
| Self-review completed | done | 已确认未改变 SSE API path、事件名和 payload 语义；实现范围未扩展到外部事件总线或 replay。 |
| Test status recorded | done | SSE 定向 26 tests pass；`session-module` 全量 250 tests pass。 |
| Remaining risks recorded | done | 非粘性多实例实时投递仍需外部事件总线或集中通知服务，已作为后续架构项记录。 |
| Acceptance readiness | done | ready-for-acceptance；本切片轻量自检收口，无需正式质量门。 |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage4-sse-deployment-boundary-acceptance.md
- blocking_items: none
- follow_up_required: yes
