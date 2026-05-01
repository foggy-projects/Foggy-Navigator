# 基于 Compose Script P2.6 的业务动作审批适配需求

## 文档作用

- doc_type: integration-requirement
- intended_for: navigator-worker-owner | java-platform-owner | upstream-business-owner | fsscript-team
- purpose: 在 FSScript / Compose Script 已交付 v1.9 P2.6 协作式暂停与恢复原语的基础上，定义 Navigator / LangGraph Biz Worker 侧业务动作、审批确认码、事件桥接和脚本复用的上层适配需求
- source_context: LangGraph Biz Worker 业务系统 Agent 规划
- status: implementation-started

## 结论

FSScript 团队边界已经明确：他们提供底层脚本、安全 DSL、capability registry / policy、对象门面和协作式 pause / resume 原语；审批系统、确认码、审批 UI、业务动作策略、脚本资产中心和跨进程 Worker 接入不属于 FSScript 引擎边界。

该边界合理。本需求不再要求 FSScript 团队实现审批系统或确认码，而是以 v1.7 和 v1.9 P2.6 为底座，在 Navigator / LangGraph Biz Worker / 上游业务系统侧实现业务动作适配层。

首版接受 v1.9 P2.6 的内存态暂停模型，不等待 durable resume。

补充决策：

1. 首版 `orderBiz` 对象门面注册在 LangGraph Biz Worker 的 Python 进程内。
2. Worker 直接使用 `foggy-data-mcp-bridge-python` 提供的 `run_script(...)`、`SuspensionManager`、`compose_pause(...)`、`ResumeCommand` / `RejectCommand`。
3. 由于 `compose_pause(...)` 是阻塞式 primitive，Worker 必须在后台线程或后台任务中执行脚本，并将 suspension 转成任务事件。
4. Worker 需要维护 `task_id -> script_run_id -> suspend_id -> SuspensionManager` 的进程内映射。
5. FSScript Python runtime 已补齐正式的活动 suspension 查询 API 和 `on_suspended` hook。Worker 首版优先使用 callback 捕获 `SuspensionResult`，查询 API 作为巡检和兜底能力。
6. LangGraph Biz Worker 已开始实现内存态 `FsscriptRunBridge`，将 `compose_pause(...)` 映射为 `approval_required` 事件；Java relay 同时兼容旧 `skill_approval_request` 事件，并通过 `/api/v1/resume` 完成同进程恢复。

## 已有底座

### v1.7 受控函数与对象门面

冻结契约：

```text
D:/foggy-projects/foggy-data-mcp/docs/v1.7/P1-ComposeScript受控函数与对象门面注册-使用手册.md
```

可复用能力：

1. “可信 Provider + 不可信脚本”的信任模型。
2. capability descriptor。
3. registry 默认空。
4. runtime policy 默认空。
5. 对象门面只暴露声明方法。
6. sandbox、错误脱敏、权限与字段治理。
7. `side_effect=none` 作为只读/纯计算安全基线。

### v1.9 P2.6 协作式暂停与恢复

交付总手册：

```text
D:/foggy-projects/foggy-data-mcp/docs/v1.9/P2.6-ComposeScript协作式暂停与恢复-交付总手册.md
```

可复用能力：

1. 可信 Python / Java 函数内部 pause。
2. 可信对象门面方法内部 pause。
3. 可选脚本可见 `runtime.pause(...)`，默认关闭，受 policy 控制。
4. run 进入 `SUSPENDED`。
5. 上游通过进程内 API resume / reject。
6. resume 后 pause 返回 payload，脚本继续执行。
7. reject / timeout 抛受控异常。
8. `script_run_id + suspend_id` 绑定。
9. 错误码：`script/pause-not-allowed`、`script/suspend-timeout`、`script/resume-token-invalid` 等。

明确不由 v1.9 P2.6 提供：

1. 审批系统。
2. 确认码。
3. 审批 UI。
4. HTTP / MCP / Worker resume endpoint。
5. durable resume。
6. 跨进程 worker 恢复。
7. 脚本资产中心。

## 目标

