# OPT-001 会话大消息分层存储实施计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 将已确认的分层存储边界拆成可独立验证、逐阶段回写的跨模块实施步骤。

## 基本信息

- version: `1.4.1-SNAPSHOT`
- status: planned-reviewed
- requirement: [OPT-001 requirement](./OPT-001-session-message-large-payload-tiered-storage.md)
- progress: [OPT-001 progress](./OPT-001-session-message-large-payload-tiered-storage-progress.md)
- execution_mode: cross-module-staged

## 总体顺序

先冻结查询契约和数据语义，再建设存储能力；先证明列表零读取和 ACK 故障安全，再接前端详情；最后启用过期清理和生产存储后端。不得先降低 48 KiB 上限、再补详情读取，否则会造成不可恢复的展示退化。

## Ownership

| Owner | 职责 | 开工条件 | 支撑成功标准 |
|---|---|---|---|
| root workspace / controller | 冻结跨模块契约、阶段门、迁移顺序和版本进度 | 可立即执行 Stage 0 | 全部，重点 5、7 |
| `navigator-common` | 消息载荷描述实体/仓储基础、活动正文列容量 | 数据契约冻结后开工 | 1、4、6 |
| `session-module` | Payload Store 能力、消息保存、列表/详情接口、鉴权、清理和会话生命周期 | Schema 与状态机冻结后开工 | 1、2、3、5、6 |
| `addons/codex-worker-agent` | 工具输出分流、Preview 构造、幂等键、ACK 故障语义 | Session 持久化接口可编译后开工 | 1、5 |
| `packages/foggy-chat` / `foggy-chat-core` | 工具结果的 Payload Descriptor 类型和详情交互 | API 契约可用后开工 | 2、3、4 |
| `packages/navigator-frontend` | 详情读取 API、缓存、错误/过期状态和工作台集成 | Chat 组件契约稳定后开工 | 2、3、4、7 |
| `launcher` / deployment | 存储目录、阈值、保留期和调度配置 | Store 配置定义后开工 | 5、6 |
| `tools/codex-agent-worker` | 只核对 JSONL 恢复与保留边界 | read-only，可并行 | 5 的恢复边界证据 |
| docs/tests | 迁移、容量、故障注入、体验和验收证据 | 随阶段同步 | 7 及全部验收映射 |

## Code Inventory

