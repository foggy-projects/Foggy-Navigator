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

## BUG-009 forced-SIGNAL 演练（Runtime 4/5/6/7/8/9 均已消费；当前无 runtime 授权）

普通 `exercise` 不能替代健康后、经 ownership proof 的 parent-TERM 演练。Runtime 4、5、6、7、8 均已永久消费。Runtime 5 exact runId `int001-bug009-20260722-r5-9f3c7a2d` 的 exact parent proof 成立，但 listener identity 为 `NO_TRUSTED_JAVA_CANDIDATE`、`0 TERM`，receipt 为 `CLEANED/UNKNOWN + HEALTH_READY + HOLD_TIMEOUT`，private/root/Docker 均无残留。不得重试、替换、手工 cleanup 或读取 run 私有证据。

后续离线修复已通过 production-like Java supervise-path seam：test-owned Java 17 JAR 经真实 `exercise_invoke_child()`、held child、`start_child()` 和双层 `setsid/env -i` 启动，在隔离 PID namespace 内由真实 `supervise_exercise()` 完成 health、parent/domain/listener、A/B/final、唯一一次 TERM、`dispatchSafe=true` 与 test-custom `EXIT_143`。该 `EXIT_143` 只属于测试自定义 trap，不是 `main()` 的 runtime completion 证据。descendant-domain 稳定性只允许最多八次独立 sampling attempt；首个完整快照成功后，同一 attempt 的第二个完整快照必须完全一致才可成功。不得跨 attempt 合并，`PROC_UNAVAILABLE`/`PROC_MALFORMED` 立即失败，穷尽仍 fail closed。

该 seam 单独只证明离线修复路径，不满足 AC-2/AC-3，也不证明真实 Launcher/Docker receipt、private absence 或 run-owned residue。Runtime 6 的独立 code/security、test-readiness、canonical/docs 重新授权复核现已另行通过；该授权不改变 seam 本身的证据含义。

Runtime 6 的历史三面冻结命令如下；该资格随后已执行并永久消费：exact runId 为 `int001-bug009-20260722-r6-4c8e1d7a`，唯一命令为：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r6-4c8e1d7a
```

其状态为 `CONSUMED_FAIL_CLOSED`。exact command 已执行一次并退出 `1`：exact parent proof 成立，receipt 为 `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`，private/root/Docker 均无残留，但 listener 仍为 `NO_TRUSTED_JAVA_CANDIDATE`、`listenerProofEverEligible=false`、`0 TERM`、`dispatchSafe=false`、`EXIT_2`，因此 controlled health 与 strict completion gate 均未成立。该 runId 永久消费，禁止重试、替换、手工 cleanup 或读取受限证据。Runtime 6 消费后的下一步当时仅允许 bounded static source/test diagnosis；后续 amendment 仍必须完成 regression-first 修复、全量离线门禁、独立复核与三文档 exact freeze 才能产生新的 runtime 权限。

Runtime 6 后的 host-namespace bounded amendment 已完成离线实现和门禁：holder 完整性绑定到稳定 run-owned descendant domain，域内任一 FD 不可读均拒绝，可读域外 exact-inode holder 继续 veto，仅无关域外不可读 procfs 在本地单一可信 same-UID operator 假设下忽略。宿主与隔离 production-like seam 均通过，完整 Python 为 `93/93`，synthetic TypeScript 为 `109 passed / 1 skipped`。证据见 `../test-records/BUG-009-int001-host-namespace-socket-holder-offline-2026-07-22.md`。该结果仍不授权 Runtime 7；必须先取得独立 code/security、test-readiness、canonical/docs 三路 PASS 并完成新的 exact freeze。

三路独立复核均为 PASS 后，Runtime 7 曾作为独立一次性授权冻结。exact runId 为 `int001-bug009-20260722-r7-6d3f8a1c`，历史唯一命令为：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r7-6d3f8a1c
```

