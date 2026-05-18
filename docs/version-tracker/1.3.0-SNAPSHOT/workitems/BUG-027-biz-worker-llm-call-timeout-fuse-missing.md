---
type: bug
bug_source: user-report
version: 1.3.0-SNAPSHOT
ticket: BUG-027
severity: major
status: implementation-complete-verification-blocked
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: biz-worker-runtime
---

# BUG-027: BizWorker LLM Call Timeout And Fuse Missing

## Background

2026-05-18 TMS 业务助手联调中，用户第一次发送 `hi` 正常完成；第二次发送带附件的“你可以帮我提个工单吗”后，前端显示 `等待响应超时（300秒）`。

Worker 日志与运行态显示，前端 300 秒超时不是 Worker 返回的终态错误，而是前端停止等待。Python LangGraph Biz Worker 仍有活跃任务，且进程仍保持到配置 LLM endpoint 的 outbound TCP 连接。

## Reproduction

1. 在 TMS 业务助手同一会话中先发送普通文本消息 `hi`。
2. 等待 root skill 正常通过 `submit_skill_result` 返回。
3. 继续发送“你可以帮我提个工单吗”，并附带图片 `image.png`。
4. 等待约 300 秒。

## Evidence

- 成功任务：`lgt_b0934859d2d94352`，root frame `frm_b701ac67f5ad`，`submit_skill_result` 有 request 与 response。
- 超时任务：`lgt_33fddb45f23c4e21`，root frame `frm_b701ac67f5ad` 停在 `WAITING_CHILD`。
- 子技能 frame：`frm_18cec314302a`，skill `tms-ticket-agent`，状态 `RUNNING`，`started_at=2026-05-18 15:18:24`，无 `ended_at`。
- `data/logs/skill-tool-calls/lgt_33fddb45f23c4e21.jsonl` 只有 `invoke_business_skill` request，没有 response。
- 子 frame 的 `private_messages` 和 `tool_calls` 均为空，说明子技能 LLM 调用没有返回到 assistant/tool-call 阶段。
- Worker `/health` 当时仍显示 `active_tasks=1`。
- Python Worker 进程仍保持到 `test.synthoflow.com:3061` 的已建立连接；该地址来自 `tools/langgraph-biz-worker/.env.local` 的 LLM base URL。
- 代码检查发现 `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_router.py` 构造 `ChatOpenAI` 时未配置显式 request timeout、总执行 deadline、熔断策略；`LlmSkillAgent.run()` 直接阻塞在 `model.invoke(messages)`。

## Expected Vs Actual

Expected:

- LLM 请求有明确 per-request timeout。
- 可重试错误按有限次数和退避策略重试。
- 超过任务预算或 provider 长时间无响应时，子 frame、root frame、LangGraph task、session task 进入可观测的失败或取消状态。
- 前端超时不应导致后端无限继续运行，除非产品语义明确为“前端停止等待但后台继续”。

Actual:

- 前端 300 秒停止轮询并显示超时。
- Java 到 Python Worker 的链路仍保持连接。
- Python Worker 子 frame 仍处于 `RUNNING`。
- LLM 调用缺少显式超时和熔断，导致任务可长期悬挂。

## Impact Scope

- TMS 业务助手用户会看到等待超时，但后台任务可能仍在占用 Worker 与 LLM provider 连接。
- root/child frame 状态不收敛，执行报告与任务消息无法给出最终原因。
- 同类问题可能影响所有由 `LlmSkillAgent` 执行的业务技能，不限于 `tms-ticket-agent`。
- 缺少明确错误分类后，上游无法判断是 LLM provider 慢、网络挂起、Worker 死锁还是业务工具失败。

## Test Strategy

Automation is required because this is a core task lifecycle issue.

- Python unit/integration test：使用可控 fake LLM 模拟 hang、read timeout、transient failure，验证 timeout、有限重试和最终 frame 失败状态。
- Python route/runtime test：验证 LLM timeout 后 task 从 active set 清理，并写入可读错误消息。
- Java relay/session test：验证 Worker timeout/failure event 能推动 session task 进入终态或明确的 recoverable state。
- Widget/component test：验证前端等待超时语义和后端取消/继续策略一致。