1. 使用 FSScript / Compose Script 作为业务系统 Agent 的数据探查和业务脚本编排基座。
2. Navigator / LangGraph Biz Worker 不直接实现业务逻辑，只承担 Agent 编排、脚本生成、事件桥接、审批交互和脚本资产沉淀。
3. 上游业务系统通过可信函数或对象门面暴露业务能力，并在高风险动作内部调用 v1.9 P2.6 pause primitive。
4. Navigator Java 平台负责用户确认码、审批 UI、审批结果校验和审计入口。
5. LangGraph Biz Worker 负责把 FSScript suspension 事件映射为 Worker / Java / 前端可消费的审批事件。
6. 首版接受内存态暂停恢复，只要求同进程 resume / reject 可用。

## 非目标

首版不做：

1. 要求 FSScript 团队实现审批系统或确认码。
2. 要求 FSScript 团队实现 durable resume。
3. 要求 FSScript 团队提供 HTTP / MCP resume endpoint。
4. 允许脚本执行裸 curl、HTTP、SQL、系统命令或 import。
5. 让 LLM 直接拿到所有业务函数作为 tool。
6. 绕过上游业务系统的最终权限和业务规则校验。
7. 无确认码执行 delete、作废、不可逆取消等高风险动作。

## 角色职责

### FSScript / Compose Script Engine

负责：

1. 安全 DSL 查询。
2. capability registry / descriptor / policy。
3. 对象门面调用边界。
4. sandbox 和错误脱敏。
5. 进程内 pause / resume / reject / timeout 原语。

不负责：

1. 审批策略。
2. 用户确认码。
3. 审批 UI。
4. 业务补偿。
5. 脚本资产中心。
6. 跨进程或重启恢复。

### 上游业务系统

负责：

1. 提供可信函数或对象门面。
2. 在业务动作函数内部判断是否需要暂停。
3. 调用 `compose_pause(...)` / `ComposePause.pause(...)`。
4. 执行真实业务动作。
5. 做最终鉴权、业务规则校验、状态写入、幂等和业务审计。

### Navigator Java 平台

负责：

1. 生成和校验用户确认码。
2. 展示审批 UI 或转交业务审批系统。
3. 持久化审批记录。
4. 绑定用户、租户、task、script run、suspend id。
5. 调用 Worker 或 Engine 桥接层 resume / reject。

### LangGraph Biz Worker

负责：

1. 通过 Skill 指导 LLM 生成 FSScript。
2. 调用 Compose Script Engine。
3. 在 Python 进程内注册 `orderBiz` 等业务对象门面。
4. 在后台线程或后台任务中运行 FSScript，避免 `compose_pause(...)` 阻塞 HTTP/SSE 事件循环。
5. 接收或桥接 suspension 信息。
6. 使用 `SuspensionManager.resume(...)` / `reject(...)` 完成同进程恢复或拒绝。
7. 转发 `approval_required` / `approval_result` / `script_resumed` 等事件。
8. 保存可复用脚本、脚本 hash、依赖 capability 和风险等级。
9. 将脚本结果纳入 Skill Frame 和最终任务输出。

不负责：

1. 真实订单、关单、删除、作废等业务规则实现。
2. 最终业务权限判定。
3. 用户确认码生成和校验。
4. durable resume 或跨进程恢复。

### Worker 内部 suspension bridge

首版建议在 Worker 内实现一个轻量桥接层，例如 `FsscriptRunBridge`：

1. 为每个脚本任务创建或分配 `SuspensionManager`。
2. 将 `run_script(...)` 放入后台线程或后台任务执行。
3. 捕获当前 run 的 `script_run_id`。
4. 当 run 进入 `SUSPENDED` 时，读取 `SuspensionResult`。
5. 生成 `script_suspended` / `approval_required` Worker 事件。
6. 缓存 `task_id`、`script_run_id`、`suspend_id`、`timeout_at` 和 manager 引用。
7. Java 审批通过后，Worker 根据缓存调用 `ResumeCommand`。
8. Java 审批拒绝或超时后，Worker 调用 `RejectCommand` 或 timeout。

当前 FSScript Python runtime 已支持注入自定义 `SuspensionManager`，并提供 `list_active_suspensions()`、`get_active_suspension(script_run_id)` 与构造期 `on_suspended` hook。Worker 首版不再读取 runtime 私有字段。

## 推荐业务动作实现模型

高风险动作不推荐让脚本显式写：

```javascript
runtime.pause(...)
```

