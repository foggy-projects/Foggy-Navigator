---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-028
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
bug_source: user-report
approved_by: project-owner-explicit-database-fix-and-main-push-request
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Claude checkpoint overflow blocks terminal replay

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Claude checkpoint 持久化溢出、终态无法回放以及取消无法收敛问题的
  修复边界、数据迁移和验收义务。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-028-claude-checkpoint-overflow-terminal-replay.md`

## Goal

- version_goal: 长时间、多 Agent Claude 会话不能因 checkpoint 列容量耗尽而阻断 durable
  Worker 事件消费和任务终态收敛。
- target_outcome: checkpoint 数据超过 64 KiB 后仍可持久化并推进 `last_acked_seq`；
  已退出 Worker 进程的真实终态可被 Java 重放，任务和会话结束 `PROCESSING` 状态。
- critical_outcomes:
  - 生产 MySQL `claude_tasks.checkpoints` 安全扩容且保留现有数据；
  - 源码实体和幂等启动迁移保证新旧环境一致；
  - 超过 64 KiB 的 checkpoint 回归测试实际通过；
  - 现场任务 `20260727-ce38` 通过 Worker durable evidence 自动收敛，不手工伪造终态；
  - 当前工作树既有用户改动不被提交。
- success_is_sufficient_when: focused migration/entity tests、Claude addon affected tests、远程
  schema 和现场状态检查、scoped diff audit 以及 main push 均有实际通过证据。

## Scope

- in_scope:
  - `claude_tasks.checkpoints` 容量扩展；
  - MySQL 幂等 startup migration；
  - Claude task checkpoint 持久化和 durable replay 回归保护；
  - 已授权开发环境数据库的非破坏性字段扩容与现场收敛验证。
- affected_modules:
  - `navigator-common`
  - `addons/claude-worker-agent`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 开发环境 MySQL、现有 Claude Worker JSONL Event Store。

## Non-Goals

- out_of_scope:
  - 修改 Claude Worker Python 协议或重新发布 Worker；
  - 修复独立的后台 Agent `Prompt is too long` 行为；
  - 修改模型映射、270K 压缩预算或 9443 网关；
  - 前端、Codex、Gemini、LangGraph 变更；
  - 手工把任务或会话直接更新为完成。
- do_not_touch:
  - 当前工作树中已有未提交改动；
  - API Key、Worker token、JWT 或数据库凭据；
  - 现场任务的 prompt、result 正文和 Worker JSONL 内容。
- non_blocking_or_waivable_items: 用户负责按 main 更新并重启 Java；本轮不发布新 Worker。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 将 checkpoint 列扩展为 `MEDIUMTEXT` | 现有 `TEXT` 在 65,535 字节处稳定复现阻塞；扩容不改变 JSON 契约 | 保留 nullability、列名和现有内容 |
| 使用独立、幂等 startup migration | Hibernate 注解不能保证已部署 MySQL 自动扩容 | 已是 `MEDIUMTEXT`/`LONGTEXT` 时不得重复 ALTER |
| 现场先做非破坏性 DDL | Java 正在从 ACK 1927 自动重试，扩容后可消费真实终态 | 不直接修改 task/session 状态 |
| 回归覆盖大于 64 KiB 的真实存储 | 单纯 annotation 测试无法证明 MySQL 迁移和业务写入边界 | focused tests 必须实际运行 |
| `Prompt is too long` 分离处理 | 两次提示来自后台 Agent 邻近事件，父任务随后产生 `COMPLETED` | 不把其误判为本次卡死根因 |

## Acceptance Criteria

- [x] AC-1: `ClaudeTaskEntity.checkpoints` 显式声明至少 `MEDIUMTEXT` 容量。
- [x] AC-2: 幂等启动迁移仅在 MySQL 表/列存在且小于 `MEDIUMTEXT` 时执行扩容。
- [x] AC-3: 自动化回归证明超过 64 KiB 的 checkpoint JSON 能持久化，且迁移测试覆盖
  `TEXT -> MEDIUMTEXT` 和已扩容 no-op。
- [x] AC-4: 开发环境 `claude_tasks.checkpoints` 扩容后保留 619 个既有 checkpoint，
  Java 能越过 ACK 1927 并消费 Worker 明确终态。
- [x] AC-5: 现场任务和统一 `session_tasks` 投影进入终态，会话不再保持
  `interaction_state=PROCESSING`；终止操作留下真实 observed/converged 证据。
- [x] AC-6: Claude addon affected tests 和 migration focused tests 实际通过。
- [x] AC-7: 只提交本 work item、实体、迁移及回归测试，推送 `origin/main`。

## Contract / Data / Security Constraints

- API or event contract: 不改变 Worker route、SSE、ESN、checkpoint JSON 或终态字段。
- data and migration: 仅做保留数据的列类型扩容；不得删除、截断或重写 checkpoint。
- compatibility and rollback: 旧 Java 可读扩容后的列；代码回滚不应主动缩窄数据库列。
  数据库类型无需回滚为 `TEXT`。
- permissions and secrets: 连接信息仅从本机安全配置读取；命令和证据不得输出凭据、JWT、
  prompt 或结果正文。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3 | must-pass | major | entity/migration failure-first unit tests | startup migration framework | red/green output |
| AC-4/5 | must-pass | major | authorized development DB DDL + read-only state checks | Worker seq 2472 terminal evidence | before/after schema, ACK and status |
| AC-6 | must-pass | major | focused migration tests + Claude addon affected tests | existing Maven suites | exact commands and counts |
| AC-7 | must-pass | major | staged diff audit + push verification | isolated worktree | committed paths and remote SHA |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: failure-first focused tests、schema/state queries、diff check，单次 `<5m`。
- medium_validation: `mvn test -pl addons/claude-worker-agent -am`，预计 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: none。
- estimated_full_chain_wall_clock: not-estimated。
- full_chain_prerequisites: none。
- user_approval_status: approved-for-development-db-migration-and-main-push。
- decision_if_not_approved: N/A。
- expensive_validation_trigger: none。
- maximum_expensive_attempts: 0。
- reusable_evidence: Worker durable events 2301/2472/2473、开发数据库当前 65,501 字节现场数据。
- stop_when_evidence_is_sufficient: AC-1 至 AC-7 均有证据且现场不再重放失败；不运行全 reactor。
- validation_not_required: 前端构建、Python Worker tests、Worker 发布、9443 smoke、全仓 Maven。

## Waiver Policy

- waivable_items: 用户更新后的目标环境重启 smoke。
- authorized_role: project owner。
- non_waivable_guards: 数据不丢失、真实终态重放、凭据不泄漏、main 提交范围隔离。
- required_risk_record: 若现场 DDL 后仍被后续事件阻塞，保留原始 ACK 和 Worker durable
  evidence，停止手工状态修复并进入重新诊断。

## Bug Context

- bug_source: user-report。
- severity: major。
- environment: `dev-kvm-jdk17` Java 8112/MySQL 13309，
  `/home/sa/.claude-worker:3033` Worker `0.1.11`。
- current_behavior: Worker 已无活动进程并持有 `COMPLETED` result，Java 因 checkpoint
  `TEXT` 溢出反复重放事件 1928，数据库任务停在 `CANCEL_REQUESTED`，会话停在
  `PROCESSING`，取消返回 503。
- expected_behavior: checkpoint 扩容后 ACK 单调推进至终态，任务、投影、会话和终止操作
  根据 Worker 真实证据收敛。
- reproduction_steps:
  1. 运行产生大量 checkpoint 的长 Claude/Agent Teams 任务。
  2. `claude_tasks.checkpoints` 增长到接近 65,535 字节。
  3. 下一 checkpoint 触发 MySQL 1406，ACK 保留在旧序号。
  4. Worker 后续 result 无法被 Java 消费；取消时 Worker 已无活动进程。
- reproduction_status: confirmed。
- existing_evidence:
  - `checkpoints=65,501 bytes / 619 distinct IDs`；
  - `last_acked_seq=1927`，Worker latest `2473`；
  - Worker result `2472 terminal_status=COMPLETED`；
  - Java 日志持续 `Data too long for column 'checkpoints'`。
- existing_tests: checkpoint append/scan tests存在，但没有超过 64 KiB 的数据库容量回归。
- regression_protection: required。
- waiver_reason_and_risk: N/A。

## Risks and Open Questions

- known_risks:
  - 现场扩容后可能先消费较早的 result；必须以最终数据库终态和 Worker durable evidence
    验证，不手工覆盖。
  - 长任务 checkpoint 仍是单行增长；`MEDIUMTEXT` 消除当前 64 KiB 阻断，但未来若增长
    到 16 MiB 需另行设计外置或保留策略。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、根和 Claude addon `AGENTS.md`。
- 对稳定复现问题先增加失败测试，再实现修复。
- 在 scope 内自主决定 migration/test 局部结构；不得改变 Worker/API 契约。
- 现场数据库只执行已批准的非破坏性扩容，随后只读验证，禁止手工伪造任务终态。
- 仅 stage 本任务文件，提交前审计 staged diff。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 将 `ClaudeTaskEntity.checkpoints` 的 MySQL 映射从 `TEXT` 扩为 `MEDIUMTEXT`。
  - 新增 `startup-007-claude-checkpoint-storage` 幂等启动迁移；表/列不存在时跳过，
    `MEDIUMTEXT`/`LONGTEXT` 保持不变，较小类型才执行非破坏性 `ALTER`。
  - 新增 900 个 checkpoint、实际 JSON 超过 65,535 字节的业务写入回归和迁移分支测试。
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceCheckpointTest.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/ClaudeCheckpointStorageMigration.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/ClaudeCheckpointStorageMigrationTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-028-claude-checkpoint-overflow-terminal-replay.md`
- tests_and_results:
  - failure-first:
    `mvn -pl addons/claude-worker-agent -am -Dtest=ClaudeTaskServiceCheckpointTest
    -Dsurefire.failIfNoSpecifiedTests=false test`，按预期 1 个失败：
    `expected MEDIUMTEXT but was TEXT`。
  - focused green:
    `mvn -pl addons/claude-worker-agent -am
    -Dtest=ClaudeCheckpointStorageMigrationTest,ClaudeTaskServiceCheckpointTest
    -Dsurefire.failIfNoSpecifiedTests=false test`，13 tests，0 failure/error。
  - dependency-aligned addon:
    `mvn -pl addons/claude-worker-agent -am -DskipTests install` 成功；
    随后 `mvn -pl addons/claude-worker-agent test` 在初始基线为 423 tests，rebase
    最新 `origin/main` 后为 427 tests，均为 0 failure/error。
  - affected reactor:
    `mvn test -pl addons/claude-worker-agent -am` 第二次运行中
    `navigator-common`、`navigator-spi`、`agent-framework`、`user-auth-module`、
    `session-module` 均通过；`business-agent-module` 的既有
    `BusinessTaskScopedTokenLifecycleJpaTest` 因未提供
    `RuntimeRequestAuditService` Bean 产生 9 个上下文错误，Claude addon 被 Maven 跳过。
