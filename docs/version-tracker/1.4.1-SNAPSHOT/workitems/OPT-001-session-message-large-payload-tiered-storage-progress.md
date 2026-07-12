# OPT-001 会话大消息分层存储进度

## 文档作用

- doc_type: progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 按阶段记录实现、测试、体验、风险和后置评审状态。

## 基本信息

- version: `1.4.1-SNAPSHOT`
- status: `stage-0-2-completed-stage-3-5-deferred`
- requirement: [OPT-001 requirement](./OPT-001-session-message-large-payload-tiered-storage.md)
- implementation_plan: [OPT-001 plan](./OPT-001-session-message-large-payload-tiered-storage-plan.md)
- implementation_started_at: `2026-07-12`
- last_updated_at: `2026-07-12`
- formal_quality_gate: [Stage 0-2 implementation quality](../quality/OPT-001-stage0-2-implementation-quality.md)

## 前置条件

| 条件 | 状态 | 说明 |
|---|---|---|
| BUG-021 48 KiB 保护已存在 | completed | `SessionMessagePayloadRoutingService.DEFAULT_INLINE_PREVIEW_BYTES`、配置默认值和旧保护均保留为 49,152 bytes。 |
| 消息类型与加载语义已确认 | completed-stage0 | 最终回复完整内联；仅超限 `TOOL_CALL_RESULT` / `TOOL_CALL_ERROR` 外置，列表、恢复和 SSE snapshot 不读取 Payload Store。 |
| 存储方向已确认 | completed-stage1 | MySQL Descriptor + session-module 后端中立 Store；首个实现为持久化文件系统。 |
| 默认阈值/保留期完成容量验证 | pending-stage5 | 本轮不启用未经验证的 8 KiB / 14 天；默认仍为 48 KiB / `PT0S`。 |
| 生产多实例存储后端决策 | pending-stage5 | 需要决定共享卷或私有对象存储后才可开启生产 Store。 |

## Stage 0：基线、链路盘点与契约

状态：`completed`

基线在 `main` 上确认，起点提交为 `33aea60b docs: plan tiered session payload storage`；实施期间未 reset、未清理无关改动。

### 实际消息路径与活动字段

| 范围 | 实际路径 / 字段结论 |
|---|---|
| 通用会话消息 | `AgentMessage -> SessionEventListener -> SessionMessageDurablePersistenceCoordinator -> SessionMessagePayloadRoutingService -> AgentMessageSessionMessageMapper -> JpaSessionManager -> session_messages`。活动正文列为 `session_messages.content`、`session_messages.metadata`。 |
| 任务投影 | `session_tasks.resultText` 为最终任务结果投影；已扩为 `MEDIUMTEXT`。 |
| Provider 任务投影 | `CodexTaskEntity.resultText`、`ClaudeTaskEntity.resultText`、`GeminiTaskEntity.resultText`、`LanggraphTaskEntity.resultText` 均为活动最终结果列；`LanggraphTaskEntity.structuredOutput` 也是结构化最终结果承载列，已纳入扩容。`BaseWorkerTaskEntity.resultText` 是 `@MappedSuperclass`，没有独立物理表。 |
| Codex | `CodexStreamRelay` 使用同步 durable message 写入；ESN 使用 `codex-event:<task>:<seq>`，无 ESN 工具结果使用 `tool_use_id` 或内容哈希形成稳定身份。 |
| Claude | `WorkerStreamRelay` 先同步 durable message、再写 Worker 进度；ESN 使用 `claude-event:<task>:<seq>`，无 ESN 工具结果同样有稳定身份。 |
| Gemini | `GeminiStreamRelay` 先同步 durable message、再写 Worker 进度；ESN 使用 `gemini-event:<task>:<seq>:<part>`，无 ESN 工具结果同样有稳定身份。 |
| LangGraph | Python `routes/query.py` 在单次 SSE generator 内发送单调 `event_id`；Java `LanggraphStreamRelay` 使用其构造稳定消息 ID。当前 LangGraph 调用的是非 durable 的 `SessionEventListener.handleMessage`，且 Provider 没有可持久化 ACK cursor；它不宣称具备 Codex/Claude/Gemini 的 ACK 语义。 |

