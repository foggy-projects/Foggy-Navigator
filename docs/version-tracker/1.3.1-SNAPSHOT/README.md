# 1.3.1-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.3.1-SNAPSHOT` 版本内的 Java 侧架构风险治理、Codex 会话体验优化、实现进度、测试证据与验收状态。

## 版本目标

本版本优先处理 Java 侧架构 review 暴露出的高风险维护点，重点围绕统一任务分发、Provider 路由、Provider 私有状态、SSE 部署边界和运行配置治理；同时承接少量与 Codex 会话工作流直接相关的体验优化事项。

## 文档清单

- [workitems/BUG-145-bizworker-sidecar-permission-recovery.md](./workitems/BUG-145-bizworker-sidecar-permission-recovery.md) - BUG-145 BizWorker sidecar 写文件权限错误恢复
- [quality/BUG-145-implementation-quality.md](./quality/BUG-145-implementation-quality.md) - BUG-145 实现质量门记录
- [coverage/BUG-145-coverage-audit.md](./coverage/BUG-145-coverage-audit.md) - BUG-145 测试覆盖审计记录
- [acceptance/BUG-145-bizworker-sidecar-permission-recovery-acceptance.md](./acceptance/BUG-145-bizworker-sidecar-permission-recovery-acceptance.md) - BUG-145 验收签收记录
- [workitems/OPT-003-codex-session-file-change-hints.md](./workitems/OPT-003-codex-session-file-change-hints.md) - Codex 会话文件变更线索记录与 TaskPane 弹窗展示实现记录
- [quality/OPT-003-implementation-quality.md](./quality/OPT-003-implementation-quality.md) - OPT-003 实现质量门记录
- [coverage/OPT-003-coverage-audit.md](./coverage/OPT-003-coverage-audit.md) - OPT-003 测试覆盖审计记录
- [acceptance/OPT-003-codex-session-file-change-hints-acceptance.md](./acceptance/OPT-003-codex-session-file-change-hints-acceptance.md) - OPT-003 验收签收记录
- [workitems/OPT-001-java-architecture-risk-governance.md](./workitems/OPT-001-java-architecture-risk-governance.md) - Java 侧架构风险治理与核心链路优化计划
- [workitems/OPT-001-java-method-responsibility-inventory.md](./workitems/OPT-001-java-method-responsibility-inventory.md) - OPT-001 Stage 0 方法级职责清单与拆分输入
- [workitems/OPT-001-stage3-provider-state-schema.md](./workitems/OPT-001-stage3-provider-state-schema.md) - OPT-001 Stage 3 Provider 状态 schema 化执行计划
- [workitems/OPT-001-stage4-sse-deployment-boundary.md](./workitems/OPT-001-stage4-sse-deployment-boundary.md) - OPT-001 Stage 4 SSE 部署边界治理执行计划
- [workitems/OPT-001-stage5-runtime-config-hardening.md](./workitems/OPT-001-stage5-runtime-config-hardening.md) - OPT-001 Stage 5 运行配置硬化执行计划
- [workitems/OPT-001-stage6-task-query-provider-port-split.md](./workitems/OPT-001-stage6-task-query-provider-port-split.md) - OPT-001 Stage 6 TaskQueryProvider 窄端口治理执行计划
- [workitems/OPT-001-stage7-provider-listing-envelope.md](./workitems/OPT-001-stage7-provider-listing-envelope.md) - OPT-001 Stage 7 Provider listing/search typed envelope 执行计划
- [workitems/OPT-001-stage8-provider-port-injection.md](./workitems/OPT-001-stage8-provider-port-injection.md) - OPT-001 Stage 8 Provider port 注入收窄执行计划
- [workitems/OPT-001-stage9-langgraph-worker-session-split.md](./workitems/OPT-001-stage9-langgraph-worker-session-split.md) - OPT-001 Stage 9 LangGraph worker-session 端口拆分执行计划
- [workitems/OPT-001-stage10-langgraph-narrow-port-bean.md](./workitems/OPT-001-stage10-langgraph-narrow-port-bean.md) - OPT-001 Stage 10 LangGraph 窄端口 bean 迁移执行计划
- [workitems/OPT-001-stage11-gemini-narrow-port-bean.md](./workitems/OPT-001-stage11-gemini-narrow-port-bean.md) - OPT-001 Stage 11 Gemini 窄端口 bean 迁移执行计划
- [workitems/OPT-001-stage12-codex-narrow-port-bean.md](./workitems/OPT-001-stage12-codex-narrow-port-bean.md) - OPT-001 Stage 12 Codex / Codex Biz 窄端口 bean 迁移执行计划
- [workitems/OPT-001-stage13-claude-narrow-port-bean.md](./workitems/OPT-001-stage13-claude-narrow-port-bean.md) - OPT-001 Stage 13 Claude 窄端口 bean 迁移执行计划
- [workitems/OPT-001-stage14-task-listing-typed-method.md](./workitems/OPT-001-stage14-task-listing-typed-method.md) - OPT-001 Stage 14 TaskListingProvider typed method contract 执行计划
- [workitems/OPT-001-stage15-worker-session-typed-envelope.md](./workitems/OPT-001-stage15-worker-session-typed-envelope.md) - OPT-001 Stage 15 WorkerSession typed DTO / envelope 执行计划
- [workitems/OPT-001-stage16-claude-worker-session-bean.md](./workitems/OPT-001-stage16-claude-worker-session-bean.md) - OPT-001 Stage 16 Claude worker-session provider bean split 执行计划
- [workitems/OPT-001-stage17-legacy-provider-method-deprecation.md](./workitems/OPT-001-stage17-legacy-provider-method-deprecation.md) - OPT-001 Stage 17 legacy provider method deprecation gate 执行计划
- [workitems/OPT-001-stage18-task-command-cancel-direct-method.md](./workitems/OPT-001-stage18-task-command-cancel-direct-method.md) - OPT-001 Stage 18 TaskCommandProvider cancel direct method 执行计划
- [workitems/OPT-001-stage19-migration-support-foundation.md](./workitems/OPT-001-stage19-migration-support-foundation.md) - OPT-001 Stage 19 migration support foundation 执行计划
- [workitems/OPT-001-stage20-startup-migration-runner.md](./workitems/OPT-001-stage20-startup-migration-runner.md) - OPT-001 Stage 20 startup migration runner / manifest 执行计划
- [workitems/RELEASE-20260626-java-architecture-main-closure.md](./workitems/RELEASE-20260626-java-architecture-main-closure.md) - 2026-06-26 Java 架构治理 main 发版收口记录
- [quality/OPT-001-implementation-quality.md](./quality/OPT-001-implementation-quality.md) - OPT-001 Stage 2 实现质量门记录
- [coverage/OPT-001-stage1-stage2-coverage-audit.md](./coverage/OPT-001-stage1-stage2-coverage-audit.md) - OPT-001 Stage 1/2 测试覆盖审计记录
- [acceptance/OPT-001-stage1-stage2-acceptance.md](./acceptance/OPT-001-stage1-stage2-acceptance.md) - OPT-001 Stage 1/2 功能级验收签收记录
- [quality/OPT-001-stage3-implementation-quality.md](./quality/OPT-001-stage3-implementation-quality.md) - OPT-001 Stage 3 实现质量门记录
- [coverage/OPT-001-stage3-coverage-audit.md](./coverage/OPT-001-stage3-coverage-audit.md) - OPT-001 Stage 3 测试覆盖审计记录
- [acceptance/OPT-001-stage3-provider-state-schema-acceptance.md](./acceptance/OPT-001-stage3-provider-state-schema-acceptance.md) - OPT-001 Stage 3 功能级验收签收记录
- [acceptance/OPT-001-stage4-sse-deployment-boundary-acceptance.md](./acceptance/OPT-001-stage4-sse-deployment-boundary-acceptance.md) - OPT-001 Stage 4 功能级验收签收记录
- [acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md](./acceptance/OPT-001-stage5-runtime-config-hardening-acceptance.md) - OPT-001 Stage 5 功能级验收签收记录
- [quality/OPT-001-stage6-implementation-quality.md](./quality/OPT-001-stage6-implementation-quality.md) - OPT-001 Stage 6 实现质量门记录
- [coverage/OPT-001-stage6-coverage-audit.md](./coverage/OPT-001-stage6-coverage-audit.md) - OPT-001 Stage 6 测试覆盖审计记录
- [acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md](./acceptance/OPT-001-stage6-task-query-provider-port-split-acceptance.md) - OPT-001 Stage 6 功能级验收签收记录
- [quality/OPT-001-stage7-implementation-quality.md](./quality/OPT-001-stage7-implementation-quality.md) - OPT-001 Stage 7 实现质量门记录
- [coverage/OPT-001-stage7-coverage-audit.md](./coverage/OPT-001-stage7-coverage-audit.md) - OPT-001 Stage 7 测试覆盖审计记录
- [acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md](./acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md) - OPT-001 Stage 7 功能级验收签收记录
- [quality/OPT-001-stage8-implementation-quality.md](./quality/OPT-001-stage8-implementation-quality.md) - OPT-001 Stage 8 实现质量门记录
- [coverage/OPT-001-stage8-coverage-audit.md](./coverage/OPT-001-stage8-coverage-audit.md) - OPT-001 Stage 8 测试覆盖审计记录
- [acceptance/OPT-001-stage8-provider-port-injection-acceptance.md](./acceptance/OPT-001-stage8-provider-port-injection-acceptance.md) - OPT-001 Stage 8 功能级验收签收记录
- [quality/OPT-001-stage9-implementation-quality.md](./quality/OPT-001-stage9-implementation-quality.md) - OPT-001 Stage 9 实现质量门记录
- [coverage/OPT-001-stage9-coverage-audit.md](./coverage/OPT-001-stage9-coverage-audit.md) - OPT-001 Stage 9 测试覆盖审计记录
- [acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md](./acceptance/OPT-001-stage9-langgraph-worker-session-split-acceptance.md) - OPT-001 Stage 9 功能级验收签收记录
- [quality/OPT-001-stage10-implementation-quality.md](./quality/OPT-001-stage10-implementation-quality.md) - OPT-001 Stage 10 实现质量门记录
- [coverage/OPT-001-stage10-coverage-audit.md](./coverage/OPT-001-stage10-coverage-audit.md) - OPT-001 Stage 10 测试覆盖审计记录
- [acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md](./acceptance/OPT-001-stage10-langgraph-narrow-port-bean-acceptance.md) - OPT-001 Stage 10 功能级验收签收记录
- [quality/OPT-001-stage11-implementation-quality.md](./quality/OPT-001-stage11-implementation-quality.md) - OPT-001 Stage 11 实现质量门记录
- [coverage/OPT-001-stage11-coverage-audit.md](./coverage/OPT-001-stage11-coverage-audit.md) - OPT-001 Stage 11 测试覆盖审计记录
- [acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md](./acceptance/OPT-001-stage11-gemini-narrow-port-bean-acceptance.md) - OPT-001 Stage 11 功能级验收签收记录
- [quality/OPT-001-stage12-implementation-quality.md](./quality/OPT-001-stage12-implementation-quality.md) - OPT-001 Stage 12 实现质量门记录
- [coverage/OPT-001-stage12-coverage-audit.md](./coverage/OPT-001-stage12-coverage-audit.md) - OPT-001 Stage 12 测试覆盖审计记录
- [acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md](./acceptance/OPT-001-stage12-codex-narrow-port-bean-acceptance.md) - OPT-001 Stage 12 功能级验收签收记录
- [quality/OPT-001-stage13-implementation-quality.md](./quality/OPT-001-stage13-implementation-quality.md) - OPT-001 Stage 13 实现质量门记录
- [coverage/OPT-001-stage13-coverage-audit.md](./coverage/OPT-001-stage13-coverage-audit.md) - OPT-001 Stage 13 测试覆盖审计记录
- [acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md](./acceptance/OPT-001-stage13-claude-narrow-port-bean-acceptance.md) - OPT-001 Stage 13 功能级验收签收记录
- [quality/OPT-001-stage14-implementation-quality.md](./quality/OPT-001-stage14-implementation-quality.md) - OPT-001 Stage 14 实现质量门记录
- [coverage/OPT-001-stage14-coverage-audit.md](./coverage/OPT-001-stage14-coverage-audit.md) - OPT-001 Stage 14 测试覆盖审计记录
- [acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md](./acceptance/OPT-001-stage14-task-listing-typed-method-acceptance.md) - OPT-001 Stage 14 功能级验收签收记录
- [quality/OPT-001-stage15-implementation-quality.md](./quality/OPT-001-stage15-implementation-quality.md) - OPT-001 Stage 15 实现质量门记录
- [coverage/OPT-001-stage15-coverage-audit.md](./coverage/OPT-001-stage15-coverage-audit.md) - OPT-001 Stage 15 测试覆盖审计记录
- [acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md](./acceptance/OPT-001-stage15-worker-session-typed-envelope-acceptance.md) - OPT-001 Stage 15 功能级验收签收记录
- [quality/OPT-001-stage16-implementation-quality.md](./quality/OPT-001-stage16-implementation-quality.md) - OPT-001 Stage 16 实现质量门记录
- [coverage/OPT-001-stage16-coverage-audit.md](./coverage/OPT-001-stage16-coverage-audit.md) - OPT-001 Stage 16 测试覆盖审计记录
- [acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md](./acceptance/OPT-001-stage16-claude-worker-session-bean-acceptance.md) - OPT-001 Stage 16 功能级验收签收记录
- [quality/OPT-001-stage17-implementation-quality.md](./quality/OPT-001-stage17-implementation-quality.md) - OPT-001 Stage 17 实现质量门记录
- [coverage/OPT-001-stage17-coverage-audit.md](./coverage/OPT-001-stage17-coverage-audit.md) - OPT-001 Stage 17 测试覆盖审计记录
- [acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md](./acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md) - OPT-001 Stage 17 功能级验收签收记录
- [quality/OPT-001-stage18-implementation-quality.md](./quality/OPT-001-stage18-implementation-quality.md) - OPT-001 Stage 18 实现质量门记录
- [coverage/OPT-001-stage18-coverage-audit.md](./coverage/OPT-001-stage18-coverage-audit.md) - OPT-001 Stage 18 测试覆盖审计记录
- [acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md](./acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md) - OPT-001 Stage 18 功能级验收签收记录
- [quality/OPT-001-stage19-implementation-quality.md](./quality/OPT-001-stage19-implementation-quality.md) - OPT-001 Stage 19 实现质量门记录
- [coverage/OPT-001-stage19-coverage-audit.md](./coverage/OPT-001-stage19-coverage-audit.md) - OPT-001 Stage 19 测试覆盖审计记录
- [acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md](./acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md) - OPT-001 Stage 19 功能级验收签收记录
- [quality/OPT-001-stage20-implementation-quality.md](./quality/OPT-001-stage20-implementation-quality.md) - OPT-001 Stage 20 实现质量门记录
- [coverage/OPT-001-stage20-coverage-audit.md](./coverage/OPT-001-stage20-coverage-audit.md) - OPT-001 Stage 20 测试覆盖审计记录
- [acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md](./acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md) - OPT-001 Stage 20 功能级验收签收记录