冻结前已确认 exact run directory、sibling projection、reservation path 均不存在，artifact root/registry/local Docker socket 安全，exact-run Docker residue 为 `0/0/0`。该命令已执行一次并退出 `1`，状态为 `CONSUMED_FAIL_CLOSED`：无 supervisor interruption，exact parent proof 成立，temporal identity diagnostic 达到 `EXACT_CANDIDATE_FOUND`，但终态 listener 为 `listener-candidate-absent`、`listenerProofEverEligible=false`、`controlledHealthPrecondition=false`、`0 TERM`、`dispatchSafe=false`、`EXIT_2`。receipt 为 schema-v4 `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`，private/root/Docker 为 absent/0/0-0-0。它不是 forced-SIGNAL 成功，AC-2/AC-3 仍未满足；证据见 `../test-records/BUG-009-int001-runtime7-failclosed-2026-07-22.md`。

Runtime 7 永久禁止重试、换 ID 冒充、手工 cleanup 或读取 `private/children/log/profile/payload/process/Docker` 详情。Runtime 7 失败后当时只允许 offline-only temporal listener-proof stage 固定枚举与回归；它不得授权 TERM 或改变任何 ownership/completion gate。该 amendment 完成本地门禁后的下一步曾仅允许独立三审；三审通过后曾冻结 Runtime 8，而 Runtime 8 现也已消费 fail-closed。当前无 runtime 权限。

该 offline amendment 已实现 `listenerProofStageDiagnostic` 固定枚举：`NOT_OBSERVED`、`EXACT_IDENTITY_FOUND`、`LISTENER_SOCKET_FOUND`、`INITIAL_OWNERSHIP_PROVED`、`FULL_ELIGIBLE`。它只保留监督窗口内达到的最远安全阶段，未知值折叠为 `NOT_OBSERVED`，不输出标识符或原始错误，也不参与 health、TERM、cleanup、receipt 或成功判定。host/isolated production-like seam、Python `96/96`、synthetic TypeScript `109 passed / 1 skipped`、typecheck、shell/Python syntax、diff check 和 secret scan 均通过；三路独立复核最终均为 PASS，证据见 `../test-records/BUG-009-int001-temporal-listener-stage-offline-2026-07-22.md`。

Runtime 8 曾作为新的独立一次性授权冻结，exact runId 为 `int001-bug009-20260722-r8-212fde1c`，历史唯一命令为：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r8-212fde1c
```

冻结前只读 preflight 已确认 strict runId、exact run directory/projection/reservation 均不存在、artifact root 与 reservation registry 安全、local Docker socket 安全、exact-run Docker residue 为 `0/0/0`。该命令已执行一次并退出 `1`，现为 `CONSUMED_FAIL_CLOSED`：exact parent 与 temporal identity 成立，最远 stage 仅 `EXACT_IDENTITY_FOUND`，终态 listener 为 candidate absent，`listenerProofEverEligible=false`、controlled health=false、`0 TERM`、dispatch unsafe、`EXIT_2`；receipt 为 `CLEANED/UNKNOWN + HOLD_TIMEOUT + HEALTH_READY + NOT_APPLICABLE`，private/root/Docker 为 absent/0/0-0-0。它未达到 `LISTENER_SOCKET_FOUND` 或 `FULL_ELIGIBLE`，不是 forced-SIGNAL 成功，AC-2/AC-3 仍未满足。证据见 `../test-records/BUG-009-int001-runtime8-failclosed-2026-07-22.md`。

Runtime 8 已永久消费，禁止重试、换 ID、手工 cleanup 或读取 `private/children/log/profile/payload/process/Docker` 详情。当前无 runtime 权限；下一步仅允许 bounded offline source/test diagnosis、regression-first 修复、完整门禁与独立复核。

Runtime 8 后的当前 offline-only replan 只允许修正等价 loopback 的 procfs 表示：`tcp` 的 literal `127.0.0.1` 与 `tcp6` 的 canonical IPv4-mapped `127.0.0.1` 可作为同一地址语义；native IPv6、`::1`、任一 wildcard、非 `127.0.0.1` mapped 地址、tcp/tcp6 重复或多 inode 必须继续拒绝。测试先红后绿，production-like Java seam 改用 no-arg `ServerSocketChannel.open()`；FD/holder/descendant-domain/A-B-final/pending-signal/one-TERM/receipt/reservation/residue 门禁不得变化。该离线工作不授权新 runtime。

该 replan 已完成离线实现与最终门禁：parser `1/1`，host happy/无 listener 负向 seam `2/2`，isolated happy/无 listener 负向 seam `2/2`，完整 Python `97/97`，synthetic TypeScript `109 passed / 1 skipped`，typecheck、shell/Python syntax、diff check、secret scan 与 test-owned Java residue check 均通过。两条早先 happy-seam orphan 经 exact test-owned 身份证明后只终止其测试进程组，最终残留为 `0`。证据见 `../test-records/BUG-009-int001-ipv4-mapped-loopback-offline-2026-07-22.md`。当前仍无 Runtime 9 权限；必须先完成独立 code/security、test/runtime-readiness、canonical/docs 三审和新的 exact freeze。

三路离线复核与三路 exact-freeze 复核均为 PASS 后，Runtime 9 曾显式推进为一次性授权。其历史 exact runId 和唯一命令为：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r9-33154d77
```