### 冻结契约

- `SessionMessagePayload`：后端中立的完整字节载荷，包含稳定 `messageId`、`sessionId`、`contentType` 和原始 bytes。
- Descriptor：`session_message_payloads` 覆盖 `messageId`、`sessionId`、`backend`、内部 `storageKey`、`contentType`、`contentEncoding`、`originalBytes`、`storedBytes`、`sha256`、`status`、`expiresAt`、JPA `version`、`createdAt`、`updatedAt`；`storageKey` 与旧式 `storage_key` 会由 `SessionMessagePublicPayloadSanitizer` 在持久化/SSE 边界递归移除，且不进入会话 DTO、列表、恢复、SSE 或公开 API。
- 状态机：`READY`（已完整写入且校验）、`UNAVAILABLE`（只保留 Preview，后续允许收敛）、`EXPIRED`（Stage 4 生命周期使用）、`PENDING`（仅在完整 bytes 已可靠暂存或有可靠重试来源且存在有限收敛期限时才允许）。Stage 1/2 不产生 `PENDING`。
- 后续详情接口路径已冻结但未实现：`GET /api/v1/sessions/{sessionId}/messages/{messageId}/payload`；Controller、鉴权、前端交互全部留给 Stage 3。
- 失败/边界测试先以 Stage 1/2 契约测试落地并在实现后执行；此前没有独立实现可自然形成的红灯，未伪造失败测试。

## Development Progress

| Stage | 范围 | 状态 | 结果/证据 |
|---|---|---|---|
| 0 | 基线、字段盘点、契约和失败测试 | completed | 链路和活动字段如上；新增多字节/JSON escaping、48 KiB 工具输出、64 KiB 最终回复、稳定消息 ID、重放和故障 ACK 契约测试。 |
| 1 | Schema、迁移、Payload Store 基础 | completed | 新增 Descriptor entity/repository/startup migration、MySQL 预部署 SQL、文件系统 Store、配置和生产 guard。 |
| 2 | 消息分流、幂等和 ACK 故障安全 | completed | 有界 Preview + Descriptor、文件先于 MySQL 持久化/flush、Provider durable ACK 顺序与最终回复完整写入已实现并测试。 |
| 3 | 列表零读取、详情 API 和前端交互 | not-started | 明确延期；没有 Controller、前端按钮或详情读取实现。 |
| 4 | 保留期、清理、配额和指标 | not-started | 明确延期；不删除已写入 Payload，不启用自动清理。 |
| 5 | 生产后端、灰度、压测和切换 | not-started | 明确延期；未发布 Worker、未部署、未重启、未操作生产数据库。 |

## Stage 1 实现与迁移状态

- 新增 `navigator-common/.../SessionMessagePayloadEntity`、`SessionMessagePayloadStatus`、`SessionMessagePayloadRepository` 和 `SessionMessagePayloadStorageMigration`；`message_id` 唯一，按 session、status/expires、expires 建索引。
- 新增可重复执行的 [SQL migration](../../../migration/2026-07-12-session-message-payload-storage.sql)。它创建 Descriptor 表、索引，并把实际活动列扩为 `MEDIUMTEXT`（`langgraph_tasks.result_text` 已是更大的 `LONGTEXT` 时保持不变）。
- JPA entity 与 SQL 同时覆盖 `session_messages.content/metadata`、`session_tasks.result_text`、Codex/Claude/Gemini/LangGraph `result_text`、LangGraph `structured_output`；禁止静默截断。
- 新增 `session-module/.../service/payload/SessionMessagePayloadStore` 和 `FileSystemSessionMessagePayloadStore`：稳定幂等键、目录 readiness、路径穿越拒绝、gzip 透明压缩/解压、原文 SHA-256、临时文件 + 原子替换。发布前在同 JVM 使用条带锁，并在 `${root}/.session-message-payload-locks` 使用 `FileChannel` 独占锁，串行化“目标存在/原文 SHA 校验/原子移动”；同 ID 同 bytes 可复用，异 bytes 会报完整性冲突。文件 I/O 不在 `navigator-common`，没有 MongoDB/GridFS，也没有把 Service/Controller 放入 launcher。
- 配置前缀：`foggy.session.message-payload`。开发默认 `enabled=true`、`inline-preview-bytes=49152`、`max-payload-bytes=67108864`、`retention=PT0S`、目录 `./data/session-message-payloads`。生产 profile 默认 `enabled=false`；若显式开启，`ProductionConfigurationGuard` 要求配置目录。
- 迁移状态：隔离临时 `mysql:8.0` 与 `mysql:8.4` 容器均将 SQL 连续执行两次并检查成功；Descriptor 索引齐全，目标列为 `MEDIUMTEXT` 或已有 `LONGTEXT`。未运行真实生产数据库。
- 重要边界：生产的 `ddl-auto=validate` 在 `ApplicationReadyEvent` 前运行，运行时 startup migration 不能替代预启动 SQL。因此生产启动前必须先执行该 SQL migration。

