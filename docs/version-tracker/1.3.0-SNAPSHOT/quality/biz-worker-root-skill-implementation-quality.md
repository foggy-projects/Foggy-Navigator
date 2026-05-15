---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.0-SNAPSHOT
target: biz-worker-root-skill-context-and-business-function
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-05-16
follow_up_required: yes
---

# Implementation Quality Gate

## Background

本次检查对象是 BizWorker root skill 常驻入口、业务函数虚拟 frame、审批恢复确定性消息、上下文可见性与 SDK/CLI `contextVisibility` 透传。

## Check Basis

- `docs/version-tracker/1.3.0-SNAPSHOT/13-biz-worker-root-skill-context-design.md`
- Python Worker: `tools/langgraph-biz-worker/src/langgraph_biz_worker/**`
- Java control plane/runtime: `business-agent-module/**`
- Java worker adapter: `addons/langgraph-biz-worker/**`
- Open SDK/CLI: `navigator-open-sdk/**`

## Changed Surface

- Worker root graph、frame runtime、resume route、skill materialize route、LLM skill agent runtime context.
- Java skill registry、function authorization、worker gateway、function suspension、resume listener.
- SDK/CLI business agent forms、DTO、manifest passthrough.
- Tests across Worker pytest, Java service/listener tests, SDK/CLI tests.

## Quality Checklist

- scope conformance: 改动集中在 root skill/function frame/context visibility/approval resume/SDK 透传，未引入新的前端 UI 行为。
- code hygiene: `git diff --check` 无 whitespace error；未发现新增 debug print、console 或临时 TODO。
- duplication and consolidation: `contextVisibility` 归一化在 Java 控制面和 Worker materialize 各自保留一处边界校验，属于跨进程契约边界重复，当前可接受。
- complexity and abstraction: Python `llm_skill_agent.py` 与 `skill_runtime.py` 增长较多，但关键职责仍按 frame runtime、prompt context、tool handling 分层；暂不需要再抽新服务。
- error handling and edge cases: 已覆盖 unknown frame、awaiting approval restore、resume 404、tenant/session/function/input hash mismatch、passthrough 降级、invalid context visibility。
- readability and maintainability: 新增系统策略以 `system.root`、`FUNCTION_CALL`、`context_visibility` 显式表达，代码可追踪。
- critical logic documentation: 设计文档已记录 root skill、passthrough 保留、Java 保完整历史、Worker 保压缩工作上下文等关键约束。
- contract and compatibility: SDK/CLI 只透传字段，不做过度校验；服务端默认 `isolated`，普通业务 skill 仅保存/下发 `isolated|summary`。
- documentation and writeback: 设计文档、Skill Bundle 设计、Business Agent Bundle 设计已回写。
- test alignment: 自动化测试覆盖 Worker runtime、Java authorization/resume/suspension、SDK/CLI JSON passthrough。
- release readiness: 无阻断性实现问题。

## Findings

未发现阻断问题。

## Risks / Follow-ups

- 上游项目需要明确是否在 skill/agent manifest 中声明 `contextVisibility: summary`；不声明时保持 `isolated`。
- 当前没有前端技能注册 UI 改动，因为现有前端未暴露相关注册表单；后续如果新增管理页，需要把 `contextVisibility` 作为可选高级项。
- 用户级函数权限当前是 ClientApp upstream user grant + ClientApp function grant 的组合，不是 per-user function grant；这与本阶段设计一致。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- 如进入正式验收，再使用 `foggy-acceptance-signoff`

## Decision

`ready-for-coverage-audit`。实现范围与设计一致，测试结果支持进入覆盖审计。
