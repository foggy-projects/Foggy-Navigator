---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001-stage16-claude-worker-session-bean
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

Stage 16 在 Stage 15 worker-session typed DTO / envelope 之后，继续收敛 Claude provider 的物理 bean 职责边界。Stage 13 已让 `ClaudeTaskService` 退出聚合 `TaskQueryProvider`，但它仍同时承接 task lookup、task command、task listing 与 worker-session 查询端口。

## Check Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage16-claude-worker-session-bean.md`
- Previous stage: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage15-worker-session-typed-envelope.md`
- Changed code:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeWorkerSessionQueryServiceTest.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`

## Changed Surface

- 新增 `ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider`，独立声明 Claude worker-session list/count/messages/sync capabilities。
- `ClaudeTaskService` 移除 `WorkerSessionQueryProvider` 实现和 worker-session capabilities，只保留 lookup / command / listing 三类端口。
- worker-session typed methods 和 legacy Map wrapper 迁移到新 service，REST payload 兼容形状不变。
- sync 路径仍复用 `ClaudeTaskService.syncLocalSessions(...)`，避免本阶段重写本地任务投影逻辑。
- 测试补充新 provider capability、list/count/messages/sync、跨用户 worker 拒绝，以及 `ClaudeTaskService` 类型边界回归。

## Quality Checklist

- scope conformance: pass。本阶段只拆 Claude worker-session provider 物理 bean，没有改变 REST、OpenAPI、SDK 或 Python Worker payload。
- code hygiene: pass。未发现 debug 输出、临时分支或临时 TODO。
- duplication and consolidation: pass。worker-session 查询逻辑集中到独立 service；sync 投影复用原有 `syncLocalSessions(...)`，避免复制落库规则。
- complexity and abstraction: pass-with-risks。职责边界已清晰，但 sync 投影仍在 task service 内，后续如继续收敛可提取专门 projection service。
- error handling and edge cases: pass。worker ownership 校验保留，list/count/messages 失败兜底为空或零，sync 失败继续包装为运行时异常。
- readability and maintainability: pass。`ClaudeTaskService` capabilities 更贴近 task lifecycle/listing 责任，新 service 名称直接表达 worker-session 查询角色。
- critical logic documentation: pass。workitem 记录了不重写 `syncLocalSessions` 与保留 legacy Map 方法的兼容原因。
- contract and compatibility: pass。providerType 仍为 `claude-worker`，legacy worker-session methods 仍存在于新 provider，REST 输出兼容。
- documentation and writeback: pass。workitem、README、governance、quality、coverage、acceptance 已回写。
- test alignment: pass。测试覆盖类型边界、新 provider capabilities 和 worker-session 主行为。
- release readiness: pass-with-risks。无阻断性实现问题，非阻断风险已列入后续项。

## Findings

- No blocking implementation issues found.

## Risks / Follow-ups

- `ClaudeWorkerSessionQueryService` sync path 仍依赖 `ClaudeTaskService.syncLocalSessions(...)`；这保留了一个跨 service 调用，但避免了本阶段重复或改写本地任务投影规则。
- legacy worker-session Map 方法仍保留。后续需等待外部插件/调用方迁移完成后，再规划 deprecation / removal。
- REST worker-session payload 仍为 Map。本阶段仅治理 Java provider bean 边界。
- `TaskCommandProvider#cancelTask` deprecated fallback 与生产 schema migration 工具化仍属于后续治理项。
- 未运行仓库级根目录全量 `mvn test`；本阶段已运行受影响 reactor。

## Recommended Next Skills

- `foggy-test-coverage-audit`
- `foggy-acceptance-signoff`

## Decision

decision=`ready-with-risks`。Stage 16 实现范围收口，Claude worker-session 查询已由独立 `WorkerSessionQueryProvider` bean 承接，`ClaudeTaskService` 不再声明 worker-session 端口或 capabilities。剩余风险均为后续架构收敛项，不阻断进入覆盖审计和验收。