首选模式是业务对象门面或可信函数内部 pause：

```javascript
const order = orderBiz.get_order({ order_id: "ORD-1001" });
const draft = orderBiz.close_apply_draft({
  order_id: order.id,
  reason: "delivery_failed"
});

return orderBiz.close_apply_submit({
  application_id: draft.application_id
});
```

`close_apply_submit` 是可信对象门面方法。它内部执行：

```python
payload = compose_pause(
    reason="business.order.close_apply.submit",
    summary={
        "order_id": order_id,
        "application_id": application_id,
        "action": "submit_close_apply"
    },
    timeout_ms=300_000,
    resume_schema=None,
)

if payload.get("approved") is not True:
    raise BusinessRejectedError(...)

return upstream.submit_close_apply(application_id)
```

这样 LLM 只负责选择业务函数和填参，不决定是否审批，也不能获得确认码。

## 业务函数 Manifest 建议

Navigator / Worker 侧仍需要维护业务函数 Manifest，用于 Skill 生成脚本、风险提示、脚本资产依赖和 UI 展示。该 Manifest 不要求 FSScript 引擎原生支持。

```yaml
id: orderBiz.close_apply_submit
display_name: 提交关单申请
script_surface:
  object: orderBiz
  method: close_apply_submit
risk_level: state_change
requires_approval: true
approval_mode: user_confirm_code
fsscript_pause_reason: business.order.close_apply.submit
input_schema:
  type: object
  properties:
    application_id:
      type: string
  required:
    - application_id
idempotency:
  key_template: close_apply_submit:${application_id}
audit:
  fields:
    - order_id
    - application_id
    - operator_id
    - tenant_id
capability_policy:
  object: orderBiz
  methods:
    - close_apply_submit
  scopes:
    - order.close_apply.submit
```

用途：

1. 告诉 LLM 哪些业务函数可用于脚本编排。
2. 生成最小 FSScript policy。
3. 在审批 UI 中展示风险等级和操作摘要。
4. 记录脚本依赖和审计字段。
5. 区分 readonly、draft、state_change、external_side_effect、delete。

## 审批确认码流程

首版流程：

1. Worker 启动 FSScript run。
2. FSScript 调用可信业务函数。
3. 业务函数内部调用 pause primitive。
4. Engine 返回 suspension 信息：`script_run_id`、`suspend_id`、`reason`、`summary`、`timeout_at`。
5. Worker 将 suspension 映射为 `approval_required` 事件，复用 Java 侧现有审批记录与 resume 通道；Java relay 保留 `skill_approval_request` 兼容处理。
6. Java 平台创建审批记录并生成确认码。
7. 前端展示 `summary`、风险等级、确认码输入要求。
8. 用户输入确认码。
9. Java 平台校验确认码，生成审批结果。
10. Java 通过 Worker/Engine 桥接层调用 resume 或 reject。
11. Engine 唤醒当前内存态 run。
12. 业务函数收到 resume payload 后继续或中止。
13. Worker 将后续脚本结果映射为任务输出。

约束：

1. 确认码不进入 LLM prompt。
2. 确认码不进入脚本变量。
3. 确认码不进入 Artifact。
4. resume payload 只包含 `approved`、`approval_id` 等 JSON-safe 摘要。
5. 真实审批凭证由 Java 平台持久化。

## 内存态风险接受口径

首版接受 v1.9 P2.6 的内存态暂停模型：

1. 暂停中的 run 依赖当前进程。
2. 进程重启后无法恢复。
3. 不支持跨进程 worker 恢复。
4. 长时间阻塞会占用运行资源。

缓解要求：

1. 暂停 timeout 不宜过长。
2. 并发 suspension 数量需要限制。
3. 前端审批应尽量即时完成。
4. timeout 默认等价于拒绝。
5. Java/Worker 需要把“内存态风险”展示为运维风险，而不是承诺 durable workflow。

## 事件桥接

建议 Worker 对外统一事件：

1. `script_started`
2. `script_suspended`
3. `approval_required`
4. `approval_result`
5. `script_resumed`
6. `script_rejected`
7. `script_timeout`
8. `script_completed`
9. `script_failed`

`approval_required` 示例：