只读 preflight 确认 strict runId 通过，exact run directory/projection/reservation 均不存在、artifact root/registry/local Docker socket 安全、exact-run Docker residue 为 `0/0/0`、test-owned Java residue 为 `0`。该命令已执行一次并退出 `1`，现为 `CONSUMED_FAIL_CLOSED`：controlled health、exact parent/listener、`FULL_ELIGIBLE`、ever eligible、one TERM 与 dispatch safe 均成立，但 outer 为 `EXIT_2`、无可接受 receipt、private absent、root residue `1`、Docker residue `2/1/1`。AC-2/AC-3 仍未满足；证据见 `../test-records/BUG-009-int001-runtime9-failclosed-2026-07-22.md`。

Runtime 9 已永久消费，禁止 retry、换 runId、手工 cleanup 或读取 `private/children/log/profile/payload/process/Docker` 详情。当前无 runtime 权限；下一步仅允许 bounded static source/test diagnosis、regression-first offline repair、完整门禁与独立复核。

Runtime 9 的完整 success gate 冻结为：无 supervisor interruption；controlled health；exact parent `commandLine+cwd+runId+uid+session+startTicks`；exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` 且 `EXACT_CANDIDATE_FOUND`；`listenerProofStageDiagnostic=FULL_ELIGIBLE`；`listenerProofEverEligible=true`；exactly one TERM；`dispatchSafe=true`；normal exact `EXIT_128`；schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`；private absent；root non-receipt residue `0`；exact reservation absent；Docker `0/0/0`。

Runtime 9 evidence boundary 只允许 fixed-enum/redacted stdout、fixed-schema sibling projection、root receipt 固定字段、private-absent boolean、root residue count、exact-reservation-absent result 与 redacted Docker counts。禁止访问共享 `8112`、真实 TMS/SIM、凭据、Worker、Gateway、Pool、Identity、Codex route 或 production configuration。child-only `NAVIGATOR_EXTERNAL_ENABLED=true` 仍仅是 disposable loopback Open API route gate，`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` 仍为强制条件。

Runtime 9 后的 bounded offline correction 已完成：receipt adoption 把 shared-lock acquisition、strict registry/reservation absence 与 receipt parse 保持在同一个受控 subshell，仍严格拒绝 retained reservation，但不再让任何内部 fatal assertion 覆盖 outer signal `EXIT_128`；`stop_owned_child()` 只在 TERM syscall 失败后独立证明 exact recorded PID 已死亡时接受该 commit race，其他状态继续 fail closed。四服务 child cleanup seam 使用固定非秘密 profile-loading stub，以及 Docker ownership/down/residue 与 manifest-writing stub；真实覆盖 reservation、`start_child/stop_owned_child`、receipt publish/release，证明 one outer TERM、四服务 TERM、`CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED`、reservation absent 与 root residue `0`。完整门禁为 synthetic safety `88/88`、synthetic TypeScript `112 passed / 1 skipped`、Python `97/97`，其余 typecheck、syntax、compile、diff、secret scan 和 test-owned Java residue 均通过。证据见 `../test-records/BUG-009-int001-runtime9-postterm-cleanup-offline-2026-07-22.md`。

