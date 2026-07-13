---
quality_scope: bug
quality_mode: post-fix-quality-review
version: 1.4.1-SNAPSHOT
target: BUG-001
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-07-13
follow_up_required: yes
---

# Implementation Quality Gate

## Background

本次复核覆盖 BUG-001：用户主动、系统意外或 Worker 接口终止 Codex CLI 后，Worker 任务注册表与 resumed thread reservation 可能残留，导致相同 thread 持续返回 `CODEX_THREAD_ACTIVE`。修复边界明确为 Worker-owned；Java 侧不新增 JVM 锁，也不成为 reservation 的权威来源。

## Check Basis

- [BUG-001](../workitems/BUG-001-codex-thread-lock-stale-after-cli-exit.md) 的复现、约束、修复清单和测试策略。
- `thread-reservations.ts`、`thread-process-watchdog.ts`、`sdk-wrapper.ts`、进程管理路由、Worker 配置与生命周期接入的实现 diff。
- 新增及更新的 reservation、watchdog、process route、config 回归测试。
- `npm test`、`npm run typecheck`、`npm run build` 和 `git diff --check` 的最终结果。

## Changed Surface

- Worker reservation：新增按 taskId 统一释放能力，供任务终止和守护收敛共同使用。
- Worker task lifecycle：`abortTask` 同时中止控制器、写入终态并释放该任务持有的 reservation。
- Process route：主动 kill 或确认进程已消失时，关联并 abort 绑定的运行任务。
- Unified watchdog：周期交叉核对 task registry、reservation 与真实 Codex CLI 进程；连续缺失超过安全窗口后才收敛，扫描失败时保持原状态。
- Worker lifecycle/config：服务启动后开启守护器，SIGINT/SIGTERM 时停止；扫描间隔和缺失窗口可配置并有安全范围校验。

## Quality Checklist

| Dimension | Result | Review evidence |
|---|---|---|
| Scope conformance | Pass | 锁权威和清理逻辑全部位于 Codex Worker；Java 代码未增加锁或解锁职责。 |
| Safety invariant | Pass | reservation 释放后，只要旧 CLI PID 仍存活，新 resume 仍由 `process_scan` 返回 `CODEX_THREAD_ACTIVE`。 |
| False-unlock prevention | Pass | 不按任务总时长解锁；要求真实进程连续缺失超过安全窗口。启动期无 PID 的任务受保护，无 PID 且无 threadId 的任务保持 uncertain。 |
| Failure handling | Pass | 进程扫描异常直接失败并不改变任务/reservation；定时调度捕获并记录告警，保持 fail closed。 |
| Lifecycle consolidation | Pass | 主动 abort、进程管理 kill、外部进程消失均收敛到统一 `abortTask`/按 taskId release 路径。 |
| Resource behavior | Pass | 无运行任务且无 reservation 时不枚举系统进程；定时扫描防重入；timer `unref` 且 Worker 信号退出时停止。 |
| Configuration | Pass | 默认 5 秒扫描、10 秒连续缺失窗口；配置有上下界校验并写入 `.env.example`。 |
| Test alignment | Pass | 测试直接覆盖活进程、缺失窗口、启动发现、扫描失败、孤儿 reservation、kill 关联、启停幂等和释放后仍由真实 PID 拦截。 |
| Build quality | Pass | 159 tests 中 158 pass、1 skip、0 fail；TypeScript 类型检查和构建通过。 |
| Documentation | Pass | BUG 文档包含根因、Worker/Java 边界、清单、验证证据、执行 check-in 和残余风险。 |

## Findings

1. 未发现阻塞性的实现质量问题。
2. 真实进程扫描保留为 reservation 之外的第二道安全边界，因此主动 abort 后即便进程尚未真正退出，也不会允许相同 thread 并发 resume。
3. 守护器只在一次成功扫描后清理非运行任务或无主任务的 reservation，避免系统命令异常被误解释为“进程不存在”。
4. 周期任务具备防重入和空闲短路，避免慢扫描堆积以及无任务时持续调用 `ps`/PowerShell。

## Risks / Follow-ups

- 当前验证环境为 Linux，Windows PowerShell 枚举脚本的运行时测试按平台条件跳过；发布候选应在 Windows Worker 上补一次实际 kill/resume smoke。
- 默认参数下，从 CLI 实际退出到 reservation 自动回收通常需要约 10–15 秒；这是双次确认以降低误解锁风险的预期延迟，而非即时回收承诺。
- 对既无 PID 又无 threadId 的新任务，守护器会保持 uncertain，不会猜测进程已死；这符合 fail-closed 原则，但若未来出现长期无法绑定 PID 的新任务，需要另行增强 SDK 子进程关联能力。

## Recommended Next Skills

1. `foggy-test-coverage-audit`：核对 BUG-001 的需求/风险与现有自动化证据是否完整映射。
2. `foggy-acceptance-signoff`：在 Windows 实机 smoke 或明确接受平台跳过项后进行正式验收。
3. `codex-worker-deploy`：仅在验收通过且用户明确要求发布时执行打包与发布流程。

## Decision

`ready-for-coverage-audit`。Worker-owned 修复已完成，关键安全不变量、失败策略、守护生命周期和全量构建均有自动化证据，未发现阻塞问题。该结论不代表正式验收、Worker 发布或生产部署；Windows 实际进程终止 smoke 仍作为发布前跟进项保留。
