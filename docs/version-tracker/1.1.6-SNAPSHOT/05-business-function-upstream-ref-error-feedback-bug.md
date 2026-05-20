# BusinessFunction upstream_ref 配置错误反馈 BUG

## 文档作用

- doc_type: bug
- intended_for: execution-agent | reviewer | upstream-integrator
- purpose: 记录 Navigator Biz Worker 调用 BusinessFunction 时，adapter `upstream_ref` 配置错误被误当成 LLM 可修复业务入参并最终折叠为 max-iterations 的问题

## Version

- `1.1.6-SNAPSHOT`

## 状态

- status: recorded
- date: 2026-05-21
- priority: P1
- coding_status: implemented
- test_status: targeted-passed

## 复现信息

- taskId: `lgt_eb4a5985bbd84be4`
- root frame: `frm_fdd95d4ba143`
- frame report: `frame-report://lgt_eb4a5985bbd84be4/frm_fdd95d4ba143`
- function: `tms.ticket.createPlatformFeedback`
- failed function frames:
  - `fn_99334a58ca22`
  - `fn_c7f2579479fe`
  - `fn_e81b957c8153`
  - `fn_b803fa2f19c8`

执行链路已经成功进入 BusinessFunction 调用阶段：

1. `list_skill_resources(tms-ticket-agent)`
2. `read_skill_resource(SKILL.md)`
3. `invoke_business_function(tms.ticket.createPlatformFeedback)`

失败点：

```text
HTTP 400: {"code":600,"exCode":"B600","msg":"upstreamRef must match [A-Za-z0-9._-]{1,128}"}
```

随后 LLM 尝试在业务 `input` 中补充 `upstreamRef`，例如：

```text
test-ticket-20260521
test_ticket_20260521
test.ticket.20260521
```

这些尝试无效，因为 `upstream_ref` 是 REST adapter / BusinessFunction 服务端配置，不是 LLM 可修改的业务入参。最终前端只展示：

```text
LLM skill agent reached max iterations without valid submit
```

## 根因判断

当前 `nav_tms_3` 租户下 `tms.ticket.createPlatformFeedback` 的 adapter 配置里存在：

```json
{
  "upstream_ref": "TMS:3"
}
```

Navigator route registry 要求：

```text
[A-Za-z0-9._-]{1,128}
```

冒号 `:` 不合法，所以请求在 Navigator 调用 REST adapter 前被拦截，大概率没有进入 TMS `/x3-agent/tms/ticket/platform-feedback` Controller。

TMS 侧已识别的配置来源：

```java
NavigatorUpstreamTenantProvisioningService
form.setUpstreamRef(..., SOURCE_SYSTEM + ":" + tmsTenantId)
```

TMS 后续应改为合法值，例如：

```text
TMS-3
TMS_3
tms-tenant-3
```

并重新 ensure binding / resync functions，使 BusinessFunction `adapter_config_json.upstream_ref` 与 Navigator enabled upstream route 保持一致。

## Navigator 侧问题

### 1. 错误分类与用户反馈

`upstreamRef must match [A-Za-z0-9._-]{1,128}` 属于 adapter 配置校验错误，应归类为：

```text
infra/configuration error
non-recoverable function error
```

它不应被归类为 LLM 可通过修改业务 `input` 恢复的工具错误。

目标行为：

1. `invoke_business_function` 收到这类错误后，工具结果应携带明确错误类别，例如 `error_category=configuration`、`recoverable=false`、`llm_retry_allowed=false`。
2. LLM 不应继续通过添加或修改 `upstreamRef`、`upstream_ref` 等业务字段盲目重试。
3. Skill frame 应以真实配置错误结束，不能最终折叠为 `LLM skill agent reached max iterations without valid submit`。
4. 用户可见错误应表达真实原因，例如：

```text
业务函数配置错误：adapter upstream_ref 不合法，需检查 ClientApp upstream route / function adapter config。
```

### 2. 前置校验

OpenAPI readiness / preflight / verify-agent-readiness 应在 TMS 初始化阶段提前发现此问题。

目标校验：

1. 对当前 ClientApp 可见的 BusinessFunction，读取 `adapter_config_json.upstream_ref`。
2. 校验 `upstream_ref` 是否满足 `[A-Za-z0-9._-]{1,128}`。
3. 校验该 `upstream_ref` 是否能解析到 enabled ClientApp upstream route，或 JVM allowlist property。
4. 任一失败时 readiness/preflight 返回失败检查项，而不是等 LLM 真正调用函数时才失败。

当前代码缺口：

1. `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/business_function_tools.py` 只把 HTTP 错误包装为普通 `BusinessFunctionToolError`。
2. `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/InvokeBusinessFunctionTool.java` 捕获异常后返回普通 `GATEWAY_ERROR`。
3. `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java` 已能校验 context 显式声明的 upstream refs，但还未扫描当前 ClientApp 可见 BusinessFunction 的 adapter `upstream_ref`。

## 非目标

1. 不把 `upstreamRef` 暴露为 LLM 业务入参。
2. 不要求 LLM 根据错误猜测服务端 route 配置。
3. 不在本 BUG 中改变 runtime context / `recentConversation` 主线设计。
4. Navigator 侧修复不替代 TMS 侧把 `TMS:3` 改成合法 upstream ref 的配置修复。

## 建议实现方向

### 工具错误结构

BusinessFunction 调用链应识别配置类错误，并传递结构化结果：

