# OPT-003 Codex App Server 原生交互输入

## 文档作用

- doc_type: optimization
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 固化 Ultra 原生 `request_user_input`、回复路由和 SSE 断流语义，并跟踪隔离实现与验收。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P1
- status: isolated-accepted-with-risks
- source_type: optimization
- owner: `codex-app-server-worker` + `addons/codex-worker-agent` + `session-module` + Navigator PC
- production_routing_changed: no

## 背景

`codex-app-server-worker` 已支持 Ultra turn、原生子任务和终态后的同 thread 续接，但当前拒绝 app-server 发起的全部 server request。用户在 Codex 原生 `request_user_input` 场景中无法直接选择 `1/2/3` 或提交结构化答案，现有 `/respond` 也尚未路由到 Codex provider。

SSE 连接只负责观察任务，不拥有 turn。单纯 SSE 断流不得创建新 turn、改变任务状态或触发“继续”；客户端应重连并以 snapshot/status 同步为准。

## 已确认语义

1. `RUNNING` 表示原生 turn 仍在执行，只允许重连、同步或 abort，不允许 resume。
2. `AWAITING_INPUT` 表示同一个 turn 正等待原生输入，只允许 respond 或 abort，不允许 resume。
3. `FAILED`、`COMPLETED`、`ABORTED` 等终态可通过 resume 创建同 thread 的新 turn；这与 SSE 重连无关。
4. 前端按钮不是唯一防线。即使绕过 PC，Java 与 Worker 也必须拒绝活动 turn 的 resume；app-server 自身的拒绝只作为最后一道协议保护。
5. 原生请求回复绑定 `runtimeId + revision + workerInstanceId + workerTaskId + threadId + turnId + requestId`，只允许一次成功回复，禁止跨 runtime、跨 instance 或跨 turn 投递。
6. 单个待处理单选问题允许直接输入 `1`、`2`、`3` 或选项文本；多问题和自由文本使用结构化卡片，避免歧义。固定 schema 没有 `multiSelect` 字段，不从 response 的数组 wire shape 推断多选。
7. app-server 连接/Worker 重启导致原 request channel 失效时 fail closed：不得把缓存答案发送到新 turn，也不得伪造成功。任务应进入明确终态，用户随后可 resume 让 Codex 重新提问。

## 范围

- 只支持 Codex app-server experimental 协议中的 `item/tool/requestUserInput`（以锁定 CLI `0.144.1` 的生成 schema 为准），初始化显式设置 `experimentalApi=true`。
- Worker 将原生请求持久化为可同步的 sanitized pending interaction，并发布统一事件。
- Java 投影 `AWAITING_INPUT` 与 `CONFIRMATION_REQUEST`，实现 Codex provider 的 `/respond`。
- Navigator PC 复用通用问题卡，支持多问题/单选/自由文本结构化答案及明确的单选数字快捷回复。
- SSE 断流继续采用重连/snapshot/status 同步；RUNNING/AWAITING_INPUT 的 resume 在 UI、Java、Worker 三层拒绝。

## 非目标

- 不开放 command/file approval、MCP elicitation 或任意未知 server request。
- `approval_policy` 继续仅允许 `never`。
- 不修改旧 `codex-agent-worker` SDK 路径。
- 不批准 P3 生产切流，也不将隔离测试计入 50 task/72h/2 rotations。
- 不在 PC/SSE 中暴露原始 reasoning、endpoint、token、Codex Home 或未清洗工具 payload。

## 实施顺序

1. 从固定 CLI `0.144.1` schema 确认 request/response 方法与字段，冻结 Navigator sanitized contract。
2. 实现 Worker pending request registry、持久化 projection、HTTP respond 与 once-only/affinity/timeout 保护。
3. 实现 Worker 事件与 snapshot/status 投影，连接丢失或进程恢复时 fail closed。
4. 实现 Java client、CodexTaskService 状态转换、TaskCommandProvider.respondToTask 和 resume active-turn guard。
5. 接入 PC 通用问题卡、单选快捷输入、disabled/resync 状态和错误提示。
6. 补齐 Worker、Java、Session、前端单元/契约测试。
7. 执行真实 Ultra、SSE 断流、刷新、重复回复、活动 turn resume 拒绝和 Playwright 验收，并完成质量/覆盖回写。

## 协议基线