code/security、test/runtime-readiness、canonical/docs 离线三审与 code/security、runtime-safety、canonical/docs exact-freeze 三审均为 PASS。distinct Runtime 10 `int001-bug009-20260722-r10-9047a550` 的唯一命令已执行一次并 `CONSUMED_SUCCESS`：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r10-9047a550
```

紧邻执行的只读 preflight 已确认 strict runId、artifact root 安全、exact run directory/projection/reservation 均 absent、strict registry 证明 reservation absent、local Docker socket 安全、exact-run Docker residue `0/0/0`、test-owned Java residue `0`。命令 exit `0`：无 supervisor interruption，controlled health，exact parent/listener，`FULL_ELIGIBLE`，one TERM，`dispatchSafe=true`，outer `EXIT_128`，schema-v4 `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`，private absent，root residue `0`，exact reservation absent，Docker `0/0/0`，projection `SUCCESS_GATE_MET`。证据见 `../test-records/BUG-009-int001-runtime10-success-2026-07-22.md`。授权已永久消费且当前为 none；不得 retry、替换 runId、手工 cleanup 或读取受限详情。BUG-009 AC-2/AC-3 已满足并进入 `READY_FOR_SIGNOFF`，但这仍不是 Provider、Worker Gateway、真实 TMS/SIM 或 production acceptance。

Runtime 5 历史唯一命令为：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r5-9f3c7a2d
```

该命令已执行一次并退出 `1`，资格已永久消费。禁止重试、换 ID、手工 cleanup 或读取任何 run `private/children/log/profile/payload/process/Docker` 详情。本段只记录 Runtime 5 的历史执行与消费状态，不授予未来运行权限，也不得把这里的历史简写当作成功合同。任何未来运行都必须另行完成离线 replan、完整门禁、独立复核和三面冻结，并满足本 runbook 当前“合格的 forced-SIGNAL 成功证据”所列完整 strict completion contract。

更早的 stable-hold 离线修复只调整 hold 拓扑：把每秒新建 `sleep 1` 的循环替换为一次固定 180 秒 sleep，避免夹具自身制造 descendant churn。单次 sleep regression、稳定 test-owned 真实 procfs 后代域、完整离线门禁及独立 security/test-matrix/canonical-contract 复核均已通过。后续 production-like seam 另行修正 bounded identity-stability。complete descendant-domain、task-set、exact Java/argv/cwd/exe/lineage、socket holder、A/B/final、pending-signal 与 one-TERM gate 均未放宽；这些离线结论仍不授权新 runtime，也不能基于 health 或端口降级授权。

- fresh prepare 的端口协调只能读取固定 artifact-root reservation namespace；不得 glob、打开、stat 后解析或回退到任何 run 的 `private/stack.env`。
- reservation 只包含 schema version、runId 和六个端口，不是 credential、runtime authority 或 cleanup ownership proof。unsafe/malformed/symlink/hardlink/stale-ambiguous reservation 必须 fail closed。
- harness 与 supervisor 的 reservation parser 都只接受 UTF-8、LF record boundaries 和固定字段顺序；CRLF、bare CR、NUL、Unicode/control line separator、额外尾随内容、oversize 或无法完整读取到 EOF 的文件均 fail closed。supervisor 仍通过 directory FD、`O_NOFOLLOW`、`fstat` 和 shared `flock` 读取 registry。
- 旧 prepared run 没有 reservation 时不得迁移或读取其 private carrier；实际 bind 与 Docker/Compose startup collision 仍须 fail closed。

