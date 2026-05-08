---
acceptance_scope: version
version: 1.1.0-SNAPSHOT
target: 1.1.0-SNAPSHOT
status: signed-off
decision: accepted-with-risks
signed_off_by: codex
signed_off_at: 2026-04-28
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 10
doc_role: acceptance-record
doc_purpose: 记录 1.1.0-SNAPSHOT 版本级封版签收结论
---

# Version Acceptance

## Background

1.1.0-SNAPSHOT 的版本目标是交付业务型 Worker 的 Skill Runtime 基线能力：

1. 业务型 Worker 轻量化。
2. LangGraph Skill Runtime。
3. Skill 生命周期、审批与上下文隔离。
4. Mock/真实 LLM Skill Agent 测试闭环。
5. Skill Registry 的公共 Skill 与账号私有 Skill 只读加载链路。

本版本首版约束明确：不引入真实外部业务工具，真实业务工具适配延迟到集成阶段。

## Acceptance Basis

- Version README: `docs/version-tracker/1.1.0-SNAPSHOT/README.md`
- Skill Runtime Design: `docs/version-tracker/1.1.0-SNAPSHOT/31-langgraph-biz-worker-skill-runtime-design.md`
- TMS API Contract: `docs/version-tracker/1.1.0-SNAPSHOT/32-langgraph-biz-worker-tms-sequence-and-api-contract.md`
- Implementation Plan: `docs/version-tracker/1.1.0-SNAPSHOT/33-langgraph-biz-worker-implementation-plan.md`
- Skill Registry: `docs/version-tracker/1.1.0-SNAPSHOT/34-skill-registry-design.md`
- Default Model Fix: `docs/version-tracker/1.1.0-SNAPSHOT/35-agent-default-model-fix.md`
- Mock/Real LLM Skill Tests: `docs/version-tracker/1.1.0-SNAPSHOT/36-mock-llm-skill-test-support.md`
- 31 Acceptance: `docs/version-tracker/1.1.0-SNAPSHOT/acceptance/31-langgraph-biz-worker-acceptance.md`
- 36 Acceptance: `docs/version-tracker/1.1.0-SNAPSHOT/acceptance/36-mock-llm-skill-test-support-acceptance.md`
- 1.1.1 Deferral: `docs/version-tracker/1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md`

## Module Summary

| Item | Status | Decision | Notes |
|---|---|---|---|
| 31 Skill Runtime | signed-off | accepted-with-risks | Frame 生命周期、嵌套 Skill、审批、上下文隔离已验收 |
| 34 Skill Registry | closed-with-deferral | accepted-with-deferral | 公共 Skill 同步和账号 Skill 加载已完成，`write_file` 创建闭环延期到 1.1.1 |
| 35 默认模型修复 | verified | accepted | `TaskDispatchFacadeTest` 已补跑通过 |
| 36 Mock/真实 LLM Skill 测试 | signed-off | accepted-with-followups | OpenAI-compatible 真实 LLM 联调已完成 |

## Checklist

- [x] 版本目标与 README 中的首版约束一致。
- [x] 31 主线 Skill Runtime 已完成正式验收。
- [x] 36 已补充 quality / coverage / acceptance。
- [x] 真实 OpenAI-compatible LLM Skill Agent 联调已完成。
- [x] Biz Worker 全量 Python 回归通过。
- [x] 35 相关 Java 定向单测已补跑通过。
- [x] 34 未闭环项已明确移入 1.1.1，不再作为 1.1.0 阻塞项。
- [x] 体验验证为 N/A：本版本交付主要为后端 Runtime、Worker、测试链路，无 UI 交互新增。

## Evidence

| # | Evidence | Result |
|---|---|---|
| E1 | `tools/langgraph-biz-worker`: `.venv/Scripts/python.exe -m pytest tests` | `202 passed` |
| E2 | 34 定向测试 | `20 passed` |
| E3 | `session-module`: `mvn -pl session-module -Dtest=TaskDispatchFacadeTest test` | `38 passed`, `BUILD SUCCESS` |
| E4 | `tools/mock-llm-service`: `pytest tests` | `17 passed, 1 warning` |
| E5 | 真实 LLM Skill Agent 联调 | Frame `COMPLETED` |
| E6 | FileFrameJournal 数据比对 | `all_checks_passed = true` |
| E7 | 31 quality gate | `ready-for-coverage-audit` |
| E8 | 31 coverage audit | `ready-for-acceptance` |
| E9 | 31 acceptance | `signed-off`, `accepted-with-risks` |
| E10 | 36 acceptance | `signed-off`, `accepted-with-followups` |

## Risks / Open Items

| # | Open Item | Blocking | Follow-up |
|---|---|---|---|
| R1 | `write_file` 创建账号私有 Skill 的路径权限与工具闭环 | no | 1.1.1 `02-account-skill-write-file-permission.md` |
| R2 | Artifact 外部化与长参数上下文治理 | no | 1.1.1 `01-biz-worker-artifact-externalization-design.md` |
| R3 | Anthropic tool_use 单独验证 | no | 后续 provider 适配 |
| R4 | 真实业务工具注册表 | no | 后续业务系统集成 |
| R5 | 完整 `SKILL.md` body 注入 frame prompt | no | 后续 Skill Runtime 增强 |

## Final Decision

**accepted-with-risks**

1.1.0-SNAPSHOT 达到版本封版条件。当前风险均已归入 1.1.1 或后续版本，不阻塞 1.1.0 作为 Skill Runtime 基线版本封版。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: codex
- signed_off_at: 2026-04-28
- acceptance_record: docs/version-tracker/1.1.0-SNAPSHOT/acceptance/version-signoff.md
- blocking_items: none
- follow_up_required: yes