## Code Inventory

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_router.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py`
- `addons/langgraph-biz-worker/src/main/java/...`
- `session-module/src/main/java/...`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`

## Fix Checklist

- [x] 为 LangGraph Biz Worker LLM client 增加显式 request timeout 配置，支持 env 默认值与请求级覆盖。
- [x] 为 LLM 调用增加有限 retry 策略，只重试可恢复错误，不重试业务拒绝或 prompt/tool 合同错误。
- [x] 增加 execution deadline / remaining budget，避免多轮 skill/tool 调用无限消耗。
- [x] 增加 timeout 熔断与并发保护，防止同一 provider 大量悬挂请求拖垮 Worker；已覆盖 timeout 后底层调用未结束时仍占用并发槽位。
- [x] LLM timeout 后 Python Worker 子 frame、root frame、错误事件和 execution report 状态收敛到明确失败或可恢复中断状态。
- [x] LLM timeout 导致 child frame 进入 `FAILED` 后，root 保留 pending recoverable child；用户发送“继续”时可在同一个失败 child frame 上重新打开并继续执行。
- [x] Worker active task 清理覆盖 guarded timeout / provider hang 路径：LLM 调用按配置返回错误后，由 query route 既有 `finally` 清理 active task。
- [ ] cancel / client disconnect 的跨 Java-session 语义仍需在 relay/session 层补充验证。
- [x] 日志与事件只记录 provider、model、timeout、frameId、taskId 等必要信息，不记录 API key 或完整敏感 URL。

## Implementation Notes

- 新增 `runtime/llm_call_guard.py`，统一封装 LangChain `model.invoke()` 的 request timeout、execution deadline、有限 retry、provider circuit breaker 和 worker-local concurrency guard。
- `llm_skill_router.py`、`llm_skill_agent.py`、`attachment_analysis.py` 均改为通过 guarded invoke 访问 LLM。
- `root_graph.py` 将 `task_id`、`frame_id`、`llm_config`、`vision_llm_config`、`attachments` 注入 runtime context，供 guard 与附件分析工具读取。
- `config.py` 增加 `BIZ_WORKER_LLM_*` 超时治理配置项，默认 provider SDK retry 关闭，由 worker guard 统一控制。
- persistent root frame 的 child skill 失败/超时路径改为恢复父 frame，并记录 recoverable interruption，避免 root 永久停在 `WAITING_CHILD`。
- `skill_runtime.py` 对明确标记为 recoverable 的 terminal frame 增加 reopen 入口，使 `resume_recoverable_child_skill` 能复用失败 child frame，而不是新建或丢弃。

## Verification

- [x] Python targeted tests pass: `107 passed in 4.71s`.
- [x] Python worker full tests pass: `428 passed, 6 skipped, 10 warnings in 17.28s`.
- [x] mock provider hang 场景下，按测试配置在 300 秒以内终止并返回 `LLM_REQUEST_TIMEOUT`。
- [x] 用户发送“继续”后可复用同一个 `FAILED` child frame 完成恢复执行，覆盖用例 `test_persistent_root_continue_reopens_failed_child_frame_after_timeout`。
- [x] Worker active task 清理由 `tests/test_query.py` 与 guarded timeout 路径共同覆盖。
- [ ] Java targeted relay/session tests pass.
  - 已尝试 `mvn -pl addons/langgraph-biz-worker "-Dtest=LanggraphWorkerClientTest,LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphStreamRelayTest,LanggraphTaskServiceTest" test`。
  - 当前工作区被既有 Java 不一致阻塞：`visionModelConfigId(String)` 与 `SessionEventListener.handleMessage(AgentMessage)` 出现 `NoSuchMethodError`；其中 `LanggraphWorkerClientTest`、`LanggraphTaskServiceTest` 已通过。
- [ ] TMS 业务助手真实链路复测待 Java/session relay 验证恢复后执行。

## References

- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `packages/navigator-chat-widget/src/composables/useNavigatorChat.ts`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_router.py`
