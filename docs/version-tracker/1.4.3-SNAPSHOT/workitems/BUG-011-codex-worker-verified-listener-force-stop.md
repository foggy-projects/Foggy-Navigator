---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-011-codex-worker-verified-listener-force-stop
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-22
open_questions: []
---

# Delivery Spec: 已验证 Codex Worker listener 的本地强制停止

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 在本地开发栈重启被任务快照阻断时，限定为已验证归属的 Codex Worker listener 增加显式 TERM 后 KILL 的恢复路径。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-011-codex-worker-verified-listener-force-stop.md`

## Goal

- version_goal: 恢复本仓库本地 Codex Worker 的可控重启，不扩张任何上游或 production 边界。
- target_outcome: 若 listener 的命令行与工作目录均证明归属当前仓库，`stop.sh` 先请求 TERM，再在有限等待后只 KILL 该已验证 listener；快照不可用时自动恢复，快照非静止时必须由 `local-dev-stack.sh --force-owned-codex` 显式授权，并留下脱敏停机证据。

## Scope

- in_scope:
  - `tools/codex-agent-worker/stop.sh` 的 Linux/macOS 本地恢复分支。
  - `scripts/local-dev-stack.sh --force-owned-codex` 到 Codex `stop.sh --force-owned` 的单向参数传递。
  - 对应静态安全回归测试与本机 `stop.sh` / `start.sh` 重启验证。
- affected_modules:
  - `tools/codex-agent-worker`
- external_dependencies: 现有本仓库 Codex Worker；不得读取或更改其真实 credential。

## Non-Goals

- out_of_scope:
  - Claude Worker、PowerShell stop script、Worker Gateway、external、production、上游凭据或任务语义。
  - 对归属未验证 listener、任意端口或 pid-file 中非 listener 进程执行终止。
- do_not_touch:
  - INT-001 / BUG-009 / BUG-010 harness 和证据。
  - 真实 SIM/TMS profile、WorkerHost、BizWorkerIdentity、WorkerPool 或 sibling workspace。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 强制路径仅限已验证归属的 Codex Worker listener | 用户明确批准 TERM 后强杀，但保留端口/工作目录/命令行 ownership proof | 不终止 pid-file 中仅历史或非 listener 的 PID |
| 快照不可用时自动恢复；快照非静止时必须显式 force | 既恢复 401/不可验证的本地重启，也避免默认中断已知任务 | `--force-owned-codex` 仅传给当前仓库的 Codex Unix stop script |
| TERM 后有限等待，再 KILL | 尽量保留优雅退出机会 | 每次结果必须记录无凭据的 evidence；KILL 失败不伪报停止成功 |

## Acceptance Criteria

- [ ] AC-1: 快照不可用且 listener ownership 已验证时，stop script 只对已验证 listener 执行 TERM，超时后执行 KILL，并确认退出或 fail closed。
- [ ] AC-2: `local-dev-stack.sh --force-owned-codex` 仅在 listener ownership 已验证后，允许对快照非静止的 Codex Worker 执行 TERM 后 KILL；默认路径仍拒绝自动中断已知任务。
- [ ] AC-3: 未验证归属、listener 解析失败、pid-file 不可信等既有保护不被放宽；force 不传递给其他 Worker。
- [ ] AC-4: 相关安全测试实际通过；本机 stop/start 实际执行并记录结果，不回显 credential。

## Contract / Data / Security Constraints

- API or event contract: 不变；只消费既有本地 `/api/v1/processes` 快照。
- data and migration: 无。
- compatibility and rollback: 删除该恢复分支即可恢复原有 snapshot-unavailable fail-closed 行为；不修改 PowerShell 行为。
- permissions and secrets: 不读取、打印或写入 token/profile 内容；证据仅包含 PID、计数、动作与枚举结果。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | 误杀非本仓库进程 | `npm test -- --test-name-pattern 'stop'` | 实际测试输出 |
| AC-3 | 本机重启不真实或 Worker 未恢复 | `stop.sh` 后 `start.sh`、health/status | 命令输出与脱敏停机 evidence |

## Bug Context

- bug_source: user-report
- severity: major
- environment: 本仓库 Linux local-dev-stack，Codex Worker :3051。
- current_behavior: listener ownership 已证明，但 snapshot endpoint 因认证不可用返回 401，`stop.sh` 拒绝停止，`local-dev-stack.sh restart` 被阻断。
- expected_behavior: 在限定 ownership proof 成功时，本地恢复分支可 TERM 后 KILL listener；当快照明确非静止，只有显式 `--force-owned-codex` 才可中断该本地 Worker。
- reproduction_steps: 执行 `bash tools/codex-agent-worker/stop.sh`，使 `/api/v1/processes` 快照不可用。
- reproduction_status: confirmed
- existing_evidence: `tools/codex-agent-worker/logs/stop-evidence/stop-20260722T090200Z-3276299.log`
- existing_tests: `tools/codex-agent-worker/tests/stop-script-safety.test.ts`
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 即使 listener 归属已验证，KILL 仍会中断本地 Codex 执行；仅限用户明确批准的 local Codex Worker 恢复路径。
  - Worker 若因 credential forwarding 未 ready 而无法通过 health，重启不等于 SIM runtime ready。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md` 与 `tools/codex-agent-worker` 现有 stop/start/test 约束。
