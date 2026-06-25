# 1.3.1-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.3.1-SNAPSHOT` 版本内的 Java 侧架构风险治理、实现进度、测试证据与验收状态。

## 版本目标

本版本优先处理 Java 侧架构 review 暴露出的高风险维护点，重点围绕统一任务分发、Provider 路由、Provider 私有状态、SSE 部署边界和运行配置治理。

## 文档清单

- [workitems/OPT-001-java-architecture-risk-governance.md](./workitems/OPT-001-java-architecture-risk-governance.md) - Java 侧架构风险治理与核心链路优化计划
- [workitems/OPT-001-java-method-responsibility-inventory.md](./workitems/OPT-001-java-method-responsibility-inventory.md) - OPT-001 Stage 0 方法级职责清单与拆分输入
- [workitems/OPT-001-stage3-provider-state-schema.md](./workitems/OPT-001-stage3-provider-state-schema.md) - OPT-001 Stage 3 Provider 状态 schema 化执行计划
- [workitems/OPT-001-stage4-sse-deployment-boundary.md](./workitems/OPT-001-stage4-sse-deployment-boundary.md) - OPT-001 Stage 4 SSE 部署边界治理执行计划
- [workitems/OPT-001-stage5-runtime-config-hardening.md](./workitems/OPT-001-stage5-runtime-config-hardening.md) - OPT-001 Stage 5 运行配置硬化执行计划
- [workitems/OPT-001-stage6-task-query-provider-port-split.md](./workitems/OPT-001-stage6-task-query-provider-port-split.md) - OPT-001 Stage 6 TaskQueryProvider 窄端口治理执行计划
- [workitems/OPT-001-stage7-provider-listing-envelope.md](./workitems/OPT-001-stage7-provider-listing-envelope.md) - OPT-001 Stage 7 Provider listing/search typed envelope 执行计划
- [workitems/OPT-001-stage8-provider-port-injection.md](./workitems/OPT-001-stage8-provider-port-injection.md) - OPT-001 Stage 8 Provider port 注入收窄执行计划
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