## Stage 2 分流、重放与 ACK 语义

- 小型工具结果继续内联；超出完整序列化 metadata 的 48 KiB 包络时，完整字节写入 Store，MySQL 仅持久化有界 Preview 和公开 Descriptor。Preview 用 UTF-8 code point 二分裁剪，并以序列化 JSON bytes 复核上限，覆盖多字节和 JSON escaping。
- Store 写入成功且 Descriptor 保存成功时状态为 `READY`；Store/readiness/校验失败时保存 Preview 与 `UNAVAILABLE` Descriptor，没有可靠重试来源时允许 ACK、后续消息和任务终态收敛。
- `SessionMessageDurablePersistenceCoordinator` 的 `@Transactional` 已开始时，文件先于 Descriptor/session message 的 MySQL 持久化/flush 写入；若 Descriptor 或 session message 的 MySQL 持久化失败，异常向 durable relay 传播，不 ACK。留下的文件仅是由稳定 key 可复用的孤儿，不在本轮删除。
- 在触碰 Store 前，Router 通过 `SessionMessagePayloadRepository.findByMessageIdForUpdate` 以 `PESSIMISTIC_WRITE` 读取稳定 `messageId`；已有 Descriptor 会在事务内锁定、校验后复用，同 ID 不同 bytes/session/content type 则抛出 replay conflict、不 ACK。跨进程首写由共享目录文件 Store 的 JVM 条带锁 + `FileChannel` 锁保护其“目标存在/SHA 校验/原子移动”临界区；若 MySQL 回滚留下孤儿且后来同 ID bytes 不同，也会转为 replay conflict，而不是 `UNAVAILABLE` 后 ACK。
- Codex、Claude、Gemini 对 ESN 使用稳定 ID 并将 ACK/终态持久化置于消息 durable 后；无 ESN 工具结果以 `tool_use_id` 或 payload hash 去重。相同重放不会新建 Descriptor/文件。旧 Worker 的无 ESN、无 `tool_use_id` fallback 只能识别相同内容，无法区分内容完全相同的独立工具调用；这是记录的兼容边界。
- Codex/Claude/Gemini 终态 ACK 与任务终态在各自 TaskService 的事务中原子持久化；失败时内存 cursor 保持未确认。LangGraph 没有 Provider ACK cursor，见 Stage 0 边界。
- 最终 Assistant 回复不走工具 Preview/外置规则，完整写入 `session_messages.content/metadata` 并写入必要的 `resultText`/`structuredOutput` 投影；现有 BUG-021 48 KiB 工具保护没有降低或移除。
- `SessionEventListener` 在持久化和 SSE 发射前都递归脱敏 `storageKey` / `storage_key`；Router 与 mapper 同时保留防御性脱敏。旧内联消息保持可读；本轮没有批量迁移历史消息，列表、历史恢复、SSE snapshot 不读取 Payload Store。

## Testing Progress

