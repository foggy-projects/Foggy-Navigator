# 1.3.0-SNAPSHOT

本目录用于跟踪 `GeminiWorker` 接入的需求、设计、代码清单与实现计划。

## 文档清单

- [01-gemini-worker-requirement.md](./01-gemini-worker-requirement.md)
- [02-gemini-worker-module-responsibility.md](./02-gemini-worker-module-responsibility.md)
- [03-gemini-worker-code-inventory.md](./03-gemini-worker-code-inventory.md)
- [04-gemini-worker-implementation-plan.md](./04-gemini-worker-implementation-plan.md)
- [05-gemini-worker-execution-checkin.md](./05-gemini-worker-execution-checkin.md)
- [06-codex-cli-gone-task-still-processing-bug.md](./06-codex-cli-gone-task-still-processing-bug.md)
- [07-project-shared-milestone-storage-design.md](./07-project-shared-milestone-storage-design.md)
- [08-new-worker-integration-guide-and-retrospective.md](./08-new-worker-integration-guide-and-retrospective.md)
- [09-history-session-page-size-bug.md](./09-history-session-page-size-bug.md)
- [10-session-auth-model-config-binding-plan.md](./10-session-auth-model-config-binding-plan.md)
- [11-navigator-upstream-cli-skill-clear-command.md](./11-navigator-upstream-cli-skill-clear-command.md)
- [12-navigator-upstream-cli-model-grant-command.md](./12-navigator-upstream-cli-model-grant-command.md) - upstream model grant + ClientApp-owned model create/update/rotate-key
- [13-biz-worker-root-skill-context-design.md](./13-biz-worker-root-skill-context-design.md) - BizWorker root skill、业务函数虚拟 frame、上下文穿透与暂停授权设计
- [14-biz-worker-root-skill-upstream-e2e-regression-bug.md](./14-biz-worker-root-skill-upstream-e2e-regression-bug.md) - TMS X6 upstream validation found contextVisibility materialize and approval suspend/resume regressions
- [15-client-app-upstream-route-registry.md](./15-client-app-upstream-route-registry.md) - ClientApp-scoped upstream_ref route registry and CLI commands for issue #115
- [16-history-session-page-content-overflow-bug.md](./16-history-session-page-content-overflow-bug.md)
- [17-biz-worker-complex-task-plan-observer-design.md](./17-biz-worker-complex-task-plan-observer-design.md) - BizWorker complex task active_plan and read-only plan observer design
- [18-biz-worker-complex-task-session-sample-audit.md](./18-biz-worker-complex-task-session-sample-audit.md) - Desensitized Codex/Claude session sample audit for complex task planning
- [19-biz-worker-frame-execution-report-design.md](./19-biz-worker-frame-execution-report-design.md) - BizWorker frame execution report and provenance markdown design
- [workitems/BUG-021-follow-up-3-langgraph-terminal-status-after-approval-result.md](./workitems/BUG-021-follow-up-3-langgraph-terminal-status-after-approval-result.md) - BUG-021 follow-up terminal status persistence after approval result
- [workitems/BUG-022-materialize-bootstrap-non-blocking.md](./workitems/BUG-022-materialize-bootstrap-non-blocking.md) - materialize should not block first bootstrap when Skill/Function resources are not ready
- [workitems/BUG-issue-115-world-sim-route-smoke-blocked.md](./workitems/BUG-issue-115-world-sim-route-smoke-blocked.md) - issue #115 live smoke is blocked by CLI release and control credential scope
- [quality/biz-worker-root-skill-implementation-quality.md](./quality/biz-worker-root-skill-implementation-quality.md)
- [coverage/biz-worker-root-skill-coverage-audit.md](./coverage/biz-worker-root-skill-coverage-audit.md)
