# 审批、暂停恢复与上游回调契约

## 文档作用

- doc_type: contract-design
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-02
- intended_for: platform-owner | java-service-owner | worker-owner | upstream-business-owner | frontend-owner | execution-agent | reviewer
- purpose: 定义副作用业务函数的审批、确认码、suspension、resume 和上游 callback 协议，明确 Java 是审批所有者

## 核心结论

副作用操作不能由 LLM 或 Worker 自批准。Navigator Java 服务是审批、确认码、审批状态和最终恢复授权的所有者。

审批必须绑定 Upstream App、upstream user、Navigator effective subject、task/session、函数和 input hash。上游 App 只能通过 Navigator Java 的确认或 callback API 更新审批状态，不能直接调用 Worker resume。

Worker 负责：

1. 将自然语言任务编排到需要审批的函数或脚本。
2. 在 Java 返回 `status=suspended` 后暂停 Skill Frame 或脚本。
3. 等待 Java 通过受控 resume 通道恢复。
4. 恢复后继续原脚本快照并返回结果。

Java 负责：

1. 判断目标函数是否需要审批。
2. 生成确认码或对接上游审批系统。
3. 校验审批人、租户、用户、task、session、suspend 绑定。
4. 持有 approval/suspension 状态。
5. 调用 Worker resume 或 reject。
6. 审计审批和最终业务调用。

## 状态模型

首版建议状态：

| 状态 | 所有者 | 说明 |
| --- | --- | --- |
| `created` | Java | 已创建审批记录，尚未通知 Worker 或前端 |
| `suspended` | Java + Worker | Worker/脚本已暂停，等待确认或审批 |
| `approved` | Java | 确认码或上游审批通过，允许恢复 |
| `rejected` | Java | 用户或上游拒绝 |
| `expired` | Java | 超过有效期，默认等价拒绝 |
| `resume_dispatched` | Java | Java 已向 Worker 下发恢复命令 |
| `resumed` | Worker | Worker 已恢复脚本或 Skill Frame |
| `completed` | Java + Worker | 业务函数或脚本完成 |
| `failed` | Java + Worker | 恢复或业务调用失败 |

## `status=suspended` 响应

`invoke_business_function` 遇到需要审批且未审批的副作用操作时，应返回：

```json
{
  "status": "suspended",
  "function_id": "tms.order.close_apply.submit",
  "version": "v1",
  "task_id": "lgt_001",
  "session_id": "worker-session-001",
  "script_run_id": "sr_001",
  "suspend_id": "sus_001",
  "approval_id": "ap_001",
  "risk_level": "state_change",
  "approval_required": {
    "mode": "user_confirm_code",
    "owner": "navigator_java",
    "title": "提交关单申请",
    "summary": {
      "order_id": "ORD-001",
      "application_id": "APP-001",
      "submit_reason": "客户取消运输需求",
      "effect": "订单将进入关单申请审批流程"
    },
    "confirmation_required": true,
    "expires_at": "2026-05-02T16:25:00+08:00"
  },
  "resume_policy": {
    "same_task_required": true,
    "same_script_run_required": true,
    "same_function_input_required": true
  },
  "binding": {
    "upstream_app_id": "app_tms_tenant_a",
    "upstream_user_id": "u_001",
    "navigator_effective_user_id": "svc_app_tms_tenant_a",
    "navigator_tenant_id": "nav-tenant-a",
    "worker_pool_id": "bwp_order_default"
  },
  "audit_ref": "audit_001"
}
```

约束：

1. 响应中不包含确认码明文。
2. 响应中不包含 approval token。
3. `summary` 必须由 Java 基于已校验 input 和 Manifest 生成或校验。
4. Worker 不得修改 input 后复用同一个 `suspend_id`。
5. Worker 不得把 `status=suspended` 解释为业务已完成。

## 审批链路

标准链路：

```text
Worker invoke_business_function
  -> Java 发现需要审批
      -> Java 创建 approval + suspension
      -> Java 返回 status=suspended
          -> Worker 暂停脚本 / Skill Frame
              -> Java / UI / 上游系统完成确认
                  -> Java 更新 approval 状态
                  -> Java 调 Worker resume_suspension
                      -> Worker 恢复原暂停点
                          -> Worker 再次进入 Java 执行业务函数
                              -> Java 调上游 REST adapter
                                  -> 上游返回结果
                                      -> Java 审计并返回 Worker
```

关键规则：

1. 审批通过只授权同一个 `upstream_app_id + upstream_user_id + task_id + session_id + script_run_id + suspend_id + function_id + input_hash`。
2. 恢复后真实上游调用仍由 Java 执行。
3. 如果 Worker 进程已重启且首版 suspension 为内存态，Java 应将恢复失败记录为 `failed` 或 `expired`，不能静默重试高风险操作。
4. 拒绝和超时必须能反馈给用户，并让脚本进入可解释的取消或失败状态。

## 确认码模型

首版确认码所有权：

1. Java 生成确认码或确认挑战。
2. 前端或上游 App 向用户展示确认要求。
3. 用户提交确认码给 Java。
4. Java 校验确认码、审批人权限、Upstream App、upstream user 和任务绑定。
5. Java 生成 server-side approval result。
6. Worker 只接收 `approved/rejected/expired` 摘要，不接触确认码。

确认码不得写入：

1. LLM prompt。
2. Skill/SKILL.md。
3. Worker retained message。
4. FSScript 变量。
5. Artifact。
6. SSE 普通文本事件。

## 上游确认与 callback 协议