| Test lane | 状态 | Evidence |
|---|---|---|
| Java unit / focused contracts | passed | `SessionMessagePayloadStorageMigrationTest`、`SessionMessagePayloadEntityTest`、`SessionMessagePayloadRepositoryTest`、`FileSystemSessionMessagePayloadStoreTest`、`SessionMessagePayloadRoutingServiceTest`、`AgentMessageSessionMessageMapperTest`、`SessionEventListenerTest`、`JpaSessionManagerTest` 通过；覆盖 48 KiB 工具输出、64 KiB 最终回复、UTF-8/escaping、Store/MySQL 故障、SSE 脱敏、同 ID replay/orphan conflict、文件锁和 Descriptor 悲观锁。最新锁相关 focused run 的 29 个测试均通过。 |
| Provider relay regressions | passed | `CodexStreamRelayTest`（41）、`WorkerStreamRelayTest`（11）+ `WorkerStreamRelayCheckpointTest`（6）、`GeminiStreamRelayTest`（12）、`LanggraphStreamRelayTest`（15）均通过；Claude/Gemini Surefire XML 最终均为 `errors=0`。 |
| LangGraph Worker Python regression | passed | `tools/langgraph-biz-worker/.venv/bin/python -m pytest -q tests/test_query.py -k query_returns_sse_events`：`1 passed, 21 deselected`；随后 `compileall -q src` 通过。 |
| Required Maven suite | passed | `mvn -B -pl navigator-common,session-module,addons/codex-worker-agent -am test` 通过。 |
| Required launcher assembly | passed | `mvn -B -pl launcher -am -DskipTests compile` 通过。 |
| Diff hygiene | passed | `git diff --check` 通过；仅有仓库既有 CRLF→LF 提示，无 whitespace error。 |
| MySQL 8.0 migration | passed | 隔离 `mysql:8.0` 容器，对 SQL migration 连续执行两次，检查 Descriptor 索引和正文列类型。 |
| MySQL 8.4 migration | passed | 隔离 `mysql:8.4` 容器，对 SQL migration 连续执行两次，检查 Descriptor 索引和正文列类型。 |
| Full Spring Boot + MySQL `ddl-auto=validate` startup | 未运行 | startup migration 发生在 `ApplicationReadyEvent`，不能验证 validate 前的预启动迁移顺序；本轮没有启动应用或操作生产环境。 |
| Session integration（详情鉴权/清理） | 未运行 | Stage 3/4 API、鉴权、清理尚未实施，不能标记通过。 |
| Frontend unit/type-check/build | 本阶段 N/A，Stage 3 执行 | 本轮没有前端改动。 |
| Capacity/compression benchmark | 未运行 | Stage 5 的容量和生产拓扑决策前不能给出容量结论。 |

### 实际命令摘要

```bash
mvn -B -pl navigator-common,session-module -am \
  -Dtest=SessionMessagePayloadStorageMigrationTest,SessionMessagePayloadEntityTest,FileSystemSessionMessagePayloadStoreTest,SessionMessagePayloadRoutingServiceTest,AgentMessageSessionMessageMapperTest,SessionEventListenerTest,JpaSessionManagerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl navigator-common,session-module -am \
  -Dtest=SessionMessagePayloadRepositoryTest,FileSystemSessionMessagePayloadStoreTest,SessionMessagePayloadRoutingServiceTest,SessionEventListenerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl addons/codex-worker-agent -am -Dtest=CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl addons/claude-worker-agent -am -Dtest=WorkerStreamRelayTest,WorkerStreamRelayCheckpointTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl addons/gemini-worker-agent -am -Dtest=GeminiStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl addons/langgraph-biz-worker -am -Dtest=LanggraphStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl navigator-common,session-module,addons/codex-worker-agent -am test
mvn -B -pl launcher -am -DskipTests compile
```

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | 历史消息和工具详情入口正常渲染 | 本阶段 N/A，Stage 3 执行 | 本轮没有前端改动。 |
| 默认加载 | 打开/刷新会话不请求外置载荷 | 本阶段 N/A，Stage 3 执行 | Stage 0-2 静态审计确认没有业务路径读取 Store；浏览器体验留 Stage 3。 |
| 核心交互 | 点击详情后完整展示并缓存 | 本阶段 N/A，Stage 3 执行 | 详情 API/交互未实施。 |
| 异常状态 | EXPIRED/UNAVAILABLE/读取失败可理解且可关闭 | 本阶段 N/A，Stage 3 执行 | `UNAVAILABLE` 数据契约已存在，UI 留 Stage 3。 |
| 权限可见性 | 无权限用户不可读取或推断载荷路径 | 本阶段 N/A，Stage 3 执行 | `storageKey` 已内部化；详情授权留 Stage 3。 |
| 数据一致性 | 最终回复刷新前后完整一致，Preview/详情摘要一致 | 本阶段 N/A，Stage 3 执行 | 后端持久化契约通过，浏览器验证留 Stage 3。 |

