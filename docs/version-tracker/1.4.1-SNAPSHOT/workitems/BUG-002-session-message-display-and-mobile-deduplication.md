---
type: bug
bug_source: user-report
version: 1.4.1-SNAPSHOT
ticket: BUG-002
severity: major
status: completed
reproduction_status: code-path-confirmed
test_strategy: unit-test-and-build
automation_decision: required
owner: project-root-session
---

# 会话末条可读性与移动端重复消息

## 文档作用

- doc_type: bug
- intended_for: project-root-session | reviewer | signoff-owner
- purpose: 跟踪 Codex 会话长最终回复的默认展示、消息关联与 PC/App 重复消息修复。

## 问题与结论

用户反馈 Session `55b6d12f-3524-4198-a598-5465f1bd1fa4`、Codex Thread `019f41fe-f888-75f1-be2d-102aadc11d63` 的末条记录把工作过程铺满会话区，难以阅读；App 端还会出现重复消息。

本地不能访问该运行记录，不能据此重放；代码链路已确认以下风险：

1. PC 正常渲染逐条 `ChatMessage`，不会直接 `join` 历史，但默认把超长最终回复完整铺在时间线，破坏扫描与续对体验。
2. 共享 Chat State 会把任意未完成 assistant chunk 与新 chunk/complete 关联，缺少 `taskId + streamId` 约束；Codex app-server 没有向前端完整透传 `itemId`。
3. PC live SSE 没有稳定消息 ID 去重；`appendMissingTaskResult` 在已有最终助手消息但文本不完全相同时仍可能补一条结果。
4. 移动端历史记录被一律映射为 `TEXT_COMPLETE`，分页重叠不会按 ID 过滤，且断线时 `error` 与 `close` 可同时安排重连。
5. 旧同步 Codex facade 会把多条 `assistant_text` append 为一个 fallback `resultText`，需禁止该累积行为。

## 目标结果

- 默认会话时间线保持紧凑：长消息仅显示可读预览，用户可主动展开或打开完整记录。
- 在 PC 的消息操作区（复制、转发旁）增加“查看记录”，弹窗按时间逐条呈现当前会话消息，并显示/复制 Session ID 与 Codex Thread ID。
- App 使用等价的逐条记录查看入口，不再将所有历史语义伪装成普通 assistant 文本。
- 同一持久化/SSE 消息不因重连、分页或 result 补偿重复显示；chunk 仅能更新同一流。
- 不截断或删除后端完整最终回复；完整内容仍可复制和查看。

## 范围与职责

| 模块 | 责任 / 代码区域 |
|---|---|
| `tools/codex-app-server-worker` | 保持 item 内 delta 聚合，向事件携带可关联流标识。 |
| `addons/codex-worker-agent` | 透传流标识，修复同步 facade 的 fallback `resultText` 累积。 |
| `packages/foggy-chat-core` | 使用稳定的 task/stream 关联 chunk 与 complete，避免误吸收其他消息。 |
| `packages/navigator-frontend`、`packages/foggy-chat` | 默认预览、记录弹窗、PC history/live 去重与 result 补偿收紧。 |
| `packages/foggy-mobile` | 正确还原历史消息、重叠去重、单次重连、紧凑预览和记录查看。 |

## 非目标

- 不修改或删除历史数据库消息。
- 不改变 OPT-001 的外置 Payload 详情鉴权/读取范围，也不在列表加载时读取 Payload Store。
- 不因展示收紧而截断 `resultText` 或最终 Assistant 原文。
- 不重构后端分页协议为 cursor；本次先在客户端消除 offset 重叠。

## 验收标准

1. 交错或重连的 `TEXT_CHUNK` / `TEXT_COMPLETE` 不得更新无关消息；同一 live `messageId` 最多显示一次。
2. `appendMissingTaskResult` 仅在当前任务没有最终 assistant 消息时补偿，不以文字差异制造重复。
3. PC 长助手消息默认预览，操作区可打开记录弹窗；弹窗逐条显示角色、类型、时间、内容和可复制的会话/Thread 标识。
4. App 历史加载保留消息 metadata 类型，分页重叠不重复，单次连接失败只调度一个重连。
5. App 同样提供完整记录查看，且长文本不再撑乱时间线。
6. Codex 同步 fallback 不再把 delta 与完整消息累积为一条 resultText。
7. 相关 Java / TypeScript 单元测试、前端构建和差异检查通过。