| Module | Path | Role | Expected change | Notes |
|---|---|---|---|---|
| common | `navigator-common/src/main/java/com/foggy/navigator/common/entity` | Session Message Payload 描述和正文容量 | create/update | Entity 不使用关联注解，使用 `messageId`/`sessionId` 字段 |
| common | `navigator-common/src/main/java/com/foggy/navigator/common/repository` | Descriptor 查询与清理候选 | create/update | 避免在 JSON metadata 内查询过期时间 |
| session | `session-module/src/main/java/com/foggy/navigator/session/service` | Payload Store、保存协调、生命周期清理 | create/update | 具体类名由执行者按现有结构决定 |
| session | `session-module/src/main/java/com/foggy/navigator/session/controller` | 鉴权后的 Payload 详情/下载接口 | create/update | 冻结路径 `/api/v1/sessions/{sessionId}/messages/{messageId}/payload`；Controller 留在业务模块 |
| session | `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java` | AgentMessage 到 Session Message 的数据契约 | update | 列表 metadata 不携带完整外置正文 |
| Codex addon | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java` | 大型工具输出分流与 ACK | update | 保留 BUG-021 保护直到切换验证完成 |
| Codex addon tests | `addons/codex-worker-agent/src/test/java` | 大小边界、重放、故障注入 | update | 覆盖多字节和 JSON escaping |
| session tests | `session-module/src/test`、`session-module/integration-tests` | 持久化、查询、权限、清理 | update | 列表必须断言 Store 读取为 0 |
| chat packages | `packages/foggy-chat-core/src`、`packages/foggy-chat/src` | Descriptor 状态与详情按钮 | update | 复用 ToolCallBlock，不默认拉取正文 |
| PC | `packages/navigator-frontend/src/api/session.ts`、`composables/useTaskPane.ts` | Payload API 与页面缓存 | update | 历史加载不得触发详情请求 |
| migration | `docs/migration` | 新表、索引和活动正文列扩容 | create | 生产 `ddl-auto=validate` 前置；验证 MySQL 8.0/8.4 |
| configuration | `launcher/src/main/resources` | 路径、阈值、保留期、大小上限 | update | 不提交环境凭据或真实对象存储密钥 |
| SDK Worker | `tools/codex-agent-worker/src/persistence/event-store.ts`、`sdk-wrapper.ts` | 当前 JSONL 恢复边界 | read-only-analysis | 不作为 Payload Store 实现 |

## 模块归属验证

- `navigator-common/pom.xml` 将该模块定义为 Entity/Enum/DTO 公共层，适合承载 Descriptor Entity 与 Repository 契约；不在其中实现文件 I/O 或业务编排。
- `session-module/pom.xml` 明确负责 JPA、SSE 和 REST，并已依赖 `navigator-common` 与用户鉴权模块，因此由其实现 Store、鉴权详情接口和生命周期协调，不产生反向依赖。
- `addons/codex-worker-agent` 当前已依赖 `session-module`；Codex relay 只调用 Session 侧能力，禁止让 `session-module` 反向依赖 Provider addon。
- `launcher` 是应用部署壳，只承载配置和装配，不新增 Controller、Service、DTO 或跨域编排逻辑。
- 前端依赖方向保持 `navigator-frontend -> @foggy/chat -> @foggy/chat-core`，Descriptor 契约先下沉到 core/chat，再由主前端接入 API。

## Stage 0：基线与契约冻结

1. 统计或构造工具输出大小分布，记录 8 KiB、48 KiB、64 KiB、1 MiB 分位样本。
2. 盘点所有活动的 `session_messages.content`、`session_tasks.resultText`、Provider task `resultText` 投影。
3. 冻结列表 DTO 与 Payload Descriptor 字段，明确最终 Assistant 与工具输出的分流规则。
4. 建立失败测试：大工具输出、超过 64 KiB 的最终回复、列表误读 Payload Store。

完成门：契约和迁移范围明确，测试在现状下能证明对应缺口。

## Stage 1：Schema 与 Payload Store 基础

1. 增加 `session_message_payloads` 描述表，保存稳定消息标识、存储后端、存储键、大小、摘要、状态和过期时间。
2. 将最终回复涉及的活动正文列提升到足够容量，生产迁移必须可重复执行。
3. 定义后端中立的 Payload Store 能力，完成持久化文件系统实现。
4. 文件写入采用临时文件 + 原子替换；存储键由服务生成，不接受用户路径。
5. 对文本载荷透明压缩，记录原始/存储字节、实际编码和原文 SHA-256；读取时透明解压并校验。
6. 增加配置校验：生产目录必须是持久化目录；无效/不可写目录以明确 readiness 错误暴露。

完成门：Store 单测、路径安全、幂等写入、摘要校验和 MySQL 8.0/8.4 迁移通过。

## Stage 2：消息持久化与 ACK 故障安全

1. 小工具输出保持内联，大工具输出生成首尾 Preview 并保存完整 Payload。
2. 使用稳定 `messageId` 作为幂等关联，不因 SSE 重放生成重复 Descriptor 或文件。
3. 载荷成功后保存 `READY`；载荷失败且无可靠重试来源时保存 Preview 和 `UNAVAILABLE`，允许事件 ACK。
4. 只有完整字节已可靠暂存，或持有可重试来源引用时才允许保存 `PENDING`，并由协调任务限时收敛到 `READY` 或 `UNAVAILABLE`。
5. MySQL 保存失败仍按现有 durable 语义停止 ACK，禁止把真正的数据库故障伪装成成功。
6. 最终 Assistant 回复绕过工具 Preview 规则，完整写入消息和必要任务投影。

完成门：大载荷、Store 故障、DB 故障、重复重放和终态顺序测试全部通过；BUG-021 不回归。

## Stage 3：列表零读取与按需详情

1. 消息列表和分页 DTO 只返回 Preview/Descriptor，不读取 Payload Store。
2. 增加 `GET /api/v1/sessions/{sessionId}/messages/{messageId}/payload` 鉴权详情/下载接口，支持流式响应和明确的 READY/EXPIRED/UNAVAILABLE 错误。
3. ToolCallBlock 展示“查看完整输出/详情/下载”入口；仅用户点击后请求。
4. 前端按 `messageId + payload version` 缓存已加载内容，切换会话时控制内存占用。
5. 最终 Assistant 回复保持当前默认渲染，不显示无意义的详情入口。

完成门：单元测试证明历史加载请求数不包含 Payload API；Playwright 证明点击前无请求、点击后完整展示、过期/失败状态可理解。

## Stage 4：保留期、清理和可观测性

1. 增加按 `expiresAt` 扫描的幂等清理；删除正文后将 Descriptor 标记为 `EXPIRED`。
2. 增加孤儿文件协调、会话删除/清理联动和存储水位保护。
3. 增加指标：内联/外置数量、原始/压缩字节、Store 写入失败、详情读取、过期清理、孤儿数量。
4. 日志只记录标识、大小和状态，不记录完整工具正文。

完成门：时间推进测试、重复清理、并发清理、孤儿收敛和会话删除测试通过。

## Stage 5：生产后端与切换

1. 根据部署拓扑决定继续使用共享持久卷，或实现私有 OBS/MinIO/S3 兼容后端。
2. 对真实容量、压缩率、详情读取率和清理速度做压测。
3. 先以配置灰度启用外置，观察无 ACK/终态回归后，再将 Preview 默认值降至建议值。
4. 评估历史 48 KiB 数据是否需要迁移；默认不迁移。
5. 更新进度、实现质量检查、测试覆盖审计和验收记录。

完成门：生产配置、回滚方案、指标告警和验收证据齐备；不得仅凭本地文件系统测试直接批准多实例生产。

## 切换与回滚原则

- Schema 只做可向后兼容的新增/扩容，灰度期不删除旧字段和 BUG-021 的 48 KiB 保护。
- 外置写入与详情入口分别受配置开关控制；回滚优先关闭新的外置写入，继续保留 Descriptor 读取能力和已写载荷。
- 新版本必须同时兼容旧内联消息与新 Descriptor 消息，禁止要求一次性迁移全部历史数据。
- 若必须回退到不识别 Descriptor 的旧二进制，消息 Preview 仍应可见，但完整详情会暂时不可用；因此生产放量前必须准备保留新读取链路的修复版本，不能把二进制降级作为唯一回滚方案。
- 回滚不得立即删除新表、载荷目录或对象；待版本稳定并完成数据清点后再执行独立清理。

## 故障矩阵

| 故障 | 预期行为 |
|---|---|
| Payload Store 写入失败且无可靠重试来源 | 保存 Preview + `UNAVAILABLE`，继续 ACK；详情明确不可用 |
| Payload 暂存成功但正式写入待重试 | 保存 `PENDING`，协调任务限时收敛；不得无限期停留 |
| Payload 写入成功、MySQL 事务失败 | 不 ACK；后续重放幂等复用，孤儿协调可清理 |
| MySQL 保存 Preview 失败 | 不 ACK，沿用 durable persistence 失败语义 |
| 重复 SSE 事件 | 不创建重复文件或 Descriptor |
| Payload 已过期 | 列表正常，详情返回 EXPIRED |
| Worker 离线/重装 | 已保存 Payload 不受影响 |
| 未授权读取 | 返回拒绝且不泄露是否存在、路径或存储键 |

## 测试与体验门槛

- Java：Entity/Repository、Store、消息持久化、Controller 鉴权、清理、Codex relay 故障注入。
- 集成：MySQL 8.0/8.4 migration + `ddl-auto=validate`、列表零读取、详情流式读取、会话删除。
- 前端：Descriptor 状态、点击加载缓存、错误/过期、最终回复完整展示。
- Playwright：页面可达、历史加载无 Payload 请求、点击详情完整展示、异常状态、跨用户拒绝、刷新后最终回复完整。
- 构建：相关 Maven 测试、前端单测、type-check 和 `bash scripts/build-frontend.sh` 必须通过。

## 完成定义

- Requirement 验收项均有代码和自动化证据映射。
- 所有相关测试实际运行通过，不能以“已编写”代替“已通过”。
- Progress 回写真实代码路径、配置、迁移、测试、体验证据和风险。
- 跨模块公共能力完成后执行 `foggy-implementation-quality-gate`。
- 质量检查通过后执行 `foggy-test-coverage-audit`，最后由 `foggy-acceptance-signoff` 签收。

## 非目标

- 不在计划阶段指定所有最终类名和 package。
- 不在本事项中改造附件、用户上传文件或所有 Provider 的 Worker 日志。
- 不在首期引入 MongoDB/GridFS。