### Playwright 状态

| 用例 | 覆盖维度 | 状态 |
|---|---|---|
| 历史列表零 Payload 请求 | 默认加载 | 本阶段 N/A，Stage 3 执行 |
| 点击查看完整工具输出 | 核心交互、数据一致性 | 本阶段 N/A，Stage 3 执行 |
| 过期和不可用载荷 | 异常状态 | 本阶段 N/A，Stage 3 执行 |
| 跨用户读取拒绝 | 权限可见性 | 本阶段 N/A，Stage 3 执行 |
| 大型最终回复刷新恢复 | 数据一致性 | 本阶段 N/A，Stage 3 执行 |

## Acceptance Criteria Tracking

| Requirement | 状态 | Evidence |
|---|---|---|
| 大工具输出不再以 48 KiB metadata 入库 | completed-stage2 | 分流服务将超限完整 bytes 外置；DB 保存 Preview + Descriptor。 |
| 列表和恢复零 Payload Store 读取 | completed-stage0-2-static | 搜索确认 session/list/history/SSE 业务路径没有 Store `read` 调用；Stage 3 再补浏览器/API 证据。 |
| 用户主动操作才读取完整载荷 | pending-stage3 | 详情路由已冻结，未实现读取端。 |
| 最终 Assistant 回复完整持久化和展示 | completed-stage2-storage / pending-stage3-ux | 后端完整持久化与 task projection 已测试；前端展示验证留 Stage 3。 |
| Store 故障不阻塞 ACK 和终态 | completed-stage2 | Store 异常转 `UNAVAILABLE` + Preview；MySQL 异常不 ACK。 |
| 重放幂等且孤儿可清理 | completed-stage2-idempotency / pending-stage4-cleanup | 稳定 ID、Descriptor 悲观锁、共享目录文件锁、同 ID conflict 不 ACK 和稳定文件 key 已测试；清理调度明确延期。 |
| 过期后消息/Preview 仍可用 | pending-stage4 | 未开启 retention/cleanup。 |
| 鉴权、路径和敏感信息边界通过 | partial-stage2 / pending-stage3 | `storageKey` 从 entity JSON 及 `SessionEventListener`/Router/mapper 的 camel/snake case payload 中移除；详情授权未实现。 |

## Implementation Self-Check

- [x] requirement scope 已收口到 Stage 0/1/2；没有扩展到附件、全量日志存储、详情 API、前端、清理或生产切换。
- [x] 最终回复和工具输出采用不同策略：仅超限工具结果外置，最终 Assistant 回复完整内联并扩容所有活动任务投影列。
- [x] 列表、历史恢复和 SSE snapshot 不读取 Store；`storageKey` 由 `@JsonIgnore` 及 `SessionEventListener`、Router、mapper 的递归 camelCase/snake_case 脱敏多重隔离，SSE 序列化回归已覆盖。
- [x] Payload Store 失败与 MySQL 失败具有不同 ACK 语义：前者 `UNAVAILABLE` 后可收敛，后者传播异常且 durable relay 不前进 cursor。
- [x] `PENDING` 状态契约已冻结为“可靠暂存/可靠重试来源 + 有限期限”；Stage 1/2 不产生它。
- [x] 重放、文件/Descriptor 和事务回滚使用稳定 messageId/key；事务内 `PESSIMISTIC_WRITE` 锁定已有 Descriptor 并校验复用，Store 的 JVM/跨进程临界区负责首写，任何 MySQL 保存异常仍不 ACK。MySQL 失败保留可复用孤儿；同 ID 不同 bytes 的孤儿转为 replay conflict，未在本轮删除。旧无 ESN Worker 的相同内容 fallback 边界已记录。
- [x] 没有提交真实 Store 凭据、生产绝对目录或敏感正文；没有 File I/O 落入 `navigator-common`。
- [x] 定向回归、用户指定 Maven 测试、launcher compile、MySQL 8.0/8.4 迁移验证、差异检查和质量闸门均已回写；未运行项有原因。

