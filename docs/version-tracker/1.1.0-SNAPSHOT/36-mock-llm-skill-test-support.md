# Mock LLM Skill 完整测试支持

## 状态

- status: signed-off
- decision: accepted-with-followups
- signed_off_at: 2026-04-28
- blocking_items: none
- follow_up_required: yes

## 文档作用

- doc_type: workitem
- intended_for: python-worker + test-owner + reviewer
- purpose: 记录在真实 LLM API 联调前，如何通过 `tools/mock-llm-service` 支持 Skill tool-call loop 的完整测试闭环

## 1. 背景

`1.1.0-SNAPSHOT` 已完成 Skill Runtime、Frame 隔离、`submit_skill_result`、嵌套 Skill 与 LLM 路由测试，但此前 LLM 只参与 Skill 选择，Skill 内部仍主要由程序化子图模拟。

真实 LLM API 联调前，需要一个可控的 mock 链路验证：

1. 模型返回 tool call。
2. Worker 执行业务工具。
3. 模型基于 tool result 继续调用工具。
4. 模型调用 `submit_skill_result`。
5. Runtime 校验 output contract，通过后写入 `COMPLETED`。
6. Frame 关闭后只上浮结果，不保留私有上下文。

## 2. 实现范围

### 2.1 Mock LLM Service

已扩展：

1. `MatchRule.message_role`：允许规则匹配最近一条指定角色消息，默认仍为 `user`。
2. 新增场景：`tools/mock-llm-service/responses/scenarios/langgraph-biz-worker-skill.yaml`。

该场景覆盖：

1. 路由响应 `exception_triage`。
2. 首轮调用 `mock_get_order`。
3. 看到订单 tool result 后调用 `mock_get_vehicle_status`。
4. 看到车辆 tool result 后调用 `submit_skill_result`。

### 2.2 LangGraph Biz Worker

已新增可选执行路径：

1. `LlmSkillAgent`：负责 LLM tool-call loop。
2. `BIZ_WORKER_LLM_EXECUTE_SKILLS=false`：默认关闭，不影响现有程序化 Skill 子图。
3. `BIZ_WORKER_LLM_SKILL_MAX_ITERATIONS=6`：限制单 Skill 最大模型轮次。
4. `root_graph.run_skill()`：当开关开启且 LLM 已配置时，使用 `LlmSkillAgent` 执行当前 Frame。

## 3. 关键设计约束

1. 模型不能直接写 Frame 状态。
2. 只有 Runtime 的 `submit_result()` 可以把 Frame 写为 `COMPLETED`。
3. `submit_skill_result` 不符合 output schema 或 business rules 时，Runtime 返回 reject，Agent loop 继续追问模型修正。
4. 默认不开启 LLM Skill 执行，避免影响 1.1.0 已验收的程序化链路。

## 4. 测试记录

已运行：

1. `tools/langgraph-biz-worker`: `.venv/Scripts/python.exe -m pytest tests`
   - 结果：`202 passed`（2026-04-28 回归）
2. `tools/mock-llm-service`: `pytest tests`
   - 结果：`17 passed, 1 warning`
3. 手工 HTTP 兼容联调：进程内启动 `mock-llm-service`，通过 `ChatOpenAI(base_url=http://127.0.0.1:18200/v1)` 驱动 `LlmSkillAgent`
   - 结果：Frame `COMPLETED`
   - 事件链：`tool_use -> tool_result -> tool_use -> tool_result -> tool_use -> skill_result_submit`

覆盖点：

1. LLM 通过 tool call 调用 `mock_get_order`、`mock_get_vehicle_status`、`submit_skill_result`。
2. Runtime 校验成功后 Frame 进入 `COMPLETED`。
3. `close_frame()` 后 private messages 被清空。
4. 首次 `submit_skill_result` 不合格时返回 reject，后续修正后完成。
5. Mock LLM 可基于 `tool` 角色消息匹配下一轮响应。

## 5. 后续真实 LLM 联调方式

Mock 联调配置：

```env
BIZ_WORKER_LLM_PROVIDER=openai
BIZ_WORKER_LLM_API_KEY=mock-key
BIZ_WORKER_LLM_BASE_URL=http://localhost:8200/v1
BIZ_WORKER_LLM_MODEL=mock-model
BIZ_WORKER_LLM_EXECUTE_SKILLS=true
```

切换到真实 LLM 时，保持 Worker 侧执行路径不变，仅替换：

1. `BIZ_WORKER_LLM_BASE_URL`
2. `BIZ_WORKER_LLM_API_KEY`
3. `BIZ_WORKER_LLM_MODEL`

