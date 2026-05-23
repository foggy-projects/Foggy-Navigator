# BUG-20260523 TMS 业务助手前端状态与附件历史展示

## 背景

TMS smoke 测试环境：`http://localhost:3199/tms/`

测试账号：`88800 / admin_88800`

测试时间：`2026-05-23 07:23-07:30`

已创建工单：

- 普通系统反馈工单：`TKT2026052307243361064531C`
- 带附件系统反馈工单：`TKT202605230728297193B30C6`

## 现象

1. 多轮回复完成后，消息下方仍显示 `查看执行报告 RUNNING`。
2. 历史会话列表中大量会话持续显示 `进行中`。
3. 创建附件工单时用户消息可见 `smoke-a.png`、`smoke-b.png` 两个附件 chip；刷新页面后，同一会话的用户历史消息附件不再展示。

后端工单详情已确认附件数量为 2，因此附件丢失重点定位在聊天历史消息持久化、OpenAPI 历史接口和前端历史消息映射链路。

## 证据

- 上传后截图：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-smoke-1779492201651\07-attachment-ticket.png`
- 刷新后截图：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-smoke-1779492201651\08-after-reload.png`
- 最终状态截图：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-smoke-1779492201651\99-final.png`
- 完整报告：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-smoke-1779492201651\report.json`

## 根因

### 附件历史展示

- `NavigatorChat` 发送用户消息时会在本地 `ChatMessage.attachments` 中保留附件。
- `ask` 请求也会把附件作为 top-level `attachments` 发送给 Navigator。
- 但 `LanggraphTaskService.persistUserPrompt` 写入用户历史消息时只保存了 `type/taskId`，没有把附件写入用户消息 metadata。
- OpenAPI session message DTO 没有稳定顶层 `attachments` 字段。
- 前端 `sessionMessagesToOpenMessages` / `ingestOpenMessages` 加载历史消息时没有恢复 user message 的附件。

### RUNNING 状态展示

- 会话摘要接口可能长期返回 `ACTIVE`；前端历史列表将 `ACTIVE` 当成 working，导致大量历史会话展示为 `进行中`。
- 终态消息的 `execution_report_digest.status` 可能仍是 `RUNNING`，前端展示执行报告时直接使用 digest 状态，导致最终答复下方仍显示 `RUNNING`。

## 修复范围

- BizWorker Java：用户消息落库 metadata 时保留 top-level attachments。
- OpenAPI Java：session message DTO 增加顶层 `attachments`，从 metadata 中透出。
- NavigatorChat Widget：
  - 历史会话消息加载时恢复 user message attachments。
  - 支持 metadata 为对象或 JSON 字符串。
  - 终态消息归一执行报告 digest 状态，避免 stale `RUNNING` 继续展示。
  - 历史会话列表不再把 `ACTIVE` 会话直接展示为 `进行中`。

## 回归点

- 刷新页面后，用户历史消息中的 2 个附件 chip 仍可见。
- 终态回复下方执行报告状态应为 `COMPLETED` 或 `FAILED`，不应继续 `RUNNING`。
- 历史会话列表中，只有真实运行中任务才显示 `进行中`。
- 普通多轮、创建工单、查询刚才工单、带附件创建工单不回退。

## 2026-05-23 复测更新

TMS 在 `2026-05-23 10:10` 复测确认：

- 实时会话完成态已正常显示 `COMPLETED`。
- 历史会话列表未再出现大量 `进行中` 残留。
- 用户历史消息附件刷新后仍可见，且后端工单详情确认 2 张附件存在。
- 剩余问题：刷新后重新打开历史会话，历史消息下方的执行报告状态仍回退为 `RUNNING`。

二次根因：

- 实时轮询接口有 task 终态，前端可正确收口。
- 历史会话消息接口只返回 message 本身，没有把 message 所属 task 的当前状态补到 message DTO。
- 历史消息 metadata 中的 `execution_report_digest.status` 可能仍是执行中快照 `RUNNING`；缺少 task 终态后，前端无法判断它是 stale 状态。

