# Navigator Runtime Context / TMS Chat 对外发布收口 - 2026-05-23

## 发布结论

本轮 `1.1.6-SNAPSHOT` runtime context、OpenAPI 会话历史、执行报告读取、TMS Chat 历史状态与附件展示问题已完成发布收口。

发布建议：

1. Navigator 代码以 `qd-win11/dev` 当前提交为准。
2. `navigator-open-sdk` 对外版本升为 `1.0.5`。
3. Navigator Upstream CLI 本地发布包随 SDK 版本生成 `1.0.5`。
4. `packages/navigator-chat-widget` 仍是私有 workspace 包，不单独 npm 发布；上游需通过源码/工作区依赖更新并重新构建。
5. TMS 侧已完成真实 smoke 复测，结论为全部通过。

## 本轮对外变化

### OpenAPI / SDK

1. `navigator-open-sdk` 增加执行报告读取能力：
   - `GET /api/v1/open/frame-reports`
   - 支持 `reportRef=frame-report://<taskId>/<frameId>`
   - 支持 `mode=markdown|digest`
   - 支持 `maxChars`
2. OpenAPI 历史消息与任务消息补齐 message 级 task 状态，避免历史回放时执行报告状态回退为 `RUNNING`。
3. OpenAPI 历史消息透出用户消息附件 metadata，支持刷新后恢复附件 chip / 图片展示。
4. `AgentApi` / `BusinessAgentApi` 暴露 frame report SDK wrapper。

### LangGraph BizWorker

1. BizWorker 继续作为 LLM runtime context 的 source of truth，上游只负责完整 UI transcript。
2. `contextId` 新会话由 BizWorker 生成；上游只在续聊时复用返回值。
3. `logs/llm-submissions/` 保存真实提交给 LLM 的 body，便于外部排查 runtime prompt。
4. prompt window 已按 turn / tool protocol 做预算治理；压缩与裁剪策略以 `09` 和 `OPT-runtime-prompt-window-turn-aware-pruning` 为准。
5. 大 tool result 支持在超过保留尾部 turn 后投影为 digest / refs，默认保留最近任务上下文。
6. 附件上下文增加 `attachmentRefs` 容错，避免旧式或跨端附件字段导致 Worker 异常。

### Navigator Chat Widget

1. 历史消息加载时恢复用户消息 `attachments`。
2. 历史消息执行报告状态根据 message 级 task status 归一，不再把已完成消息显示为 `RUNNING`。
3. 历史会话列表不再因 stale `ACTIVE` 摘要长期显示 `进行中`。

### Upstream CLI / Skill 口径

1. CLI 包版本随 `navigator-open-sdk` 为 `1.0.5`。
2. CLI model create/update 已支持 `--runtime-budget-preset` 与 `--runtime-budget-override-json`。
3. 配套 `navigator-upstream-cli` skill 的当前口径仍适用：
   - 新会话不由上游生成 `contextId`。
   - `clientContext` 只承载元数据，不承载 prompt / token budget / workspace 配置。
   - LangGraph Biz 的模型预算通过后端 model config 字段配置。

## 上游升级说明

### Navigator 侧

1. 拉取 `qd-win11/dev` 最新代码。
2. 重新构建并重启 Navigator launcher。
3. 重新启动 LangGraph BizWorker。
4. 确认以下服务健康：
   - Navigator OpenAPI / launcher
   - BizWorker `/health`
   - 当前 ClientApp 绑定的 `bizWorkerBaseUrl` 指向实际持有会话与报告数据的 Worker 或同一持久化后端。

### Java 上游 / TMS BFF

如上游直接依赖 SDK，将版本升级到：

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>navigator-open-sdk</artifactId>
    <version>1.0.5</version>