本地已增加两类测试 env 文件，文件被 git ignore，不进入提交：

1. `tools/langgraph-biz-worker/.env.mock-llm.local`
2. `tools/langgraph-biz-worker/.env.real-llm.local`

切换方式：

```powershell
$env:BIZ_WORKER_ENV_FILE=".env.real-llm.local"
```

或启动服务：

```powershell
powershell -ExecutionPolicy Bypass -File start.ps1 -EnvFile .env.real-llm.local
```

## 6. 真实 LLM 联调记录

联调环境：

1. Base URL: `http://test.synthoflow.com:3061/v1`
2. Model: `qwen3.5-plus`
3. API Key: 已由测试人员本地注入，不写入文档

### 6.1 Skill Agent 完整链路

执行结果：

1. Frame 状态：`COMPLETED`
2. 事件链：`tool_use -> tool_result -> tool_use -> tool_result -> tool_use -> skill_result_submit`
3. 输出分类：`vehicle_delay`
4. 推荐动作：`manual_dispatch`
5. evidence refs:
   - `mock_get_order:<order_id>`
   - `mock_get_vehicle_status:V09`

### 6.1.1 Env 文件切换验证

已支持通过 `BIZ_WORKER_ENV_FILE` 切换 LLM 配置文件：

1. `.env.mock-llm.local`：本地 mock LLM
2. `.env.real-llm.local`：公司内网真实 OpenAI-compatible LLM

验证结果：

1. `settings.model_config.env_file == ".env.real-llm.local"`
2. `settings.llm_base_url == "http://test.synthoflow.com:3061/v1"`
3. `settings.llm_model == "qwen3.5-plus"`
4. `settings.llm_execute_skills == true`
5. Frame 状态：`COMPLETED`
6. 事件链：`tool_use -> tool_result -> tool_use -> tool_result -> tool_use -> skill_result_submit`

### 6.2 Journal 数据比对

带 `FileFrameJournal` 执行真实 LLM 联调，验证 Biz Worker 产生的数据：

1. `persisted_before_close.status == COMPLETED`
2. `memory_frame.output == persisted_frame.output`
3. `memory_frame.result_summary == persisted_frame.result_summary`
4. `promoted.structured_output == memory_frame.output`
5. `close_frame()` 后内存 Frame 的 `private_messages/private_working_state` 已清空
6. `close_frame()` 后 Journal 中的 `private_messages/tool_calls` 已清空

结果：`all_checks_passed = true`

补充观察：真实模型有一次先提交不合格结果，Runtime 返回 `skill_result_reject` 后，模型再次调用 `submit_skill_result` 并通过校验。这验证了“模型不按协议或输出不合格时，由 Runtime 拒绝并要求修正”的闭环。

## 7. 风险

1. 当前 mock 场景主要覆盖 OpenAI Chat Completions tool_calls；Anthropic tool_use 可由 mock 服务支持，但 Worker 侧真实联调仍需单独验证。
2. `LlmSkillAgent` 当前只注册了首批 Mock 业务工具，真实业务工具接入时需要统一工具注册表。
3. 当前 Skill instruction 主要来自 Manifest 元数据和系统提示；后续如要完全执行 `SKILL.md` 正文，应在 `SkillRegistry` 中保留正文并注入 frame prompt。

## 8. 执行 Check-in

- completed_work: 已完成 mock LLM 场景扩展、Worker LLM Skill Agent、配置项、README 与测试补充。
- touched_code:
  - `tools/mock-llm-service/src/mock_llm/models.py`
  - `tools/mock-llm-service/src/mock_llm/strategies/keyword.py`
  - `tools/mock-llm-service/responses/scenarios/langgraph-biz-worker-skill.yaml`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py`
  - `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`
  - `tools/langgraph-biz-worker/tests/test_llm_skill_agent.py`
- testing_status: pass
- experience_status: N/A，纯后端测试能力，无 UI 变更。
- acceptance_readiness: signed-off

## 9. 轻量验收结论

36 已完成 1.1.0 范围内的测试能力补强：

1. Mock LLM Service 可驱动 Skill tool-call loop。
2. Biz Worker 可通过 env 文件在 mock LLM 与真实 OpenAI-compatible LLM 之间切换。
3. `LlmSkillAgent` 能执行业务工具、处理 schema reject/retry，并通过 `submit_skill_result` 由 Runtime 完成 Frame。
4. FileFrameJournal 数据与内存 Frame、promoted result 一致；Frame 关闭后私有上下文已清理。

验收结论：`accepted-with-followups`。后续项不阻塞 1.1.0：Anthropic tool_use 单独验证、真实业务工具注册表、完整 `SKILL.md` body 注入。
