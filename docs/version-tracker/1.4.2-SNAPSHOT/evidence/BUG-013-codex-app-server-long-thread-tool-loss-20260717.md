# BUG-013 Codex App Server Long-Thread Tool Loss Evidence

## Result

2026-07-17 的受控观察证明：故障请求在离开本地时仍带完整工具 schema；对同一 Codex Thread 执行原生压缩后，不重启 Worker/app-server、不新建 Thread，下一 turn 恢复了真实 `exec` 调用。该结果支持“长历史退化 + 压缩可恢复”，但不证明上游按某种顺序裁掉了工具 token。

本文件不保留用户 Bearer Token、LLM API key、证书私钥或原始提示词全文。

## Environment

- Date: 2026-07-17 Asia/Shanghai.
- Worker: installed `codex-app-server-worker` 0.3.18 on port 3071.
- Codex CLI: `codex-cli 0.144.3`.
- Source: official tag `rust-v0.144.3`, commit `78ad6e6bfd1d3b6a209acd3ef82172a96b25179c`.
- Target Navigator Session: `e0218960-52ed-44f0-b605-f6e5f11c0b75`.
- Target Codex Thread: `019f6b57-c39f-7093-9d63-4dbdbb4d32d0`.

## Before Compaction

- Failed turn: `019f6e0e-455e-73f1-8884-f45da0553b2c`.
- User-visible behavior: assistant stated that terminal/file tools were unavailable and did not issue a tool call.
- Recorder object: `000017_POST_v1_responses`.
- Request size: 429,495 bytes.
- Response size: 71,992 bytes.
- Input items: 155.
- Approximate input text characters: 56,077.
- Recorded `additional_tools`: 4 — `exec`, `wait`, `request_user_input`, `collaboration`.
- Model: `gpt-5.6-sol`.
- App-server usage: `input_tokens=63,901`.
- App-server-reported `model_context_window=353,400`.
- Upstream result: HTTP 200; no `contextWindowExceeded` error.

This rules out a local omission of the tool schema for this turn. It does not expose or prove the model service's internal attention, truncation, alias routing or effective limit.

## Native Compaction

- Request: `thread/compact/start` with only the existing `threadId`.
- Compact turn: `019f6e1f-d0e3-7d40-956f-27adc5ee6a4a`.
- Started: `2026-07-17T03:30:06.692Z`.
- Completed: `2026-07-17T03:30:29.203Z`.
- Observed lifecycle: turn started, summary response, `type=compacted`, `context_compacted`, task complete.
- Recorder object: `000018_POST_v1_responses`.
- Request/response size: 412,838 / 287,537 bytes.
- Input items: 158.
- Approximate input text characters: 56,503.
- `additional_tools`: 0, as expected for the compaction request itself.

## After Compaction

- Verification prompt explicitly required executing `pwd` rather than describing tool availability.
- Turn: `019f6e21-2cb8-73b0-8cbc-1a0687936d11`.
- Recorder initial request: `000019_POST_v1_responses`.
- Request/response size: 70,927 / 80,138 bytes.
- Input items: 21.
- Approximate input text characters: 43,569.
- Recorded `additional_tools`: all 4 tools present, including `exec`.
- Follow-up recorder request `000020` contained the assistant `custom_tool_call` and matching `custom_tool_call_output`.
- Actual tool call id: `call_GhHDdGNaLkJULLsTocYJVaBz`.
- Actual output: `/home/sa/workspace/tms-x3`.
- Upstream result: HTTP 200 and app-server terminal completion.

## Process Continuity

No Worker or app-server restart occurred between the failed turn, compaction and successful verification.

- Worker PID: 1599279.
- App-server wrapper PID: 1653765.
- Native app-server PID: 1653787.
- Health after verification: `ready=true`, `active_tasks=0`, pool `instances=1`, `idle=1`.

The successful turn therefore demonstrates recovery inside the same process and Thread, not recovery caused by creating a clean process.

## Source and Protocol Verification

Commands used for the fixed source inspection:

```bash
git clone --depth 1 --branch rust-v0.144.3 \
  https://github.com/openai/codex.git \
  temp/test-artifacts/codex-app-server-context-analysis-20260717/source-0.144.3

git -C temp/test-artifacts/codex-app-server-context-analysis-20260717/source-0.144.3 rev-parse HEAD
/home/sa/.codex-app-server-worker/node_modules/.bin/codex --version
```

Observed results:

```text
78ad6e6bfd1d3b6a209acd3ef82172a96b25179c
codex-cli 0.144.3
```

Relevant fixed-source behavior:

