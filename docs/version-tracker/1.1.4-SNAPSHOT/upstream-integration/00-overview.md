# 1.1.4 Upstream Integration Overview

## 文档作用

- doc_type: integration-plan-index
- version: 1.1.4-SNAPSHOT
- status: draft
- date: 2026-05-19
- intended_for: Navigator maintainer | BizWorker maintainer | Python integration developer | reviewer
- purpose: 汇总 1.1.4 中 BizWorker standalone Skill Agent 方向的设计决策与执行规划

## 目标

1. 将 BizWorker 从 Navigator Java 远程执行器升级为可独立集成的 Python Skill Agent Runtime。
2. 将 `skill_name` 定义为 Worker 侧 canonical identity，即 Skill 文件夹 basename。
3. 保持 Navigator/TMS 企业链路兼容，让 Navigator Java 作为 enterprise adapter，而不是 Worker core 的前置依赖。

## 文档索引

| 文档 | 主题 | 适用角色 |
| --- | --- | --- |
| [01-worker-skill-name-boundary-decision.md](./01-worker-skill-name-boundary-decision.md) | Worker `skill_name` 边界决策 | Navigator / BizWorker / reviewer |
| [02-bizworker-standalone-skill-agent-plan.md](./02-bizworker-standalone-skill-agent-plan.md) | BizWorker standalone Skill Agent 规划 | BizWorker / Navigator / Python integration developer |
| [03-bizworker-standalone-plan-review.md](./03-bizworker-standalone-plan-review.md) | 规划轻量评审与行动项 | BizWorker / Navigator / reviewer |
| [04-bizworker-skill-name-phase1-execution-plan.md](./04-bizworker-skill-name-phase1-execution-plan.md) | `skill_name` Phase 1 执行计划 | BizWorker implementer / Navigator reviewer |
| [05-bizworker-skill-name-phase1-progress.md](./05-bizworker-skill-name-phase1-progress.md) | `skill_name` Phase 1 进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [06-bizworker-local-skill-governance-phase2-plan.md](./06-bizworker-local-skill-governance-phase2-plan.md) | Phase 2 本地 Skill 治理 facade 执行计划 | BizWorker implementer / Python integration developer |
| [07-bizworker-local-skill-governance-phase2-progress.md](./07-bizworker-local-skill-governance-phase2-progress.md) | Phase 2 本地 Skill 治理进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [08-bizworker-standalone-service-phase3-plan.md](./08-bizworker-standalone-service-phase3-plan.md) | Phase 3A standalone service API 执行计划 | BizWorker implementer / Python integration developer |
| [09-bizworker-standalone-service-phase3-progress.md](./09-bizworker-standalone-service-phase3-progress.md) | Phase 3A standalone service API 进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [10-bizworker-standalone-provider-config-phase3b-plan.md](./10-bizworker-standalone-provider-config-phase3b-plan.md) | Phase 3B standalone provider 配置执行计划 | BizWorker implementer / Python integration developer |
| [11-bizworker-standalone-provider-config-phase3b-progress.md](./11-bizworker-standalone-provider-config-phase3b-progress.md) | Phase 3B standalone provider 配置进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [12-bizworker-standalone-integration-phase3c-plan.md](./12-bizworker-standalone-integration-phase3c-plan.md) | Phase 3C standalone 对外集成可用化计划 | BizWorker implementer / Python integration developer |
| [13-bizworker-standalone-integration-phase3c-progress.md](./13-bizworker-standalone-integration-phase3c-progress.md) | Phase 3C standalone 对外集成可用化进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [14-bizworker-upstream-execution-policy-phase3d-plan.md](./14-bizworker-upstream-execution-policy-phase3d-plan.md) | Phase 3D 上游执行策略约束计划 | BizWorker implementer / Navigator reviewer / Python integration developer |
| [15-bizworker-upstream-execution-policy-phase3d-progress.md](./15-bizworker-upstream-execution-policy-phase3d-progress.md) | Phase 3D 上游执行策略约束进展与测试证据 | BizWorker implementer / Navigator reviewer |
| [16-navigator-java-adapter-skill-name-execution-policy-progress.md](./16-navigator-java-adapter-skill-name-execution-policy-progress.md) | Navigator Java adapter 衔接 `skill_name` 与上游执行策略 | Navigator implementer / BizWorker reviewer |
| [17-navigator-skill-name-compatibility-narrowing-progress.md](./17-navigator-skill-name-compatibility-narrowing-progress.md) | Navigator Java adapter 收窄 legacy skill alias 兼容层 | Navigator implementer / BizWorker reviewer |

## 当前状态

| Area | Status | Notes |
| --- | --- | --- |
| Design decision | accepted-for-planning | `skill_name = skill folder basename` |
| Implementation plan | accepted-for-planning | 以 standalone simple runtime 为主线，Navigator 作为 enterprise adapter |
| Plan review | completed-with-actions | 已完成轻量架构评审；Phase 1 需遵守 scope guard 与 compatibility constraint |
| Phase 1 execution plan | implemented | 对外字段收敛为 `skill_name`，内部 `skill_id` 保持兼容 alias |
| Phase 2 execution plan | implemented | Python facade 本地治理已实现；FastAPI CRUD route 后续复用同一边界 |
| Phase 3A execution plan | implemented | 已将 `SkillAgent` 暴露为最小 standalone FastAPI service |
| Phase 3B provider config | implemented | 已支持 standalone skills/data root、tool module、custom model provider 与既有 LLM settings fallback |
| Phase 3C integration | implemented | 已补 standalone status、使用手册、外部项目 sample 与测试 |
| Phase 3D execution policy | implemented | 已支持上游 `workdir`、`allowed_dirs`、`allowed_tools` 归一化、工具暴露过滤、tool call 二次校验与 provider context 注入 |
| Java adapter contract | implemented | Navigator Java 已向 BizWorker 发送 top-level `skill_name`，并通过 hidden `runtimeContext.execution_policy` 透传上游 `workdir`、`allowed_dirs`、`allowed_tools` |
| Java skill alias narrowing | implemented | providerConfig canonical `skill_name`，legacy inbound `skill_id` / `skillId` 仅作为 deprecated fallback 且冲突即拒绝 |
| Coding | implemented | Phase 1/Phase 2 facade、Phase 3A service route、Phase 3B provider config、Phase 3C integration docs/sample/status、Phase 3D execution policy、Java adapter contract、skill alias narrowing 已完成 |
| Testing | passed | Phase 3D targeted suite: 18 passed；BizWorker full suite: 513 passed, 6 skipped；Java adapter targeted suite: 87 passed；skill alias narrowing targeted suite: 29 passed |