## 体验审查摘要

| Severity | 发现 | 对应原则 | 修复 |
|---|---|---|---|
| 3 | 长最终回复占满主时间线 | 简洁性、可感知性、结构 | 默认预览 + 主动查看完整记录。 |
| 3 | 重连/分页可制造相同消息 | 错误预防、系统状态 | 按稳定 ID 去重；重连 timer 单例。 |
| 2 | Thread / Session 标识须靠用户记忆 | 识别优于回忆 | 记录弹窗中明确展示并支持复制。 |
| 2 | 行内文字操作的触达与键盘提示不足 | 可供性、无障碍 | 用有标签按钮、tooltip、focus-visible 和可关闭弹窗。 |

## 实施进度

### Development

- status: completed
- completed:
  - app-server `itemId` 作为 `stream_id` 透传到 Java relay、PC/App Chat State；同一 `taskId + streamId` 才允许 chunk 与 complete 合并，缺失流标识绝不回退拼接历史 chunk。
  - 同步 Codex facade 仅选用最新 completed assistant item，不再累计 delta/commentary 形成错误 `resultText`。
  - PC 主时间线对超长 assistant 正文默认紧凑预览；复制、转发旁新增“查看记录”，弹窗完整逐条展示、支持复制 Session / Codex Thread ID。
  - PC、App 的 DB 历史、分页、SSE 重放使用稳定消息 ID 去重；独立记录查看使用隔离的去重集合加载全量历史，不会漏掉已显示行。
  - App 端还原持久化 AIP 类型，error/close 只调度一个重连 timer；长文本预览与逐条记录弹层同步落地。

### Testing

- status: completed
- evidence:
  - `node --import tsx --test tests/native-subtask.test.ts`（`tools/codex-app-server-worker`）：5/5 passed。
  - `mvn -B -pl agent-framework,addons/codex-worker-agent -am -Dtest=CodexWorkerFacadeImplTest,CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test`：50 tests passed。
  - `npx --yes --package=node@22 -- ./node_modules/.bin/vitest run src/__tests__/chatState.test.ts`（`packages/foggy-chat`）：50/50 passed。
  - `npx --yes --package=node@22 -- npm run type-check` + PC 定向 Vitest：39/39 passed。
  - `npx --yes --package=node@22 -- ./node_modules/.bin/vitest run --config vitest.config.ts src/__tests__/useTaskStream.test.ts src/__tests__/useUnifiedSse.test.ts`：11/11 passed；`npm run build:h5` passed。
  - `npx --yes --package=node@22 --package=pnpm@10 -- bash scripts/build-frontend.sh`：passed。
  - `git diff --check`：passed。

### Experience

- status: completed
- completed: 主时间线保留摘要与展开入口；记录查看将身份、时间、角色、正文分行呈现，弹窗/底部抽屉均可关闭、标识均可复制。
- note: 用户提供的生产 Session / Thread 未在本地环境可访问，未对该真实记录做在线回放；以协议级回归、构建和定向 UI 状态测试覆盖。

### Risks / Decisions

- 现场 Session 和 Thread 标识未出现在本地可读日志；修复以协议与回归测试保证，实际数据不写入仓库。
- 记录查看是独立功能，不能替代消息关联与去重修复。
- 采用用户提出的第二种方案：保留简洁主界面，完整会话记录按需弹窗逐条展示。

## Execution check-in

- scope: `BUG-002`
- outcome: completed
- changed areas: `tools/codex-app-server-worker`、`agent-framework`、`addons/codex-worker-agent`、`packages/foggy-chat-core`、`packages/foggy-chat`、`packages/navigator-frontend`、`packages/foggy-mobile`。
- follow-up: 发布后以用户给出的 Session / Codex Thread 做一次线上验收，重点确认既有历史记录的“查看记录”顺序、App 断线重连和多轮续聊没有重复。