- self_check_summary: `检查了跨模块调用边界、DB/Store 故障矩阵、稳定身份、共享目录 first-write-only/孤儿 conflict、公共 Descriptor 与 SSE 脱敏、最终回复容量、迁移幂等性和延期范围；未发现阻断 Stage 0-2 的实现遗漏。`
- self_check_decision: `passed-stage-0-2-with-deferred-stage-3-5`
- formal_quality_gate_required: `yes-cross-module-shared-capability`
- formal_quality_gate_status: `reviewed-ready-with-risks`

## 计划外变更

- 为保持 LangGraph Python 原始 JSON 文本与 Java 解析后的 `data` 一致，增加单次 generator 的 `event_id`；这属于 Stage 2 重放身份收口，不改变前端或详情 API。
- 增加 camelCase 与 snake_case `storageKey` 的递归脱敏，避免上游 payload 的字段风格泄露内部路径。
- Store 增加 JVM 条带锁和 `FileChannel` 跨进程锁，Router 增加 Descriptor 悲观锁与同 ID conflict 非 ACK 语义；这是共享 Payload 目录的 Stage 1/2 正确性收口，不包含生产多实例启用。
- Codex/Claude/Gemini 的 legacy 无 ESN 工具事件补充稳定 identity，且把终态 ACK 与任务终态改为原子持久化，避免先 ACK 后落库。

## 阻塞项与待确认项

| Item | 状态 | Owner/Decision |
|---|---|---|
| 8 KiB Preview 是否为最终默认值 | pending-capacity-evidence | implementation owner + reviewer；本轮禁止降低 48 KiB。 |
| 14 天是否为最终默认保留期 | pending-capacity-evidence | product/release owner；本轮保持 `PT0S`。 |
| 生产使用共享卷还是对象存储 | pending-deployment-topology | release owner；Stage 5 前不得开启多实例 filesystem Store。 |
| 共享文件系统的跨主机 `FileChannel` 锁语义 | pending-topology-validation | Stage 5 必须在选定共享卷/挂载配置上验证合作实例的锁与原子移动语义；本轮仅完成实现与本机回归。 |
| 预启动 SQL 的 DDL 权限与真实 `ddl-auto=validate` 启动 | pending-environment-validation | deployment owner；本轮未启动应用，startup migration 不能替代 pre-start SQL。 |
| LangGraph replay identity | known-boundary | `event_id` 在新的 Python generator 会从 1 重启，旧 Worker 也可能没有该字段；无 Provider ACK cursor，不能宣称跨执行 exactly-once。 |
| 旧 Claude/Gemini/Codex 无 ESN 且无 `tool_use_id` | known-boundary | 内容哈希只能合并相同内容的重放，不能区分内容相同但本应独立的调用。 |
| 历史 48 KiB 数据是否迁移 | deferred | 不迁移全部历史数据；按真实容量与产品策略在后续决定。 |

## 后续衔接

Stage 3 开工前必须满足：

1. 实现并授权冻结的 payload 详情路径，基于 session ownership 校验 Descriptor，不暴露 `storageKey`、真实文件路径或 backend 凭据。
2. 保持列表/历史/SSE snapshot 零 Store 读取；仅用户主动详情请求读取并验证 SHA-256/状态。
3. 为 `READY`、`UNAVAILABLE`、`EXPIRED` 补齐前端可理解状态与 Playwright 证据；体验验证继续标记为“本阶段 N/A，Stage 3 执行”直到完成。
4. 不降低 48 KiB Preview，也不启用 8 KiB/14 天，直到 Stage 5 的容量、压缩与生产拓扑证据完成。
5. 在任何多实例 filesystem Store 启用前，Stage 5 必须验证选定共享目录支持 `FileChannel` 锁和原子移动；否则改用经验证的后端。
6. Stage 4 独立设计清理/配额/指标，确保不删除消息、Preview、Descriptor 或已写入 Payload 的现存数据。