- manual_or_experience_evidence:
  - 通过安全读取容器配置执行
    `ALTER TABLE claude_tasks MODIFY COLUMN checkpoints MEDIUMTEXT NULL`；复核
    `DATA_TYPE=mediumtext`、`CHARACTER_MAXIMUM_LENGTH=16777215`。
  - DDL 后既有 Java 重放立即从 ACK 1927 继续；日志在终态前记录 checkpoint 总数从
    619 推进到 726，证明 DDL 未丢失既有数据；随后终态 auto-scan 按现有业务规则归一为
    4 个会话 checkpoint。
  - 现场 `claude_tasks` 和 `session_tasks` 均为 `COMPLETED`、`last_acked_seq=2301`；
    session 为 `ACTIVE/AWAITING_REPLY`；termination operation 为
    `COMPLETED/OBSERVED`，`attention_code` 与 `failure_code` 均清空。
  - 后端日志确认消费 Worker `result` 事件后调用 `Task completed`；未执行任何任务、
    会话或 termination 状态修正 SQL。
  - 实现提交 `d4e6146c` 已 fast-forward 推送至 `origin/main`；提交只包含本 work item
    列出的实体、迁移、测试和交付文档。
- deviations:
  - 计划的单条 `mvn test -pl addons/claude-worker-agent -am` 无法全绿，原因是当前
    `main` 的独立 business-agent 测试夹具缺少依赖 Bean；改用当前源码依赖快照安装后
    直接完整运行 Claude addon，423 个用例全部通过。
  - 第一次 affected reactor 还遇到 `JwtUtilTest` 临界时间抖动；单独复跑通过，第二次
    reactor 的完整 user-auth 测试 173 个全部通过。
- residual_risks:
  - 单行 checkpoint 若未来超过 `MEDIUMTEXT` 的约 16 MiB 上限，仍需另行设计分片、
    外置或保留策略。
  - business-agent 既有测试夹具错误仍会阻断该 reactor 的单命令全绿，与本次 Claude
    checkpoint 修复无代码依赖。
- reused_evidence:
  - Worker durable result seq 2301：`COMPLETED`、117 turns、input 197107、
    output 25456。
  - 修复前数据库：619 个 distinct checkpoint、65,501 bytes、ACK 1927。
- omitted_validation_and_reason:
  - 未运行 Worker Python tests/发布：Worker 协议和 Python 代码未变更。
  - 未运行前端、9443、Codex/Gemini/LangGraph 验证：均在 non-goal。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: user-reported task `20260727-ce38`
- architecture / glossary: `docs/a2a-agent-architecture.md`
- related work items:
  - `BUG-026-claude-terminal-replay-reconnect-convergence.md`
  - `BUG-027-claude-tenantless-terminal-session-convergence.md`