- `ThreadCompactStartParams` contains only `thread_id`.
- `ThreadForkParams.last_turn_id` forks through a completed turn, inclusive, into a new Thread.
- `ThreadRollbackParams` is deprecated, drops a number of tail turns, does not revert files and returns lossy history.
- auto-compaction compares active usage with `model_auto_compact_token_limit`; `model_auto_compact_token_limit_scope` defaults to `total`.
- manual compact is a normal session task and emits a `ContextCompaction` item.
- there is no dedicated thread token-usage read RPC and `thread/read` does not return usage;
- live usage is emitted through `thread/tokenUsage/updated` with `threadId`, `turnId`, `total`, `last` and nullable `modelContextWindow`;
- `thread/resume` replays the latest persisted usage notification to the attaching connection unless `excludeTurns: true` is used;
- Codex computes context remaining from the latest `last_token_usage`, not cumulative `total_token_usage`; its TUI percentage subtracts a fixed 12,000-token baseline for prompts, tools and compaction headroom.

## Worker Configuration Verification

Current repository code:

```text
tools/codex-app-server-worker/src/app-server/executor.ts
  model_auto_compact_token_limit: 140_000

tools/codex-app-server-worker/src/codex-config.ts
  allowed numeric overrides:
  model_context_window
  model_auto_compact_token_limit
  tool_output_token_limit
```

Because the observed failure usage was below 140,000, the configured automatic compaction threshold did not protect this Thread. This is a local mitigation gap, not proof that the Worker caused the model's tool-selection failure.

## GPT-5.6 Window Verification

Official OpenAI model pages checked on 2026-07-17:

- GPT-5.6 Sol: `1,050,000` context window; prompts above `272K` input tokens use long-context pricing.
- GPT-5.6 Terra: `1,050,000` context window; the same `272K` pricing boundary.
- GPT-5.6 Luna: `1,050,000` context window; the same `272K` pricing boundary.

The public documentation therefore does not support the statement that all GPT-5.6 models have been reduced to a `270K` hard context limit. It also does not guarantee that the private `codex2.qlfloor.com` aliases expose the full public API window.

Fixed CLI `0.144.3` source establishes a separate app-server metadata layer:

```text
codex-rs/models-manager/models.json
  gpt-5.6-sol:   context_window=372000, max_context_window=372000
  gpt-5.6-terra: context_window=372000, max_context_window=372000
  gpt-5.6-luna:  context_window=372000, max_context_window=372000

codex-rs/protocol/src/openai_models.rs
  default_effective_context_window_percent() = 95

codex-rs/models-manager/src/model_info.rs
  unknown-model fallback context_window=272000
  fallback effective_context_window_percent=95
```

Consequences:

- recognized GPT-5.6 app-server window: `372000 * 95% = 353400`;
- unknown-model fallback window: `272000 * 95% = 258400`;
- a live `258400` notification would be evidence of fallback-like metadata, not evidence by itself of a public GPT-5.6 hard-limit change.

Read-only inspection of the installed runtime found:

```text
Worker PID 1599279
app-server wrapper PID 1653765
native app-server PID 1653787
CODEX_HOME=/home/sa/.codex-app-server-worker/codex-home
config.toml: no model_context_window override
rollout files containing token_count: 91
token_count records with model_context_window: 32785
observed values: 32785 x 353400
```

The target Thread used `gpt-5.6-terra` and `gpt-5.6-sol`; repository-wide rollouts also include `gpt-5.6-luna`. No installed-runtime usage record reported `270000`, `272000` or `258400`.

The same target Thread also contains compaction evidence before the reported tool-loss turn:

```text
2026-07-17T01:41:31.206Z last.total_tokens=138585
2026-07-17T01:42:47.861Z compacted
2026-07-17T01:48:48.022Z last.total_tokens=140033
2026-07-17T01:50:08.228Z compacted
2026-07-17T03:11:01.001Z last.total_tokens=63938, tool-loss observation
2026-07-17T03:30:29.193Z manual compact completed
```

The automatic compactions occurred near the Worker's configured `140000` limit, but a later compacted history still degraded at `63938`, only `18.09%` of the reported `353400` window. This is why the proposed canary thresholds remain absolute values below the observed failure point (`48k` and `56k`) rather than a conventional 70%/80% context-window percentage.

## Interpretation Boundaries

Supported statements:

- the outbound failed request still contained tools;
- the history was large and contained many prior interactions;
- native compaction greatly reduced the next request's item count and byte size;
- the same Thread then performed a real terminal call;
- an earlier automatic compaction threshold could plausibly reduce recurrence.

Unsupported statements:

- the request definitely exceeded the model's real context limit;
- OpenAI definitely discarded the oldest tokens or the tool prefix;
- the issue is wholly unrelated to local configuration;
- one successful recovery proves all future long Threads will recover;
- restarting app-server is the required or causal fix.
- OpenAI reduced every GPT-5.6 model to a 270K hard context limit.
- the public 1.05M window is the same limit that Codex CLI `0.144.3` exposes through app-server notifications.