</dependency>
```

TMS 侧需要重新构建 BFF / Web，使历史消息状态、附件字段与 frame report proxy 的新 SDK / OpenAPI 行为生效。

### 前端 / Widget

`packages/navigator-chat-widget` 当前是私有包，不发布 npm 制品。上游需要：

1. 更新包含本轮改动的源码或 workspace dependency。
2. 重新构建使用该 widget 的前端应用。
3. 刷新后重新打开历史会话，验证用户消息附件与执行报告终态展示。

## 验证记录

### Navigator 自动化验证

已执行并通过：

```powershell
mvn -pl session-module,business-agent-module,addons/claude-worker-agent,addons/langgraph-biz-worker,navigator-open-sdk -am '-Dtest=OpenApiSessionQueryServiceTest,OpenApiControllerMessageMappingTest,LanggraphTaskServiceTest,LanggraphWorkerClientTest,BusinessAgentFrameReportServiceTest,BusinessAgentApiSmokeTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：91 tests passed。

已执行并通过：

```powershell
pnpm --filter @foggy/navigator-chat-widget test -- src/composables/useNavigatorChat.ux.test.ts
pnpm --filter @foggy/navigator-chat-widget build
```

结果：23 tests passed，widget build passed。

已执行并通过：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_llm_tool_dispatcher.py tests/test_query.py tests/test_attachment_context.py tests/test_llm_skill_agent.py::test_llm_agent_child_skill_receives_sanitized_attachment_context
```

结果：26 tests passed。

### TMS 真实复测

TMS 在 `2026-05-23 11:21-11:26` 完成复测，结论：全部通过。

1. `hi` 实时完成态为 `COMPLETED`，未卡 `RUNNING`。
2. 普通工单创建成功：`TKT2026052311223276294D8B0`。
3. 多轮追问“刚才创建的那个工单”能正确查询上一轮工单。
4. 带 2 张图片附件工单创建成功：`TKT202605231124109988A4C7E`。
5. 刷新后重新打开历史会话，用户消息中的 2 个附件仍可见。
6. 历史消息下方执行报告没有再回退 `RUNNING`，`reopenedRawRunningCount=0`。
7. 历史会话列表没有 `进行中` 残留，`reopenedHistoryGoingCount=0`。
8. 后端工单详情确认附件数量为 2：`retake3-a.png`、`retake3-b.png`。
9. 浏览器 console error / page error 为 0。

证据：

- `D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake3-1779506477281\report.json`
- `D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake3-1779506477281\04-before-refresh-attachment.png`
- `D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake3-1779506477281\06-reopened-history-session.png`

## 本地制品

待执行 `tools\navigator-upstream-cli\dist\package.ps1` 后记录：

1. `tools\navigator-upstream-cli\dist\output\navigator-upstream-cli-1.0.5-windows.zip`
2. `tools\navigator-upstream-cli\dist\output\navigator-upstream-cli-1.0.5-windows.zip.sha256`
3. `tools\navigator-upstream-cli\dist\output\BUILD_INFO.json`

本次不默认上传 OBS / 制品库；如需外部分发，另行执行 `tools\navigator-upstream-cli\dist\upload.ps1` 或 `package.ps1 -Upload` 并记录发布 URL。

## 回滚说明

1. SDK 调用方可临时回退到 `1.0.4`，但会失去 OpenAPI frame report SDK wrapper 和本轮历史状态/附件相关 DTO 行为。
2. TMS 前端若回退 widget 源码，历史消息附件恢复与执行报告终态归一会回退。
3. BizWorker runtime context 目录与 `bctx_yyyyMMdd_<hash>_<id>` 新会话目录设计保持向前兼容，不需要迁移历史数据。

## 后续未关闭项

1. `plan` 工具函数仍为设计态，见 `OPT-runtime-plan-tool-contract`。
2. prompt 压缩 / 裁剪的默认阈值仍需要结合真实长会话继续调参。
3. 受限 `shell_command` 工具仍为设计态，见 `15-restricted-shell-command-tool-design.md`。
4. 若上游要求 CLI 直接读取 frame report，可在后续为 CLI 增加 `upstream frame-report read` 命令；当前 Java SDK 已可覆盖 BFF 集成。