- 仅实现已批准 recovery / explicit force 分支；不得扩大至其他 Worker、其他平台脚本或任意 listener。
- 若实现需要放宽 ownership proof、读取 credential、改变 task/Worker 安全模型或影响上游边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行 focused test 后执行一次受控本机 stop/start；记录精确结果。
- 完成后填写 Implementation Result 并将状态设为 `READY_FOR_SIGNOFF`；不得自行设为 `ACCEPTED`。

## Implementation Result

- implementation_summary: `stop.sh` 在既有 listener 命令行与工作目录归属验证完成后，如 `/api/v1/processes` 快照不可用，先发送 TERM、等待 5 秒，再只对仍存活的已验证 listener 发送 KILL；新增 `--force-owned` 仅在快照明确非静止时启用同一条 TERM→KILL 路径。`local-dev-stack.sh --force-owned-codex` 只向 Codex Unix stop script 传递该参数；所有其他 Worker 均不接收 force。
- changed_paths:
  - `scripts/local-dev-stack.sh`
  - `tools/codex-agent-worker/stop.sh`
  - `tools/codex-agent-worker/tests/stop-script-safety.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-011-codex-worker-verified-listener-force-stop.md`
- tests_and_results:
  - `bash -n stop.sh` — PASS。
  - `npm test -- --test-name-pattern 'stop'` — PASS，224 tests：223 passed、1 skipped；包含新增 verified-listener force-stop 静态保护。
  - `bash stop.sh && bash start.sh` — PASS；快照不可用时对已验证 listener 请求 TERM，listener 在等待窗口内退出，随后新 Worker 达到 READY；本次无需发送 KILL。
  - `bash scripts/local-dev-stack.sh restart` — PASS（在前一次 Codex listener 已退出后重启）；8112、3031、3051、3061、3072、3161 均为 UP。
  - `bash -n scripts/local-dev-stack.sh && bash -n tools/codex-agent-worker/stop.sh` — PASS。
  - `bash scripts/local-dev-stack.sh --help && bash tools/codex-agent-worker/stop.sh --help` — PASS；确认 `--force-owned-codex` 与内部 `--force-owned` 均可发现。
  - `npm test -- --test-name-pattern 'stop'`（在 `tools/codex-agent-worker`）— PASS，224 tests：223 passed、1 skipped；包含 stack 参数仅传递给 Codex、ownership 前置和 listener-only KILL 静态保护。
  - `git diff --check` — PASS。
- manual_or_experience_evidence: 停机 evidence 位于 `tools/codex-agent-worker/logs/stop-evidence/`，仅包含 PID、计数、动作和枚举结果；未记录或回显 credential。
- deviations: 初次整栈重启在 Codex listener 已因 TERM 退出、但 post-drain snapshot 未能再次确认时按既有保护中止；随后在无 listener 状态重新执行整栈重启并成功。用户随后明确批准对“已验证归属 Codex Worker”的显式 force 范围，契约已更新；未执行真实 force，以避免人为中断运行时任务。
- residual_risks:
  - 已批准的 KILL 路径仍可能中断无法由快照证明的本地 Codex 执行；它仅限本仓库、已验证归属的 Unix listener。
  - `--force-owned-codex` 在快照明确非静止时会有意中断本地 Codex managed work；仅在操作者明确选择该参数时使用。
  - PowerShell、Claude Worker 和全部 upstream/runtime 权限边界保持不变。
  - 本机栈 UP 不证明 SIM/TMS runtime tuple、readiness、owner-smoke、external 或 production ready。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-22 明确批准“仅已验证归属的 Codex Worker，TERM 后强杀”，随后明确批准 `--force-owned-codex`。
- related work items: `GOV-001-dev-s1-s2-integration-mvp.md`（隔离，不改变其范围）。