- 固定 CLI: `@openai/codex 0.144.1`；schema digest 保持 `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
- server request: `item/tool/requestUserInput`。
- params required: `threadId`、`turnId`、`itemId`、`questions`；optional: `autoResolutionMs`。
- question required: `id`、`header`、`question`；optional: `options`、`isOther`、`isSecret`。
- JSON-RPC result: `{ "answers": { "<questionId>": { "answers": ["..."] } } }`。
- app-server 在请求被响应，或被 turn start/completion/interruption 清理后发送 `serverRequest/resolved {threadId, requestId}`；Worker 以此作为 pending request 失效信号。
- `experimentalApi=true` 只开放协议握手能力；Worker 仍只 allowlist `item/tool/requestUserInput`，其他 server request 保持 unsupported。
- 固定 CLI 的 `default_mode_request_user_input` 当前为 `under development / false`；独立 Worker 通过 server-controlled `features.default_mode_request_user_input=true` 打开默认协作模式工具，并在 capability 中明确标记 experimental。请求方不得覆盖该开关或注入其他 feature。
- 官方参考: <https://learn.chatgpt.com/docs/app-server#toolrequestuserinput>。

## 验收标准

- [x] Ultra 原生单选请求在 PC 展示，选择或输入 `1/2/3` 后同一 turn 继续并完成。
- [x] 多问题和单选按 schema 结构化提交；`isOther`/free-text wire shape 有协议与 UI 覆盖，不把普通 resume prompt 当作 native response，也不伪造 schema 未声明的 Codex 多选语义。
- [x] 同一 request 重复回复只有一次生效，后续返回稳定的 409/terminal 业务错误。
- [x] RUNNING/AWAITING_INPUT 的 resume 在 Java 与 Worker 均 fail closed，不产生第二个 turn 或重复副作用。
- [x] SSE 断流、刷新和重连不改变任务状态；snapshot/status 能恢复同一 pending interaction。
- [x] runtime revision/instance/thread/turn/request 任一不匹配均拒绝，不静默切换 SDK 或其他 runtime。
- [x] app-server/request channel 丢失后明确失败，不缓存重放用户答案。
- [x] command/file approval 仍为 `never`，未知 server request 仍返回 unsupported。
- [x] PC desktop 与 320px 无溢出，选择卡、异常、权限和重复提交状态可辨识。
- [x] secret 答案不持久化、不进入事件或日志；不泄露 reasoning、token、endpoint 或原始 server request payload。

> Gap: 固定 CLI `0.144.1` 的真实模型侧工具未生成纯 secret/freeform-only 请求；当前只签收对应协议、安全和 UI 自动化，不宣称已完成真实模型闭环。

## Progress Tracking

### Development

- completed: Worker `0.2.0` allowlist 原生 `request_user_input`，实现 durable sanitized pending、一次性 respond、精确 affinity、活动 thread guard 和 channel-loss fail closed。
- completed: Java/Session 贯通 `AWAITING_INPUT`、typed response、SSE/snapshot 同步、活动态 resume guard 和 HTTP 响应丢失补偿。
- completed: PC/Mobile 问题卡、数字快捷回复、多问题、刷新恢复、已答状态与 320px 布局。
- completed: Claude array answer 兼容；旧 Codex SDK Worker 设计保持不变。

### Testing

- passed: Worker `215 total / 208 passed / 7 platform-skipped / 0 failed`；schema/typecheck/build/release 通过。
- passed: Session `306/306`、Codex addon `276/276`、Claude normalization `2/2`。
- passed: foggy-chat `100/100`、Navigator `196/196`、Mobile `40/40`；相关 build 通过。
- passed-isolated: 真实 Ultra 单选、多问题、重复回复、断流/刷新、误 continue 拒绝、Worker 重启 channel-loss 和终态 resume。
- artifact: `codex-app-server-worker-0.2.0.zip`, SHA-256 `03949845DE8C405E1CC679D5DE5FB7F2AE86734C13C16E63102BC150A003343E`, `1,634,838` bytes, `176` entries。

### Experience

| 检查项 | 状态 |
|---|---|
| 页面可达性与待输入卡展示 | passed-isolated |
| 单选数字/选项文字回复 | passed-isolated |
| 多问题/`isOther` 表单 | passed-isolated；pure freeform live gap |
| SSE 断流、刷新与恢复 | passed-isolated |
| 重复提交/失效请求异常状态 | passed-isolated |
| desktop/320px responsive | passed-isolated |

## Execution Check-in

- completed_work: 原生交互输入、SSE 重连同步、防重复执行、恢复和 PC 体验已完成隔离闭环
- touched_areas: app-server Worker, Codex/Session/Claude Java, foggy-chat, Navigator PC, Mobile, version tracker
- self_check: 独立 review 发现的跨进程 accepted-answer/HTTP-response-loss 竞态已修复并回归；正式质量闸门完成
- test_status: passed-isolated-with-declared-live-gap
- acceptance_readiness: signed-off-isolated-with-risks
- remaining_risks: experimental schema/feature、pure secret/freeform-only live gap、Windows process settle 时序和 P3 生产零样本

## Formal Records

- [Implementation quality gate](../quality/OPT-003-interactive-input-implementation-quality.md)
- [Coverage audit](../coverage/OPT-003-interactive-input-coverage-audit.md)
- [Acceptance record](../acceptance/OPT-003-interactive-input-acceptance.md)
- [Worker/live evidence](../evidence/OPT-003-ultra-native-input-v1.json)
- [PC evidence](../evidence/OPT-003-pc-interactive-input-v1.json)

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- isolated_experience: accepted
- production_enablement: not-approved
- production_routing_changed: no
- signed_off_at: 2026-07-11
