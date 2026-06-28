# 1.3.2-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 `1.3.2-SNAPSHOT` 版本内 Codex Biz Route readiness、调用契约、回归测试、Worker 诊断与 smoke 证据。

## 版本目标

本版本优先把 `codex-biz-worker` 从“已有可用路由”推进到“可交给上游业务继续联调”的 readiness 状态：固定 OpenAPI/统一分派/Worker 请求契约，补齐缺失的回归保护，暴露非敏感 Worker 诊断，并给出 scoped `CODEX_HOME` 的可执行 smoke 入口。

## 文档清单

- [workitems/OPT-001-codex-biz-route-readiness.md](./workitems/OPT-001-codex-biz-route-readiness.md) - Codex Biz Route readiness 与 1~5 推进计划
