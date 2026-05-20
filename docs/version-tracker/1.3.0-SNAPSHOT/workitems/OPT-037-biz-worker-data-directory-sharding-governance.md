# OPT-037 BizWorker data directory sharding governance

## 文档作用

- doc_type: optimization
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 BizWorker 文件型运行数据的目录分片治理要求，避免生产环境单目录文件数随会话和任务增长而失控。

## 基本信息

- version: 1.3.0-SNAPSHOT
- priority: P1
- status: implemented
- owner: langgraph-biz-worker
- source_type: optimization
- created_at: 2026-05-20

## 背景

`tools/langgraph-biz-worker/data` 当前承载 frame 快照、frame report、LLM/tool 调试日志、账号级 skill/context/artifact 等多类文件。用户在真实链路测试时发现，每次会话都会在该目录下产生多个 task、conversation、report 和日志文件。

本项不优先讨论哪些文件是否可以关闭，而是先治理文件系统可维护性：生产环境不能让某一个目录被大量任务、会话或 frame 文件持续堆满。

## 问题陈述

当前目录结构存在以下风险：

- `frames/<task_id>/`、`frames/by-conversation/<context_id>/`、`frame-reports/<task_id>/`、`frame-reports/by-conversation/<context_id>/` 直接按任务或会话平铺。
- 顶层和二级目录会随着任务量持续增长，没有年月日、租户、账号或分片层。
- 即使 `frame-reports`、debug logs 后续允许关闭，`frames` 等恢复必需数据仍可能在高并发生产环境中造成单目录文件数膨胀。
- 清理策略如果只按 task 删除，可能遗漏 conversation 维度副本或索引。

## 目标结果

生产态文件布局需要满足：

- 同一物理目录下文件或子目录数量有可控上限，不随全量历史任务线性无限增长。
- 可按时间、账号、会话或 task 维度定位和清理数据。
- 不破坏当前中断恢复、`WAITING_FOR_USER_INPUT` 继续、审批恢复、frame report 读取等现有能力。
- 同一个 session 的 frame、report 和调试日志优先集中在一个目录，便于排查、备份和整会话归档。
- 支持“单份 canonical 数据”，避免 task/conversation 维度重复保存大文件或新增索引目录。

## 建议方向

采用“日期 + 一层 hash + session/context”的 canonical 目录。业务 `contextId` 必须采用 `bctx_yyyyMMdd_<hash>_<id>` 格式，其中 `<hash>` 直接作为目录分片；缺失或非标准 `contextId` 视为调用方 BUG。新写入不再创建 task/conversation/report 索引：

```text
data/
  runtime/
    sessions/
      by-date/YYYY/MM/DD/
        <hash>/<context_id>/
          frames/<frame_id>.json
          reports/<frame_id>.md
          reports/<frame_id>.digest.json
          logs/llm-conversations/<seq>_<task_id>.jsonl
          logs/skill-tool-calls/<task_id>.jsonl
  accounts/
    <account_id>/...
```

也可以选择账号优先分片：

```text
data/
  accounts/<account_id>/
    runtime/sessions/YYYY/MM/DD/...
    artifacts/...
```

最终方案需结合 Java 侧传入的 `contextId`、`accountId`、`taskId` 可用性决定。若生产部署按账号隔离 data root，则时间分片即可；若多个账号共享同一 data root，则应优先账号分片，再按日期分片。

## 验收标准

- 新建会话和新建 task 不再直接在 `data/frames`、`data/frame-reports` 等目录下无限平铺。
- 新写入不再创建 `runtime/frames`、`runtime/frames/indexes/...` 或 `reports/frame-execution/indexes/...`。
- frame journal 的读写、按 task 加载、按 conversation 加载仍可通过测试。
- recoverable continuation、`WAITING_FOR_USER_INPUT` 用户回复恢复、approval resume 路径不退化。
- approval resume 必须携带 `contextId`，Python Worker 对缺失 `contextId` 返回 422；Java 调用链从 `LanggraphTaskEntity.contextId` 传递。
- 业务 `contextId` 必须符合 `bctx_yyyyMMdd_<hash>_<id>`；Worker 通过 ID 内嵌 `<hash>` 直接定位目录，不再对业务 ID 二次计算分片。
- frame report 保留稳定 `frame-report://<task>/<frame>` 引用格式，但物理文件只从 session canonical 目录读取/生成。
- 清理任务可以按日期分片删除过期数据，并明确跳过 active/recoverable 状态数据。
- 文档说明哪些目录是恢复必需、哪些是派生 report、哪些是 debug log，但不把“关闭 report/log”作为本项完成前提。

## 非目标

- 本项不要求迁移旧数据；历史 `runtime/frames`、`reports/frame-execution`、`frame-reports` 等目录可直接删除。
- 本项不要求把文件存储迁移到数据库或对象存储。
- 本项不处理 TMS 业务接口或附件上传问题。