```json
{
  "status": "ERROR",
  "error_category": "CONFIGURATION",
  "recoverable": false,
  "llm_retry_allowed": false,
  "message": "adapter upstream_ref is invalid",
  "user_message": "业务函数配置错误：adapter upstream_ref 不合法，需检查 ClientApp upstream route / function adapter config。"
}
```

识别范围至少包括：

1. `upstreamRef must match [A-Za-z0-9._-]{1,128}`
2. `Unauthorized or unconfigured upstream_ref`
3. adapter config 缺失或非法导致的服务端 400

### Skill 运行时收口

当工具结果为 non-recoverable function error：

1. 当前 Skill frame 应停止继续工具调用。
2. frame/report/log 保留完整失败证据。
3. 面向用户的最终回复使用 `user_message` 或同等语义。
4. max-iterations 只能作为真正迭代耗尽的 fallback，不能覆盖已有的 non-recoverable root cause。

### Readiness 扩展

readiness/preflight 应新增检查项，建议命名：

```text
BUSINESS_FUNCTION_ADAPTER:<functionId>
BUSINESS_FUNCTION_UPSTREAM_ROUTE:<upstreamRef>
```

检查失败时需要返回可操作原因：

```text
BusinessFunction tms.ticket.createPlatformFeedback adapter upstream_ref "TMS:3" is invalid; expected [A-Za-z0-9._-]{1,128}
```

或：

```text
BusinessFunction tms.ticket.createPlatformFeedback upstream_ref "TMS-3" is not configured as enabled ClientApp route or JVM allowlist property
```

## 验收标准

1. 使用非法 adapter `upstream_ref=TMS:3` 时，`invoke_business_function` 不再触发 LLM 盲目补参重试。
2. 最终用户可见错误展示真实配置原因，不再显示 `max iterations without valid submit`。
3. frame report 中能看到 BusinessFunction 配置错误分类、原始 gateway 错误和失败 function frame。
4. OpenAPI readiness / preflight 能提前发现当前 ClientApp 可见函数的非法 `upstream_ref`。
5. 合法 `upstream_ref` 但未配置 enabled route / JVM allowlist property 时，readiness/preflight 同样失败并给出明确原因。
6. `upstreamRef` 仍不出现在 LLM 可填写的业务参数契约中。

## 测试规划

需要补自动化测试：

1. Python BizWorker：模拟 `invoke_business_function` 返回 `upstreamRef must match ...`，断言工具错误被标记为 non-recoverable configuration error。
2. Python Skill runtime：断言 non-recoverable function error 不继续循环到 max-iterations，最终用户消息保留真实原因。
3. Java `InvokeBusinessFunctionToolTest`：断言 gateway 配置错误不会只返回泛化 `GATEWAY_ERROR`。
4. Java `OpenApiAgentReadinessServiceTest`：补非法 adapter `upstream_ref`、未解析 route、合法 route 三类用例。
5. 前端或 BFF 展示层：确认用户可见错误不会泄露完整 adapter config JSON，但会展示可操作配置原因。

## 实现记录

- date: 2026-05-21
- implementation_status: Navigator side completed
- upstream_status: TMS side completed, pending integrated smoke verification

已完成 Navigator 侧调整：

1. Python BizWorker `business_function_tools` 将 upstream route / adapter 配置类 400 错误分类为 `CONFIGURATION`，并返回 `recoverable=false`、`llm_retry_allowed=false` 与用户可见中文提示。
2. Python `llm_tool_dispatcher` 透传结构化 BusinessFunction 错误结果，避免降级成普通工具异常。
3. Python `llm_skill_agent` 识别 non-recoverable 工具错误后停止 LLM 继续补参重试，避免最终折叠为 `max iterations without valid submit`。
4. Java `InvokeBusinessFunctionTool` 将 gateway 配置类错误返回为 `CONFIGURATION_ERROR`，并在 `data` 中携带 `error_category`、`recoverable`、`llm_retry_allowed`、`gateway_error`。
5. Java `OpenApiAgentReadinessService` 扫描当前 ClientApp 可见 BusinessFunction 的 REST adapter `upstream_ref`，校验格式并校验是否存在 enabled upstream route / JVM allowlist。

自动化验证：

```text
tools/langgraph-biz-worker/.venv/Scripts/python.exe -m pytest tests/test_business_function_tools.py tests/test_llm_tool_dispatcher.py tests/test_llm_skill_agent.py -q
51 passed

mvn -pl addons/langgraph-biz-worker,addons/claude-worker-agent -am "-Dtest=InvokeBusinessFunctionToolTest,OpenApiAgentReadinessServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
OpenApiAgentReadinessServiceTest: 11 passed
InvokeBusinessFunctionToolTest: 10 passed
BUILD SUCCESS
```

剩余验证：

1. TMS 修复完成后，用真实 `tms.ticket.createPlatformFeedback` 再跑一次端到端提交。
2. 确认 readiness / preflight 对新的合法 `upstream_ref` 返回通过。
3. 如再次构造非法 `upstream_ref`，确认用户侧看到配置错误，不再看到 max-iterations。

## 体验验证

- experience_status: pending
- reason: 该 BUG 的主要症状是用户可见错误被折叠为 max-iterations。实现后需要通过实际 TMS 提交工单或等价端到端用例确认前端错误文案。

## 与主线设计关系

本 BUG 与当前 `runtimeVisibleConversation` / Skill 内部上下文隔离设计不是同一个问题域。

它属于 BusinessFunction 调用错误治理和 readiness 前置校验缺口，可以并行记录并排期修复；不阻塞继续讨论普通会话运行时上下文治理。