## Phase A + Phase B Implementation Evidence

The repository implementation completed on 2026-07-17 without deploying or restarting the installed Worker.

Implemented boundaries:

- Worker task-bound `GET /api/v1/tasks/{taskId}/context-usage`.
- Worker task-bound idempotent `POST /api/v1/tasks/{taskId}/compact-context` with `operation_id`.
- Worker durable operation read `GET /api/v1/tasks/{taskId}/compact-context/{operationId}`.
- Native compact uses the original encrypted task request/lane evidence, resident child, and same-Thread keyed lock.
- Compact terminal correlation is exact on `(threadId, compactTurnId)`; unconfirmed terminal state remains fail-closed.
- Usage persistence stores `last.totalTokens` as current context and preserves nullable `modelContextWindow` as unknown.
- Java owner/runtime-affinity facade and Navigator TaskPane usage/manual compact controls were added without accepting a caller-supplied Thread ID or endpoint.

Failure-first evidence:

- The first runtime regression failed because `compactThread` did not exist.
- The first HTTP regression exposed that terminal task request material was no longer resident in memory. The fix added an explicit maintenance-only reread of the original encrypted journal record without restoring plaintext into the resident task projection.

Executed validation:

```text
cd tools/codex-app-server-worker && npm test
PASS: 311 tests; 310 passed, 1 platform-conditional skipped, 0 failed

cd tools/codex-app-server-worker && npm run typecheck
PASS

cd tools/codex-app-server-worker && npm run build
PASS

mvn -pl addons/codex-worker-agent -am \
  -Dtest=CodexWorkerClientTest,CodexTaskExtensionControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
PASS: BUILD SUCCESS; 43 tests, 0 failures/errors/skips

mvn -pl addons/codex-worker-agent -am -DskipTests package
PASS: BUILD SUCCESS; 8-module reactor

PATH=<Node-22.23.1> pnpm --filter @foggy/navigator-frontend \
  exec vitest run src/components/worker/__tests__/TaskPane.test.ts
PASS: 9/9

PATH=<Node-22.23.1> pnpm --filter @foggy/navigator-frontend run type-check
PASS

PATH=<Node-22.23.1> bash scripts/build-frontend.sh
PASS: canonical frontend typecheck/test/build matrix
Navigator frontend 251/251; Foggy Chat 114/114; Mobile 59/59; Widget 31/31
```

Worker full-suite coverage includes the pre-existing crash, drain, process-tree, single-child, different-Thread concurrency, same-Thread serialization, targeted abort, user-input and lifecycle fail-closed regressions. New coverage adds compact canonical terminal correlation, same-Thread queueing, different-Thread non-blocking behavior, idempotency/restart recovery, usage isolation and current-vs-cumulative token semantics.

## Local Worker 0.3.19 Deployment

Owner explicitly authorized the local Worker deployment on 2026-07-17. The release version was
advanced from 0.3.18 to 0.3.19 while retaining Codex CLI 0.144.3.

```text
cd tools/codex-app-server-worker && npm run package:release
PASS: 311 tests; 310 passed, 1 platform-conditional skipped, 0 failed
PASS: schema verification, typecheck and clean build
archive: codex-app-server-worker-0.3.19.zip
sha256: fdffeb70d84b56b2d1b66a90677f0bbaacf9948325c8ac4818c6358e0e92bf53

~/.codex-app-server-worker/update.sh --package <archive> --dry-run
PASS: candidate validated without modifying the installation

~/.codex-app-server-worker/update.sh --package <archive>
PASS: controlled drain, process-tree verification, swap and candidate start
```

The first candidate process became ready but then exited when its invoking execution session ended.
The retained schema-v2 process snapshot was checked with both `process-tree verify` and
`process-tree status`; each returned `clean` with zero processes. Only the dead PID and matching
snapshot were then removed, and the existing `start.sh --no-build` was started in a detached
session. A separate later command confirmed:

```text
version=0.3.19
ready=true
port=3071
runtime_id=codex-app-server-primary
runtime_revision=5
instance_id preserved
cli_version=0.144.3
active_tasks=0
no lifecycle.lock/update.in-progress/stop.failed/lifecycle.failed evidence
```

## Remaining Runtime Validation Boundary

- Source implementation and the local installed Worker are ready for independent signoff.
- Java/frontend changes have not been deployed to the JDK17 Navigator service; the owner will update that service separately.
- The new Navigator → Java → installed Worker path and long-running multi-Thread stability still require owner manual validation after that update.
- Automatic compaction, threshold changes and thread fork/rollback remain deferred.