二次修复范围：

- OpenAPI 历史消息按 `taskId` 批量预取任务状态，并在每条 `OpenSessionMessageDTO.status` 中返回对外 task 状态。
- 任务消息增量接口同样给每条 message 补充所属 task 状态。
- 前端历史加载时，若 message 的有效状态已是终态，则将 stale `execution_report_digest.status=RUNNING` 归一为 `COMPLETED/FAILED/CANCELED`。

## 2026-05-23 10:50 二次复测更新

TMS 在 `2026-05-23 10:50-10:55` 复测确认：

- 实时会话完成态仍正常显示 `COMPLETED`。
- 普通工单创建成功：`TKT2026052310513248815BF38`。
- 追问“刚才创建的那个工单”可正确查询上一轮工单，多轮上下文正常。
- 刷新后历史用户消息里的 2 个附件 chip / 图片仍可见。
- 历史会话列表未看到 `进行中` 残留。

仍异常：

1. 刷新后重新打开历史会话，4 条历史消息下方执行报告仍显示 `RUNNING`。
2. 带附件工单创建失败，LLM 返回：`附件上传失败，系统返回了 B600 错误（系统异常，请稍后重试）`。

证据：

- 完整报告：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake2-1779504639487\report.json`
- 刷新前附件截图：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake2-1779504639487\04-before-refresh-attachment.png`
- 刷新后历史会话截图：`D:\workspace\tms-x6-dev\x3-web-tms\test-results\tms-assistant-retake2-1779504639487\06-reopened-history-session.png`

三次根因补充：

- 历史状态：前端原测试只覆盖了历史消息 DTO 已携带 `status=COMPLETED` 的场景。本次复测说明历史接口或 BFF 仍可能只携带历史消息本身和 stale `execution_report_digest.status=RUNNING`，没有携带可用 task 终态。历史回放中的已落库助手 `TEXT/RESULT` 消息应按历史终态处理，不应继续展示旧运行中快照。
- 附件 B600：BizWorker 真实 LLM 提交日志显示，会话 `bctx_20260523_a2_a27faecd5a2042a994e128dd9bbee4e4` 中，上游已经提供了 `retake2-a.png`、`retake2-b.png` 的已上传 URL/ref，但 LLM 仍先加载 `tms-attachment-agent`，并调用 `attachment.upload`，把 `local/tenant-...png` 当成 multipart `file` 入参提交，触发业务函数 B600。

三次修复范围：

- NavigatorChat 历史加载时，对历史来源的已落库助手 `TEXT/RESULT` 消息，如果没有失败/取消终态，统一视为 `COMPLETED`，并将 stale `execution_report_digest.status=RUNNING` 归一为 `COMPLETED`。
- BizWorker 附件上下文提示明确声明：上游系统提供的附件已经上传；创建工单或追加沟通时应直接映射为 `attachmentRefs`，不要再调用 `attachment.upload`，也不要把 `id/objectKey/local path/url` 作为 `attachment.upload.file` 入参。
- Root Agent 系统提示词补充同样约束，降低业务 Skill 文案中“附件上传路由到 tms-attachment-agent”对已上传上游附件场景的误导。
- 工具调度层增加保护：当 `attachment.upload` 的 `file` 等入参命中当前请求中的已上传附件 `id/url/name` 时，不再调用上游业务函数，直接返回可恢复的 `RUNTIME_CONTRACT` 工具结果，并附带建议的 `attachmentRefs` 映射，避免再次触发 TMS B600。

新增回归覆盖：

- `useNavigatorChat.ux.test.ts`：历史消息缺少 task 终态字段但带 stale report digest 时，回放状态归一为 `COMPLETED`。
- `test_query.py` / `test_llm_skill_agent.py`：附件上下文提示保留安全 URL，同时明确 `attachmentRefs` 映射和禁止重复调用 `attachment.upload`。
- `test_llm_tool_dispatcher.py`：覆盖已上传上游附件误调用 `attachment.upload` 时不会继续打到上游。