- supervisor 只会启动一个新的 loopback-only target；它拒绝既有 runId、共享 `8112`、非固定 Docker Unix socket、非 canonical `0700` artifact root、已被 block 的 HUP/INT/TERM，或任何 pending/unobservable control signal。
- 每次 candidate discovery 前，它先重新证明 exact exercise parent。随后从该 parent 的 live `/proc/<pid>/task/<tid>/children` 关系遍历全部 task，建立有界传递 descendant domain；完整 domain 必须连续两次完全一致，并对每个域内进程绑定 PID、parent edge 和 start ticks。域内读取失败、格式错误、task-set churn、PID reuse、edge/start-tick 漂移、cycle 或 size/depth 越界均 fail closed；域外 current-user 进程不参与 Launcher identity scan。
- live procfs 的 `/proc/<pid>/task/<tid>/children` 只用于当前 test-owned process topology proof，不是历史 run 目录中的 `<run>/children/` artifact；后者仍禁止读取。candidate 确定后，supervisor 从 candidate 自身 procfs 证明 loopback listener 与 FD，并把完整 socket-holder proof 绑定到连续两次一致的 run-owned descendant domain：每个域内 FD view 都必须完整可读且 exact-inode holder 只能是 candidate。它还会检查所有可读的域外 current-user FD view，任何可见的同 inode holder 都 veto；仅无关域外进程的不可读或瞬时 procfs 状态可在 single-same-UID-operator disposable-harness 威胁模型下忽略。该 bounded amendment 不适用于共享或 production target。
- 随后执行 A/B identity/socket reproof，并在 signal mask commit point 下完成 final reproof。只有 parent、domain、candidate、socket 和 holder 全部稳定一致时才允许一次 parent `TERM`。它绝不按端口、child PID、process group 或 Docker resource 发信号。
- `--forced-signal-rehearsal` 只能出现在 supervisor 启动的 exact outer `exercise` argv。outer 会以 exact canonical argv 启动 `setsid run --hold-for-parent-term` child；只有该 child 的 PID、start ticks、UID、session、cwd、runId 和 NUL argv 全部仍匹配时，outer 才把它唯一收到的 TERM 转发给该 child 的已证明 session。直接调用 `run --hold-for-parent-term` 只会有界 self-clean，不能构成合格 forced-SIGNAL evidence，也不是 operator recovery 或手工清理入口。
- hold 只在 harness 证明 Launcher child 当时仍存活且目标 URL 返回成功后开始，固定为不可调用方覆盖的 180 秒。receipt 中的历史枚举 `HEALTH_READY` 仅表示这两个独立观察，不证明响应 listener 属于 Launcher，也不授权 TERM。若没有来自已证明 outer parent 的 TERM，held child 必须经 owned cleanup 以 `CLEANED/UNKNOWN` 非零结束；它绝不能形成 `CLEANED/SIGNAL`，也不能继续 bootstrap、audit 或普通 cleanup 流程。
- 最后一项无 pending signal 检查是 deliberate dispatch commit point。POSIX 无法将该检查与对另一 PID 的 `kill()` 合成原子操作：commit 前已观测到的 control signal 必须是 `0 TERM`；commit 后观测到的 signal 最多保留事实上的 `1 TERM`，但必须令本次 run `dispatchSafe=false`、不读取成功形态 evidence 且以非零退出。不得将这种 run 解释为成功。
- 不要把 stdout 重定向到 run 根目录；成功时根目录除 `cleanup-report.json` 外必须没有 residue。只允许读取 supervisor 的脱敏 JSON 和新 run 根级 receipt/名称快照，绝不读取 `private/`、`children/`、日志、profile 或 payload。失败不重试、不手工 cleanup，也不检查历史 failed run。
- supervisor 会在安全的 `temp/test-artifacts/INT-001/` artifact root 写入 `${RUN_ID}.forced-signal-projection.json`。它是 run 目录的 sibling，不参与 run-root residue 计数；文件必须为当前用户拥有的 `0600` 单链接普通文件。它只用于确认 supervisor 到达的固定阶段、是否采样到合法 receipt/root snapshot、以及 stdout summary 是否已经 emit。

### Receipt v4 与诊断边界