```json
{
  "type": "approval_required",
  "task_id": "lgt_xxx",
  "script_run_id": "sr_xxx",
  "suspend_id": "sp_xxx",
  "reason": "business.order.close_apply.submit",
  "summary": {
    "order_id": "ORD-1001",
    "application_id": "APP-1001",
    "action": "submit_close_apply"
  },
  "risk_level": "state_change",
  "required": "user_confirm_code",
  "timeout_at": "2026-04-30T10:10:00Z"
}
```

Java 平台审批通过后传给 Engine 的 resume payload 示例：

```json
{
  "approved": true,
  "approval_id": "ap_xxx",
  "approved_by": "user_xxx"
}
```

## 脚本资产沉淀

稳定脚本应保存为可复用资产。保存内容：

1. `script_id`
2. `script_hash`
3. `language=fsscript`
4. 依赖对象门面和方法
5. 所需 scopes
6. 风险等级
7. 是否需要审批
8. 创建人、更新时间、版本

示例：

```yaml
script_id: order_close_apply_v1
script_hash: sha256:...
language: fsscript
dependencies:
  objects:
    orderBiz:
      - get_order
      - close_apply_draft
      - close_apply_submit
required_scopes:
  - order.read
  - order.close_apply.submit
risk_level: state_change
approval_required: true
```

复用规则：

1. 匹配到稳定脚本时，优先填参和执行，不要求 LLM 重新生成完整脚本。
2. 业务函数 Manifest 变化后，脚本需要重新 validate。
3. 高风险脚本发布给公共范围前必须人工审核。

## 首版 PoC 建议

1. 使用 FSScript 作为脚本语言。
2. LangGraph Biz Worker Python 侧注册一个 `orderBiz` 对象门面。
3. 对象门面包含：
   - `get_order`：只读
   - `close_apply_draft`：生成草稿
   - `close_apply_submit`：内部 pause，等待确认码审批
4. `orderBiz` 内部通过 REST / RPC / mock adapter 调用真实上游或测试替身，不在 Worker 内实现业务规则。
5. Worker 侧提供 Skill，指导 LLM 生成或选择脚本。
6. Java 侧提供最小审批确认码 API。
7. Worker 将 suspension 事件转为 `approval_required` 事件，Java relay 保留 `skill_approval_request` 兼容处理。
8. 前端展示审批请求并提交确认码。
9. Java 调 Worker resume / reject endpoint。
10. Worker 在同一 Python 进程内调用 `SuspensionManager.resume(...)` / `reject(...)`。
11. 完成同进程 resume / reject 验证。

## 后续生产化需求

后续再推动：

1. durable pause / resume。
2. HTTP / MCP resume endpoint。
3. 跨进程 worker 恢复。
4. 审批适配协议标准化。
5. 多租户 suspend quota / timeout policy 配置化。
6. 脚本资产中心。
7. 业务函数 Manifest 动态发现和版本化。

这些可以与 FSScript v1.9.1 路线或 Navigator 自身 wrapper 分阶段衔接。

## 验收标准

1. 文档明确 FSScript 团队不负责审批系统和确认码。
2. 文档明确首版接受内存态暂停恢复。
3. 文档明确业务动作通过可信函数/对象门面内部 pause 实现。
4. 文档明确确认码由 Navigator Java 平台生成和校验。
5. 文档明确 Worker 负责事件桥接和脚本资产沉淀，不实现业务逻辑。
6. 文档明确首版 PoC 的 `orderBiz` 对象门面范围。
7. 文档明确 `orderBiz` 首版在 LangGraph Biz Worker Python 侧注册。
8. 文档明确 Worker 需要后台执行脚本，并将阻塞式 `compose_pause(...)` 桥接为审批事件。
9. 文档明确 FSScript Python runtime 活动 suspension 查询 API 已满足，Worker 不再依赖私有字段。

## 当前未决问题

1. 前端是否需要针对 `approval_required` 增加专用审批 UI，展示 `scriptRunId`、`suspendId`、`timeoutAt` 与业务摘要。
2. 脚本资产先落 Worker 文件系统、Java 数据库，还是 Artifact Store。
3. `orderBiz` PoC 使用 mock adapter 还是真实上游 REST。
4. 生产化是否需要 durable resume / 跨进程恢复，以及由 Navigator wrapper 还是 FSScript 后续版本承担。
