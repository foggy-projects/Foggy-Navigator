---
acceptance_scope: version
version: 1.1.2-SNAPSHOT
target: langgraph-biz-worker-skill-runtime-and-business-tool-boundary
doc_role: acceptance-record
doc_purpose: 记录 1.1.2-SNAPSHOT 版本正式验收与签收结论
status: signed-off
decision: accepted-with-risks
signed_off_by: platform-owner
signed_off_at: 2026-05-02
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 8
---

# Version Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: platform-owner / worker-owner / reviewer
- purpose: 汇总 `1.1.2-SNAPSHOT` 的设计、实现收口、测试证据与剩余风险，形成版本级签收结论。

## Background

- Version: `1.1.2-SNAPSHOT`
- Scope: LangGraph Biz Worker 标准 Skill / 工具契约、业务脚本编排边界、FSScript suspension bridge 需求、会话历史链路与 UI 边界验收。
- Goal: 将 1.1.2 的讨论和实现收口固化为可追溯文档，并确认 LangGraph Biz Worker History API 与 Live UI 边界已完成验收。

## Acceptance Basis

- [../README.md](../README.md)
- [../01-langgraph-biz-skill-tool-contract.md](../01-langgraph-biz-skill-tool-contract.md)
- [../02-business-script-engine-and-function-manifest.md](../02-business-script-engine-and-function-manifest.md)
- [../03-compose-script-business-action-adapter-requirement.md](../03-compose-script-business-action-adapter-requirement.md)
- [../04-fsscript-active-suspension-query-api-request.md](../04-fsscript-active-suspension-query-api-request.md)
- [../05-langgraph-biz-worker-session-history-and-ui-boundary.md](../05-langgraph-biz-worker-session-history-and-ui-boundary.md)

## Module Summary

| Module | Owner | Status | Acceptance Record | Notes |
|---|---|---|---|---|
| Skill / standard tool contract | worker-owner | accepted | this file | 标准工具边界已建档，不在本轮展开业务工具实现 |
| Business script engine boundary | platform-owner / worker-owner | accepted-with-risks | this file | 业务系统交互设计进入下一阶段 |
| Compose Script action adapter | platform-owner | accepted | this file | Navigator 侧审批适配需求已沉淀 |
| FSScript active suspension API request | worker-owner / fsscript-team | accepted | this file | 上游已补齐后可继续 Worker bridge |
| LangGraph History API / UI boundary | platform-owner / frontend-owner | accepted | this file | Unit、mock UI e2e、Live API、Live UI 均已验证 |

## Checklist

- [x] 版本索引明确 1.1.2 与 1.3.0 Gemini 版本线隔离。
- [x] Skill、标准工具、业务工具边界已建档。
- [x] FSScript / Compose Script 业务动作审批适配需求已建档。
- [x] LangGraph Biz Worker History API 统一端点已完成 Java 单元验收。
- [x] LangGraph / Claude UI 行为边界已完成 mock-based Playwright 验收。
- [x] Live History API 已通过真实登录态和真实 LangGraph 任务验证。
- [x] Live UI 已通过包含 `directoryId` 的真实浏览器断言验证。
- [x] 临时 live 验收脚本已清理，避免 token 误提交。

## Evidence

- Test: `pnpm --filter @foggy/navigator-frontend exec playwright test e2e/langgraph-worker-ui.spec.ts`
- Test: `pnpm --filter @foggy/navigator-frontend test`
- Test: `pnpm --filter @foggy/navigator-frontend build`
- Test: `pnpm --filter @foggy/navigator-frontend build:check`
- Test: `mvn -pl session-module -am "-Dtest=TaskControllerTest,TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Test: `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Live API: `GET /api/v1/tasks/workers/langgraph-biz-wsl/sessions`、`message-count`、`messages` 均返回 200，且 `message-count.total = 4`。
- Live UI: 浏览器断言确认 `Worker Session ID` 显示，`Claude Session ID`、`回退`、`重新同步` 不显示。

## Blocking Items

- none

## Risks / Open Items

- 业务系统交互设计尚未进入实现，本版本只完成边界和需求沉淀；下一阶段需要明确 Worker 调上游 REST、FSScript 编排层、审批确认码和业务函数 Manifest 的最终组合。
- 当前工作区存在独立的 Codex worker rewind/session-store 改动，已确认不属于 1.1.2 LangGraph 验收范围，应拆成单独任务评审和提交。

## Final Decision

`1.1.2-SNAPSHOT` 验收结论为 `accepted-with-risks`。

版本范围内的 LangGraph Biz Worker History API、Live UI 边界、Skill / 标准工具 / FSScript 编排需求文档已满足当前验收标准，无阻断项。剩余风险均为下一阶段业务系统交互设计和独立 Codex rewind 任务，不阻塞 1.1.2 收口。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: platform-owner
- signed_off_at: 2026-05-02
- acceptance_record: docs/version-tracker/1.1.2-SNAPSHOT/acceptance/version-signoff.md
- blocking_items: none
- follow_up_required: yes
