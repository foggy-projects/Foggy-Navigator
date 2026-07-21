---
doc_type: runbook
version: 1.4.3-SNAPSHOT
scope: synthetic-disposable-local-only
related_workitem: ../workitems/INT-001-synthetic-upstream-integration-harness.md
---

# INT-001 Synthetic Upstream Harness 操作手册

## Boundary

- 仅用于真实 SIM/TMS 联调前的可销毁本机验证。它不读取或代替真实上游 profile、账号、业务数据或验收。
- 每次必须是独立 runId、loopback 端口、Compose project、数据库、Launcher、Mock LLM、directory facade 和 synthetic `LANGGRAPH_BIZ` fixture；禁止使用共享 `8112`、共享数据库或既有 Worker。
- `NAVIGATOR_EXTERNAL_ENABLED=true` 仅在 disposable target 内开启 `/api/v1/open/**` 路由。保持 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`；两者均不表示 Provider、Gateway external 或 production ready。
- 不得为了本 harness 或 Codex 路由创建/加入额外 Worker、BizWorkerIdentity 或 WorkerPool member。fixture 中的 Biz identity 仅属于它自己创建和清理的 disposable stack。

## Prerequisites

1. 在当前 Navigator checkout 内执行；先确认 `git status`，不要把其他工作树或 private profile 纳入本 run。
2. 当前源码 Launcher、source-matched upstream CLI、Docker/Compose、JDK 和 LangGraph fixture virtualenv 必须可用。
3. 只允许由 harness 生成 `temp/test-artifacts/INT-001/<runId>/private/` 下的 `0600` carrier；不得手工复制、打印或提交它。

## One-shot exercise

```bash
RUN_ID='int001-<unique-lowercase-id>'
/usr/bin/bash -p tools/navigator-upstream/scripts/synthetic-upstream-harness.sh \
  exercise --allow-create --allow-execute --build-launcher --run-id "$RUN_ID"