如果上游业务系统已经有审批流，Java 可以作为审批状态桥接方。上游仍只与 Navigator Java 服务交互，不直接调用 Worker。

回调的授权主体是 `upstream_app_id`。上游多租户系统必须按 Navigator 已注册的 App 分别配置 callback credential，不能用一个上游系统级密钥横跨多个 App。

### Java 创建或转交审批

```text
POST /api/v1/business-agent/approvals
```

```json
{
  "task_id": "lgt_001",
  "suspend_id": "sus_001",
  "upstream_app_id": "app_tms_tenant_a",
  "upstream_user_id": "u_001",
  "function_id": "tms.order.close_apply.submit",
  "risk_level": "state_change",
  "summary": {
    "order_id": "ORD-001",
    "application_id": "APP-001",
    "effect": "订单将进入关单申请审批流程"
  },
  "callback_url": "navigator-managed-callback-ref"
}
```

说明：`callback_url` 对上游可以是 Java 暴露的回调引用或注册 ID，不需要也不应该暴露 Worker 地址。

### 上游审批回调 Java

```text
POST /api/v1/business-agent/approvals/{approvalId}/callback
```

```json
{
  "status": "approved",
  "approved_by": "upstream-user-001",
  "approved_at": "2026-05-02T16:22:00+08:00",
  "upstream_approval_id": "oa_001",
  "comment": "同意提交关单申请",
  "signature": "server-side-verification-material"
}
```

Java 处理要求：

1. 校验回调归属的 `upstream_app_id`、签名或共享密钥。
2. 校验 approval 仍处于 `suspended` 且未过期。
3. 校验上游审批人和业务权限，且审批人属于当前 App。
4. 写入 approval result 和 audit。
5. 调用 Worker `resume_suspension`。

## Java 到 Worker resume

Java 通过内部通道恢复 Worker：

```text
POST /internal/worker-gateway/v1/suspensions/{suspendId}/resume
```

```json
{
  "approval_result": {
    "approval_id": "ap_001",
    "status": "approved",
    "approval_source": "navigator_java",
    "approved_by": "u_001",
    "approved_at": "2026-05-02T16:22:00+08:00"
  },
  "binding": {
    "upstream_app_id": "app_tms_tenant_a",
    "upstream_user_id": "u_001",
    "navigator_effective_user_id": "svc_app_tms_tenant_a",
    "navigator_tenant_id": "nav-tenant-a",
    "task_id": "lgt_001",
    "session_id": "worker-session-001",
    "script_run_id": "sr_001",
    "function_id": "tms.order.close_apply.submit",
    "input_hash": "sha256:..."
  }
}
```

Worker 处理要求：

1. 只按 `suspend_id` 恢复当前活动暂停点。
2. 校验 `task_id`、`script_run_id`、`function_id` 与本地 suspension 记录一致。
3. 将 approval result 作为 host-provided resume payload，而不是 LLM 消息。
4. 恢复后继续原脚本，不重新让 LLM 规划高风险参数。
5. 如果找不到活动 suspension，返回可审计错误。

## 拒绝和超时

拒绝：

```json
{
  "approval_result": {
    "approval_id": "ap_001",
    "status": "rejected",
    "reason": "用户拒绝提交关单申请"
  }
}
```

超时：

```json
{
  "approval_result": {
    "approval_id": "ap_001",
    "status": "expired",
    "reason": "确认码已过期"
  }
}
```

Worker 应把拒绝和超时映射为脚本取消或失败，不得自动重新发起相同高风险调用。

## 内存态优先与后续持久化

首版接受内存态 suspension：

1. 暂停点保存在当前 Worker 进程内。
2. 进程重启后不可恢复。
3. Java 仍保存 approval/suspension 审批记录和审计记录。
4. Java resume 失败时记录明确错误，并提示用户重新发起任务或等待后续 durable resume 能力。

后续可扩展：

1. 持久化脚本快照。
2. durable suspension store。
3. 跨进程 Worker resume。
4. 幂等恢复命令队列。
5. 上游审批系统长周期回调。

首版不承诺 durable workflow，不把长时间审批挂起作为默认路径。

## 安全约束

1. LLM/Worker 不能自批准。
2. Worker 不能持有确认码。
3. Worker 不能直接调用上游 REST。
4. Java resume 必须绑定原始 input hash、`upstream_app_id` 和 `upstream_user_id`。
5. 审批摘要必须可审计，不能只保存自然语言。
6. 同一 `suspend_id` 只能完成一次最终状态转移。
7. 审批通过后仍需执行上游权限和业务规则校验。
8. 所有回调必须有签名、token 或可信网络边界校验。
9. 上游 App 的 runtime credential 不能用于创建新 App；创建 App 必须使用 provisioning credential。

## 当前验收口径

- status: draft
- verified: no
- implementation_required: yes
- development_progress: 审批、暂停恢复与 callback 契约已拆出
- testing_progress: 文档自检，未执行代码测试
- experience_progress: N/A，本文不涉及 UI 交互

## 待决策

1. 审批确认码首版由 Navigator Java 内置，还是优先对接上游已有审批系统。
2. 内存态 suspension 的默认超时时间和并发上限。
3. 上游 callback 的签名方式和重放保护策略。
4. durable resume 是 Navigator Java 持久化脚本快照，还是等待 FSScript/Worker 后续能力。
5. 上游审批系统回调是否允许 `approved_by` 与原始 `upstream_user_id` 不同，如果允许，需要记录代理审批关系。
