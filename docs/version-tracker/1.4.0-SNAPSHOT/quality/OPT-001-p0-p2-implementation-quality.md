---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P2 local implementation checkpoint
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-07-10
follow_up_required: yes
production_enablement: not-approved
---

# Implementation Quality Gate

## Background

- 检查对象：独立 `codex-app-server-worker`、旧 SDK lane 拆分、Codex Runtime Registry、Task/Session affinity、统一 SSE 与 PC 原生子任务投影。
- 检查范围：P0 契约和 P1/P2 本地实现检查点。
- 非检查范围：P3-P7 生产 canary、默认切换和 SDK 退役；本文件不批准生产路由变化。

## Check Basis

- requirement: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md`
- plan/progress: 同版本 `workitems` 下实施计划与进度
- migrations: `docs/migration/2026-07-10-codex-runtime-affinity.sql`、`docs/migration/2026-07-10-native-subtask-states.sql`
- implementation review: Worker 与 Java 并行 diff review、root 独立 reactor 回归、前端与真实 Worker/MySQL 分层证据

## Changed Surface

- `tools/codex-app-server-worker/**`：固定 CLI `0.144.1`、app-server pool、幂等任务受理、durable task/event journal、恢复/中止/删除、capability manifest、启动停止脚本。
- `tools/codex-agent-worker/**`：保持 SDK lane，新 Ultra fail closed，已有 SDK Ultra affinity 只允许原地 drain。
- `addons/codex-worker-agent/**`：Runtime Registry、能力刷新/路由、不可变 binding、幂等 client、SSE relay、恢复/删除和 native projection。
- `navigator-common/**`、`session-module/**`：跨模块 affinity/native DTO、Session 锁、统一事件持久化、快照和任务操作路由。
- `packages/navigator-frontend/**`：Runtime 管理、Ultra preflight、SSE/snapshot 重试与原生子任务状态展示。

## Quality Checklist

- scope conformance: pass。继续使用现有 Provider/Task/Session/PC 页面，没有新增 app-server Provider。
- execution boundary: pass。`turn/start` committed 后禁止跨 runtime prompt replay；首个可信 terminal outcome 不可逆。
- idempotency and recovery: pass for automated local checkpoint。同 key/同请求返回原任务，异请求 409；abort 意图、task/event ESN 和 binding 可持久恢复。最终 post-fix HTTP 幂等 live 通过，真实 crash/restart provider lane 被账户额度阻塞。
- process isolation: pass。池键和 realpath 边界覆盖 CLI、`CODEX_HOME`、认证、base URL、cwd/state/workspace；新 Worker 无 SDK fallback/import。
- event durability: pass。append + fsync 后才发布，订阅游标原子化；截断 JSONL 尾可物理修复，terminal 与 tombstone 清理可恢复。
- runtime affinity: pass。Task/Session 固定 runtime/revision/instance/routing epoch；能力过期或策略变化不重选 active binding。
- stream ordering: pass。Java 只接受连续 Worker ESN；duplicate 跳过、gap 触发恢复，失败事件不会被更高序号越过。
- deletion semantics: pass。APP_SERVER 先按不可变 binding 确认远端删除，再清理 provider/native 状态，统一 SessionTask 最后删除以保留重试标记。
- privacy: pass for reviewed paths。manifest/DTO/UI 不回显 endpoint token、请求正文、真实 Codex Home、子 prompt、reasoning 或工具载荷。
- UI state handling: pass。SSE/snapshot 独立退避、连接 epoch 隔离、dirty runtime draft 保留；Ultra guard 只约束新建，不破坏 resume/drain。
- migration discipline: partial。一次性 SQL 已在干净 MySQL 8.4.8 验证；生产库、N-1 和 `ddl-auto=validate` 尚未验证。
- release engineering: partial-pass。0.1.0 deterministic ZIP、checksum、Windows temp install/in-place update、状态保留和失败前验证已通过；POSIX 仅语法校验，运行中 drain/restart 与故障回滚仍待目标环境验证。

## Findings

- 最终 review 发现的终态覆盖、durable abort、journal 截断、发布时序、目录隔离、stream gap、并发 relay、late terminal 和远端删除顺序问题均已修复并纳入回归。
- 固定 CLI 的可路由模型矩阵使用显式逐模型档位，不存在 wildcard 或全局 reasoning 放行。
- `CodexStreamRelay` 与 Worker recovery 是高复杂度区域，但复杂度来自持久状态、ESN、恢复和 affinity 的必要约束；当前职责与测试边界可审查，未发现必须在本次拆分的新抽象。
- 未发现阻断 P0-P2 本地代码检查点的问题。

## Risks / Follow-ups

- P1：本地 release artifact 已生成并验证；仍缺 POSIX 实装、运行中 Worker drain/restart、故障注入回滚和目标机升级证据。
- P2：缺少真实 Worker -> Java -> unified SSE -> PC、真实浏览器重连、N-1、instance-aware 多副本和生产 migration/validate 证据。
- P1 live refresh：此前真实模型/native/abort smoke 保留；最终硬化后只完成 health/CLI/schema/matrix 与 HTTP 幂等，provider call 在工具执行前被 ChatGPT usage limit 阻塞，硬杀恢复未重验。
- P5：当前 catalog 是固定 CLI `0.144.1` 的静态快照；账号动态 catalog refresh 尚未实现，不应把未知未来模型自动暴露给平台路由。
- P3-P7：生产任务量、72 小时窗口、实例轮换、SLO、drain 和零例外数据均未产生。

## Recommended Next Skills

- `foggy-test-coverage-audit`: 已执行，结论见 coverage 记录。
- `foggy-acceptance-signoff`: 已执行当前阶段审查；整体生产验收被门禁阻塞。
- release/operations: 完成发布物、目标环境全链/N-1/迁移后重新进入 P2 exit review。

## Decision

- decision: ready-with-risks
- decision_scope: P0 completed；P1/P2 local implementation checkpoint only
- can_enter_coverage_audit: yes
- production_routing_change: no
- P3_entry_approved: no
- follow_up_required: yes