```

`exercise` 顺序执行 `prepare → doctor → run → bootstrap → audit → cleanup`。它只接受自己的 run ownership proof；target 非 loopback、端口 `8112`、profile 不安全、Gateway external 不是 false、或 carrier 不属于该 run 时会 fail closed。

不要在 `run` 之前调用 bootstrap，也不要对一个无法由 runId/cwd/PID-start/process-group/Compose label 证明为 harness-owned 的目标调用 cleanup。

## BUG-009 forced-SIGNAL 演练（窗口已关闭）

普通 `exercise` 不能替代健康后、经 ownership proof 的 parent-TERM 演练。BUG-009 candidate-first 修复及全部离线 gate 已完成，但 exact runId `int001-bug009-20260721-c4n8v2k6` 已执行并 fail closed；窗口现已关闭。不得重跑该 runId、替换新 runId 或基于本 runbook 启动另一轮 rehearsal。

- supervisor 只会启动一个新的 loopback-only target；它拒绝既有 runId、共享 `8112`、非固定 Docker Unix socket、非 canonical `0700` artifact root、已被 block 的 HUP/INT/TERM，或任何 pending/unobservable control signal。
- 它先发现唯一 exact Launcher candidate，再从 candidate 自身 procfs 证明 loopback listener、FD 与 current-user 唯一 holder；随后执行 A/B identity/socket reproof，并在 signal mask commit point 下完成 final reproof。只有全部证明稳定一致时才允许一次 parent `TERM`。它绝不按端口、child PID、process group 或 Docker resource 发信号。
- `--forced-signal-rehearsal` 只能出现在 supervisor 启动的 exact outer `exercise` argv。outer 会以 exact canonical argv 启动 `setsid run --hold-for-parent-term` child；只有该 child 的 PID、start ticks、UID、session、cwd、runId 和 NUL argv 全部仍匹配时，outer 才把它唯一收到的 TERM 转发给该 child 的已证明 session。直接调用 `run --hold-for-parent-term` 只会有界 self-clean，不能构成合格 forced-SIGNAL evidence，也不是 operator recovery 或手工清理入口。
- hold 只在 harness 证明 Launcher child 当时仍存活且目标 URL 返回成功后开始，固定为不可调用方覆盖的 180 秒。receipt 中的历史枚举 `HEALTH_READY` 仅表示这两个独立观察，不证明响应 listener 属于 Launcher，也不授权 TERM。若没有来自已证明 outer parent 的 TERM，held child 必须经 owned cleanup 以 `CLEANED/UNKNOWN` 非零结束；它绝不能形成 `CLEANED/SIGNAL`，也不能继续 bootstrap、audit 或普通 cleanup 流程。
- 最后一项无 pending signal 检查是 deliberate dispatch commit point。POSIX 无法将该检查与对另一 PID 的 `kill()` 合成原子操作：commit 前已观测到的 control signal 必须是 `0 TERM`；commit 后观测到的 signal 最多保留事实上的 `1 TERM`，但必须令本次 run `dispatchSafe=false`、不读取成功形态 evidence 且以非零退出。不得将这种 run 解释为成功。
- 不要把 stdout 重定向到 run 根目录；成功时根目录除 `cleanup-report.json` 外必须没有 residue。只允许读取 supervisor 的脱敏 JSON 和新 run 根级 receipt/名称快照，绝不读取 `private/`、`children/`、日志、profile 或 payload。失败不重试、不手工 cleanup，也不检查历史 failed run。
- supervisor 会在安全的 `temp/test-artifacts/INT-001/` artifact root 写入 `${RUN_ID}.forced-signal-projection.json`。它是 run 目录的 sibling，不参与 run-root residue 计数；文件必须为当前用户拥有的 `0600` 单链接普通文件。它只用于确认 supervisor 到达的固定阶段、是否采样到合法 receipt/root snapshot、以及 stdout summary 是否已经 emit。

### Receipt v4 与诊断边界

- 新 `cleanup-report.json` 固定为 schema v4，只允许 `schemaVersion`、`runId`、`result`、`failureStage`、`rehearsalLifecycleObservation`、`launcherReadinessObservation`、`launcherFailureClass`、`finishedAtUtc`、`secretsRedacted`；v3、重复 JSON object key、额外字段、未知或非字符串枚举均 fail closed，包含 parent-adoption reader。
- `rehearsalLifecycleObservation` 仅是 held-child 的脱敏诊断：`NOT_REHEARSAL`、`HOLD_ENTERED`、`HOLD_TIMEOUT`、`HOLD_WAIT_FAILURE` 或 `HOLD_SIGNAL_RECEIVED`。`HOLD_SIGNAL_RECEIVED` 只说明 held child 观察到了其生命周期信号；它不证明 outer parent 已获授权、TERM 已精确派发、资源归属、cleanup 成功，且不参与 supervisor completion 判定。
- `listenerProofEverEligible` 只会出现在 supervisor 的脱敏 JSON，不会写入 root receipt。它只表示“曾出现过一个合格 listener proof”，不含 PID、argv、cwd、port、inode 或 socket 标识，也不能授权 TERM、cleanup 或成功结论。后续 A→B/final re-proof 失败仍必须拒绝。
- forced-SIGNAL execution projection 固定为 schema v1，仅允许 `schemaVersion`、`runId`、`phase`、`outcome`、`receiptState`、`rootSnapshotState`、`stdoutSummaryState`、`secretsRedacted`。重复 key、额外字段、未知 enum、错误类型、runId 不匹配、非 `0600`、symlink/hardlink 或非 canonical artifact root 均视为无效。
- projection 不包含也不得扩展为 PID、argv、cwd、port、inode、socket、目录名称清单、日志、异常文本、profile、credential、payload 或 Docker identifier/count。`stdoutSummaryState=EMITTED` 但执行包装层没有返回 stdout 时，只能诊断为 stdout capture/projection 链路缺失；不得据此推断 TERM、cleanup 或成功。
- projection 永远不是授权或验收证据的替代品。即使 `phase=COMPLETE`、`outcome=SUCCESS_GATE_MET`，仍必须独立满足下述完整 forced-SIGNAL gate；projection 缺失、格式错误或与 receipt/residue 证据冲突时一律 fail closed。

此前批准的 projection rehearsal 已完成并停止。当前不允许任何 runId；canonical work item 中的 exact runId 仅可用于引用既有记录，不得重跑、替换或追加第二次运行。任一允许文件缺失或不符合固定 schema 时必须记录后停止，不得读取 `private/`、`children/`、日志、profile、payload、进程或 Docker 对象补证。

合格的 forced-SIGNAL 成功证据必须同时含 `controlledHealthPrecondition=true`、parent proof `commandLine+cwd+runId+uid+session+startTicks`、listener proof `uid+java+argv+cwd+ancestor+socket+startTicks`、`termDispatches=1`、`dispatchSafe=true`、`CLEANED/SIGNAL`、`privateAbsent=true`、root non-receipt residue `0` 以及 Docker container/network/volume residue 各为 `0`。任何一项缺失均为 fail-closed 失败，不能由额外检查私有产物补证。

## Read only the redacted receipts (ordinary exercise only)

以下两个 root-level JSON receipt 只适用于普通 `exercise` 成功后的 runtime 证据；绝不读取 `private/`：

```bash
sed -n '1,220p' "temp/test-artifacts/INT-001/$RUN_ID/runtime-audit-summary.json"
sed -n '1,160p' "temp/test-artifacts/INT-001/$RUN_ID/cleanup-report.json"
```

成功的最低期望是：

| Evidence | Expected value |
| --- | --- |
| runtime token / readiness / owner-smoke | `PASS` |
| positive ask | `taskCreated=true`，model submission 与 Biz ingress 各为 `1` |
| seven deny probes | `taskCreated=false`，model submission 与 ingress 均为 `0` |
| cleanup | `CLEANED` 且 `secretsRedacted=true` |

receipt 不包含 token、profile、prompt、response、URL、task id 或业务数据。若 receipt 缺失、格式不符或结果不是上述值，视为未通过，勿通过读取 private log 来“补证”。

BUG-009 forced-SIGNAL 演练不读取或保留 `runtime-audit-summary.json`：它只允许 supervisor 的脱敏 stdout、artifact-root fixed-enum projection、`cleanup-report.json` 的固定字段和根级名称计数快照。若这些证据未同时满足专用章节的成功条件，视为 fail-closed 失败；不得查看 `private/`、`children/`、日志、profile 或 payload 来补充诊断。

## Failure handling and handoff

- 任一 ownership / credential / target preflight 失败：不要转向真实 8112、TMS/SIM profile 或更宽凭据；保留仅根级脱敏 receipt，先修复 harness 或环境。
- 正向 ask 被 owner-context 拒绝：先确认 active ClientApp 的 server-resolved upstream scope 是否进入 physical `LANGGRAPH_BIZ` lookup；不得用 Pool/member/额外 identity 规避。
- runtime token/readiness/owner-smoke 成功不等于 ask-ready、Gateway-ready、Provider-ready 或 production-ready。
- 每次有意义运行后，回写版本 test record 中的 runId、状态、计数和 cleanup 结果；任何真实上游凭据或数据都需要单独授权和验收。