- 新 `cleanup-report.json` 固定为 schema v4，只允许 `schemaVersion`、`runId`、`result`、`failureStage`、`rehearsalLifecycleObservation`、`launcherReadinessObservation`、`launcherFailureClass`、`finishedAtUtc`、`secretsRedacted`；v3、重复 JSON object key、额外字段、未知或非字符串枚举均 fail closed，包含 parent-adoption reader。
- `rehearsalLifecycleObservation` 仅是 held-child 的脱敏诊断：`NOT_REHEARSAL`、`HOLD_ENTERED`、`HOLD_TIMEOUT`、`HOLD_WAIT_FAILURE` 或 `HOLD_SIGNAL_RECEIVED`。`HOLD_SIGNAL_RECEIVED` 单独只说明 held child 观察到了其生命周期信号；它不证明 outer parent 已获授权、TERM 已精确派发、资源归属或 cleanup 成功。严格 `main()` completion 仅把它作为完整复合成功门中的一个必要条件，不能覆盖任何其他失败。
- `listenerProofEverEligible` 只会出现在 supervisor 的脱敏 JSON，不会写入 root receipt。它只表示“曾出现过一个合格 listener proof”，不含 PID、argv、cwd、port、inode 或 socket 标识，也不能授权 TERM、cleanup 或成功结论。后续 A→B/final re-proof 失败仍必须拒绝。
- `listenerIdentityDiagnostic` 保留监督窗口内曾达到的最深固定枚举身份进度；如果先出现 `EXACT_CANDIDATE_FOUND`、后因 hold cleanup 变为 `NO_TRUSTED_JAVA_CANDIDATE`，summary 仍报告前者，但最终 proof、TERM 数、receipt 和 completion gate 不受该诊断提升影响。
- `listenerIdentityDiagnostic` 同样只存在于 supervisor 脱敏 JSON，并且只能取 canonical BUG-009 中的固定枚举。`NO_TRUSTED_JAVA_CANDIDATE` 表示没有进程先通过 trusted Java executable gate；只有通过该 gate 后的 exact argv 失败才可使用 `NO_EXACT_ARGV_MATCH`。它只描述 exact Launcher identity proof 达到的阶段、exact candidate 歧义类或 `PROC_*` 失败类；不得输出 PID、port、inode、argv、cwd/path、异常原文、进程数量或其他原始值，也不得授权 health、TERM、cleanup、receipt 或成功结论。未知值必须折叠为 `NOT_OBSERVED`。
- forced-SIGNAL execution projection 固定为 schema v1，仅允许 `schemaVersion`、`runId`、`phase`、`outcome`、`receiptState`、`rootSnapshotState`、`stdoutSummaryState`、`secretsRedacted`。重复 key、额外字段、未知 enum、错误类型、runId 不匹配、非 `0600`、symlink/hardlink 或非 canonical artifact root 均视为无效。
- projection 不包含也不得扩展为 PID、argv、cwd、port、inode、socket、目录名称清单、日志、异常文本、profile、credential、payload 或 Docker identifier/count。`stdoutSummaryState=EMITTED` 但执行包装层没有返回 stdout 时，只能诊断为 stdout capture/projection 链路缺失；不得据此推断 TERM、cleanup 或成功。
- projection 永远不是授权或验收证据的替代品。即使 `phase=COMPLETE`、`outcome=SUCCESS_GATE_MET`，仍必须独立满足下述完整 forced-SIGNAL gate；projection 缺失、格式错误或与 receipt/residue 证据冲突时一律 fail closed。

此前批准的 projection rehearsal 已完成并停止。Runtime 4 exact runId `int001-bug009-20260722-484c6216` 已消费且失败关闭，禁止再次执行或替换。其允许的脱敏结果已进入 canonical/test record；不得读取 `private/`、`children/`、日志、profile、payload、进程或 Docker 对象补证。Runtime 4 后的后续 runtime 均必须先完成新的 bounded offline replan、回归、完整 gate、独立复核和三面 exact-runId 授权；Runtime 8 曾按该流程冻结并现已消费，不恢复任何历史授权。

合格的 forced-SIGNAL 成功证据必须同时含无 supervisor interruption、`controlledHealthPrecondition=true`、parent proof `commandLine+cwd+runId+uid+session+startTicks`、listener proof `uid+java+argv+cwd+ancestor+socket+startTicks` 与 identity `EXACT_CANDIDATE_FOUND`、temporal observation `listenerProofStageDiagnostic=FULL_ELIGIBLE`、`listenerProofEverEligible=true`、`termDispatches=1`、`dispatchSafe=true`、normal `EXIT_128`、`CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`、`privateAbsent=true`、root non-receipt residue `0` 以及 Docker container/network/volume residue 各为 `0`。其中 receipt 采用必须与 reservation registry 串行化：在 shared registry lock 下严格验证 registry 安全且 exact `${runId}.ports` 已不存在；unsafe registry 或残留 exact reservation 必须拒绝即使形态正确的 receipt。`FULL_ELIGIBLE` 只是所需观测，不能单独授权 TERM 或成功；任何一项缺失均为 fail-closed 失败，不能由额外检查私有产物补证。

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
