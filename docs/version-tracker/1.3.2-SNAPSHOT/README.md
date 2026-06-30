# 1.3.2-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.3.2-SNAPSHOT` 版本内 Codex Biz Route readiness、调用契约、回归测试、Worker 诊断与 smoke 证据。

## 版本目标

本版本优先把 `codex-biz-worker` 从“已有可用路由”推进到“可交给上游业务继续联调”的 readiness 状态：固定 OpenAPI/统一分派/Worker 请求契约，补齐缺失的回归保护，暴露非敏感 Worker 诊断，并给出 scoped `CODEX_HOME` 的可执行 smoke 入口。

路线定位：`LANGGRAPH_BIZ` / LangBizWorker 与 `codex-biz-worker` / CodexBizWorker 是互补路线。企业应用和正式业务编排默认继续使用 LangBizWorker；CodexBizWorker 面向内部调试、开发者自用和 Codex-native 执行/诊断，必须显式 opt-in，不作为原 BizWorker 的透明替换。

后续补充 `LANGGRAPH_BIZ` Actor-owned BizWorker 目录契约：Actor-owned 任务必须携带 `directoryId`，OpenAPI / BusinessAgentTaskService / LangGraph direct create 均 fail-fast；A2A / launcher / direct create 透传 `directoryId` 和 `cwd`；Python delegated workspace 文件工具写入 Actor Home，并在结果中标明 storage mode。

CodexBizWorker 后续验收从 SIM 单点评估升级为通用上游接入闭环：以 Navigator effective directory 作为派发前置结果，而不是要求所有上游显式传 `directoryId`；先完成 self-owned smoke upstream，再分别沉淀 SIM / TMS 的 consumer 接入差异和验收证据。

## 版本状态

- status: signed-off
- decision: accepted
- signed_off_at: 2026-06-29
- acceptance_record: [acceptance/OPT-001-codex-biz-route-readiness-acceptance.md](./acceptance/OPT-001-codex-biz-route-readiness-acceptance.md)
- follow_up_status: ready-for-signoff
- follow_up_record: [workitems/OPT-002-langgraph-biz-actor-home-readiness.md](./workitems/OPT-002-langgraph-biz-actor-home-readiness.md)
- upstream_acceptance_status: signed-off-with-risks; self-owned smoke, basic live ask, `submit_skill_result`, BusinessFunction schema/invoke/tool-message, context continuation passed; MCP tool allowlist and TaskEvidence OPEN_ARTIFACT lifting covered by regression; SIM / TMS consumer-side smoke handed off
- upstream_acceptance_record: [workitems/OPT-003-codex-biz-upstream-acceptance.md](./workitems/OPT-003-codex-biz-upstream-acceptance.md)
- worker_response_timeout_status: ready-for-signoff
- worker_response_timeout_record: [workitems/OPT-004-worker-response-timeout-indicator.md](./workitems/OPT-004-worker-response-timeout-indicator.md)

## 文档清单

- [workitems/OPT-001-codex-biz-route-readiness.md](./workitems/OPT-001-codex-biz-route-readiness.md) - Codex Biz Route readiness 与 1~5 推进计划
- [quality/OPT-001-implementation-quality.md](./quality/OPT-001-implementation-quality.md) - 实现质量门禁记录
- [coverage/OPT-001-coverage-audit.md](./coverage/OPT-001-coverage-audit.md) - 测试覆盖审计记录
- [acceptance/OPT-001-codex-biz-route-readiness-acceptance.md](./acceptance/OPT-001-codex-biz-route-readiness-acceptance.md) - 功能级签收记录
- [workitems/OPT-002-langgraph-biz-actor-home-readiness.md](./workitems/OPT-002-langgraph-biz-actor-home-readiness.md) - LangGraph BizWorker Actor Home 目录契约与文件工具对齐
- [quality/OPT-002-implementation-quality.md](./quality/OPT-002-implementation-quality.md) - OPT-002 实现质量门禁记录
- [coverage/OPT-002-coverage-audit.md](./coverage/OPT-002-coverage-audit.md) - OPT-002 测试覆盖审计记录
- [acceptance/OPT-002-langgraph-biz-actor-home-readiness-acceptance.md](./acceptance/OPT-002-langgraph-biz-actor-home-readiness-acceptance.md) - OPT-002 待签收记录
- [workitems/OPT-003-codex-biz-upstream-acceptance.md](./workitems/OPT-003-codex-biz-upstream-acceptance.md) - CodexBizWorker 通用上游接入；self-owned smoke、`submit_skill_result`、BusinessFunction、context continuation 已通过，SIM / TMS consumer 验收已交付
- [quality/OPT-003-implementation-quality.md](./quality/OPT-003-implementation-quality.md) - OPT-003 实现质量门禁记录
- [coverage/OPT-003-coverage-audit.md](./coverage/OPT-003-coverage-audit.md) - OPT-003 测试覆盖审计记录
- [acceptance/OPT-003-codex-biz-upstream-acceptance.md](./acceptance/OPT-003-codex-biz-upstream-acceptance.md) - OPT-003 功能级签收记录
- [handoff/OPT-003-consumer-handoff.md](./handoff/OPT-003-consumer-handoff.md) - SIM / TMS consumer smoke 交接清单与 wrapper gate
- [workitems/OPT-004-worker-response-timeout-indicator.md](./workitems/OPT-004-worker-response-timeout-indicator.md) - Worker 任务长时间无用户可见输出时的响应超时辅助提示