## 影响代码区域

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_frame_journal.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/resume.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/frame_reports.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/artifact_store.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClient.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerResumeEventListener.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`

## 测试要求

- unit: frame journal 新路径写入、按 task 读取、按 conversation 读取、排序逻辑。
- unit: frame report 新路径写入和 `read_frame_execution_report` 读取兼容。
- regression: continuation after timeout/cancel 仍能找到 recoverable focus。
- regression: `WAITING_FOR_USER_INPUT` 后同一 `contextId` 用户回复仍能恢复到正确 frame。
- cleanup dry-run: 按日期分片列出待清理目录，不删除 active/recoverable frame。

## Progress Tracking

- development: completed
- testing: completed
- experience: N/A，纯后端存储布局治理，无直接 UI 行为变更。

## Execution Check-in

- completed_at: 2026-05-20
- completed_work:
  - `FileFrameJournal` 新写入路径迁移到 `runtime/sessions/by-date/YYYY/MM/DD/<hash>/<contextId>/frames/...`，同一 session/context 的多个 task/frame 落在同一目录下。
  - `FrameExecutionReport` 新写入路径迁移到同一 session 目录的 `reports/...`，保留旧 `frame-report://<task>/<frame>` 引用格式。
  - LLM conversation log 和 skill tool audit log 写入同一 session 目录的 `logs/...`，新任务不再直接堆到旧平铺目录。
  - 移除 frame task/conversation locator 写入、report locator 写入和 `.journal-seq` 文件写入；新写入不再创建 `runtime/frames` 或 `reports/frame-execution`。
  - `POST /api/v1/resume` 强制要求 `contextId`，Java LangGraph Worker 调用链从任务实体传入 `sessionId/contextId`。
  - Java 业务会话默认生成 `bctx_yyyyMMdd_<hash>_<uuid32>`，Worker 对业务 context 只接受 `bctx_yyyyMMdd_<hash>_<id>`，目录分片直接取 ID 内嵌 `<hash>`。
  - OpenAPI 新会话未传 `contextId` 时同步生成标准 `bctx_yyyyMMdd_<hash>_<uuid32>`，避免首轮请求生成旧短 ID 后被业务会话格式校验拒绝。
  - frame report GET 和 LLM 工具读取支持携带 `contextId/sessionId`，优先从同一 session 目录定位报告。
  - 增加 frame 日期分片 cleanup dry-run 规划，跳过 active/recoverable frame。
- compatibility:
  - 不保留历史 frame/report 物理路径读取兼容；`runtime/frames`、`reports/frame-execution`、`frame-reports` 历史数据可删除。
  - 保留外部引用格式 `frame-report://<task>/<frame>`，内部通过 `contextId/sessionId` 定位 session canonical 目录。
- touched_code:
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_layout.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/file_frame_journal.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_dispatcher.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/resume.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/frame_reports.py`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/client/LanggraphWorkerClient.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerResumeEventListener.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `tools/langgraph-biz-worker/tests/test_file_frame_journal.py`
  - `tools/langgraph-biz-worker/tests/test_frame_execution_report.py`
  - `tools/langgraph-biz-worker/tests/test_frame_report_route.py`
  - `tools/langgraph-biz-worker/tests/test_resume.py`
  - `tools/langgraph-biz-worker/tests/test_account_skill_routing.py`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerResumeEventListenerTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceApprovalTest.java`
  - `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/e2e/BusinessFunctionApprovalResumeFlowTest.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentSessionService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
- test_status:
  - `PYTHONPATH=src .\.venv\Scripts\python.exe -m pytest tests/test_file_frame_journal.py tests/test_frame_execution_report.py tests/test_frame_report_route.py tests/test_resume.py tests/test_account_skill_routing.py -q` passed: 68 passed.
  - `mvn -pl business-agent-module -am "-Dtest=BusinessAgentSessionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed: 5 tests.
  - `mvn -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerResumeEventListenerTest,LanggraphTaskServiceApprovalTest,BusinessFunctionApprovalResumeFlowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed: 20 tests.
  - `mvn -pl addons/claude-worker-agent,business-agent-module -am "-Dtest=OpenApiControllerMessageMappingTest,BusinessAgentSessionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed: 21 tests.
  - `mvn -pl addons/claude-worker-agent,business-agent-module -am test` passed: 261 tests.
- acceptance_readiness: ready-for-review

## Implementation Quality Self-Check

- scope: OPT-037 BizWorker data directory sharding governance
- mode: lightweight-self-check
- self-check-summary:
  - 已按确认后的方案将布局收敛为“日期 + 一层 hash + session”，同一 session 的 frame/report/log 集中落盘。
  - 新写路径、report_ref 兼容、resume context 契约、cleanup dry-run 跳过 recoverable frame 均已覆盖相关测试。
  - 旧 index/locator 目录已从新写入和读取路径移除；历史运行数据不迁移，可直接清理。
  - 本项未变更 `accounts/<account_id>/artifacts/task/<task_id>` 布局；artifact 已账号隔离，若生产上单账号 artifact task 量很高，可另开二阶段。
- decision: self-check-only
- needs-formal-quality-gate: no

## 后续执行提示

实施前先确认生产部署维度：

- `data_root` 是全局共享、按 worker 共享，还是按账号隔离。
- Java 侧是否稳定传入 `accountId`、`contextId`、`taskId`。
- 旧数据是否需要迁移，还是仅新数据采用分片路径并保留旧路径读取兼容。

## ID 生成口径

- Python Worker 不生成业务侧 `contextId`；它只消费上游/Java 侧传入的 `contextId`、`sessionId`、`taskId`。
- 当前 Java 侧 `sessionId` 由会话模块/上游创建，默认是普通 UUID。
- 业务会话绑定时如果没有传入 `contextId`，Java 会生成 `bctx_yyyyMMdd_<hash>_<uuid32>` 形式的默认 context，例如 `bctx_20260520_ab_0123456789abcdef0123456789abcdef`。
- `contextId` 必须是单段 ID，必须匹配 `bctx_yyyyMMdd_<hash>_<id>`，不要包含 `/`。
- Worker 对业务 `contextId` 不再执行 `sha256(contextId)[:2]` 分片；目录日期和分片直接来自 `bctx_yyyyMMdd_<hash>_<id>` 中的 `yyyyMMdd` 与 `<hash>`，因此可直接定位到 `runtime/sessions/by-date/YYYY/MM/DD/<hash>/<contextId>/`。
- 非标准自定义业务 `contextId` 直接拒绝；`sha256` 回退只保留给无业务 context 的内部 task/session fallback。
